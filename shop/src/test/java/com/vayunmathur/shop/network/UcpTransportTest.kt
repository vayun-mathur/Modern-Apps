package com.vayunmathur.shop.network

import com.vayunmathur.library.network.NetworkDataStream
import com.vayunmathur.library.network.RawResponse
import com.vayunmathur.library.network.SimpleResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UcpTransportTest {
    @Test
    fun modernJsonHandshakeUsesSessionProfileAndExactGeneratedIds() = runBlocking {
        for (version in listOf(UcpApi.VERSION, UcpApi.APRIL_VERSION)) {
            for (key in listOf("idempotency_key", "idempotency-key")) {
                val arguments = obj("""{"checkout":{"line_items":[]},"meta":{"$key":"mutation-1","custom":"keep"}}""")
                val original = arguments.toString()
                val expected = data(version)
                val wire = modernWire { request -> jsonReply(request, toolResult(expected)) }
                assertEquals(expected, UcpMcp.call(wire, ENDPOINT, "create_checkout", arguments, version))
                assertEquals(listOf("POST", "POST", "POST", "DELETE"), wire.requests.map { it.method })
                assertEquals(listOf("initialize", "notifications/initialized", "tools/call", null), wire.requests.map { it.rpc()?.text("method") })
                val initialize = assertNotNull(wire.requests[0].rpc())
                assertEquals("2025-03-26", initialize["params"].asObject()?.text("protocolVersion"))
                assertNotNull(initialize["id"])
                assertNull(wire.requests[1].rpc()?.get("id"))
                val call = assertNotNull(wire.requests[2].rpc())
                assertTrue(call["id"] != initialize["id"])
                assertEquals("create_checkout", call["params"].asObject()?.text("name"))
                val sent = assertNotNull(call["params"].asObject()?.get("arguments").asObject())
                assertEquals(arguments["checkout"], sent["checkout"])
                assertEquals("mutation-1", sent["meta"].asObject()?.text(key))
                assertEquals("keep", sent["meta"].asObject()?.text("custom"))
                assertEquals(UcpApi.profileUrl(version), sent["meta"].asObject()?.get("ucp-agent").asObject()?.text("profile"))
                assertEquals(original, arguments.toString())
                wire.requests.forEach {
                    assertEquals(ENDPOINT, it.url)
                    assertEquals("profile=\"${UcpApi.profileUrl(version)}\"", it.headers["UCP-Agent"])
                    assertNotNull(it.headers["Request-Id"])
                    assertFalse(it.followRedirects)
                }
                wire.requests.drop(1).forEach {
                    assertEquals("session-1", it.headers["Mcp-Session-Id"])
                    assertEquals("2025-03-26", it.headers["MCP-Protocol-Version"])
                }
                assertEquals(wire.requests.size, wire.requests.map { it.headers["Request-Id"] }.toSet().size)
                assertNull(wire.requests.last().body)
                wire.assertClosed()
            }
        }
    }

    @Test
    fun streamableHttpIgnoresWrongIdsAndRepliesOnlyToServerRequests() = runBlocking {
        val expected = data()
        val wire = modernWire(initializeSse = true) { request ->
            val body = event("""{"jsonrpc":"2.0","id":"wrong","result":{"structuredContent":${data(UcpApi.APRIL_VERSION)}}}""") +
                event("""{"jsonrpc":"2.0","method":"notifications/progress","params":{"progress":1}}""") +
                event("""{"jsonrpc":"2.0","id":7,"method":"ping"}""") +
                event("""{"jsonrpc":"2.0","id":"server-2","method":"sampling/createMessage"}""") +
                "event: ignored\ndata: not JSON\n\n" + event(envelope(request, toolResult(expected)).toString())
            Reply.text(body, "Text/Event-Stream; charset=utf-8", chunk = 1)
        }
        assertEquals(expected, UcpMcp.call(wire, ENDPOINT, "create_checkout", empty(), UcpApi.VERSION))
        val replies = wire.requests.mapNotNull { it.rpc() }.filter { it["id"] != null && it.text("method") == null }
        assertEquals(2, replies.size)
        assertEquals(JsonPrimitive(7), replies[0]["id"])
        assertEquals(empty(), replies[0]["result"])
        assertEquals(JsonPrimitive("server-2"), replies[1]["id"])
        assertEquals(JsonPrimitive(-32601), replies[1]["error"].asObject()?.get("code"))
        assertEquals(1, wire.requests.count { it.rpc()?.text("method") == "tools/call" })
        assertTrue(wire.requests.none { it.method == "GET" || it.followRedirects })
        wire.assertClosed()
    }

    @Test
    fun jsonRejectsWrongMissingNullAndNumericResponseIds() = runBlocking {
        for (id in listOf<JsonElement?>(JsonPrimitive("wrong"), null, obj("""{"id":null}""")["id"], JsonPrimitive(1))) {
            val wire = modernWire { request ->
                val reply = buildJsonObject {
                    put("jsonrpc", "2.0")
                    if (id != null) put("id", id)
                    put("result", toolResult(data()))
                }
                assertTrue(id != request.rpc()?.get("id"))
                Reply.text(reply.toString())
            }
            assertFailsWith<IllegalArgumentException> { UcpMcp.call(wire, ENDPOINT, "create_checkout", empty(), UcpApi.VERSION) }
            assertSingleMutationAndCleanup(wire)
        }
    }

    @Test
    fun invalidInitializationIdNeverSendsToolAndStillDeletesCapturedSession() = runBlocking {
        val wire = FakeTransport { request ->
            if (request.method == "DELETE") Reply(status = 204)
            else Reply.text(
                """{"jsonrpc":"2.0","id":"wrong","result":{"protocolVersion":"2025-03-26"}}""",
                headers = SESSION_HEADERS,
            )
        }
        assertFailsWith<IllegalArgumentException> { UcpMcp.call(wire, ENDPOINT, "create_checkout", empty(), UcpApi.VERSION) }
        assertEquals(listOf("POST", "DELETE"), wire.requests.map { it.method })
        wire.assertClosed()
    }

    @Test
    fun sessionlessModernResponseDoesNotIssueDelete() = runBlocking {
        val wire = modernWire(session = false) { request -> jsonReply(request, toolResult(data())) }
        assertEquals(data(), UcpMcp.call(wire, ENDPOINT, "search_catalog", empty(), UcpApi.VERSION))
        assertEquals(listOf("POST", "POST", "POST"), wire.requests.map { it.method })
        assertTrue(wire.requests.all { "Mcp-Session-Id" !in it.headers })
        wire.assertClosed()
    }

    @Test
    fun cleanupFailureDoesNotReplaceSuccessfulOrFailedMutation() = runBlocking {
        for (failedTool in listOf(false, true)) {
            for (throwsOnDelete in listOf(false, true)) {
                val failure = IOException("ambiguous mutation")
                val wire = modernWire(
                    delete = {
                        if (throwsOnDelete) throw IOException("cleanup failed")
                        Reply(status = 503)
                    },
                ) { request ->
                    if (failedTool) throw failure
                    jsonReply(request, toolResult(data()))
                }
                if (failedTool) {
                    assertEquals(failure, assertFailsWith<IOException> { UcpMcp.call(wire, ENDPOINT, "create_checkout", empty(), UcpApi.VERSION) })
                } else {
                    assertEquals(data(), UcpMcp.call(wire, ENDPOINT, "create_checkout", empty(), UcpApi.VERSION))
                }
                assertSingleMutationAndCleanup(wire)
            }
        }
    }

    @Test
    fun callerCancellationClosesResponseAndDeletesSessionInActiveCleanupContext() = runBlocking {
        val reading = CompletableDeferred<Unit>()
        var cleanupActive = false
        val input = object : NetworkDataStream {
            override val isClosedForRead = false
            override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reading.complete(Unit)
                awaitCancellation()
            }
        }
        val wire = modernWire(delete = {
            currentCoroutineContext().ensureActive()
            cleanupActive = true
            Reply(status = 204)
        }) { Reply(input = input) }
        val job = launch { UcpMcp.call(wire, ENDPOINT, "create_checkout", empty(), UcpApi.VERSION) }
        reading.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(cleanupActive)
        assertSingleMutationAndCleanup(wire)
    }

    @Test
    fun ambiguousErrorsEofAndMalformedStreamsNeverReplayMutationOrFallBack() = runBlocking {
        val outcomes: List<suspend (UcpRequest) -> Reply> = listOf(
            { throw IOException("connection lost after write") },
            { Reply.text("", "text/event-stream") },
            { Reply.text(event("""{"jsonrpc":"2.0","id":"wrong","result":{}}"""), "text/event-stream") },
            { Reply.text("data: {\"jsonrpc\":\"2.0\"}\n", "text/event-stream") },
            { Reply.text("data: not JSON\n\n", "text/event-stream") },
            { Reply.text("{\"jsonrpc\":", "application/json") },
            { Reply(status = 200) },
        )
        for (outcome in outcomes) {
            val wire = modernWire(tool = outcome)
            assertFailsWith<Exception> { UcpMcp.call(wire, ENDPOINT, "create_checkout", empty(), UcpApi.VERSION) }
            assertSingleMutationAndCleanup(wire)
        }
    }

    @Test
    fun toolHttpFailuresIncludingLegacyStatusesAndRedirectsNeverReplay() = runBlocking {
        for (status in listOf(301, 302, 303, 307, 308, 400, 404, 405, 415, 429, 500, 501)) {
            val wire = modernWire { Reply(status = status, headers = mapOf("Location" to listOf("https://other.example/mcp"))) }
            assertFailsWith<UcpHttpException>("status=$status") { UcpMcp.call(wire, ENDPOINT, "create_checkout", empty(), UcpApi.VERSION) }
            assertSingleMutationAndCleanup(wire)
            assertTrue(wire.requests.all { it.url == ENDPOINT })
        }
    }

    @Test
    fun initializationRedirectsDoNotOpenLegacyGetOrSendTools() = runBlocking {
        for (status in listOf(301, 302, 303, 307, 308, 429, 500)) {
            val wire = FakeTransport { Reply(status = status, headers = mapOf("Location" to listOf("https://other.example/sse"))) }
            assertFailsWith<UcpHttpException> { UcpMcp.call(wire, ENDPOINT, "create_checkout", empty(), UcpApi.VERSION) }
            assertEquals(1, wire.requests.size)
            assertFalse(wire.requests.single().followRedirects)
            wire.assertClosed()
        }
    }

    @Test
    fun onlyInitialization404FallsBackToLegacyAnnouncementAndDynamicGetReplies() = runBlocking {
        val incoming = DynamicStream(chunk = 1)
        incoming.append("event: endpoint\ndata: /messages?session=legacy-1\n\n")
        val messages = "https://merchant.example/messages?session=legacy-1"
        val wire = FakeTransport { request ->
            when {
                request.method == "GET" -> Reply(input = incoming, type = "text/event-stream")
                request.url == ENDPOINT -> {
                    assertEquals("initialize", request.rpc()?.text("method"))
                    Reply(status = 404)
                }
                else -> {
                    assertEquals(messages, request.url)
                    assertEquals("2024-11-05", request.headers["MCP-Protocol-Version"])
                    val message = assertNotNull(request.rpc())
                    when (message.text("method")) {
                        "initialize" -> {
                            assertEquals("2024-11-05", message["params"].asObject()?.text("protocolVersion"))
                            incoming.append(event(envelope(request, obj("""{"protocolVersion":"2024-11-05"}""")).toString()))
                        }
                        "notifications/initialized" -> assertNull(message["id"])
                        "tools/call" -> {
                            incoming.append(event("""{"jsonrpc":"2.0","id":"unrelated","result":{}}"""))
                            incoming.append(event("""{"jsonrpc":"2.0","id":"legacy-ping","method":"ping"}"""))
                            incoming.append(event(envelope(request, toolResult(data())).toString()))
                        }
                        null -> assertEquals(JsonPrimitive("legacy-ping"), message["id"])
                        else -> error("Unexpected legacy message")
                    }
                    Reply(status = 202)
                }
            }
        }
        assertEquals(data(), UcpMcp.call(wire, ENDPOINT, "create_checkout", empty(), UcpApi.VERSION))
        assertEquals(listOf("POST", "GET", "POST", "POST", "POST", "POST"), wire.requests.map { it.method })
        assertEquals(1, wire.requests.count { it.rpc()?.text("method") == "tools/call" })
        assertEquals("text/event-stream", wire.requests[1].headers["Accept"])
        assertTrue(wire.requests.none { it.followRedirects || it.method == "DELETE" })
        wire.assertClosed()
    }

    @Test
    fun unsafeLegacyAnnouncementsFailBeforeMessagePosts() = runBlocking {
        for (announcement in listOf("https://evil.example/messages", "//evil.example/messages", "http://merchant.example/messages", "/messages#fragment", "https://merchant.example:444/messages", "https://user:pass@merchant.example/messages")) {
            val wire = FakeTransport { request ->
                if (request.method == "POST") Reply(status = 404)
                else Reply.text("event: endpoint\ndata: $announcement\n\n", "text/event-stream", chunk = 1)
            }
            assertFailsWith<IllegalArgumentException>(announcement) { UcpMcp.call(wire, ENDPOINT, "create_checkout", empty(), UcpApi.VERSION) }
            assertEquals(listOf("POST", "GET"), wire.requests.map { it.method })
            wire.assertClosed()
        }
        assertEquals("https://merchant.example/messages", UcpMcp.legacyEndpoint(ENDPOINT, "/messages"))
        assertEquals("https://merchant.example:443/messages?q=1", UcpMcp.legacyEndpoint(ENDPOINT, "https://merchant.example:443/messages?q=1"))
    }

    @Test
    fun legacyEofAfterMutationDoesNotRestartGetOrResendTool() = runBlocking {
        val incoming = DynamicStream(chunk = 2)
        incoming.append("event: endpoint\ndata: /messages\n\n")
        val wire = FakeTransport { request ->
            when {
                request.method == "GET" -> Reply(input = incoming, type = "text/event-stream")
                request.url == ENDPOINT -> Reply(status = 404)
                request.rpc()?.text("method") == "initialize" -> {
                    incoming.append(event(envelope(request, obj("""{"protocolVersion":"2024-11-05"}""")).toString()))
                    Reply(status = 202)
                }
                else -> Reply(status = 202)
            }
        }
        assertFailsWith<UcpHttpException> { UcpMcp.call(wire, ENDPOINT, "create_checkout", empty(), UcpApi.VERSION) }
        assertEquals(1, wire.requests.count { it.method == "GET" })
        assertEquals(1, wire.requests.count { it.rpc()?.text("method") == "tools/call" })
        wire.assertClosed()
    }

    @Test
    fun restMappingsUseDirectBodiesOpaqueIdsProfilesAndBothIdempotencySpellings() = runBlocking {
        val operations = listOf(
            Triple("search_catalog", "POST", "catalog/search"),
            Triple("lookup_catalog", "POST", "catalog/lookup"),
            Triple("get_product", "POST", "catalog/product"),
            Triple("create_checkout", "POST", "checkout-sessions"),
            Triple("get_checkout", "GET", "checkout-sessions/a%2Fb%20%3F%23"),
            Triple("update_checkout", "PUT", "checkout-sessions/a%2Fb%20%3F%23"),
        )
        for (version in listOf(UcpApi.APRIL_VERSION, UcpApi.VERSION)) {
            for (key in listOf("idempotency_key", "idempotency-key")) {
                for ((tool, method, path) in operations) {
                    val arguments = obj("""{"id":"a/b ?#","catalog":{"query":"tea","meta":{"keep":true}},"checkout":{"line_items":[],"meta":{"keep":true}},"meta":{"$key":"stable-key"}}""")
                    val before = arguments.toString()
                    val service = UcpService(version, "rest", "https://merchant.example/api/", emptySet(), emptySet())
                    val wire = FakeTransport { Reply.text(data(version).toString(), "Application/JSON; charset=utf-8", chunk = 1) }
                    assertEquals(data(version), UcpRest.call(wire, service, tool, arguments))
                    val request = wire.requests.single()
                    assertEquals("https://merchant.example/api/$path", request.url)
                    assertEquals(method, request.method)
                    val bodyKey = if (tool in setOf("create_checkout", "update_checkout")) "checkout" else "catalog"
                    if (tool == "get_checkout") assertNull(request.body)
                    else assertEquals(arguments[bodyKey], request.body?.let(::obj))
                    assertEquals("stable-key", request.headers["Idempotency-Key"])
                    assertEquals("profile=\"${UcpApi.profileUrl(version)}\"", request.headers["UCP-Agent"])
                    assertFalse(request.followRedirects)
                    assertEquals(before, arguments.toString())
                    wire.assertClosed()
                }
            }
        }
    }

    @Test
    fun restFailuresAndRedirectsNeverReplayCreateOrUpdate() = runBlocking {
        val service = UcpService(UcpApi.VERSION, "rest", "https://merchant.example/api", emptySet(), emptySet())
        val arguments = obj("""{"id":"c1","checkout":{},"meta":{"idempotency_key":"stable-key"}}""")
        val failures = listOf<suspend (UcpRequest) -> Reply>(
            { throw IOException("ambiguous write") },
            { Reply.text("") },
            { Reply.text("{\"ucp\":") },
            { Reply.text(data(UcpApi.APRIL_VERSION).toString()) },
            { Reply.text(data().toString(), "text/html") },
        ) + listOf(301, 302, 303, 307, 308, 404, 429, 500).map { status ->
            val respond: suspend (UcpRequest) -> Reply = {
                Reply(status = status, headers = mapOf("Location" to listOf("https://other.example/api")))
            }
            respond
        }
        for (tool in listOf("create_checkout", "update_checkout")) {
            for (failure in failures) {
                val wire = FakeTransport(failure)
                assertFailsWith<Exception> { UcpRest.call(wire, service, tool, arguments) }
                assertEquals(1, wire.requests.size)
                assertFalse(wire.requests.single().followRedirects)
                wire.assertClosed()
            }
        }
    }

    @Test
    fun restRejectsInvalidInputsWithoutSending() {
        val service = UcpService(UcpApi.VERSION, "rest", "https://merchant.example/api", emptySet(), emptySet())
        for (key in listOf("", " ", "x".repeat(257), "injected\r\nHeader: value")) {
            val args = buildJsonObject {
                put("checkout", empty())
                put("meta", buildJsonObject { put("idempotency_key", key) })
            }
            assertFailsWith<IllegalArgumentException> { UcpRest.request(service, "create_checkout", args) }
        }
        for (tool in listOf("get_checkout", "update_checkout")) {
            assertFailsWith<IllegalArgumentException> { UcpRest.request(service, tool, obj("""{"checkout":{}}""")) }
        }
        assertFailsWith<IllegalArgumentException> { UcpRest.request(service, "create_checkout", empty()) }
        assertFailsWith<IllegalArgumentException> { UcpRest.request(service, "search_catalog", empty()) }
        for (suffix in listOf("?q=1", "#fragment")) {
            assertFailsWith<IllegalArgumentException> {
                UcpRest.request(service.copy(endpoint = service.endpoint + suffix), "create_checkout", obj("""{"checkout":{}}"""))
            }
        }
    }

    @Test
    fun serviceDispatchForbidsCompletionAndCancellationBeforeAnyTransportCall() = runBlocking {
        val wire = FakeTransport { error("Forbidden operation reached transport") }
        val previous = UcpApi.transport
        try {
            UcpApi.transport = wire
            for (transport in listOf("mcp", "rest")) {
                for (tool in listOf("complete_checkout", "cancel_checkout", "complete", "cancel")) {
                    val service = UcpService(UcpApi.VERSION, transport, ENDPOINT, emptySet(), emptySet())
                    assertTrue(UcpApi.serviceCall(service, tool, empty()).isFailure)
                }
            }
            assertTrue(wire.requests.isEmpty())
        } finally {
            UcpApi.transport = previous
        }
    }

    @Test
    fun serviceDispatchPropagatesCancellationAndRestoresInjectedTransport() = runBlocking {
        val cancelled = CancellationException("caller cancelled")
        val wire = FakeTransport { throw cancelled }
        val previous = UcpApi.transport
        try {
            UcpApi.transport = wire
            val service = UcpService(UcpApi.VERSION, "rest", "https://merchant.example/api", emptySet(), emptySet())
            assertFailsWith<CancellationException> {
                UcpApi.serviceCall(service, "create_checkout", obj("""{"checkout":{},"meta":{"idempotency_key":"cancelled-1"}}"""))
            }
            assertEquals(1, wire.requests.size)
            wire.assertClosed()
        } finally {
            UcpApi.transport = previous
        }
    }

    @Test
    fun serviceSessionSnapshotsInjectedTransportIncludingCleanup() = runBlocking {
        val replacement = FakeTransport { error("Session switched transports") }
        val previous = UcpApi.transport
        val wire = modernWire { request ->
            UcpApi.transport = replacement
            jsonReply(request, toolResult(data()))
        }
        try {
            UcpApi.transport = wire
            val service = UcpService(UcpApi.VERSION, "mcp", ENDPOINT, emptySet(), emptySet())
            assertEquals(data(), UcpApi.serviceCall(service, "create_checkout", empty()).getOrThrow())
            assertTrue(replacement.requests.isEmpty())
            assertSingleMutationAndCleanup(wire)
        } finally {
            UcpApi.transport = previous
        }
    }

    @Test
    fun requestLimitsUseUtf8BytesAndRejectHeaderInjection() {
        UcpWire.checkRequest(UcpRequest(ENDPOINT, body = "x".repeat(UcpWire.MAX_BODY_BYTES)))
        assertFailsWith<IllegalArgumentException> { UcpWire.checkRequest(UcpRequest(ENDPOINT, body = "x".repeat(UcpWire.MAX_BODY_BYTES + 1))) }
        assertFailsWith<IllegalArgumentException> { UcpWire.checkRequest(UcpRequest(ENDPOINT, body = "\u00e9".repeat(UcpWire.MAX_BODY_BYTES / 2 + 1))) }
        for (value in listOf("x\ry", "x\ny", "x\r\ny")) {
            assertFailsWith<IllegalArgumentException> { UcpWire.checkRequest(UcpRequest(ENDPOINT, headers = mapOf("X-Test" to value))) }
        }
        assertFalse(UcpRequest(ENDPOINT).followRedirects)
    }

    @Test
    fun checkoutValidationMessagesReachReviewAcrossBindingsAndVersions() = runBlocking {
        for (version in listOf(UcpApi.VERSION, UcpApi.APRIL_VERSION)) {
            for (binding in listOf("rest", "mcp-json", "mcp-sse", "mcp-legacy")) {
                for (tool in listOf("create_checkout", "get_checkout", "update_checkout")) {
                    for (status in listOf("incomplete", "requires_escalation", "ready_for_complete", "complete_in_progress", "completed", "canceled")) {
                        val response = changed(checkoutValidationSession(version), "status", "\"$status\"")
                        assertEquals(response, checkoutBusinessReply(binding, tool, response, version))
                    }
                }
            }
        }
    }

    @Test
    fun malformedCheckoutErrorResponsesRejectAcrossBindings() = runBlocking {
        val valid = checkoutValidationSession()
        val malformed = listOf("id", "status", "currency", "totals", "line_items").map { JsonObject(valid - it) } + listOf(
            changed(valid, "id", "17"),
            changed(valid, "status", "\"unknown\""),
            changed(valid, "currency", "\"usd\""),
            changed(valid, "totals", "{}"),
            changed(valid, "totals", "[null]"),
            changed(valid, "totals", "[{\"type\":\"subtotal\",\"amount\":\"1200\"},{\"type\":\"total\",\"amount\":1100}]"),
            changed(valid, "totals", "[{\"type\":\"total\",\"amount\":1100}]"),
            changed(valid, "totals", "[{\"type\":\"subtotal\",\"amount\":1200},{\"type\":\"subtotal\",\"amount\":1200},{\"type\":\"total\",\"amount\":1100}]"),
            changed(valid, "totals", "[{\"type\":\"subtotal\",\"amount\":1200},{\"type\":\"total\",\"amount\":1100,\"lines\":{}}]"),
            changed(valid, "line_items", "{}"),
            changed(valid, "line_items", "[null]"),
            changed(valid, "line_items", "[{\"id\":\"line-1\"}]"),
            changed(valid, "line_items", "[{\"id\":\"line-1\",\"item\":{\"id\":\"sku-1\",\"title\":\"Tea\",\"price\":1200},\"quantity\":0,\"totals\":[]}]"),
            changed(valid, "line_items", "[{\"id\":\"line-1\",\"item\":{\"id\":\"sku-1\",\"title\":\"Tea\",\"price\":\"1200\"},\"quantity\":1,\"totals\":[]}]"),
            obj("""{"ucp":{"version":"2026-08-25"},"messages":[{"type":"error","code":"invalid"}]}"""),
        )
        for (binding in listOf("rest", "mcp-json", "mcp-sse", "mcp-legacy")) {
            for (tool in listOf("create_checkout", "get_checkout", "update_checkout")) {
                for (response in malformed) {
                    assertFailsWith<UcpHttpException> { checkoutBusinessReply(binding, tool, response) }
                }
            }
        }
    }

    @Test
    fun catalogErrorMessagesRejectEvenWhenResponseLooksLikeCheckout() = runBlocking {
        val response = changed(checkoutValidationSession(), "products", "[]")
        for (binding in listOf("rest", "mcp-json", "mcp-sse", "mcp-legacy")) {
            for (tool in listOf("search_catalog", "lookup_catalog", "get_product")) {
                assertFailsWith<UcpHttpException> { checkoutBusinessReply(binding, tool, response) }
            }
        }
    }

    @Test
    fun checkoutProtocolAndMerchantErrorEnvelopesStillReject() = runBlocking {
        val valid = checkoutValidationSession()
        val merchantError = changed(valid, "ucp", "{\"version\":\"2026-08-25\",\"status\":\"error\"}")
        val bodyRpcError = changed(valid, "error", "{\"code\":-32603,\"message\":\"Rejected\"}")
        for (binding in listOf("rest", "mcp-json", "mcp-sse", "mcp-legacy")) {
            for (tool in listOf("create_checkout", "get_checkout", "update_checkout")) {
                assertFailsWith<UcpHttpException> { checkoutBusinessReply(binding, tool, merchantError) }
                assertFailsWith<UcpHttpException> { checkoutBusinessReply(binding, tool, bodyRpcError) }
                if (binding != "rest") {
                    assertFailsWith<UcpHttpException> { checkoutBusinessReply(binding, tool, valid, mcpError = true) }
                    assertFailsWith<UcpHttpException> { checkoutBusinessReply(binding, tool, valid, rpcError = true) }
                }
            }
        }
    }

    @Test
    fun checkoutValidationPermissionStillEnforcesExactSelectedVersion() = runBlocking {
        for (expected in listOf(UcpApi.VERSION, UcpApi.APRIL_VERSION)) {
            val other = if (expected == UcpApi.VERSION) UcpApi.APRIL_VERSION else UcpApi.VERSION
            for (binding in listOf("rest", "mcp-json", "mcp-sse", "mcp-legacy")) {
                for (tool in listOf("create_checkout", "get_checkout", "update_checkout")) {
                    assertEquals(checkoutValidationSession(expected), checkoutBusinessReply(binding, tool, checkoutValidationSession(expected), expected))
                    assertFailsWith<UcpHttpException> { checkoutBusinessReply(binding, tool, checkoutValidationSession(other), expected) }
                }
            }
        }
    }

    private fun checkoutValidationSession(version: String = UcpApi.VERSION) = obj("""{
        "ucp":{"version":"$version"},"id":"checkout-1","status":"incomplete","currency":"USD",
        "line_items":[{"id":"line-1","item":{"id":"sku-1","title":"Tea","price":1200},"quantity":1,"totals":[]}],
        "totals":[{"type":"subtotal","amount":1200},{"type":"discount","amount":-100},{"type":"total","amount":1100}],
        "messages":[{"type":"error","code":"invalid_email","severity":"recoverable","path":"$.buyer.email","content":"Enter a valid email."}],
        "links":[]
    }""")

    private fun changed(source: JsonObject, key: String, raw: String): JsonObject =
        JsonObject(source + (key to UcpApi.json.parseToJsonElement(raw)))

    private suspend fun checkoutBusinessReply(
        binding: String, tool: String, response: JsonObject, version: String = UcpApi.VERSION,
        mcpError: Boolean = false, rpcError: Boolean = false,
    ): JsonObject {
        fun rpcReply(request: UcpRequest): JsonObject {
            if (rpcError) return buildJsonObject {
                put("jsonrpc", "2.0"); put("id", requireNotNull(request.rpc()?.get("id")))
                put("error", obj("""{"code":-32603,"message":"Rejected"}"""))
            }
            return envelope(request, buildJsonObject {
                put("structuredContent", response)
                if (mcpError) put("isError", true)
            })
        }
        val incoming = DynamicStream(3)
        if (binding == "mcp-legacy") incoming.append("event: endpoint\ndata: /messages\n\n")
        val wire = when (binding) {
            "rest" -> FakeTransport { Reply.text(response.toString()) }
            "mcp-json", "mcp-sse" -> modernWire { request ->
                val reply = rpcReply(request).toString()
                if (binding == "mcp-sse") Reply.text(event(reply), "text/event-stream") else Reply.text(reply)
            }
            "mcp-legacy" -> FakeTransport { request ->
                when {
                    request.method == "GET" -> Reply(input = incoming, type = "text/event-stream")
                    request.url == ENDPOINT -> Reply(status = 404)
                    request.rpc()?.text("method") == "initialize" -> {
                        incoming.append(event(envelope(request, obj("""{"protocolVersion":"2024-11-05"}""")).toString()))
                        Reply(status = 202)
                    }
                    request.rpc()?.text("method") == "tools/call" -> {
                        incoming.append(event(rpcReply(request).toString()))
                        Reply(status = 202)
                    }
                    else -> Reply(status = 202)
                }
            }
            else -> error("Unexpected test binding")
        }
        val arguments = obj("""{"id":"checkout-1","checkout":{},"catalog":{},"meta":{"idempotency_key":"stable-validation-key"}}""")
        try {
            return if (binding == "rest") UcpRest.call(wire, UcpService(version, "rest", "https://merchant.example/api", emptySet(), emptySet()), tool, arguments)
                else UcpMcp.call(wire, ENDPOINT, tool, arguments, version)
        } finally {
            wire.assertClosed()
        }
    }

    private fun modernWire(
        initializeSse: Boolean = false,
        session: Boolean = true,
        delete: suspend () -> Reply = { Reply(status = 204) },
        tool: suspend (UcpRequest) -> Reply,
    ) = FakeTransport { request ->
        when {
            request.method == "DELETE" -> delete()
            request.rpc()?.text("method") == "initialize" -> {
                val response = envelope(request, obj("""{"protocolVersion":"2025-03-26","capabilities":{}}"""))
                val headers = if (session) SESSION_HEADERS else emptyMap()
                if (initializeSse) Reply.text(event(response.toString()), "text/event-stream", headers, 1)
                else Reply.text(response.toString(), "application/json", headers, 1)
            }
            request.rpc()?.text("method") == "tools/call" -> tool(request)
            else -> Reply(status = 202)
        }
    }

    private fun assertSingleMutationAndCleanup(wire: FakeTransport) {
        assertEquals(1, wire.requests.count { it.rpc()?.text("method") == "tools/call" })
        assertEquals(1, wire.requests.count { it.rpc()?.text("method") == "initialize" })
        assertEquals(1, wire.requests.count { it.method == "DELETE" })
        assertEquals("DELETE", wire.requests.last().method)
        assertTrue(wire.requests.none { it.method == "GET" || it.followRedirects })
        wire.assertClosed()
    }

    private fun obj(raw: String) = UcpApi.json.parseToJsonElement(raw) as JsonObject
    private fun empty() = JsonObject(emptyMap())
    private fun data(version: String = UcpApi.VERSION) = obj("""{"ucp":{"version":"$version"},"id":"checkout-1","status":"incomplete"}""")
    private fun toolResult(data: JsonObject) = buildJsonObject { put("structuredContent", data) }
    private fun UcpRequest.rpc(): JsonObject? = body?.let(::obj)
    private fun envelope(request: UcpRequest, result: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", assertNotNull(request.rpc()?.get("id")))
        put("result", result)
    }
    private fun jsonReply(request: UcpRequest, result: JsonObject) = Reply.text(envelope(request, result).toString(), chunk = 1)
    private fun event(json: String) = "data: $json\n\n"

    private data class Reply(
        val status: Int = 200,
        val type: String = "application/json",
        val headers: Map<String, List<String>> = emptyMap(),
        val input: NetworkDataStream? = null,
    ) {
        companion object {
            fun text(body: String, type: String = "application/json", headers: Map<String, List<String>> = emptyMap(), chunk: Int = 3): Reply {
                val input = DynamicStream(chunk)
                input.append(body)
                return Reply(type = type, headers = headers, input = input)
            }
        }
    }

    private class FakeTransport(private val respond: suspend (UcpRequest) -> Reply) : UcpTransport {
        val requests = mutableListOf<UcpRequest>()
        private val exchanges = mutableListOf<Exchange>()

        override suspend fun execute(request: UcpRequest): RawResponse {
            requests += request
            error("Unexpected buffered request: $request")
        }

        override suspend fun stream(request: UcpRequest, block: suspend (NetworkDataStream?, SimpleResponse) -> Unit): SimpleResponse {
            requests += request
            val exchange = Exchange()
            exchanges += exchange
            try {
                val reply = respond(request)
                val response = SimpleResponse(reply.status, "fake", "", mapOf("Content-Type" to listOf(reply.type)) + reply.headers, request.url)
                block(reply.input, response)
                return response
            } finally {
                exchange.closed = true
            }
        }

        fun assertClosed() {
            assertEquals(requests.size, exchanges.size)
            assertTrue(exchanges.all { it.closed }, "Every stream callback must leave through finally")
        }

        private class Exchange(var closed: Boolean = false)
    }

    // Legacy GET replies are appended by subsequent POST handlers, with IDs from the actual requests.
    private class DynamicStream(private val chunk: Int) : NetworkDataStream {
        private val pending = ArrayDeque<ByteArray>()
        private var position = 0
        override val isClosedForRead get() = pending.isEmpty()

        fun append(text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.isNotEmpty()) pending.addLast(bytes)
        }

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pending.isEmpty()) return -1
            val bytes = pending.first()
            val count = minOf(chunk, length, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            if (position == bytes.size) {
                pending.removeFirst()
                position = 0
            }
            return count
        }
    }

    private companion object {
        const val ENDPOINT = "https://merchant.example/mcp"
        val SESSION_HEADERS = mapOf("mCp-SeSsIoN-Id" to listOf("session-1"))
    }
}

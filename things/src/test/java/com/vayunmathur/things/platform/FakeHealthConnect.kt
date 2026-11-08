package com.vayunmathur.things.platform

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.response.InsertRecordsResponse
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/**
 * A [HealthConnectClient] fake backed by dynamic proxies: the interface is far too
 * wide to implement by hand, and the production code only touches the permission
 * controller plus `insertRecords`.
 */
class FakeHealthConnect(
    var granted: Set<String> = HealthConnectHelper.requiredPermissions,
    var insertError: Exception? = null,
) {
    val inserted = mutableListOf<List<Record>>()
    var permissionChecks = 0

    private fun resume(args: Array<out Any?>?, value: Any?) {
        val cont = args?.lastOrNull() as? Continuation<Any?>
        cont?.resumeWith(Result.success(value))
    }

    private fun resumeError(args: Array<out Any?>?, error: Exception) {
        val cont = args?.lastOrNull() as? Continuation<Any?>
        cont?.resumeWith(Result.failure(error))
    }

    private val permissionHandler = InvocationHandler { proxy, method, args ->
        when (method.name) {
            "getGrantedPermissions" -> {
                permissionChecks++
                resume(args, granted)
                COROUTINE_SUSPENDED
            }
            "revokeAllPermissions" -> {
                granted = emptySet()
                resume(args, Unit)
                COROUTINE_SUSPENDED
            }
            "toString" -> "FakePermissionController"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> throw UnsupportedOperationException(method.name)
        }
    }

    val permissionController: PermissionController = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(PermissionController::class.java),
        permissionHandler,
    ) as PermissionController

    private val clientHandler = InvocationHandler { proxy, method, args ->
        when (method.name) {
            "getPermissionController" -> permissionController
            "insertRecords" -> {
                val error = insertError
                if (error != null) {
                    resumeError(args, error)
                } else {
                    @Suppress("UNCHECKED_CAST")
                    val records = args?.firstOrNull() as? List<Record> ?: emptyList()
                    inserted.add(records)
                    resume(args, InsertRecordsResponse(records.indices.map { it.toString() }))
                }
                COROUTINE_SUSPENDED
            }
            "toString" -> "FakeHealthConnectClient"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> throw UnsupportedOperationException(method.name)
        }
    }

    val client: HealthConnectClient = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(HealthConnectClient::class.java),
        clientHandler,
    ) as HealthConnectClient
}

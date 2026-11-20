package com.vayunmathur.passwords.platform

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.service.autofill.FillCallback
import android.service.autofill.FillContext
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.Presentations
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.core.net.toUri
import com.vayunmathur.library.util.DatabaseHelper
import com.vayunmathur.passwords.R
import com.vayunmathur.passwords.data.PasswordRepository
import com.vayunmathur.passwords.domain.DomainMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.vayunmathur.library.log.Log
import com.vayunmathur.passwords.data.Password

/** Password Autofill Service: a autofill service implementation for password autofill service. */
class PasswordAutofillService : AutofillService() {

    private val tag = "AutofillService"

    private val isDatabaseAvailable by lazy {
        DatabaseHelper(applicationContext).isKeyGenerated()
    }
    private val repository by lazy { PasswordRepository.get(applicationContext) }

    // Owned by the service (no lifecycleScope on a Service): cancelled in
    // onDestroy. All repository work runs on Dispatchers.IO.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        if (!isDatabaseAvailable) {
            callback.onSuccess(null)
            return
        }
        val parser = StructureParser(request.fillContexts)

        val usernameId = parser.usernameId
        val passwordId = parser.passwordId

        if (passwordId == null) {
            callback.onSuccess(null)
            return
        }

        val targetPackage = request.fillContexts.lastOrNull()?.structure?.activityComponent?.packageName
        val targetWebDomain = parser.webDomain

        val inlineSpecs = request.inlineSuggestionsRequest?.inlinePresentationSpecs

        // FillCallback is the autofill framework's async contract: return
        // without blocking and invoke the callback exactly once when the
        // IO vault read completes.
        val job = serviceScope.launch {
            try {
                val allPasswords = repository.getAllPasswords()

                val matches = allPasswords.filter { pass ->
                    pass.websites.any { site -> matchesContext(site, targetPackage, targetWebDomain) }
                }

                if (matches.isEmpty()) {
                    callback.onSuccess(null)
                    return@launch
                }

                val responseBuilder = FillResponse.Builder()

                for ((index, pass) in matches.withIndex()) {
                    val datasetBuilder = Dataset.Builder()
                    val identity = pass.username.ifBlank { pass.email }
                    val label = pass.name.ifBlank { identity }
                    val inlineSpec = inlineSpecs?.getOrNull(index)
                    val presentation = createPresentations(label, inlineSpec)

                    if (usernameId != null) {
                        val field = Field.Builder()
                            .setValue(AutofillValue.forText(identity))
                            .setPresentations(presentation)
                            .build()
                        datasetBuilder.setField(usernameId, field)
                    }

                    val passField = Field.Builder()
                        .setValue(AutofillValue.forText(pass.password))
                        .setPresentations(presentation)
                        .build()
                    datasetBuilder.setField(passwordId, passField)

                    responseBuilder.addDataset(datasetBuilder.build())
                }

                callback.onSuccess(responseBuilder.build())
            } catch (expected: CancellationException) {
                throw expected
            } catch (expected: IllegalStateException) {
                Log.error(tag, "Error in onFillRequest", expected)
                callback.onSuccess(null)
            } catch (expected: Throwable) {
                // e.g. SQLCipher native load failure: the callback must
                // still complete, with the same no-fill outcome.
                Log.error(tag, "Error in onFillRequest", expected)
                callback.onSuccess(null)
            }
        }
        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val parser = StructureParser(request.fillContexts)
        val username = parser.usernameText
        val password = parser.passwordText

        if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
            // SaveCallback is the autofill framework's async contract:
            // invoke it exactly once when the IO vault write completes.
            // onSaveRequest has no CancellationSignal; serviceScope
            // cancellation in onDestroy is the cancellation path here.
            serviceScope.launch {
                try {
                    val candidates = repository.getAllPasswords()
                    val existing = candidates.firstOrNull { it.username == username || it.email == username }
                    if (existing != null) {
                        repository.upsertPassword(existing.copy(password = password))
                    } else {
                        val looksLikeEmail = username.contains("@")
                        repository.upsertPassword(
                            Password(
                                name = "Saved Login",
                                username = if (looksLikeEmail) "" else username,
                                email = if (looksLikeEmail) username else "",
                                password = password,
                                totpSecret = null,
                                websites = listOfNotNull(parser.webDomain)
                            )
                        )
                    }
                    callback.onSuccess()
                } catch (expected: CancellationException) {
                    throw expected
                } catch (expected: IllegalStateException) {
                    Log.error(tag, "Error in onSaveRequest", expected)
                    callback.onFailure("Unable to open vault")
                } catch (expected: Throwable) {
                    // e.g. SQLCipher native load failure: complete the
                    // callback with the same vault-failure outcome.
                    Log.error(tag, "Error in onSaveRequest", expected)
                    callback.onFailure("Unable to open vault")
                }
            }
            return
        }
        callback.onSuccess()
    }

    private fun createPresentations(text: String, inlineSpec: InlinePresentationSpec?): Presentations {
        val remoteViews = RemoteViews(packageName, android.R.layout.simple_list_item_1)
        remoteViews.setTextViewText(android.R.id.text1, text)

        val builder = Presentations.Builder()
            .setMenuPresentation(remoteViews)

        if (inlineSpec != null) {
            try {
                val styles = UiVersions.getVersions(inlineSpec.style)
                if (styles.contains(UiVersions.INLINE_UI_VERSION_1)) {
                    val attributionIntent = PendingIntent.getActivity(
                        applicationContext, 0, Intent(), PendingIntent.FLAG_IMMUTABLE
                    )
                    val inlineBuilder = InlineSuggestionUi.newContentBuilder(attributionIntent)
                        .setTitle(text)
                        .setStartIcon(Icon.createWithResource(applicationContext, R.drawable.key_24px))
                    val inlineContent: UiVersions.Content = inlineBuilder.build()
                    builder.setInlinePresentation(
                        InlinePresentation(inlineContent.slice, inlineSpec, false)
                    )
                }
            } catch (expected: IllegalArgumentException) {
                Log.dev(tag, "Could not create inline presentation", expected)
            }
        }

        return builder.build()
    }

    private fun matchesContext(storedSite: String, currentPkg: String?, currentWeb: String?): Boolean {
        if (currentPkg != null) {
            val normalized = storedSite.trim().lowercase().removeSuffix("/")
            val pkg = currentPkg.lowercase()
            if (normalized == pkg || normalized == "android-app://$pkg") return true
        }

        if (currentWeb != null) {
            val currentHost = try {
                val uri = if (currentWeb.contains("://")) currentWeb.toUri() else "https://$currentWeb".toUri()
                uri.host?.lowercase() ?: currentWeb
            } catch (_: IllegalArgumentException) {
                currentWeb
            }
            // The stored site is the parent: saving example.com fills on login.example.com, but
            // saving login.example.com does not fill on bare example.com.
            return DomainMatch.isSameSiteOrSubdomain(
                DomainMatch.normalizeSite(currentHost),
                DomainMatch.normalizeSite(storedSite),
            )
        }

        return false
    }

    private class StructureParser(contexts: List<FillContext>) {
        var usernameId: AutofillId? = null
        var passwordId: AutofillId? = null
        var usernameText: String? = null
        var passwordText: String? = null
        var webDomain: String? = null

        init {
            contexts.forEach { ctx ->
                val struct = ctx.structure
                for (i in 0 until struct.windowNodeCount) {
                    traverse(struct.getWindowNodeAt(i).rootViewNode)
                }
            }
        }

        private fun traverse(node: AssistStructure.ViewNode) {
            captureDomain(node)
            captureCredentials(node)

            for (i in 0 until node.childCount) {
                traverse(node.getChildAt(i))
            }
        }

        private fun captureDomain(node: AssistStructure.ViewNode) {
            if (webDomain == null && node.webDomain != null) {
                webDomain = node.webDomain
            }
        }

        private fun captureCredentials(node: AssistStructure.ViewNode) {
            val signals = fieldSignals(node)
            if (isUsernameField(signals) && usernameId == null) {
                usernameId = node.autofillId
                usernameText = node.autofillValue?.textValue?.toString() ?: node.text?.toString()
            }
            if (isPasswordField(signals, node.inputType) && passwordId == null) {
                passwordId = node.autofillId
                passwordText = node.autofillValue?.textValue?.toString() ?: node.text?.toString()
            }
        }

        private data class FieldSignals(
            val hints: Array<String>?,
            val htmlName: String?,
            val idEntry: String?,
        )

        private fun fieldSignals(node: AssistStructure.ViewNode): FieldSignals = FieldSignals(
            hints = node.autofillHints,
            htmlName = node.htmlInfo?.attributes?.find { it.first == "name" }?.second?.lowercase(),
            idEntry = node.idEntry?.lowercase(),
        )

        private fun isUsernameField(signals: FieldSignals): Boolean =
            signals.hints?.any { it.contains("username") || it.contains("email") } == true ||
                signals.htmlName?.contains("user") == true ||
                signals.htmlName?.contains("email") == true ||
                signals.idEntry?.contains("user") == true ||
                signals.idEntry?.contains("email") == true

        private fun isPasswordField(signals: FieldSignals, inputType: Int): Boolean =
            signals.hints?.any { it.contains("password") } == true ||
                signals.htmlName?.contains("pass") == true ||
                signals.idEntry?.contains("pass") == true ||
                (inputType and INPUT_TYPE_MASK) == INPUT_TYPE_TEXT_PASSWORD

        companion object {
            private const val INPUT_TYPE_MASK = 0xFFF
            private const val INPUT_TYPE_TEXT_PASSWORD = 0x81
        }
    }
}

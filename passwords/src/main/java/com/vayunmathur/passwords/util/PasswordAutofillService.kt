package com.vayunmathur.passwords.util

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
import com.vayunmathur.library.room.buildDatabase
import com.vayunmathur.passwords.R
import com.vayunmathur.passwords.data.PasswordDatabase
import kotlinx.coroutines.runBlocking
import android.util.Log
import com.vayunmathur.passwords.data.Password

class PasswordAutofillService : AutofillService() {

    private val TAG = "AutofillService"

    private val isDatabaseAvailable by lazy {
        DatabaseHelper(applicationContext).isKeyGenerated()
    }
    private val passwordDao by lazy {
        applicationContext.buildDatabase<PasswordDatabase>().passwordDao()
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

        runBlocking {
            try {
                val allPasswords = passwordDao.getAll()

                val matches = allPasswords.filter { pass ->
                    pass.websites.any { site -> matchesContext(site, targetPackage, targetWebDomain) }
                }

                if (matches.isEmpty()) {
                    callback.onSuccess(null)
                    return@runBlocking
                }

                val responseBuilder = FillResponse.Builder()

                for ((index, pass) in matches.withIndex()) {
                    val datasetBuilder = Dataset.Builder()
                    val label = pass.name.ifBlank { pass.userId }
                    val inlineSpec = inlineSpecs?.getOrNull(index)
                    val presentation = createPresentations(label, inlineSpec)

                    if (usernameId != null) {
                        val field = Field.Builder()
                            .setValue(AutofillValue.forText(pass.userId))
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
            } catch (e: Exception) {
                Log.e(TAG, "Error in onFillRequest", e)
                callback.onSuccess(null)
            }
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val parser = StructureParser(request.fillContexts)
        val username = parser.usernameText
        val password = parser.passwordText

        if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
            runBlocking {
                val existing = passwordDao.getAll().firstOrNull { it.userId == username }
                if (existing != null) {
                    passwordDao.upsert(existing.copy(password = password))
                } else {
                    passwordDao.upsert(
                        Password(
                            name = "Saved Login",
                            userId = username,
                            password = password,
                            totpSecret = null,
                            websites = listOfNotNull(parser.webDomain)
                        )
                    )
                }
            }
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
                    builder.setInlinePresentation(
                        InlinePresentation(inlineBuilder.build().slice, inlineSpec, false)
                    )
                }
            } catch (e: Exception) {
                Log.d(TAG, "Could not create inline presentation", e)
            }
        }

        return builder.build()
    }

    private fun matchesContext(storedSite: String, currentPkg: String?, currentWeb: String?): Boolean {
        val normalized = storedSite.trim().lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .removeSuffix("/")

        if (currentPkg != null) {
            val pkg = currentPkg.lowercase()
            if (normalized == pkg || normalized == "android-app://$pkg") return true
        }

        if (currentWeb != null) {
            val currentHost = try {
                val uri = if (currentWeb.contains("://")) currentWeb.toUri() else "https://$currentWeb".toUri()
                uri.host?.lowercase() ?: currentWeb.lowercase()
            } catch (_: Exception) {
                currentWeb.lowercase()
            }

            if (currentHost.endsWith(normalized) || normalized.endsWith(currentHost)) return true
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
            if (webDomain == null && node.webDomain != null) {
                webDomain = node.webDomain
            }

            val hints = node.autofillHints
            val htmlName = node.htmlInfo?.attributes?.find { it.first == "name" }?.second?.lowercase()
            val idEntry = node.idEntry?.lowercase()

            val isUsername = hints?.any { it.contains("username") || it.contains("email") } == true ||
                    htmlName?.contains("user") == true || htmlName?.contains("email") == true ||
                    idEntry?.contains("user") == true || idEntry?.contains("email") == true

            val isPassword = hints?.any { it.contains("password") } == true ||
                    htmlName?.contains("pass") == true ||
                    idEntry?.contains("pass") == true ||
                    (node.inputType and 0xFFF) == 0x81

            if (isUsername && usernameId == null) {
                usernameId = node.autofillId
                usernameText = node.autofillValue?.textValue?.toString() ?: node.text?.toString()
            }

            if (isPassword && passwordId == null) {
                passwordId = node.autofillId
                passwordText = node.autofillValue?.textValue?.toString() ?: node.text?.toString()
            }

            for (i in 0 until node.childCount) {
                traverse(node.getChildAt(i))
            }
        }
    }
}

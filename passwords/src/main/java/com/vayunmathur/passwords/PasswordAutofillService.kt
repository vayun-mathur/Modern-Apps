package com.vayunmathur.passwords

import android.app.assist.AssistStructure
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.service.autofill.FillCallback
import android.service.autofill.FillContext
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.Presentations
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.core.net.toUri
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.passwords.data.PasswordDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class PasswordAutofillService : AutofillService() {

    private val db by lazy { applicationContext.buildDatabase<PasswordDatabase>() }

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val parser = StructureParser(request.fillContexts)

        // 1. If we can't find username or password fields, abort
        val usernameId = parser.usernameId
        val passwordId = parser.passwordId
        if (usernameId == null && passwordId == null) {
            callback.onSuccess(null)
            return
        }

        // 2. Identify the App Package or Web Domain
        val targetPackage = request.fillContexts.lastOrNull()?.structure?.activityComponent?.packageName
        val targetWebDomain = parser.webDomain

        runBlocking {
            try {
                // 3. Fetch and Filter Passwords
                val allPasswords = db.passwordDao().getAll().first()

                val matches = allPasswords.filter { pass ->
                    pass.websites.any { site -> matchesContext(site, targetPackage, targetWebDomain) }
                }

                // 4. Build Response
                val responseBuilder = FillResponse.Builder()

                for (pass in matches) {
                    val dataset = Dataset.Builder()

                    // 1. Create the RemoteViews (The UI)
                    val remoteViews = createPresentation(pass.name.ifBlank { pass.userId })

                    // 2. Wrap it in a 'Presentations' object (REQUIRED for setField)
                    val presentation = Presentations.Builder()
                        .setMenuPresentation(remoteViews)
                        .build()

                    // Set Username Field
                    if (usernameId != null) {
                        val field = Field.Builder()
                            .setValue(AutofillValue.forText(pass.userId))
                            .setPresentations(presentation) // Now passing 'Presentations' type
                            .build()
                        dataset.setField(usernameId, field)
                    }

                    // Set Password Field
                    if (passwordId != null) {
                        val field = Field.Builder()
                            .setValue(AutofillValue.forText(pass.password))
                            .setPresentations(presentation)
                            .build()
                        dataset.setField(passwordId, field)
                    }

                    responseBuilder.addDataset(dataset.build())
                }

                callback.onSuccess(responseBuilder.build())
            } catch (_: Exception) {
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
                val dao = db.passwordDao()
                val existing = dao.getAll().first().firstOrNull { it.userId == username }

                if (existing != null) {
                    dao.upsert(existing.copy(password = password))
                } else {
                    dao.upsert(
                        Password(
                            name = "New Login",
                            userId = username,
                            password = password,
                            totpSecret = null,
                            websites = emptyList()
                        )
                    )
                }
            }
        }
        callback.onSuccess()
    }

    /**
     * Creates the UI that appears in the dropdown menu.
     */
    private fun createPresentation(text: String): RemoteViews {
        // Note: Ensure you have a layout (e.g., simple_list_item_1) or a custom layout in your resources
        val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_1)
        presentation.setTextViewText(android.R.id.text1, text)
        return presentation
    }

    /**
     * Checks if a stored website matches the current app package or web domain.
     */
    private fun matchesContext(storedSite: String, currentPkg: String?, currentWeb: String?): Boolean {
        val normalized = storedSite.trim().lowercase()

        // Check Android Package match
        if (currentPkg != null && normalized == currentPkg.lowercase()) return true
        if (currentPkg != null && normalized == "android-app://${currentPkg.lowercase()}") return true

        // Check Web Domain match
        if (currentWeb != null) {
            val host = try { (if (normalized.startsWith("http")) normalized else "https://$normalized").toUri().host } catch (_: Exception) { normalized }
            if (currentWeb.contains(host ?: normalized)) return true
        }
        return false
    }

    /**
     * Helper class to traverse the View Structure and find IDs/Text.
     */
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
            // Check for Web Domain (Standard API)
            if (webDomain == null && node.webDomain != null) {
                webDomain = node.webDomain
            }

            val hints = node.autofillHints
            val text = node.text?.toString()

            // Heuristics to identify fields
            if (hints != null) {
                for (hint in hints) {
                    val h = hint.lowercase()
                    if (h.contains("username") || h.contains("email")) {
                        if (usernameId == null) usernameId = node.autofillId
                        if (usernameText == null) usernameText = text
                    }
                    if (h.contains("password")) {
                        if (passwordId == null) passwordId = node.autofillId
                        if (passwordText == null) passwordText = text
                    }
                }
            }
            // Fallback: Check input type if hints are missing (129 = textPassword, 145 = textVisiblePassword, etc)
            else {
                if (passwordId == null && (node.inputType and 0xFFF) == 0x81) { // InputType.TYPE_TEXT_VARIATION_PASSWORD
                    passwordId = node.autofillId
                    passwordText = text
                }
                // Very basic fallback for username if it looks like an email
                if (usernameId == null && text != null && text.contains("@")) {
                    usernameText = text
                }
            }

            for (i in 0 until node.childCount) {
                traverse(node.getChildAt(i))
            }
        }
    }
}
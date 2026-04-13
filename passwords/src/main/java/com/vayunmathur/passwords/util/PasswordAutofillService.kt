package com.vayunmathur.passwords.util
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
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.passwords.data.PasswordDatabase
import kotlinx.coroutines.runBlocking
import android.util.Log
import com.vayunmathur.passwords.data.Password

/**
 * Updated Password Manager Service.
 * Fixed for Chrome Virtual Hierarchy and modern Android Autofill requirements.
 */
class PasswordAutofillService : AutofillService() {

    private val TAG = "AutofillService"

    private val viewModel by lazy {
        val db = applicationContext.buildDatabase<PasswordDatabase>()
        DatabaseViewModel(db, Password::class to db.passwordDao())
    }

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val parser = StructureParser(request.fillContexts)

        // 1. Identify fields
        val usernameId = parser.usernameId
        val passwordId = parser.passwordId

        // If we can't find a password field at minimum, we likely aren't on a login form
        if (passwordId == null) {
            callback.onSuccess(null)
            return
        }

        // 2. Identify the App Package or Web Domain
        val targetPackage = request.fillContexts.lastOrNull()?.structure?.activityComponent?.packageName
        val targetWebDomain = parser.webDomain

        runBlocking {
            try {
                // 3. Fetch and Filter Passwords
                val allPasswords = viewModel.getAll<Password>()

                val matches = allPasswords.filter { pass ->
                    pass.websites.any { site -> matchesContext(site, targetPackage, targetWebDomain) }
                }

                if (matches.isEmpty()) {
                    callback.onSuccess(null)
                    return@runBlocking
                }

                // 4. Build Response
                val responseBuilder = FillResponse.Builder()

                for (pass in matches) {
                    val datasetBuilder = Dataset.Builder()

                    // Create Presentations for both Dropdown and Keyboard suggestions
                    val presentation = createPresentations(pass.name.ifBlank { pass.userId })

                    // Handle Username
                    if (usernameId != null) {
                        val field = Field.Builder()
                            .setValue(AutofillValue.forText(pass.userId))
                            .setPresentations(presentation)
                            .build()
                        datasetBuilder.setField(usernameId, field)
                    }

                    // Handle Password
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
                val existing = viewModel.getAll<Password>().firstOrNull { it.userId == username }
                if (existing != null) {
                    viewModel.upsertAsync(existing.copy(password = password))
                } else {
                    viewModel.upsertAsync(
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

    /**
     * Creates standard Presentations for the system to show UI.
     * Includes Menu (dropdown) support.
     */
    private fun createPresentations(text: String): Presentations {
        val remoteViews = RemoteViews(packageName, android.R.layout.simple_list_item_1)
        remoteViews.setTextViewText(android.R.id.text1, text)

        return Presentations.Builder()
            .setMenuPresentation(remoteViews)
            .build()
    }

    /**
     * Robust matching logic for Android Packages and Web Domains.
     */
    private fun matchesContext(storedSite: String, currentPkg: String?, currentWeb: String?): Boolean {
        val normalized = storedSite.trim().lowercase()
            .replace("https://", "")
            .replace("http://", "")
            .removeSuffix("/")

        // 1. Package Name Match
        if (currentPkg != null) {
            val pkg = currentPkg.lowercase()
            if (normalized == pkg || normalized == "android-app://$pkg") return true
        }

        // 2. Web Domain Match (Chrome / WebViews)
        if (currentWeb != null) {
            val currentHost = try {
                val uri = if (currentWeb.contains("://")) currentWeb.toUri() else "https://$currentWeb".toUri()
                uri.host?.lowercase() ?: currentWeb.lowercase()
            } catch (_: Exception) {
                currentWeb.lowercase()
            }

            // Check for suffix match (e.g. "facebook.com" matches "m.facebook.com")
            if (currentHost.endsWith(normalized) || normalized.endsWith(currentHost)) return true
        }

        return false
    }

    /**
     * Parser optimized for Chrome and Virtual Hierarchies.
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
            // 1. Extract Web Domain (Chrome stores this in virtual nodes or root)
            if (webDomain == null && node.webDomain != null) {
                webDomain = node.webDomain
            }

            // 2. Identify Fields using Hints and HTML Info (Chrome/WebViews)
            val hints = node.autofillHints
            val htmlName = node.htmlInfo?.attributes?.find { it.first == "name" }?.second?.lowercase()
            val idEntry = node.idEntry?.lowercase()

            val isUsername = hints?.any { it.contains("username") || it.contains("email") } == true ||
                    htmlName?.contains("user") == true || htmlName?.contains("email") == true ||
                    idEntry?.contains("user") == true || idEntry?.contains("email") == true

            val isPassword = hints?.any { it.contains("password") } == true ||
                    htmlName?.contains("pass") == true ||
                    idEntry?.contains("pass") == true ||
                    (node.inputType and 0xFFF) == 0x81 // TYPE_TEXT_VARIATION_PASSWORD

            if (isUsername && usernameId == null) {
                usernameId = node.autofillId
                usernameText = node.autofillValue?.textValue?.toString() ?: node.text?.toString()
            }

            if (isPassword && passwordId == null) {
                passwordId = node.autofillId
                passwordText = node.autofillValue?.textValue?.toString() ?: node.text?.toString()
            }

            // 3. Recurse into children (including Virtual nodes for Chrome)
            for (i in 0 until node.childCount) {
                traverse(node.getChildAt(i))
            }
        }
    }
}
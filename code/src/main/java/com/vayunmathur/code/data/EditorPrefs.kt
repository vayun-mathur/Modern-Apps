package com.vayunmathur.code.data
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vayunmathur.code.domain.syntax.EditorThemes
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import com.vayunmathur.code.domain.UserSnippet
import com.vayunmathur.code.platform.setGitAuthorEmail
import com.vayunmathur.code.platform.setGitAuthorName
import com.vayunmathur.code.platform.setGitToken
import com.vayunmathur.code.platform.setGitUsername

private val Context.editorDataStore: DataStore<Preferences> by preferencesDataStore(name = "code_editor")

/**
 * Persists the small amount of state that should survive relaunches: the last opened folder
 * path (so the file browser reopens where it was) and the editor preferences (soft-wrap, font
 * size, tab width, theme mode and the smart-input toggles).
 */
class EditorPrefs(context: Context) {

    private val appContext = context.applicationContext

    /** Fire-and-forget legacy-migration rewrites triggered from the [gitToken] Flow. */
    private val migrationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val defaultSealer by lazy { KeystoreTokenSealer(KEY_ALIAS) }

    private fun activeSealer(): TokenSealer = sealerForTest ?: defaultSealer

    /** Rewrites a legacy plaintext git token sealed, only if it is still the stored value. */
    private fun queueGitTokenMigration(legacyRaw: String) {
        migrationScope.launch {
            val sealed = activeSealer().seal(legacyRaw) ?: return@launch
            appContext.editorDataStore.edit { prefs ->
                if (prefs[GIT_TOKEN_KEY] == legacyRaw) prefs[GIT_TOKEN_KEY] = sealed
            }
        }
    }

    val folderPath: Flow<String?> = appContext.editorDataStore.data.map { it[FOLDER_PATH_KEY] }
    val softWrap: Flow<Boolean> = appContext.editorDataStore.data.map { it[SOFT_WRAP_KEY] ?: false }
    val fontSize: Flow<Int> = appContext.editorDataStore.data.map { it[FONT_SIZE_KEY] ?: DEFAULT_FONT_SIZE }
    val tabWidth: Flow<Int> = appContext.editorDataStore.data.map { it[TAB_WIDTH_KEY] ?: DEFAULT_TAB_WIDTH }
    val themeMode: Flow<String> = appContext.editorDataStore.data.map { it[THEME_MODE_KEY] ?: THEME_SYSTEM }
    val autoIndent: Flow<Boolean> = appContext.editorDataStore.data.map { it[AUTO_INDENT_KEY] ?: true }
    val autoCloseBrackets: Flow<Boolean> =
        appContext.editorDataStore.data.map { it[AUTO_CLOSE_KEY] ?: true }
    val autoSave: Flow<Boolean> = appContext.editorDataStore.data.map { it[AUTO_SAVE_KEY] ?: false }
    val trimTrailingOnSave: Flow<Boolean> =
        appContext.editorDataStore.data.map { it[TRIM_TRAILING_KEY] ?: false }
    val finalNewlineOnSave: Flow<Boolean> =
        appContext.editorDataStore.data.map { it[FINAL_NEWLINE_KEY] ?: false }
    val experimentalEditor: Flow<Boolean> =
        appContext.editorDataStore.data.map { it[EXPERIMENTAL_EDITOR_KEY] ?: false }
    val showWhitespace: Flow<Boolean> =
        appContext.editorDataStore.data.map { it[SHOW_WHITESPACE_KEY] ?: false }
    val showIndentGuides: Flow<Boolean> =
        appContext.editorDataStore.data.map { it[SHOW_INDENT_GUIDES_KEY] ?: false }
    val showMinimap: Flow<Boolean> =
        appContext.editorDataStore.data.map { it[SHOW_MINIMAP_KEY] ?: false }
    val editorTheme: Flow<String> =
        appContext.editorDataStore.data.map { it[EDITOR_THEME_KEY] ?: EditorThemes.DEFAULT }

    /** Open tab file paths, in order, for session restore. */
    val sessionPaths: Flow<List<String>> = appContext.editorDataStore.data.map { prefs ->
        prefs[SESSION_PATHS_KEY]?.split("\n")?.filter { it.isNotEmpty() } ?: emptyList()
    }

    /** File path of the tab that was in the foreground, for session restore. */
    val sessionCurrent: Flow<String?> = appContext.editorDataStore.data.map { it[SESSION_CURRENT_KEY] }

    /** Most-recently-opened file paths, newest first, for quick-open. */
    val recentFiles: Flow<List<String>> = appContext.editorDataStore.data.map { prefs ->
        prefs[RECENT_FILES_KEY]?.split("\n")?.filter { it.isNotEmpty() } ?: emptyList()
    }

    /** User-defined snippets, stored as a JSON array. */
    val userSnippets: Flow<List<UserSnippet>> =
        appContext.editorDataStore.data.map { decodeSnippets(it[USER_SNIPPETS_KEY]) }

    /** Per-file collapsed fold headers, keyed by absolute file path. */
    val foldState: Flow<Map<String, List<Int>>> =
        appContext.editorDataStore.data.map { decodeFoldState(it[FOLD_STATE_KEY]) }

    // Git remote credentials + author. Only git_token is a secret: it is stored
    // Keystore-sealed (see KeystoreTokenSealer), so the DataStore file holds
    // ciphertext and a token carried to another device by device transfer is
    // undecryptable there (reads as signed-out, never a crash). Legacy plaintext
    // tokens are used once and rewritten sealed on first read. Username and
    // author identity are not secrets and stay plain.
    val gitUsername: Flow<String> = appContext.editorDataStore.data.map { it[GIT_USERNAME_KEY] ?: "" }
    val gitToken: Flow<String> = appContext.editorDataStore.data.map { prefs ->
        val raw = prefs[GIT_TOKEN_KEY] ?: ""
        when {
            raw.isEmpty() -> ""
            activeSealer().isSealed(raw) -> activeSealer().unseal(raw) ?: ""
            else -> {
                queueGitTokenMigration(raw)
                raw
            }
        }
    }
    val gitAuthorName: Flow<String> = appContext.editorDataStore.data.map { it[GIT_AUTHOR_NAME_KEY] ?: "" }
    val gitAuthorEmail: Flow<String> = appContext.editorDataStore.data.map { it[GIT_AUTHOR_EMAIL_KEY] ?: "" }

    suspend fun setFolderPath(path: String) {
        appContext.editorDataStore.edit { it[FOLDER_PATH_KEY] = path }
    }

    suspend fun clearFolderPath() {
        appContext.editorDataStore.edit { it.remove(FOLDER_PATH_KEY) }
    }

    suspend fun setSoftWrap(enabled: Boolean) {
        appContext.editorDataStore.edit { it[SOFT_WRAP_KEY] = enabled }
    }

    suspend fun setFontSize(size: Int) {
        appContext.editorDataStore.edit { it[FONT_SIZE_KEY] = size }
    }

    suspend fun setTabWidth(width: Int) {
        appContext.editorDataStore.edit { it[TAB_WIDTH_KEY] = width }
    }

    suspend fun setThemeMode(mode: String) {
        appContext.editorDataStore.edit { it[THEME_MODE_KEY] = mode }
    }

    suspend fun setAutoIndent(enabled: Boolean) {
        appContext.editorDataStore.edit { it[AUTO_INDENT_KEY] = enabled }
    }

    suspend fun setAutoCloseBrackets(enabled: Boolean) {
        appContext.editorDataStore.edit { it[AUTO_CLOSE_KEY] = enabled }
    }

    suspend fun setAutoSave(enabled: Boolean) {
        appContext.editorDataStore.edit { it[AUTO_SAVE_KEY] = enabled }
    }

    suspend fun setTrimTrailingOnSave(enabled: Boolean) {
        appContext.editorDataStore.edit { it[TRIM_TRAILING_KEY] = enabled }
    }

    suspend fun setFinalNewlineOnSave(enabled: Boolean) {
        appContext.editorDataStore.edit { it[FINAL_NEWLINE_KEY] = enabled }
    }

    suspend fun setExperimentalEditor(enabled: Boolean) {
        appContext.editorDataStore.edit { it[EXPERIMENTAL_EDITOR_KEY] = enabled }
    }

    suspend fun setShowWhitespace(enabled: Boolean) {
        appContext.editorDataStore.edit { it[SHOW_WHITESPACE_KEY] = enabled }
    }

    suspend fun setShowIndentGuides(enabled: Boolean) {
        appContext.editorDataStore.edit { it[SHOW_INDENT_GUIDES_KEY] = enabled }
    }

    suspend fun setShowMinimap(enabled: Boolean) {
        appContext.editorDataStore.edit { it[SHOW_MINIMAP_KEY] = enabled }
    }

    suspend fun setEditorTheme(theme: String) {
        appContext.editorDataStore.edit { it[EDITOR_THEME_KEY] = theme }
    }

    /** Persists the open-tabs session; clears the keys when there is nothing open. */
    suspend fun setSession(paths: List<String>, current: String?) {
        appContext.editorDataStore.edit { prefs ->
            if (paths.isEmpty()) prefs.remove(SESSION_PATHS_KEY)
            else prefs[SESSION_PATHS_KEY] = paths.joinToString("\n")
            if (current == null) prefs.remove(SESSION_CURRENT_KEY) else prefs[SESSION_CURRENT_KEY] = current
        }
    }

    suspend fun setGitUsername(value: String) {
        appContext.editorDataStore.edit { it[GIT_USERNAME_KEY] = value }
    }

    /** Persists the recent-file list (newest first); clears the key when empty. */
    suspend fun setRecentFiles(paths: List<String>) {
        appContext.editorDataStore.edit { prefs ->
            if (paths.isEmpty()) prefs.remove(RECENT_FILES_KEY)
            else prefs[RECENT_FILES_KEY] = paths.joinToString("\n")
        }
    }

    /** Persists the user-defined snippet list as JSON; clears the key when empty. */
    suspend fun setUserSnippets(snippets: List<UserSnippet>) {
        appContext.editorDataStore.edit { prefs ->
            if (snippets.isEmpty()) prefs.remove(USER_SNIPPETS_KEY)
            else prefs[USER_SNIPPETS_KEY] = encodeSnippets(snippets)
        }
    }

    /** Persists the per-file fold state as JSON; clears the key when empty. */
    suspend fun setFoldState(state: Map<String, List<Int>>) {
        appContext.editorDataStore.edit { prefs ->
            val nonEmpty = state.filterValues { it.isNotEmpty() }
            if (nonEmpty.isEmpty()) prefs.remove(FOLD_STATE_KEY)
            else prefs[FOLD_STATE_KEY] = encodeFoldState(nonEmpty)
        }
    }

    suspend fun setGitToken(value: String) {
        if (value.isEmpty()) {
            appContext.editorDataStore.edit { it[GIT_TOKEN_KEY] = "" }
            return
        }
        val sealed = activeSealer().seal(value)
        if (sealed == null) {
            // Never persist the token in plaintext; a failed seal keeps the previous value.
            Log.error(TAG, "Sealing failed; git token not persisted")
            return
        }
        appContext.editorDataStore.edit { it[GIT_TOKEN_KEY] = sealed }
    }

    suspend fun setGitAuthorName(value: String) {
        appContext.editorDataStore.edit { it[GIT_AUTHOR_NAME_KEY] = value }
    }

    suspend fun setGitAuthorEmail(value: String) {
        appContext.editorDataStore.edit { it[GIT_AUTHOR_EMAIL_KEY] = value }
    }

    /** Editor preference defaults and DataStore keys. */
    companion object {
        private const val KEY_ALIAS = "code_git_token_key"
        private const val TAG = "EditorPrefs"

        /** Test seam: Robolectric has no AndroidKeyStore; tests install a fake sealer here. */
        internal var sealerForTest: TokenSealer? = null

        const val DEFAULT_FONT_SIZE = 14
        const val DEFAULT_TAB_WIDTH = 4
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        private val FOLDER_PATH_KEY = stringPreferencesKey("folder_path")
        private val SOFT_WRAP_KEY = booleanPreferencesKey("soft_wrap")
        private val FONT_SIZE_KEY = intPreferencesKey("font_size")
        private val TAB_WIDTH_KEY = intPreferencesKey("tab_width")
        private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
        private val AUTO_INDENT_KEY = booleanPreferencesKey("auto_indent")
        private val AUTO_CLOSE_KEY = booleanPreferencesKey("auto_close_brackets")
        private val AUTO_SAVE_KEY = booleanPreferencesKey("auto_save")
        private val TRIM_TRAILING_KEY = booleanPreferencesKey("trim_trailing_on_save")
        private val FINAL_NEWLINE_KEY = booleanPreferencesKey("final_newline_on_save")
        private val EXPERIMENTAL_EDITOR_KEY = booleanPreferencesKey("experimental_editor")
        private val SHOW_WHITESPACE_KEY = booleanPreferencesKey("show_whitespace")
        private val SHOW_INDENT_GUIDES_KEY = booleanPreferencesKey("show_indent_guides")
        private val SHOW_MINIMAP_KEY = booleanPreferencesKey("show_minimap")
        private val EDITOR_THEME_KEY = stringPreferencesKey("editor_theme")
        private val SESSION_PATHS_KEY = stringPreferencesKey("session_paths")
        private val SESSION_CURRENT_KEY = stringPreferencesKey("session_current")
        private val RECENT_FILES_KEY = stringPreferencesKey("recent_files")
        private val USER_SNIPPETS_KEY = stringPreferencesKey("user_snippets")
        private val FOLD_STATE_KEY = stringPreferencesKey("fold_state")
        private val GIT_USERNAME_KEY = stringPreferencesKey("git_username")
        private val GIT_TOKEN_KEY = stringPreferencesKey("git_token")
        private val GIT_AUTHOR_NAME_KEY = stringPreferencesKey("git_author_name")
        private val GIT_AUTHOR_EMAIL_KEY = stringPreferencesKey("git_author_email")
    }
}

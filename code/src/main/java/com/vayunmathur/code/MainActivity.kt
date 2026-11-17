package com.vayunmathur.code

import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.code.data.EditorPrefs
import com.vayunmathur.code.platform.EditorViewModel
import com.vayunmathur.code.platform.checkExternalChanges
import com.vayunmathur.code.platform.openExternal
import com.vayunmathur.library.ui.AppPermissionsGate
import com.vayunmathur.library.ui.AppPermissionsSpec
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconFolderOpen
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.ui.PermissionRequirement

class MainActivity : MaActivity() {
    private val viewModel: EditorViewModel by viewModels()

    override fun onCreateBeforeContent() {
        handleIntent(intent)
    }

    @Composable
    override fun AppTheme(content: @Composable () -> Unit) {
        val darkTheme = when (viewModel.themeMode) {
            EditorPrefs.THEME_LIGHT -> false
            EditorPrefs.THEME_DARK -> true
            else -> null
        }
        DynamicTheme(darkTheme = darkTheme, content = content)
    }

    @Composable
    override fun Content() {
        AppPermissionsGate(
            spec = AppPermissionsSpec(
                title = stringResource(R.string.storage_permission_title),
                subtitle = stringResource(R.string.storage_permission_rationale),
                icon = { IconFolderOpen() },
                requirements = listOf(PermissionRequirement.AllFiles)
            )
        ) {
            Navigation(viewModel)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkExternalChanges()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** VIEW/EDIT opens from other apps carry the file in [Intent.getData]; open it in a tab. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_EDIT) {
            intent.data?.let { viewModel.openExternal(it) }
        }
    }
}

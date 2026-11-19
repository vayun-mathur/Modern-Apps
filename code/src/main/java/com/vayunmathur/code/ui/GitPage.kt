package com.vayunmathur.code.ui

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.code.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.code.Route
import com.vayunmathur.code.platform.EditorViewModel
import com.vayunmathur.code.platform.clearDiffRows
import com.vayunmathur.code.platform.clearGitDiff
import com.vayunmathur.code.platform.clearGitMessage
import com.vayunmathur.code.platform.gitCheckout
import com.vayunmathur.code.platform.gitClone
import com.vayunmathur.code.platform.gitCommit
import com.vayunmathur.code.platform.gitCreateBranch
import com.vayunmathur.code.platform.gitInit
import com.vayunmathur.code.platform.gitPull
import com.vayunmathur.code.platform.gitPush
import com.vayunmathur.code.platform.gitStage
import com.vayunmathur.code.platform.gitUnstage
import com.vayunmathur.code.platform.loadGitDiff
import com.vayunmathur.code.platform.loadSideBySideDiff
import com.vayunmathur.code.platform.refreshGit
import com.vayunmathur.code.platform.setGitAuthorEmail
import com.vayunmathur.code.platform.setGitAuthorName
import com.vayunmathur.code.platform.setGitToken
import com.vayunmathur.code.platform.setGitUsername
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconMerge
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconRefresh
import com.vayunmathur.library.ui.IconRemove
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.OverflowMenu
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.util.NavBackStack
import java.io.File

/**
 * Source Control panel over the open project: branch switcher, staged/unstaged change lists with
 * per-file stage toggles, a commit box, pull/push, a unified diff view, plus init/clone and the
 * HTTPS + PAT credentials. All git work runs in the ViewModel off the main thread.
 */
@Composable
fun GitPage(viewModel: EditorViewModel, backStack: NavBackStack<Route>) {
    LaunchedEffect(Unit) { viewModel.refreshGit() }

    AppScaffold(
        title = stringResource(R.string.source_control),
        backStack = backStack,
        actions = {
            IconButton(onClick = { viewModel.refreshGit() }) { IconRefresh() }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            if (viewModel.gitBusy) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }
            }
            viewModel.gitMessage?.let { message ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    IconButton(onClick = { viewModel.clearGitMessage() }) { IconClose() }
                }
            }

            if (viewModel.gitIsRepo) {
                RepoSection(viewModel)
            } else {
                NoRepoSection(viewModel)
            }

            CredentialsSection(viewModel)
        }
    }

    viewModel.gitDiff?.let { diff ->
        GitPageSection(diff = diff, onDismiss = { viewModel.clearGitDiff() })
    }

    viewModel.gitDiffRows?.let { rows ->
        SideBySideDiffDialog(rows = rows, onDismiss = { viewModel.clearDiffRows() })
    }
}

@Composable
private fun NoRepoSection(viewModel: EditorViewModel) {
    var cloneUrl by remember { mutableStateOf("") }
    SettingsSection(title = stringResource(R.string.source_control)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.not_a_git_repo),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { viewModel.gitInit() }) { Text(stringResource(R.string.git_init)) }
            HorizontalDivider()
            OutlinedTextField(
                value = cloneUrl,
                onValueChange = { cloneUrl = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.clone_url_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Button(
                onClick = {
                    val into = cloneTargetFor(cloneUrl)
                    if (into != null) viewModel.gitClone(cloneUrl.trim(), into)
                },
                enabled = cloneUrl.isNotBlank(),
            ) { Text(stringResource(R.string.git_clone)) }
        }
    }
}

/** Clones into a sibling of the external storage root named after the repo in the URL. */
private fun cloneTargetFor(url: String): File? {
    val name = url.trim().trimEnd('/').substringAfterLast('/').removeSuffix(".git")
    if (name.isBlank()) return null
    return File(Environment.getExternalStorageDirectory(), name)
}

@Composable
private fun RepoSection(viewModel: EditorViewModel) {
    val status = viewModel.gitStatus
    var message by remember { mutableStateOf("") }
    var showNewBranch by remember { mutableStateOf(false) }

    SettingsSection(title = stringResource(R.string.branch)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconMerge()
            Spacer(Modifier.size(8.dp))
            Text(status?.branch.orEmpty(), Modifier.weight(1f))
            OverflowMenu(icon = { Text(stringResource(R.string.switch_branch)) }) {
                viewModel.gitBranches.forEach { branch ->
                    Item(text = branch) { viewModel.gitCheckout(branch) }
                }
            }
            TextButton(onClick = { showNewBranch = true }) { Text(stringResource(R.string.new_branch)) }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { viewModel.gitPull() }) { Text(stringResource(R.string.git_pull)) }
            Button(onClick = { viewModel.gitPush() }) { Text(stringResource(R.string.git_push)) }
        }
    }

    SettingsSection(title = stringResource(R.string.commit)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.commit_message)) },
            )
            Button(
                onClick = { viewModel.gitCommit(message.trim()); message = "" },
                enabled = message.isNotBlank() && status?.staged?.isNotEmpty() == true,
            ) { Text(stringResource(R.string.commit)) }
        }
    }

    if (status != null) {
        ChangeList(
            title = stringResource(R.string.staged_changes),
            paths = status.staged,
            staged = true,
            viewModel = viewModel,
        )
        ChangeList(
            title = stringResource(R.string.changes),
            paths = status.unstaged + status.untracked,
            staged = false,
            viewModel = viewModel,
        )
        if (status.isClean) {
            Text(
                stringResource(R.string.no_changes),
                Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showNewBranch) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewBranch = false },
            title = { Text(stringResource(R.string.new_branch)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(UiR.string.name)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.gitCreateBranch(name.trim()); showNewBranch = false },
                    enabled = name.isNotBlank(),
                ) { Text(stringResource(UiR.string.create)) }
            },
            dismissButton = {
                TextButton(onClick = { showNewBranch = false }) { Text(stringResource(UiR.string.cancel)) }
            },
        )
    }
}

@Composable
private fun ChangeList(title: String, paths: List<String>, staged: Boolean, viewModel: EditorViewModel) {
    if (paths.isEmpty()) return
    Text(
        text = title,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    paths.forEach { path ->
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { viewModel.loadGitDiff(path, staged) }
                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(path, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            OverflowMenu(icon = { IconMoreVert() }) {
                Item(text = stringResource(R.string.diff)) { viewModel.loadGitDiff(path, staged) }
                Item(text = stringResource(R.string.side_by_side)) { viewModel.loadSideBySideDiff(path, staged) }
            }
            if (staged) {
                IconButton(onClick = { viewModel.gitUnstage(path) }) { IconRemove() }
            } else {
                IconButton(onClick = { viewModel.gitStage(path) }) { IconAdd() }
            }
        }
    }
}

@Composable
private fun CredentialsSection(viewModel: EditorViewModel) {
    SettingsSection(title = stringResource(R.string.git_credentials)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = viewModel.gitUsername,
                onValueChange = viewModel::setGitUsername,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.git_username)) },
            )
            OutlinedTextField(
                value = viewModel.gitToken,
                onValueChange = viewModel::setGitToken,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.git_token)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            OutlinedTextField(
                value = viewModel.gitAuthorName,
                onValueChange = viewModel::setGitAuthorName,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.git_author_name)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.PersonName),
            )
            OutlinedTextField(
                value = viewModel.gitAuthorEmail,
                onValueChange = viewModel::setGitAuthorEmail,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.git_author_email)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )
        }
    }
}

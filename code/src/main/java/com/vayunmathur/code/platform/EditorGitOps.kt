package com.vayunmathur.code.platform
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.vayunmathur.code.data.GitRepo

internal const val GIT_LOG_LIMIT = 30

/** Re-reads repo status, log and branches for the open folder (no-op if none is open). */
fun EditorViewModel.refreshGit() {
    val dir = rootDir
    if (dir == null) {
        gitIsRepo = false
        gitStatus = null
        gitLog.clear()
        gitBranches.clear()
        return
    }
    viewModelScope.launch {
        gitIsRepo = withContext(Dispatchers.IO) { GitRepo.isRepo(dir) }
        if (gitIsRepo) {
            runCatching { loadGitState(dir) }.onFailure { gitMessage = it.message ?: it.toString() }
        } else {
            gitStatus = null
            gitLog.clear()
            gitBranches.clear()
        }
    }
}

internal suspend fun EditorViewModel.loadGitState(dir: File) {
    val status = withContext(Dispatchers.IO) { GitRepo.status(dir) }
    val log = withContext(Dispatchers.IO) { GitRepo.log(dir, GIT_LOG_LIMIT) }
    val branches = withContext(Dispatchers.IO) { GitRepo.branches(dir) }
    gitStatus = status
    gitLog.clear(); gitLog.addAll(log)
    gitBranches.clear(); gitBranches.addAll(branches)
}

/** Runs a git mutation off-main, surfacing failures in [EditorViewModel.gitMessage], then refreshes status. */
internal fun EditorViewModel.gitOp(block: suspend (File) -> Unit) {
    val dir = rootDir ?: return
    viewModelScope.launch {
        gitBusy = true
        gitMessage = null
        runCatching { withContext(Dispatchers.IO) { block(dir) } }
            .onFailure { gitMessage = it.message ?: it.toString() }
        gitIsRepo = withContext(Dispatchers.IO) { GitRepo.isRepo(dir) }
        if (gitIsRepo) runCatching { loadGitState(dir) }
        gitBusy = false
        checkExternalChanges()
    }
}

fun EditorViewModel.gitInit() = gitOp { GitRepo.init(it) }
fun EditorViewModel.gitStage(path: String) = gitOp { GitRepo.stage(it, path) }
fun EditorViewModel.gitUnstage(path: String) = gitOp { GitRepo.unstage(it, path) }
fun EditorViewModel.gitPull() = gitOp { GitRepo.pull(it, gitUsername, gitToken) }
fun EditorViewModel.gitPush() = gitOp { GitRepo.push(it, gitUsername, gitToken) }
fun EditorViewModel.gitCheckout(name: String) = gitOp { GitRepo.checkout(it, name) }

fun EditorViewModel.gitCreateBranch(name: String) = gitOp {
    GitRepo.createBranch(it, name)
    GitRepo.checkout(it, name)
}

fun EditorViewModel.gitCommit(message: String) = gitOp { dir ->
    val name = gitAuthorName.ifBlank { "Code" }
    val email = gitAuthorEmail.ifBlank { "code@localhost" }
    GitRepo.commit(dir, message, name, email)
}

/** Clones [url] into [into] and, on success, opens it as the project. */
fun EditorViewModel.gitClone(url: String, into: File) {
    viewModelScope.launch {
        gitBusy = true
        gitMessage = null
        val result = runCatching {
            withContext(Dispatchers.IO) { GitRepo.clone(url, into, gitUsername, gitToken) }
        }
        gitBusy = false
        result.onSuccess { openFolder(into) }
            .onFailure { gitMessage = it.message ?: it.toString() }
    }
}

fun EditorViewModel.loadGitDiff(path: String, staged: Boolean) {
    val dir = rootDir ?: return
    viewModelScope.launch {
        gitDiff = runCatching {
            withContext(Dispatchers.IO) { GitRepo.diff(dir, path, staged) }
        }.getOrDefault("")
    }
}

/** Loads the side-by-side (aligned rows) diff for [path] into [EditorViewModel.gitDiffRows]. */
fun EditorViewModel.loadSideBySideDiff(path: String, staged: Boolean) {
    val dir = rootDir ?: return
    viewModelScope.launch {
        gitDiffRows = runCatching {
            withContext(Dispatchers.IO) { GitRepo.structuredDiff(dir, path, staged) }
        }.getOrDefault(emptyList())
    }
}

fun EditorViewModel.clearDiffRows() {
    gitDiffRows = null
}

fun EditorViewModel.clearGitDiff() {
    gitDiff = null
}

fun EditorViewModel.clearGitMessage() {
    gitMessage = null
}

fun EditorViewModel.setGitUsername(value: String) {
    gitUsernameState.value = value
    viewModelScope.launch { prefs.setGitUsername(value) }
}

fun EditorViewModel.setGitToken(value: String) {
    gitTokenState.value = value
    viewModelScope.launch { prefs.setGitToken(value) }
}

fun EditorViewModel.setGitAuthorName(value: String) {
    gitAuthorNameState.value = value
    viewModelScope.launch { prefs.setGitAuthorName(value) }
}

fun EditorViewModel.setGitAuthorEmail(value: String) {
    gitAuthorEmailState.value = value
    viewModelScope.launch { prefs.setGitAuthorEmail(value) }
}

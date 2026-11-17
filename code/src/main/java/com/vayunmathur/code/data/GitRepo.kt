package com.vayunmathur.code.data
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.filter.PathFilter
import java.io.ByteArrayOutputStream
import java.io.File
import com.vayunmathur.code.domain.DiffRow
import com.vayunmathur.code.domain.parseUnifiedDiff

/** Current repository status: the branch plus the three change buckets, each a list of paths. */
data class GitStatus(
    val branch: String,
    val staged: List<String>,
    val unstaged: List<String>,
    val untracked: List<String>,
) {
    val isClean: Boolean get() = staged.isEmpty() && unstaged.isEmpty() && untracked.isEmpty()
}

/** One entry in the commit log. */
data class GitCommitInfo(
    val shortId: String,
    val message: String,
    val author: String,
    val timeSeconds: Long,
)

/**
 * IO-only wrapper over JGit. Every call opens the repository fresh and closes it, so callers hold
 * no handles; all methods block and must run off the main thread (the ViewModel does this).
 *
 * Remote auth is HTTPS + Personal Access Token only (no SSH). JGit's default transport is
 * `HttpURLConnection`-based, so no extra HTTP dependency is pulled in.
 */
object GitRepo {

    /** Abbreviated commit id length shown in the log. */
    const val SHORT_ID_LENGTH = 7

    /** True if [dir] (or JGit's discovery from it) contains a git repository. */
    fun isRepo(dir: File): Boolean = File(dir, Constants.DOT_GIT).exists()

    fun init(dir: File) {
        Git.init().setDirectory(dir).call().close()
    }

    private inline fun <T> withRepo(dir: File, block: (Git) -> T): T =
        Git.open(dir).use(block)

    fun currentBranch(dir: File): String = withRepo(dir) { it.repository.branch ?: "" }

    fun status(dir: File): GitStatus = withRepo(dir) { git ->
        val s = git.status().call()
        GitStatus(
            branch = git.repository.branch ?: "",
            staged = (s.added + s.changed + s.removed).sorted().distinct(),
            unstaged = (s.modified + s.missing).sorted().distinct(),
            untracked = s.untracked.sorted(),
        )
    }

    fun stage(dir: File, path: String) = withRepo(dir) { git ->
        // add() also stages deletions on recent JGit; call rm for a path that no longer exists.
        if (File(dir, path).exists()) {
            git.add().addFilepattern(path).call()
        } else {
            git.rm().addFilepattern(path).setCached(true).call()
        }
        Unit
    }

    fun unstage(dir: File, path: String) = withRepo(dir) { git ->
        git.reset().addPath(path).call()
        Unit
    }

    fun commit(dir: File, message: String, name: String, email: String): String = withRepo(dir) { git ->
        val commit = git.commit()
            .setMessage(message)
            .setAuthor(name, email)
            .setCommitter(name, email)
            .call()
        commit.abbreviate(SHORT_ID_LENGTH).name()
    }

    fun log(dir: File, max: Int = 50): List<GitCommitInfo> = withRepo(dir) { git ->
        if (git.repository.resolve(Constants.HEAD) == null) return@withRepo emptyList()
        git.log().setMaxCount(max).call().map { commit ->
            GitCommitInfo(
                shortId = commit.abbreviate(SHORT_ID_LENGTH).name(),
                message = commit.shortMessage,
                author = commit.authorIdent.name,
                timeSeconds = commit.commitTime.toLong(),
            )
        }
    }

    /** Unified diff for [path]; [staged] chooses the index-vs-HEAD diff over the worktree one. */
    fun diff(dir: File, path: String, staged: Boolean): String = withRepo(dir) { git ->
        ByteArrayOutputStream().use { out ->
            git.diff()
                .setOutputStream(out)
                .setCached(staged)
                .setPathFilter(PathFilter.create(path))
                .call()
            out.toString(Charsets.UTF_8.name())
        }
    }

    /** The [diff] for [path] parsed into aligned rows for the side-by-side viewer. */
    fun structuredDiff(dir: File, path: String, staged: Boolean): List<DiffRow> =
        parseUnifiedDiff(diff(dir, path, staged))

    fun branches(dir: File): List<String> = withRepo(dir) { git ->
        git.branchList().call().map { it.name.removePrefix(Constants.R_HEADS) }
    }

    fun createBranch(dir: File, name: String) = withRepo(dir) { git ->
        git.branchCreate().setName(name).call()
        Unit
    }

    fun checkout(dir: File, name: String) = withRepo(dir) { git ->
        git.checkout().setName(name).call()
        Unit
    }

    fun clone(url: String, dir: File, username: String, token: String) {
        Git.cloneRepository()
            .setURI(url)
            .setDirectory(dir)
            .apply { if (token.isNotBlank()) setCredentialsProvider(credentials(username, token)) }
            .call()
            .close()
    }

    fun pull(dir: File, username: String, token: String) = withRepo(dir) { git ->
        git.pull()
            .apply { if (token.isNotBlank()) setCredentialsProvider(credentials(username, token)) }
            .call()
        Unit
    }

    fun push(dir: File, username: String, token: String) = withRepo(dir) { git ->
        git.push()
            .apply { if (token.isNotBlank()) setCredentialsProvider(credentials(username, token)) }
            .call()
        Unit
    }

    /** For a GitHub-style PAT the username is often the token itself; fall back to that if blank. */
    private fun credentials(username: String, token: String) =
        UsernamePasswordCredentialsProvider(username.ifBlank { token }, token)
}

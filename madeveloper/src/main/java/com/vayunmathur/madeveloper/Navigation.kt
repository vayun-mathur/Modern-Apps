package com.vayunmathur.madeveloper

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.madeveloper.platform.MaDevViewModel
import com.vayunmathur.madeveloper.ui.CatalogScreen
import com.vayunmathur.madeveloper.ui.CommitScreen
import com.vayunmathur.madeveloper.ui.CommitsScreen
import com.vayunmathur.madeveloper.ui.FileScreen
import com.vayunmathur.madeveloper.ui.HomeScreen
import com.vayunmathur.madeveloper.ui.IssuesScreen
import com.vayunmathur.madeveloper.ui.PullRequestsScreen
import com.vayunmathur.madeveloper.ui.PushScreen
import com.vayunmathur.madeveloper.ui.RepoScreen
import com.vayunmathur.madeveloper.ui.TestsScreen

/** Navigation for Developer. */
@Composable
fun Navigation(viewModel: MaDevViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Home)
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.loadHome() }
    MainNavigation(backStack) {
        entry<Route.Home> {
            HomeScreen(
                state = viewModel.state,
                onRefresh = viewModel::refresh,
                onOpenRepo = { backStack.add(Route.Repo(it)) },
                onOpenTests = { backStack.add(Route.Tests) },
                onOpenCatalog = { backStack.add(Route.Catalog) },
                onOpenPush = { backStack.add(Route.Push) },
                onOpenIssues = { backStack.add(Route.Issues) },
                onOpenPrs = { backStack.add(Route.Prs) },
            )
        }
        entry<Route.Issues> {
            IssuesScreen(
                state = viewModel.state,
                onOpenIssue = { issue -> ExternalIntents.openUrl(context, issue.webUrl) },
                onBack = { backStack.pop() },
            )
        }
        entry<Route.Prs> {
            PullRequestsScreen(
                state = viewModel.state,
                onOpenPr = { pr ->
                    if (viewModel.repoByName(pr.hostRepo) != null) {
                        backStack.add(Route.Repo(pr.hostRepo))
                    } else {
                        ExternalIntents.openUrl(context, pr.webUrl)
                    }
                },
                onBack = { backStack.pop() },
            )
        }
        entry<Route.Repo> { route ->
            RepoScreen(
                repoName = route.name, viewModel = viewModel,
                onOpenFile = { branch, path -> backStack.add(Route.File(route.name, path, branch)) },
                onOpenCommits = { branch -> backStack.add(Route.Commits(route.name, branch)) },
                onOpenCommit = { branch, sha ->
                    backStack.add(Route.Commit(route.name, branch, sha))
                },
                onBack = { backStack.pop() },
            )
        }
        entry<Route.Commits> { route ->
            CommitsScreen(
                repoName = route.repo, branch = route.branch, viewModel = viewModel,
                onOpenCommit = { sha ->
                    backStack.add(Route.Commit(route.repo, route.branch, sha))
                },
                onBack = { backStack.pop() },
            )
        }
        entry<Route.Commit> { route ->
            CommitScreen(repoName = route.repo, sha = route.sha, viewModel = viewModel, onBack = { backStack.pop() })
        }
        entry<Route.File> { route ->
            FileScreen(
                repoName = route.repo, path = route.path, branch = route.branch,
                viewModel = viewModel, onBack = { backStack.pop() },
            )
        }
        entry<Route.Tests> { TestsScreen(state = viewModel.state, onBack = { backStack.pop() }) }
        entry<Route.Catalog> {
            LaunchedEffect(Unit) { viewModel.loadCatalog() }
            CatalogScreen(state = viewModel.state, onBack = { backStack.pop() })
        }
        entry<Route.Push> {
            PushScreen(state = viewModel.state, onInstall = viewModel::installPending, onBack = { backStack.pop() })
        }
    }
}

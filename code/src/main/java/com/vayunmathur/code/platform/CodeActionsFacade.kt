package com.vayunmathur.code.platform
/**
 * Composes the five role action delegates into one [CodeActions] via interface delegation.
 *
 * Kotlin generates all forwarding methods, so this class declares no functions of its own
 * and stays clear of the function budget. The ViewModel exposes it as
 * [EditorViewModel.actions] instead of implementing the 47-method [CodeActions] itself.
 */
internal class CodeActionsFacade(
    tab: CodeTabActions,
    tree: CodeTreeActions,
    edit: CodeEditActions,
    search: CodeSearchActions,
    settings: CodeSettingsActions,
) : CodeActions,
    CodeTabActions by tab,
    CodeTreeActions by tree,
    CodeEditActions by edit,
    CodeSearchActions by search,
    CodeSettingsActions by settings

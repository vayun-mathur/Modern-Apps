package com.vayunmathur.keyboard.platform.ime

import com.vayunmathur.keyboard.data.ClipItem
import com.vayunmathur.keyboard.domain.KeyboardPage

/**
 * The [ImeActions] implementation, delegating to the `KeyboardService*Ops.kt`
 * extension functions. Lets the UI talk to the interface without the service class
 * itself carrying every key-action method (TooManyFunctions).
 */
internal class KeyboardActionHandler(private val service: KeyboardService) : ImeActions {
    override fun onChar(text: String) = service.onChar(text)
    override fun onBackspace() = service.onBackspace()
    override fun onEnter() = service.onEnter()
    override fun onSpace() = service.onSpace()
    override fun onShift() = service.onShift()
    override fun setPage(page: KeyboardPage) = service.setPage(page)
    override fun commitSuggestion(word: String) = service.commitSuggestion(word)
    override fun startEmojiSearch() = service.startEmojiSearch()
    override fun endEmojiSearch() = service.endEmojiSearch()
    override fun commitEmoji(emoji: String) = service.commitEmoji(emoji)
    override fun pasteClip(item: ClipItem) = service.pasteClip(item)
    override fun deleteClip(item: ClipItem) = service.deleteClip(item)
    override fun clearClips() = service.clearClips()
    override fun dismissClipSuggestion() = service.dismissClipSuggestion()
    override fun onVoiceInput() = service.onVoiceInput()
    override fun stopVoiceInput() = service.stopVoiceInput()
    override fun dismissVoice() = service.dismissVoice()
    override fun openVoiceSettings() = service.openVoiceSettings()
}

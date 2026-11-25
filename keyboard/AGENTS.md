# AGENTS.md — keyboard/ (':keyboard')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `keyboard/` + allowed shared modules. Do not root-scan.

_Install: ./install keyboard (dev by default)._
- Gradle: :keyboard / dir: keyboard/
- Package roots present: ui, platform
- Entry files: MainActivity.kt (settings/setup UI) + ime/KeyboardService.kt (the actual IME entry)
- Deps: (none — zero network permission by design)
- Metadata: metadata_data/keyboard.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** Private offline keyboard (IME) with Material You — no network permission at all, so keystrokes cannot leave the device.
**Key features:**
- Full IME (`KeyboardService`) with letters/symbols/function rows, key popups, adjustable height, suggestion strip
- CJK + Indic composition: Han (Pinyin), Kana, Hangul, Ethiopic composers over `TextBeforeCursor`
- Emoji pages + search, clipboard strip/page, voice-input strip (gated by a permission activity)
**Architecture / data-flow:** System binds `ime/KeyboardService` → `ime/KeyboardState` + `Composer` routespre-edit composition → `ui/` rows/pages render keys; `util/` holds layouts (Latin/Cyrillic/Other), dictionaries (incl. Pinyin), prefs, clipboard store. `MainActivity` is only the setup/settings UI, not the keyboard.
**Entry points + reading order:** `ime/KeyboardService.kt` → `ime/KeyboardState.kt` → `ime/Composer.kt` → `ui/KeyboardScreen.kt` → `util/KeyboardLayouts*.kt`.
**Gotchas:**
- `ime/` and `util/` are NOT closed package roots — don't add more files there
- No-network is a guarantee, not an accident — never add a network dep or permission here
- Voice input needs its own permission activity before the strip works

## Key files
### ime/ — service, state, composers (the real entry)
- com/vayunmathur/keyboard/ime/KeyboardService.kt — IME entry
- com/vayunmathur/keyboard/ime/KeyboardState.kt
- com/vayunmathur/keyboard/ime/Composer.kt
- com/vayunmathur/keyboard/ime/HanComposer.kt
- com/vayunmathur/keyboard/ime/KanaComposers.kt
- com/vayunmathur/keyboard/ime/HangulComposer.kt
- com/vayunmathur/keyboard/ime/EthiopicComposer.kt
- com/vayunmathur/keyboard/ime/TextBeforeCursor.kt
### platform/ — voice input
- com/vayunmathur/keyboard/platform/VoiceInput.kt
- com/vayunmathur/keyboard/platform/VoicePermissionActivity.kt
### ui/ — rows, pages, setup
- com/vayunmathur/keyboard/MainActivity.kt — setup/settings only
- com/vayunmathur/keyboard/ui/KeyboardScreen.kt
- com/vayunmathur/keyboard/ui/KeyboardLetters.kt
- com/vayunmathur/keyboard/ui/KeyboardSymbolPages.kt
- com/vayunmathur/keyboard/ui/KeyboardBottomRow.kt
- com/vayunmathur/keyboard/ui/FunctionKeys.kt
- com/vayunmathur/keyboard/ui/KeyButton.kt
- com/vayunmathur/keyboard/ui/KeyPopups.kt
- com/vayunmathur/keyboard/ui/SuggestionStrip.kt
- com/vayunmathur/keyboard/ui/EmojiPage.kt
- com/vayunmathur/keyboard/ui/EmojiSearch.kt
- com/vayunmathur/keyboard/ui/ClipboardPage.kt
- com/vayunmathur/keyboard/ui/ClipboardStrip.kt
- com/vayunmathur/keyboard/ui/VoiceStrip.kt
- com/vayunmathur/keyboard/ui/SetupScreen.kt
### util/ — layouts, dictionaries, prefs
- com/vayunmathur/keyboard/util/KeyboardLayout.kt
- com/vayunmathur/keyboard/util/KeyboardLayouts.kt
- com/vayunmathur/keyboard/util/KeyboardLayoutsLatin.kt
- com/vayunmathur/keyboard/util/KeyboardLayoutsCyrillic.kt
- com/vayunmathur/keyboard/util/KeyboardLayoutsOther.kt
- com/vayunmathur/keyboard/util/Layouts.kt
- com/vayunmathur/keyboard/util/Dictionary.kt
- com/vayunmathur/keyboard/util/PinyinDictionary.kt
- com/vayunmathur/keyboard/util/Emojis.kt
- com/vayunmathur/keyboard/util/Clipboard.kt
- com/vayunmathur/keyboard/util/KeyboardPrefs.kt

## Verify (this module only)
```
./gradlew :keyboard:compileDevKotlin
./gradlew :keyboard:lint
./gradlew :keyboard:checkMetadata
```

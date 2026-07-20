# Gradle Module Explorer

A VS Code extension that adds a custom **Modules** view to the activity bar for this
Gradle multi-module project. Written in plain JavaScript — no build step, no `npm install`
required to run it.

## What it shows

- A flat list of every Gradle module from `settings.gradle.kts`, using the Gradle
  colon path as the label (e.g. `games:chess`, `sdk:openassistant`).
- Top-level modules are sorted **alphabetically**, with the `library:*` group and then
  the `sdk:*` group pinned to the **bottom**.
- Expanding a module shows its **real filesystem**, with two things filtered out:
  - **Nested-module folders** (they appear as their own top-level entries instead).
  - **Build/IDE clutter**: `build/`, `.gradle/`, `.idea/`, `.cxx/`, `.kotlin/`, `out/`,
    `.git/`, `.DS_Store`.
- Clicking a file opens it. A **refresh** button (top-right of the view) re-reads
  `settings.gradle.kts`; it also auto-refreshes when that file changes.

The extension only activates in a workspace that contains a Gradle settings file
(`workspaceContains:settings.gradle.kts`), so it stays dormant in other projects.

## Run it (F5, no toolchain needed)

1. Open the `gradle-module-explorer` folder in VS Code.
2. Press **F5** ("Run Gradle Module Explorer").

This launches an Extension Development Host with the parent repo (`..`) already open, and
the **Modules** icon appears in the activity bar. Because it's plain JS, there is nothing
to compile.

## Install it permanently (optional)

Packaging a `.vsix` requires Node/npm (not currently on this machine). Once Node is
available:

```bash
cd gradle-module-explorer
npx @vscode/vsce package
code --install-extension gradle-module-explorer-0.0.1.vsix
```

Because it is gated on `settings.gradle.kts`, it only shows up when you open a Gradle
workspace.

## How modules are discovered

Modules come from parsing `include(":...")` statements in `settings.gradle.kts`. The
Gradle path maps to disk by replacing `:` with the path separator (e.g. `:games:chess`
→ `games/chess`), which matches this repo's default layout.

## Files

- `extension.js` — all the logic (module parsing, sorting, tree data provider).
- `package.json` — view container, view, command, and activation declarations.
- `resources/modules.svg` — activity-bar icon.

const vscode = require('vscode');
const fs = require('fs');
const path = require('path');

/** Directory/file names hidden from every filesystem view (Gradle/IDE clutter). */
const IGNORED_NAMES = new Set([
  'build',
  '.gradle',
  '.idea',
  '.cxx',
  '.kotlin',
  'out',
  '.git',
  '.DS_Store',
]);

/** First workspace folder that contains a Gradle settings file, else the first folder. */
function findGradleRoot() {
  const folders = vscode.workspace.workspaceFolders;
  if (!folders || folders.length === 0) {
    return undefined;
  }
  for (const folder of folders) {
    const root = folder.uri.fsPath;
    if (
      fs.existsSync(path.join(root, 'settings.gradle.kts')) ||
      fs.existsSync(path.join(root, 'settings.gradle'))
    ) {
      return root;
    }
  }
  return folders[0].uri.fsPath;
}

/** Read the settings file and extract every included module via `include(":a:b")`. */
function parseModules(root) {
  let content = '';
  for (const name of ['settings.gradle.kts', 'settings.gradle']) {
    const file = path.join(root, name);
    if (fs.existsSync(file)) {
      content = fs.readFileSync(file, 'utf8');
      break;
    }
  }

  const modules = [];
  const seen = new Set();
  const re = /include\s*\(?\s*(["'])\s*:([^"']+)\1/g;
  let match;
  while ((match = re.exec(content)) !== null) {
    const gradlePath = match[2].trim();
    if (!gradlePath || seen.has(gradlePath)) {
      continue;
    }
    seen.add(gradlePath);
    modules.push({
      gradlePath,
      dir: path.join(root, ...gradlePath.split(':')),
    });
  }
  return modules;
}

/** 0 = normal (top, alphabetical), 1 = library group, 2 = sdk group (both at bottom). */
function groupRank(gradlePath) {
  const first = gradlePath.split(':')[0];
  if (first === 'library') {
    return 1;
  }
  if (first === 'sdk') {
    return 2;
  }
  return 0;
}

function sortModules(modules) {
  return [...modules].sort((a, b) => {
    const ra = groupRank(a.gradlePath);
    const rb = groupRank(b.gradlePath);
    if (ra !== rb) {
      return ra - rb;
    }
    return a.gradlePath.localeCompare(b.gradlePath);
  });
}

class ModuleTreeProvider {
  constructor() {
    this._onDidChangeTreeData = new vscode.EventEmitter();
    this.onDidChangeTreeData = this._onDidChangeTreeData.event;
    this.modules = [];
    this.moduleDirs = [];
    this.reload();
  }

  refresh() {
    this.reload();
    this._onDidChangeTreeData.fire();
  }

  reload() {
    this.root = findGradleRoot();
    this.modules = this.root ? sortModules(parseModules(this.root)) : [];
    this.moduleDirs = this.modules.map((m) => m.dir);
  }

  getTreeItem(node) {
    if (node.kind === 'module') {
      const item = new vscode.TreeItem(
        node.module.gradlePath,
        vscode.TreeItemCollapsibleState.Collapsed,
      );
      item.resourceUri = vscode.Uri.file(node.module.dir);
      item.iconPath = new vscode.ThemeIcon('package');
      item.tooltip = node.module.dir;
      item.contextValue = 'gradleModule';
      return item;
    }

    if (node.kind === 'dir') {
      // Compact a chain of single-child folders into one row (a/b/c), like
      // VS Code's "compact folders". resourceUri (final dir) gives the icon.
      const chain = this.collapseChain(node.path);
      const item = new vscode.TreeItem(
        chain.label,
        vscode.TreeItemCollapsibleState.Collapsed,
      );
      item.resourceUri = vscode.Uri.file(chain.finalDir);
      item.iconPath = vscode.ThemeIcon.Folder;
      return item;
    }

    const item = new vscode.TreeItem(
      vscode.Uri.file(node.path),
      vscode.TreeItemCollapsibleState.None,
    );
    item.command = {
      command: 'vscode.open',
      title: 'Open',
      arguments: [vscode.Uri.file(node.path)],
    };
    return item;
  }

  getChildren(node) {
    if (!node) {
      return this.modules.map((module) => ({ kind: 'module', module }));
    }
    if (node.kind === 'file') {
      return [];
    }
    if (node.kind === 'module') {
      return this.readModuleChildren(node.module.dir);
    }
    // Descend through any collapsed single-folder chain first.
    return this.readDir(this.collapseChain(node.path).finalDir);
  }

  /**
   * Children shown directly under a module. Collapses the `src/` layer: its
   * children (main, androidTest, test, ...) are lifted to the module root and
   * shown alongside the module's other files/folders.
   */
  readModuleChildren(moduleDir) {
    let entries;
    try {
      entries = fs.readdirSync(moduleDir, { withFileTypes: true });
    } catch (e) {
      return [];
    }

    const dirs = [];
    const files = [];
    for (const entry of entries) {
      if (IGNORED_NAMES.has(entry.name)) {
        continue;
      }
      const full = path.join(moduleDir, entry.name);
      if (entry.isDirectory()) {
        if (entry.name === 'src') {
          // Lift src/'s children up to the module root instead of showing src/.
          for (const child of this.readDir(full)) {
            (child.kind === 'dir' ? dirs : files).push(child);
          }
          continue;
        }
        if (this.isModuleSubtree(full)) {
          continue; // Shown as its own top-level module entry instead.
        }
        dirs.push({ kind: 'dir', path: full });
      } else if (entry.isFile()) {
        files.push({ kind: 'file', path: full });
      }
    }

    const byBasename = (a, b) =>
      path.basename(a.path).localeCompare(path.basename(b.path));
    dirs.sort(byBasename);
    files.sort(byBasename);
    return [...dirs, ...files];
  }

  /** True if `dir` is a module directory or an ancestor of one (a nested-module subtree). */
  isModuleSubtree(dir) {
    return this.moduleDirs.some(
      (md) => md === dir || md.startsWith(dir + path.sep),
    );
  }

  /**
   * Follow a run of single-child directories starting at `dir`. Returns the
   * deepest directory reached and a "a/b/c" label of the joined segment names.
   * Stops when a directory has zero or multiple visible children, or its single
   * child is a file.
   */
  collapseChain(dir) {
    const segments = [path.basename(dir)];
    let current = dir;
    // Guard against pathological depth / symlink loops.
    for (let i = 0; i < 100; i++) {
      const children = this.readDir(current);
      if (children.length === 1 && children[0].kind === 'dir') {
        current = children[0].path;
        segments.push(path.basename(current));
      } else {
        break;
      }
    }
    return { finalDir: current, label: segments.join('/') };
  }

  readDir(dir) {
    let entries;
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch (e) {
      return [];
    }

    const dirs = [];
    const files = [];
    for (const entry of entries) {
      if (IGNORED_NAMES.has(entry.name)) {
        continue;
      }
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        if (this.isModuleSubtree(full)) {
          continue; // Shown as its own top-level module entry instead.
        }
        dirs.push({ kind: 'dir', path: full });
      } else if (entry.isFile()) {
        files.push({ kind: 'file', path: full });
      }
    }

    const byBasename = (a, b) =>
      path.basename(a.path).localeCompare(path.basename(b.path));
    dirs.sort(byBasename);
    files.sort(byBasename);
    return [...dirs, ...files];
  }
}

function activate(context) {
  const provider = new ModuleTreeProvider();

  context.subscriptions.push(
    vscode.window.registerTreeDataProvider('gradleModuleExplorer.view', provider),
    vscode.commands.registerCommand('gradleModuleExplorer.refresh', () =>
      provider.refresh(),
    ),
  );

  const watcher = vscode.workspace.createFileSystemWatcher(
    '**/settings.gradle{.kts,}',
  );
  watcher.onDidChange(() => provider.refresh());
  watcher.onDidCreate(() => provider.refresh());
  watcher.onDidDelete(() => provider.refresh());
  context.subscriptions.push(watcher);
}

function deactivate() {}

module.exports = { activate, deactivate };

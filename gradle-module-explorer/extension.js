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
      // Passing a Uri gives us the themed folder icon and the basename label.
      return new vscode.TreeItem(
        vscode.Uri.file(node.path),
        vscode.TreeItemCollapsibleState.Collapsed,
      );
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
    const dir = node.kind === 'module' ? node.module.dir : node.path;
    return this.readDir(dir);
  }

  /** True if `dir` is a module directory or an ancestor of one (a nested-module subtree). */
  isModuleSubtree(dir) {
    return this.moduleDirs.some(
      (md) => md === dir || md.startsWith(dir + path.sep),
    );
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

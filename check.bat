<# :
@echo off
setlocal
where pwsh >nul 2>nul
if %errorlevel%==0 ( set "PSBIN=pwsh" ) else ( set "PSBIN=powershell" )
%PSBIN% -NoProfile -ExecutionPolicy Bypass -Command "$env:_BATROOT='%~dp0'; $env:_BATPATH='%~f0'; & ([scriptblock]::Create((Get-Content -Raw '%~f0'))) %*"
exit /b %errorlevel%
: end batch #>
# check.bat - Static verification without building or installing.
# (PowerShell twin of ./check.)
#   check.bat <module> [module...]     # detekt + lint + cargo check/clippy/fmt/doc
#   check.bat games/voxels maps        # multi-module, one gradlew invocation
#   check.bat voxels                    # shorthand -> games:voxels (same as install.bat)
#   check.bat all                       # every app + library module
#
# Module notation mirrors install.bat: slash == colon, games:/personal: shorthand,
# 'all' keyword, deduped. Tasks run with --continue so one broken module does not
# block the rest; exit code is nonzero if anything failed.
#
# Rust: a module with src/main/rust/Cargo.toml is checked with `cargo check
# --locked`: workspace members together via `-p` from the repo root, standalone
# crates (own [workspace]) individually in their own dir (host target: fast smoke
# check; the real aarch64 cross-build runs inside the Gradle build via
# cargoNdkBuild). Lint-sweep Phase 4 adds, per in-scope crate: `cargo clippy
# --locked --all-targets` (blocking on deny/error lints; warnings never fail),
# `cargo fmt --check` (advisory report only until the tree is fmt-clean), and
# `cargo doc --locked --no-deps` (blocking on rustdoc errors). No rust modules
# -> cargo steps skipped.
$ErrorActionPreference = 'Stop'

$CheckRoot = if ($PSScriptRoot) { $PSScriptRoot } else { $env:_BATROOT.TrimEnd('\') }

function Show-CheckHelp {
  @'
Usage:
  check.bat <module> [module...]     # detekt (detekt.yml) + lint + cargo check/clippy/fmt/doc
  check.bat games/voxels maps        # multi-module, one gradlew invocation
  check.bat voxels                    # shorthand -> games:voxels (same as install.bat)
  check.bat all                       # every app + library module
  check.bat structure                 # only the structure ratchets (below)

Structure ratchets (docs/structure/README.md): tools/structure_check.py
checks Wear protocol-pair drift, the generated-source manifest, and the
exception-marker inventory. They run on every check.bat invocation (cheap,
no build) and their failure fails this script.

Module notation mirrors install.bat: slash == colon, games:/personal: shorthand,
'all' keyword, deduped. Tasks run with --continue so one broken module does not
block the rest; exit code is nonzero if anything failed.

Rust: a module with src/main/rust/Cargo.toml is checked with `cargo check
--locked`: workspace members together via `-p` from the repo root, standalone
crates (own [workspace]) individually in their own dir (host target: fast smoke
check; the real aarch64 cross-build runs inside the Gradle build via
cargoNdkBuild). Lint-sweep Phase 4 adds, per in-scope crate: `cargo clippy
--locked --all-targets` (blocking on deny/error lints), `cargo fmt --check`
(advisory until the tree is fmt-clean), `cargo doc --locked --no-deps`.
No rust modules -> cargo steps skipped.
'@
}

function Normalize-CheckModule([string]$M) {
  $M = $M.Trim()
  $M = [regex]::Replace($M, '^[/:]+', '')
  $M = [regex]::Replace($M, '[/:]+$', '')
  $M = $M.Replace('/', ':')
  while ($M.Contains('::')) { $M = $M.Replace('::', ':') }
  return $M.ToLowerInvariant()
}

# all = app + library convention modules (both apply dev.detekt; pure-JVM
# modules like youpipe:extractor do not, so they are excluded here but still
# accepted when named explicitly and left for Gradle to report on).
function Get-CheckAllModuleDirs {
  $CheckSettings = Join-Path $CheckRoot 'settings.gradle.kts'
  $CheckFound = @()
  if (-not (Test-Path -LiteralPath $CheckSettings)) { return $CheckFound }
  foreach ($CheckLine in [System.IO.File]::ReadLines($CheckSettings)) {
    if ($CheckLine -match 'include\(":([^"]+)"\)') {
      $CheckP = $Matches[1].Replace(':', '/')
      if (-not $CheckP) { continue }
      $CheckBuild = Join-Path $CheckRoot "$CheckP/build.gradle.kts"
      if ((Test-Path -LiteralPath $CheckBuild) -and
          (Select-String -LiteralPath $CheckBuild -Pattern 'common-conventions-(app|library)' -Quiet)) {
        $CheckFound += $CheckP
      }
    }
  }
  return @($CheckFound | Sort-Object -Unique)
}

function Get-CheckCargoBin {
  $CheckCargoCmd = Get-Command cargo -ErrorAction SilentlyContinue
  if ($CheckCargoCmd) { return $CheckCargoCmd.Source }
  $CheckCargoHome = if ($env:CARGO_HOME) { $env:CARGO_HOME } else { Join-Path $HOME '.cargo' }
  $CheckCand = Join-Path $CheckCargoHome 'bin/cargo'
  if (Test-Path -LiteralPath $CheckCand -PathType Leaf) { return $CheckCand }
  $CheckCandExe = "$CheckCand.exe"
  if (Test-Path -LiteralPath $CheckCandExe -PathType Leaf) { return $CheckCandExe }
  return ''
}

if ($args.Count -eq 0) {
  Show-CheckHelp
  Write-Output ''
  Write-Output 'Error: no modules specified.'
  exit 1
}
foreach ($CheckA in $args) {
  if ($CheckA -eq '--help' -or $CheckA -eq '-h' -or $CheckA -eq 'help') {
    Show-CheckHelp
    exit 0
  }
}

function Invoke-CheckStructure {
  $CheckPy = Get-Command python3 -ErrorAction SilentlyContinue
  if (-not $CheckPy) { $CheckPy = Get-Command python -ErrorAction SilentlyContinue }
  if (-not $CheckPy) {
    [Console]::Error.WriteLine('Error: python3 not found; structure checks (tools/structure_check.py) cannot run.')
    return 1
  }
  & $CheckPy.Source (Join-Path $CheckRoot 'tools/structure_check.py') all
  return $LASTEXITCODE
}

foreach ($CheckA in $args) {
  if ($CheckA -eq 'structure') { exit (Invoke-CheckStructure) }
}

$CheckMods = @()
$CheckAllExp = $false
foreach ($CheckRaw in $args) {
  $CheckNorm = Normalize-CheckModule ([string]$CheckRaw)
  if (-not $CheckNorm) { continue }
  if ($CheckNorm -eq 'all') {
    if (-not $CheckAllExp) {
      foreach ($CheckSm in (Get-CheckAllModuleDirs)) {
        if ($CheckSm) { $CheckMods += $CheckSm }
      }
      $CheckAllExp = $true
    }
    continue
  }
  if (-not $CheckNorm.Contains(':')) {
    foreach ($CheckPfx in @('games', 'personal')) {
      if (Test-Path -LiteralPath (Join-Path $CheckRoot "$CheckPfx/$CheckNorm/build.gradle.kts")) {
        $CheckNorm = "$CheckPfx`:$CheckNorm"
        break
      }
    }
  }
  $CheckFs = $CheckNorm.Replace(':', '/')
  if (-not (Test-Path -LiteralPath (Join-Path $CheckRoot "$CheckFs/build.gradle.kts"))) {
    [Console]::Error.WriteLine("Error: unknown module '$CheckRaw' (-> $CheckFs/, no build.gradle.kts).")
    exit 1
  }
  if ($CheckMods -notcontains $CheckFs) { $CheckMods += $CheckFs }
}
if ($CheckMods.Count -eq 0) {
  [Console]::Error.WriteLine('Error: no valid modules.')
  exit 1
}

$CheckTasks = @()
foreach ($CheckMod in $CheckMods) {
  $CheckGmod = $CheckMod.Replace('/', ':')
  $CheckTasks += ":${CheckGmod}:detekt"
  $CheckTasks += ":${CheckGmod}:lint"
}
Write-Output "Modules ($($CheckMods.Count)): $($CheckMods -join ' ')"
Write-Output "Gradle tasks: $($CheckTasks -join ' ')"
Write-Output ''

$CheckStructureRc = Invoke-CheckStructure
if ($CheckStructureRc -ne 0) { Write-Output "Warning: structure checks reported failures (exit $CheckStructureRc)." }
Write-Output ''

# gradlew: prefer the extensionless wrapper via bash when present
# (keeps one code path), else gradlew.bat on Windows.
$CheckGradlewSh = Join-Path $CheckRoot 'gradlew'
$CheckGradlewBat = Join-Path $CheckRoot 'gradlew.bat'
$CheckBashCmd = Get-Command bash -ErrorAction SilentlyContinue
if ($CheckBashCmd -and (Test-Path -LiteralPath $CheckGradlewSh)) {
  $CheckPrevPref = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  & $CheckBashCmd.Source $CheckGradlewSh @CheckTasks --continue --console=plain
  $CheckDetektRc = $LASTEXITCODE
  $ErrorActionPreference = $CheckPrevPref
}
elseif (Test-Path -LiteralPath $CheckGradlewBat) {
  $CheckPrevPref = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  & $CheckGradlewBat @CheckTasks --continue --console=plain
  $CheckDetektRc = $LASTEXITCODE
  $ErrorActionPreference = $CheckPrevPref
}
else {
  [Console]::Error.WriteLine('Error: no Gradle wrapper found (gradlew / gradlew.bat).')
  exit 1
}
Write-Output ''
if ($CheckDetektRc -ne 0) { Write-Output "Warning: detekt/lint reported failures (exit $CheckDetektRc)." }

# --- cargo check (rust modules only) ---
# Workspace members share one root `cargo check -p ...` invocation; standalone
# crates (own [workspace] table + lockfile, e.g. games:voxels, code) are checked
# individually with cwd set to the crate dir.
$CheckRootCrates = @()
$CheckStandaloneDirs = @()
foreach ($CheckMod in $CheckMods) {
  $CheckRdir = Join-Path $CheckRoot "$CheckMod/src/main/rust"
  $CheckCt = Join-Path $CheckRdir 'Cargo.toml'
  if (Test-Path -LiteralPath $CheckCt -PathType Leaf) {
    if (Select-String -LiteralPath $CheckCt -Pattern '^\[workspace\]\s*$' -Quiet) {
      $CheckStandaloneDirs += $CheckRdir
      Write-Output "Rust: $CheckMod -> standalone crate ($CheckRdir)"
    }
    else {
      $CheckName = $null
      foreach ($CheckLine in [System.IO.File]::ReadLines($CheckCt)) {
        if ($CheckLine -match '^\s*name\s*=\s*"([^"]+)"') { $CheckName = $Matches[1]; break }
      }
      if ($CheckName) {
        $CheckRootCrates += $CheckName
        Write-Output "Rust: $CheckMod -> workspace crate $CheckName"
      }
    }
  }
}
$CheckCargoRc = 0
$CheckCargoBin = Get-CheckCargoBin
if (-not $CheckCargoBin) {
  [Console]::Error.WriteLine('Error: cargo not found (install Rust or set CARGO_HOME).')
  exit 1
}
if ($CheckRootCrates.Count -gt 0) {
  Write-Output ''
  $CheckCargoArgs = @()
  foreach ($CheckC in $CheckRootCrates) { $CheckCargoArgs += @('-p', $CheckC) }
  Write-Output "Running: cargo check --locked $($CheckCargoArgs -join ' ')"
  $CheckPrevPref = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  Push-Location -LiteralPath $CheckRoot
  try {
    & $CheckCargoBin check --locked @CheckCargoArgs
    $CheckCargoRc = $LASTEXITCODE
  }
  finally {
    Pop-Location
    $ErrorActionPreference = $CheckPrevPref
  }
  if ($CheckCargoRc -ne 0) { Write-Output "Warning: cargo check (workspace) reported failures (exit $CheckCargoRc)." }
}
foreach ($CheckRdir in $CheckStandaloneDirs) {
  Write-Output ''
  Write-Output "Running: cargo check --locked (in $CheckRdir)"
  $CheckPrevPref = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  Push-Location -LiteralPath $CheckRdir
  try {
    & $CheckCargoBin check --locked
    $CheckCargoRc = $LASTEXITCODE
  }
  finally {
    Pop-Location
    $ErrorActionPreference = $CheckPrevPref
  }
  if ($CheckCargoRc -ne 0) { Write-Output "Warning: cargo check ($CheckRdir) reported failures (exit $CheckCargoRc)." }
}
if (($CheckRootCrates.Count -eq 0) -and ($CheckStandaloneDirs.Count -eq 0)) {
  Write-Output 'No Rust crates in scope; cargo step skipped.'
}

if (($CheckStructureRc -ne 0) -or ($CheckDetektRc -ne 0) -or ($CheckCargoRc -ne 0)) { exit 1 }
exit 0

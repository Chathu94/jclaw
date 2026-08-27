# Windows Support

JClaw supports Windows 11 and current Windows Server releases through Git Bash,
WSL2, or Docker Desktop. Git Bash is the preferred bare-metal path: the launcher
keeps its POSIX lifecycle semantics while the JVM and installed tools remain
native Windows processes.

## Choose a runtime

| Path | Best for | Boundary |
|---|---|---|
| Git Bash + Windows Java | Normal desktop/server installs, native Tesseract, ffmpeg, Node, MCP, printers, browsers, and local tools | ACP OS sandboxing is unavailable; leave `subagent.acp.sandbox=false` or use WSL2. |
| WSL2 | Linux-compatible development and sandboxed coding-harness runs | Install Java, Tesseract, ffmpeg, Node, and other subprocess tools inside the distro, not only on Windows. |
| Docker Desktop | Reproducible production runtime with Tesseract and Chromium already in the image | Host-only binaries are not visible unless explicitly mounted/configured. |

WSL1 is not a supported sandbox host. It lacks the Linux kernel features used by
Bubblewrap; use WSL2 when `subagent.acp.sandbox` is enabled.

## Install a release

Open PowerShell and run:

```powershell
irm https://raw.githubusercontent.com/tsukhani/jclaw/main/install.ps1 | iex
```

The installer downloads the self-contained bundle, provisions a user-local Java
25 runtime when needed, writes `jclaw.cmd`, adds `%USERPROFILE%\.local\bin` to
the user `PATH`, and starts JClaw through Git Bash or WSL2. Open a new terminal
after the first install so the updated `PATH` is visible.

Useful installer overrides:

```powershell
$env:JCLAW_NO_START = '1'               # install only
$env:JCLAW_VERSION = 'v0.18.0'          # pin a release
$env:JCLAW_BIN_DIR = 'D:\Tools\bin'     # launcher location
$env:JCLAW_INSTALL_TESSERACT = '1'      # optional OCR dependency
irm https://raw.githubusercontent.com/tsukhani/jclaw/main/install.ps1 | iex
```

Normal lifecycle commands work from PowerShell and cmd.exe:

```powershell
jclaw start
jclaw status
jclaw logs
jclaw restart
jclaw upgrade
jclaw stop
```

## Develop from source

Install Git for Windows, JDK 25, Node 22.19 or newer, corepack, and the pinned
Play 1.x fork. Point `PLAY1_HOME` at that checkout; `/opt/play1` remains the
default only for Linux, containers, and CI:

```powershell
git clone https://bitbucket.abundent.com/scm/jclaw/jclaw.git
Set-Location jclaw
$playVersion = (Get-Content .play-version).Trim()
$playArchive = Join-Path $env:TEMP "play-$playVersion.zip"
Invoke-WebRequest "https://github.com/tsukhani/play1/releases/download/v$playVersion/play-$playVersion.zip" -OutFile $playArchive
Expand-Archive $playArchive -DestinationPath C:\src -Force
$env:PLAY1_HOME = "C:\src\play-$playVersion"
$env:Path += ";$env:PLAY1_HOME"
bash ./jclaw.sh setup
bash ./jclaw.sh --dev start
bash ./jclaw.sh test
```

Use the release distribution shown above, not a Git source archive: the Gradle
build needs the pre-published plugin repository bundled with the release. Persist
both variables in the user environment if this is your regular checkout:

```powershell
[Environment]::SetEnvironmentVariable('PLAY1_HOME', $env:PLAY1_HOME, 'User')
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if (($userPath -split ';') -notcontains $env:PLAY1_HOME) {
    [Environment]::SetEnvironmentVariable('Path', "$userPath;$env:PLAY1_HOME", 'User')
}
```

Frontend-only commands run directly in PowerShell because the package scripts
do not rely on POSIX environment-assignment syntax:

```powershell
Set-Location frontend
corepack pnpm install --frozen-lockfile
corepack pnpm test
corepack pnpm typecheck
corepack pnpm test:e2e:headed
```

## Tesseract OCR

Install Tesseract with either package manager:

```powershell
winget install --id UB-Mannheim.TesseractOCR
# or
choco install tesseract
```

JClaw resolves Tesseract in this order:

1. `ocr.tesseract.path` in `conf/application.conf`;
2. `TESSERACT_PATH`;
3. the process `PATH`;
4. the standard UB Mannheim, per-user, and Scoop install directories.

Both an executable path and an install directory are accepted:

```properties
ocr.tesseract.path=C:/Program Files/Tesseract-OCR
ocr.tesseract.languages=eng
```

Install language data alongside Tesseract and join language codes with `+`, for
example `eng+fra+jpn`. Restart JClaw after installing or moving the binary; the
startup probe and **Settings → OCR** then show the exact executable in use.

## Tools and subprocesses

- The `exec` tool uses Git Bash when it is visible to the JVM and falls back to
  PowerShell for a native launch. Its allowlist recognizes `/` and `\` paths,
  including quoted paths containing spaces.
- npm-installed MCP servers and coding harnesses commonly resolve to `.cmd`
  shims. JClaw detects those through `PATH`/`PATHEXT` and launches them through
  `cmd.exe`; configurations can keep using commands such as `npx -y …`.
- Direct executables such as `tesseract.exe`, `ffmpeg.exe`, `uv.exe`,
  `tailscale.exe`, and `git.exe` are launched without a shell.
- Process memory in Settings uses PowerShell's `WorkingSet64` reading on Windows.
- The printer tool uses printers and drivers registered with Windows; CUPS is
  not required on the native Windows path.

Paths stored in JClaw configuration may use forward slashes on Windows. They
avoid escaping ambiguity in `.properties` files and Java accepts them natively.

## Troubleshooting

- **`jclaw` is not recognized:** open a new terminal, or run
  `%USERPROFILE%\.jclaw\jclaw\jclaw.cmd` directly.
- **Tesseract installed but not detected:** set `ocr.tesseract.path` as shown
  above, restart, and inspect **Settings → OCR**.
- **An npm MCP server reports “not a valid Win32 application”:** upgrade JClaw;
  current releases wrap `.cmd`/`.bat` shims automatically.
- **Sandbox unavailable:** use WSL2 and install `bubblewrap` inside the distro,
  or disable `subagent.acp.sandbox`. JClaw fails closed when sandboxing was
  requested but the host cannot provide it.

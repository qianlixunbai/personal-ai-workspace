Personal AI Workspace - portable Windows x64

Requirements
Windows x64; Java 21 runtime on PATH; Ollama installed with the configured
qwen3.5:4b model available; Microsoft Edge WebView2 Evergreen Runtime for the
Main Workspace. No .NET installation or developer/build tools are needed.
No Java, Ollama, model or WebView2 download is performed by this package.

Start
Double-click start-workspace.cmd. Assistant is the default Main Workspace page.
A second launch, tray double-click or Open Personal AI Workspace activates the
same window. Close the window to keep the tray app; choose tray Exit to quit.
Quick Assistant / Quick Translate retains the native selection hotkey:
Ctrl+Alt+Shift+T. Native fallback remains available if WebView2 cannot open.

First credential import
Open Settings > Credential management (凭据管理) to enter the native flow.
Click Import Runtime credential and select the private client-token file under
%LOCALAPPDATA%\PersonalAiWorkspace\RuntimeState\Auth\client-token.
Import is explicit; the launcher never imports into Windows Credential Manager.
Settings initially reports Missing / 未导入. Credential contents never enter JS.

Storage and backups
Workspace data defaults to %USERPROFILE%\.personal-ai-workspace\data.
Runtime credentials, browser registry and logs default to
%LOCALAPPDATA%\PersonalAiWorkspace\RuntimeState (Auth and Logs subdirectories).
WebView uses its separate private account-local profile. Package files are
payload only: you can move the folder without moving your user state.
Database and Memory/Workspace backups are plaintext; protect backups like
personal documents. Workspace Backup recovers Memory and Conversation into a
new/empty directory; it does not replace or switch the running Workspace.

Advanced startup
powershell -NoProfile -ExecutionPolicy Bypass -File release\start-release.ps1
  -DataDirectory "C:\your private restored workspace"
Optional -StateDirectory selects a separate private auth/log directory.
Optional -TokenFile explicitly selects an existing Runtime's private credential
file for authenticated reuse (no automatic credential import).
-CheckOnly checks prerequisites and packaged integrity without starting services.

Troubleshooting
Install Java 21, Ollama and WebView2 if missing. Install the configured model
yourself if unavailable. The launcher checks all existing listening services;
an occupied port with an invalid Runtime fails closed, without killing it.
Existing Runtime/Ollama are reused. Runtime and Ollama stay running after Desktop
Exit; stop them explicitly using their own controls / Task Manager. The launcher
only stops its own Runtime if that process fails startup. No data reset occurs.
Runtime/model readiness can change; Settings Refresh checks current safe status.

SHA256SUMS.txt and package-manifest.json detect corruption. They are not a digital
signature or authenticity guarantee. This candidate is unsigned and has no
installer or updater. Publication requires a separate final review.

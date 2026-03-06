; SyncForge Nexus - Full System Installer
; Build: 2026-03-05

[Setup]
AppName=SyncForge Nexus
AppVersion=2.0
DefaultDirName={autopf}\SyncForge
DefaultGroupName=SyncForge
UninstallDisplayIcon={app}\bin\server.jar
Compression=lzma2
SolidCompression=yes
OutputDir=.
OutputBaseFilename=SyncForge_Setup_v2
SetupIconFile=nginx\html\favicon.ico
UninstallDisplayIcon={app}\nginx\html\favicon.ico

[Files]
; Binaries
Source: "bin\server.jar"; DestDir: "{app}\bin"; Flags: ignoreversion
Source: "bin\autopost.exe"; DestDir: "{app}\bin"; Flags: ignoreversion

; Configuration
Source: "config\application.properties"; DestDir: "{app}\config"; Flags: ignoreversion

; Nginx Web Tier & Assets
Source: "nginx\*"; DestDir: "{app}\nginx"; Flags: ignoreversion recursesubdirs
Source: "nginx\html\draygen_avatar.jpg"; DestDir: "{app}\nginx\html"; Flags: ignoreversion
Source: "nginx\html\favicon.ico"; DestDir: "{app}\nginx\html"; Flags: ignoreversion

; Database Layer (Binaries must be provided in dist folder)
Source: "postgres\bin\*"; DestDir: "{app}\postgres\bin"; Flags: ignoreversion recursesubdirs
Source: "postgres\setup_db.bat"; DestDir: "{app}\postgres"; Flags: ignoreversion

; JRE Runtime (Binaries must be provided in dist folder)
Source: "jre\*"; DestDir: "{app}\jre"; Flags: ignoreversion recursesubdirs

; WinSW Service Wrappers (Need to provide winsw.exe as SyncForgeDB.exe, SyncForgeServer.exe, etc)
Source: "bin\winsw_db.xml"; DestDir: "{app}\bin"; Flags: ignoreversion
Source: "bin\SyncForgeDB.exe"; DestDir: "{app}\bin"; Flags: ignoreversion
Source: "bin\winsw_server.xml"; DestDir: "{app}\bin"; Flags: ignoreversion
Source: "bin\SyncForgeServer.exe"; DestDir: "{app}\bin"; Flags: ignoreversion
Source: "bin\winsw_proxy.xml"; DestDir: "{app}\bin"; Flags: ignoreversion
Source: "bin\SyncForgeProxy.exe"; DestDir: "{app}\bin"; Flags: ignoreversion

[Icons]
Name: "{group}\SyncForge Nexus"; Filename: "http://localhost:8888"
Name: "{group}\Uninstall SyncForge"; Filename: "{unissel}"
Name: "{commondesktop}\SyncForge Dashboard"; Filename: "http://localhost:8888"

[Run]
; Initialize the portable database
Filename: "{app}\postgres\setup_db.bat"; Flags: runhidden

; Install and Start Services
Filename: "{app}\bin\SyncForgeDB.exe"; Parameters: "install"; Flags: runhidden
Filename: "{app}\bin\SyncForgeDB.exe"; Parameters: "start"; Flags: runhidden

Filename: "{app}\bin\SyncForgeServer.exe"; Parameters: "install"; Flags: runhidden
Filename: "{app}\bin\SyncForgeServer.exe"; Parameters: "start"; Flags: runhidden

Filename: "{app}\bin\SyncForgeProxy.exe"; Parameters: "install"; Flags: runhidden
Filename: "{app}bin\SyncForgeProxy.exe"; Parameters: "start"; Flags: runhidden

[UninstallRun]
Filename: "{app}\bin\SyncForgeProxy.exe"; Parameters: "stop"; Flags: runhidden
Filename: "{app}\bin\SyncForgeProxy.exe"; Parameters: "uninstall"; Flags: runhidden
Filename: "{app}\bin\SyncForgeServer.exe"; Parameters: "stop"; Flags: runhidden
Filename: "{app}\bin\SyncForgeServer.exe"; Parameters: "uninstall"; Flags: runhidden
Filename: "{app}\bin\SyncForgeDB.exe"; Parameters: "stop"; Flags: runhidden
Filename: "{app}\bin\SyncForgeDB.exe"; Parameters: "uninstall"; Flags: runhidden

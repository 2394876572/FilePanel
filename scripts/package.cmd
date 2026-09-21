@echo off
rem ============================================================================
rem  FilePanel - build a self-contained application image (M6 deliverable).
rem
rem  Produces, under target\dist:
rem    FilePanel\                 self-contained app image (its own trimmed JRE)
rem    <launcher>.bat             copy this next to the FilePanel folder
rem
rem  Steps:
rem    1. mvn clean package            -> target\app, target\javafx, filepanel.jar
rem    2. copy the app jar into target\app (jpackage's --input must hold the
rem       whole classpath: app jar + dependencies)
rem    3. jlink                        -> a runtime that ALREADY CONTAINS JavaFX,
rem       so the app never has to explain that its JavaFX came from nowhere
rem    4. jpackage --type app-image    -> the green folder, no installer needed
rem    5. copy the launcher
rem
rem  Usage:  scripts\package.cmd
rem          scripts\package.cmd deploy     also copy the result into the folder
rem                                         that contains this project
rem
rem  NOTE ON ENCODING - keep this file ASCII-only (see the launcher for why).
rem ============================================================================

chcp 65001 >nul
setlocal

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PROJECT_DIR=%%~fI"
set "JDK=C:\jdk17"
set "APP_NAME=FilePanel"
set "APP_VERSION=1.0.0"
set "DIST=%PROJECT_DIR%\target\dist"

if not exist "%JDK%\bin\jpackage.exe" (
  echo [package] ERROR: jpackage not found at %JDK%\bin
  echo [package] jpackage ships with JDK 14+. Point JDK at a JDK 17 installation.
  exit /b 1
)

rem ---- 1) build ------------------------------------------------------------
echo [package] 1/5 building...
call "%SCRIPT_DIR%build.cmd"
if errorlevel 1 (
  echo [package] ERROR: build failed
  exit /b 1
)

rem ---- 2) assemble the classpath input -------------------------------------
echo [package] 2/5 assembling input...
copy /y "%PROJECT_DIR%\target\filepanel.jar" "%PROJECT_DIR%\target\app\filepanel.jar" >nul
if errorlevel 1 (
  echo [package] ERROR: could not copy the application jar
  exit /b 1
)

rem ---- 3) jlink runtime ----------------------------------------------------
echo [package] 3/5 creating runtime image (jlink)...
if exist "%PROJECT_DIR%\target\runtime" rmdir /s /q "%PROJECT_DIR%\target\runtime"
"%JDK%\bin\jlink.exe" ^
  --module-path "%JDK%\jmods;%PROJECT_DIR%\target\javafx" ^
  --add-modules java.base,java.desktop,java.logging,java.xml,java.prefs,java.naming,jdk.unsupported,javafx.base,javafx.graphics,javafx.controls,javafx.fxml ^
  --output "%PROJECT_DIR%\target\runtime" ^
  --strip-debug --no-header-files --no-man-pages --compress=2
if errorlevel 1 (
  echo [package] ERROR: jlink failed
  exit /b 1
)

rem ---- 4) jpackage app-image -----------------------------------------------
echo [package] 4/5 creating application image (jpackage)...
if exist "%DIST%" rmdir /s /q "%DIST%"
"%JDK%\bin\jpackage.exe" ^
  --type app-image ^
  --name %APP_NAME% ^
  --dest "%DIST%" ^
  --input "%PROJECT_DIR%\target\app" ^
  --main-jar filepanel.jar ^
  --main-class com.zean.filepanel.Launcher ^
  --runtime-image "%PROJECT_DIR%\target\runtime" ^
  --java-options "-Dfile.encoding=UTF-8" ^
  --java-options "--add-modules=javafx.controls,javafx.fxml" ^
  --app-version %APP_VERSION% ^
  --vendor zean ^
  --description "Local folder file manager" ^
  --icon "%PROJECT_DIR%\assets\app.ico"
if errorlevel 1 (
  echo [package] ERROR: jpackage failed
  exit /b 1
)

rem ---- 5) verify the packaged layout --------------------------------------
echo [package] 5/5 checking the packaged layout...
if not exist "%DIST%\%APP_NAME%\%APP_NAME%.exe" (
  echo [package] ERROR: expected %DIST%\%APP_NAME%\%APP_NAME%.exe
  exit /b 1
)

echo.
echo [package] Done. Application image: %DIST%\%APP_NAME%
echo [package]   %APP_NAME%.exe        the real exe entry point (no console, has an icon)
echo [package]   app\  runtime\        program internals + bundled JRE (auto-hidden)
echo.

rem ---- optional deploy -----------------------------------------------------
rem Jump to a label instead of using an ( ... ) block: inside a block, %VAR% is
rem expanded when the WHOLE block is parsed, so a variable set on the first line
rem reads as empty on the next one. A label sidesteps that entirely.
if /i "%~1"=="deploy" goto :deploy
if /i "%~1"=="installer" goto :installer
if /i "%~1"=="uninstall" goto :uninstall

endlocal
exit /b 0

rem ---------------------------------------------------------------------------
rem  MSI INSTALLER (per-user).  Usage: scripts\package.cmd installer
rem
rem  WHY A SECOND PACKAGING MODE INSTEAD OF REPLACING THE GREEN ONE
rem  They serve different situations and both are worth keeping:
rem    - app-image (this script with no argument) = "drop the exe in a folder and
rem      use it". No install, nothing written outside that folder. This is the
rem      product's original shape and still the best fit for a USB stick.
rem    - msi = "install once, then use it on any folder". Shows up in Windows
rem      "Apps & features", gets a Start Menu entry, and - the part the user
rem      asked for - installing a NEWER msi UNINSTALLS THE OLD VERSION first.
rem
rem  WHY WiX IS NEEDED (and why the zip, not the installer)
rem  jpackage's msi/exe types only generate a .wxs and then shell out to
rem  candle.exe/light.exe. Those come from the WiX Toolset, which is NOT part of
rem  the JDK. We unpack the official binaries zip into tools\wix314 instead of
rem  running WiX's own installer: no admin rights, nothing written to Program
rem  Files, and the toolchain travels with the project like tools\maven does.
rem  Run "node scripts\fetch-wix.mjs" once to obtain it.
rem
rem  WHY --win-per-user-install
rem  It installs under %LOCALAPPDATA%\Programs, so there is NO UAC prompt at all.
rem  That matters for this app: anyone who can run it from a folder can also
rem  install it, on a locked-down machine with no admin account.
rem
rem  WHY -Dfilepanel.installed=true
rem  The GREEN edition derives the folder to manage from where its launcher sits.
rem  The INSTALLED edition cannot do that - its launcher lives in
rem  %LOCALAPPDATA%\Programs\FilePanel, which is not something anyone wants to
rem  browse. This flag tells the app to ask the user for a folder on first run
rem  and to remember it afterwards. Without it, an installed app would open its
rem  own installation directory, which looks like a bug.
rem
rem  UPDATING: build and run this again with a HIGHER APP_VERSION. Windows then
rem  transparently removes the previous version and installs the new one - one
rem  entry in "Apps & features", no manual uninstall. Keep APP_NAME unchanged:
rem  that is what ties the two versions together as the same product.
rem
rem  IMPORTANT: close FilePanel before installing/updating. A running instance
rem  holds app\*.jar and runtime\bin\*.dll, and Windows will refuse to replace
rem  them (this project has already been bitten twice by exactly that).
rem ---------------------------------------------------------------------------
:installer
set "WIX=%PROJECT_DIR%\tools\wix314"
if not exist "%WIX%\candle.exe" (
  echo [package] ERROR: WiX Toolset not found at %WIX%
  echo [package] Run this once:  node scripts\fetch-wix.mjs
  echo [package]   then unpack wix314-binaries.zip into tools\wix314
  endlocal
  exit /b 1
)
set "PATH=%WIX%;%PATH%"
rem Keep jpackage's intermediate WiX sources: when light.exe cannot run ICE
rem validation we finish the link ourselves and need the .wixobj files.
set "WORK=%PROJECT_DIR%\target\msiwork"
set "MSI=%PROJECT_DIR%\target\installer\%APP_NAME%-%APP_VERSION%.msi"

echo [package] Building MSI installer (WiX at %WIX%) ...
if exist "%PROJECT_DIR%\target\installer" rmdir /s /q "%PROJECT_DIR%\target\installer"
"%JDK%\bin\jpackage.exe" ^
  --type msi ^
  --name %APP_NAME% ^
  --dest "%PROJECT_DIR%\target\installer" ^
  --input "%PROJECT_DIR%\target\app" ^
  --main-jar filepanel.jar ^
  --main-class com.zean.filepanel.Launcher ^
  --runtime-image "%PROJECT_DIR%\target\runtime" ^
  --java-options "-Dfile.encoding=UTF-8" ^
  --java-options "--add-modules=javafx.controls,javafx.fxml" ^
  --java-options "-Dfilepanel.installed=true" ^
  --app-version %APP_VERSION% ^
  --vendor zean ^
  --description "Local folder file manager" ^
  --icon "%PROJECT_DIR%\assets\app.ico" ^
  --win-per-user-install ^
  --win-menu ^
  --win-menu-group "FilePanel" ^
  --win-shortcut ^
  --win-dir-chooser ^
  --temp "%WORK%"
if errorlevel 1 goto :msiFallback
goto :msiDone

rem ---------------------------------------------------------------------------
rem  Fallback: jpackage generated the WiX objects, but its own light.exe call
rem  could not run ICE validation because the Windows Installer API is
rem  unreachable here ("error LGHT0217 ... The Windows Installer Service could
rem  not be accessed", which jpackage reports only as "exited with 216 code").
rem  ICE validation is a lint pass over an ALREADY BUILT MSI, so we suppress it
rem  and link the objects ourselves. Full explanation: scripts\finish-msi.ps1
rem ---------------------------------------------------------------------------
:msiFallback
if not exist "%WORK%\wixobj" (
  echo [package] ERROR: jpackage msi failed and produced no WiX objects
  endlocal
  exit /b 1
)
echo [package] jpackage could not validate the MSI in this environment; linking without ICE checks...
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%finish-msi.ps1" -Work "%WORK%" -Out "%MSI%" -Wix "%WIX%"
if errorlevel 1 (
  echo [package] ERROR: fallback MSI link failed
  endlocal
  exit /b 1
)
goto :msiDone

:msiDone
if not exist "%MSI%" (
  echo [package] ERROR: no .msi produced
  endlocal
  exit /b 1
)
echo.
echo [package] Done. Installer: %MSI%
echo [package]   installs per-user (no UAC), Start Menu + desktop shortcut
echo [package]   updating = build again with a higher APP_VERSION, then run the new msi
echo [package]   silent uninstall:  msiexec /x "%MSI%" /qn
echo.
echo [package] Reminder: close FilePanel before installing or updating.
endlocal
exit /b 0

rem ---------------------------------------------------------------------------
rem  Silent uninstall of an installed copy. Usage: scripts\package.cmd uninstall
rem  Uses the product code that Windows registered, so it works regardless of
rem  where the msi file currently is.
rem ---------------------------------------------------------------------------
:uninstall
echo [package] Uninstalling any installed FilePanel (per-user) ...
powershell -NoProfile -Command ^
  "$keys = Get-ChildItem 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall' -ErrorAction SilentlyContinue | Where-Object { (Get-ItemProperty $_.PSPath).DisplayName -like 'FilePanel*' }; if (-not $keys) { Write-Host '  nothing installed'; exit 0 }; foreach ($k in $keys) { $code = $k.PSChildName; Write-Host ('  removing ' + $code); Start-Process msiexec.exe -ArgumentList ('/x' + $code + ' /qn') -Wait }"
echo [package] Done. User data (.filepanel inside managed folders, and %LOCALAPPDATA%\FilePanel) is NOT touched.
endlocal
exit /b 0

rem ---------------------------------------------------------------------------
rem  FLAT deploy: put the app image's CONTENTS directly into the target folder so
rem  that <target>\FilePanel.exe is the entry point a user double-clicks.
rem
rem  Why flat instead of keeping a FilePanel\ subfolder:
rem    - the user gets a REAL .exe at the top level: proper icon, version
rem      properties, and no console window flashing like a .bat does;
rem    - the app derives its scan root from its own location, and with the exe
rem      sitting in the target folder that resolves to the target folder itself.
rem  Cost: two extra top-level folders (app\ and runtime\), which the panel hides
rem  from its own list via exact-path self exclusion (see Exclusions).
rem
rem  WHY STAGE-THEN-SWAP (learned the hard way):
rem  This used to delete app\ and runtime\ FIRST and then copy. When any file was
rem  locked (a running instance holds app\*.jar and runtime\bin\*.dll), the delete
rem  succeeded partially and the copy failed partially - leaving a BROKEN
rem  installation. The exe then died with "Error opening app\FilePanel.cfg" and
rem  the previously working product was gone. Now the new image is copied to a
rem  staging folder first; only a complete staged copy is allowed to replace the
rem  old one. A failed deploy can no longer destroy what is already installed.
rem ---------------------------------------------------------------------------
:deploy
for %%I in ("%PROJECT_DIR%\..") do set "TARGET_DIR=%%~fI"
echo [package] Deploying (flat layout) into "%TARGET_DIR%" ...

rem ---- 1) stage the new image and verify it --------------------------------
rem ---- 2) move the old installation ASIDE (this is also the lock check) -----
rem  Why rename, not delete:
rem    Windows refuses to rename a directory that contains an open file. A running
rem    FilePanel holds app\*.jar and runtime\bin\*.dll, so this rename fails exactly
rem    when an instance is running - a RELIABLE lock check that needs no process
rem    list. (tasklist cannot be used: it answers "Access denied" in some
rem    environments, including the one this project was built in, and a guard that
rem    silently does nothing is worse than no guard at all.)
rem  Renaming is also NON-DESTRUCTIVE: if it fails, the old installation is still
rem    exactly where it was. Only after every rename succeeded do we copy.
set "STAGE=%TARGET_DIR%\.filepanel-deploy"
if exist "%STAGE%" rmdir /s /q "%STAGE%"
mkdir "%STAGE%"
xcopy /e /i /q /y "%DIST%\%APP_NAME%" "%STAGE%\" >nul
if errorlevel 1 goto :stageFailed
if not exist "%STAGE%\%APP_NAME%.exe" goto :stageFailed
if not exist "%STAGE%\app\%APP_NAME%.cfg" goto :stageFailed
if not exist "%STAGE%\app\filepanel.jar" goto :stageFailed
if not exist "%STAGE%\runtime\lib\modules" goto :stageFailed
echo [package] Staged OK - checking whether the installed copy is in use.

if exist "%TARGET_DIR%\app.old" rmdir /s /q "%TARGET_DIR%\app.old"
if exist "%TARGET_DIR%\runtime.old" rmdir /s /q "%TARGET_DIR%\runtime.old"
if exist "%TARGET_DIR%\%APP_NAME%.exe.old" del /q "%TARGET_DIR%\%APP_NAME%.exe.old"

if not exist "%TARGET_DIR%\app" goto :asideRuntime
ren "%TARGET_DIR%\app" "app.old"
if errorlevel 1 goto :locked

:asideRuntime
if not exist "%TARGET_DIR%\runtime" goto :asideExe
ren "%TARGET_DIR%\runtime" "runtime.old"
if errorlevel 1 goto :lockedAfterApp

:asideExe
if exist "%TARGET_DIR%\%APP_NAME%.exe" ren "%TARGET_DIR%\%APP_NAME%.exe" "%APP_NAME%.exe.old"
if errorlevel 1 goto :lockedAfterApp
if exist "%TARGET_DIR%\%APP_NAME%.ico" del /q "%TARGET_DIR%\%APP_NAME%.ico"

rem ---- 3) copy the new image into place ------------------------------------
xcopy /e /i /q /y "%STAGE%\" "%TARGET_DIR%\" >nul
if errorlevel 1 goto :swapFailed

rem ---- 4) verify what landed, only then drop the old copy -------------------
if not exist "%TARGET_DIR%\%APP_NAME%.exe" goto :swapFailed
if not exist "%TARGET_DIR%\app\%APP_NAME%.cfg" goto :swapFailed
if not exist "%TARGET_DIR%\app\filepanel.jar" goto :swapFailed
if not exist "%TARGET_DIR%\runtime\lib\modules" goto :swapFailed

if exist "%TARGET_DIR%\app.old" rmdir /s /q "%TARGET_DIR%\app.old"
if exist "%TARGET_DIR%\runtime.old" rmdir /s /q "%TARGET_DIR%\runtime.old"
if exist "%TARGET_DIR%\%APP_NAME%.exe.old" del /q "%TARGET_DIR%\%APP_NAME%.exe.old"
rmdir /s /q "%STAGE%"

echo [package] Deployed. Double-click "%TARGET_DIR%\%APP_NAME%.exe".
echo [package] Note: if an older "%TARGET_DIR%\*.bat" launcher is still around it
echo [package]       is no longer needed and can be deleted.

endlocal
exit /b 0

:lockedAfterApp
rem app\ was already moved aside - put it back before reporting failure
if exist "%TARGET_DIR%\app.old" ren "%TARGET_DIR%\app.old" "app"
goto :locked

:locked
if exist "%STAGE%" rmdir /s /q "%STAGE%"
echo [package] ERROR: the installed copy is in use, so it was NOT replaced.
echo [package]        Close %APP_NAME%.exe and run the deploy again.
echo [package]        (a running instance holds app\*.jar and runtime\bin\*.dll)
endlocal
exit /b 1

:stageFailed
echo [package] ERROR: staging copy failed. The existing installation was NOT touched.
if exist "%STAGE%" rmdir /s /q "%STAGE%"
endlocal
exit /b 1

:swapFailed
rem Put the previous installation back: the staged copy was not good enough.
if exist "%TARGET_DIR%\app" rmdir /s /q "%TARGET_DIR%\app"
if exist "%TARGET_DIR%\runtime" rmdir /s /q "%TARGET_DIR%\runtime"
if exist "%TARGET_DIR%\%APP_NAME%.exe" del /q "%TARGET_DIR%\%APP_NAME%.exe"
if exist "%TARGET_DIR%\app.old" ren "%TARGET_DIR%\app.old" "app"
if exist "%TARGET_DIR%\runtime.old" ren "%TARGET_DIR%\runtime.old" "runtime"
if exist "%TARGET_DIR%\%APP_NAME%.exe.old" ren "%TARGET_DIR%\%APP_NAME%.exe.old" "%APP_NAME%.exe"
echo [package] ERROR: swap failed; the previous installation was restored.
echo [package]        The new image is staged at "%STAGE%".
endlocal
exit /b 1

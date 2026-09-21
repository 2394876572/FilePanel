@echo off
rem ============================================================================
rem  FilePanel - shared java launcher (used by run.cmd / scan.cmd / selftest.cmd /
rem  uiselftest.cmd). Kept as a single place so the classpath / module-path
rem  arrangement exists exactly once.
rem
rem  The arrangement below is deliberately IDENTICAL to what jpackage will
rem  produce in M6:
rem      JavaFX modules   -> --module-path (avoids "unnamed module" warning)
rem      app + other deps -> -cp
rem  so that a dev run exercises the same wiring as the shipped build.
rem
rem  Usage: scripts\_java.cmd <Launcher args...>
rem
rem  NOTE ON ENCODING - DO NOT ADD NON-ASCII TEXT TO THIS FILE
rem  "chcp 65001" switches the console to UTF-8. cmd.exe tracks its position in
rem  a batch file by BYTE offset, so combining chcp with non-ASCII bytes makes
rem  the parser resume in the middle of a line and execute garbage. Every .cmd
rem  in this project is deliberately ASCII-only. Put notes in docs, not here.
rem ============================================================================

chcp 65001 >nul
setlocal

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PROJECT_DIR=%%~fI"
set "JAVA=C:\jdk17\bin\java.exe"

if not exist "%JAVA%" (
  echo [_java.cmd] ERROR: JDK 17 not found at C:\jdk17
  exit /b 1
)

if not exist "%PROJECT_DIR%\target\filepanel.jar" (
  echo [_java.cmd] target\filepanel.jar not found - building first...
  call "%SCRIPT_DIR%build.cmd"
  if errorlevel 1 exit /b 1
)

rem javafx.cachedir: JavaFX extracts native dlls from the jar into
rem %USERPROFILE%\.openjfx\cache by default. When that location is not
rem writable it falls back to a temp dir but spams "Can not create cache",
rem drowning useful output. Pointing it at target\ keeps runs clean.
"%JAVA%" -Dfile.encoding=UTF-8 ^
  -Djavafx.cachedir="%PROJECT_DIR%\target\jfxcache" ^
  --module-path "%PROJECT_DIR%\target\javafx" ^
  --add-modules javafx.controls,javafx.fxml ^
  -cp "%PROJECT_DIR%\target\filepanel.jar;%PROJECT_DIR%\target\app\*" ^
  com.zean.filepanel.Launcher %*

exit /b %ERRORLEVEL%

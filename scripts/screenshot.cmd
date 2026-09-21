@echo off
rem ============================================================================
rem  FilePanel - render the UI to a PNG.
rem
rem  Shows the real window, waits for the scan (and the hidden-content stats) to
rem  finish, snapshots the scene and exits. Unit tests and uiselftest.cmd only
rem  verify data; this verifies that CSS and layout actually render.
rem
rem  Usage: scripts\screenshot.cmd [folder] [output.png] [search-query] [theme]
rem         theme is "light" or "dark".
rem
rem  NOTE: the argument accumulator below deliberately uses `set VAR=...` rather
rem  than `set "VAR=..."`. The quoted form cannot hold nested quotes, so an
rem  argument like --search "size:>1MB" would come out mangled.
rem ============================================================================

chcp 65001 >nul

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PROJECT_DIR=%%~fI"

set "TARGET=%~1"
if not defined TARGET for %%I in ("%PROJECT_DIR%\..") do set "TARGET=%%~fI"

set "OUT=%~2"
if not defined OUT set "OUT=%PROJECT_DIR%\target\screenshots\ui.png"

set "QUERY=%~3"
set "THEME=%~4"

set ARGS=--screenshot "%OUT%"
if defined QUERY set ARGS=%ARGS% --search "%QUERY%"
if defined THEME set ARGS=%ARGS% --theme %THEME%

call "%SCRIPT_DIR%_java.cmd" %ARGS% --root "%TARGET%"
exit /b %ERRORLEVEL%

@echo off
rem ============================================================================
rem  FilePanel - UI pipeline self test.
rem
rem  Builds the real MainWindow and runs the whole chain
rem  (resolve root -> background scan -> loading progress -> table -> status bar)
rem  without showing a window, then prints the actual numbers at each step and
rem  exits 0 or 1.
rem
rem  Complements scan.cmd: scan.cmd proves "the scanner computed the right
rem  numbers", this proves "the numbers actually reached the table".
rem
rem  Usage: scripts\uiselftest.cmd [folder]
rem ============================================================================

chcp 65001 >nul

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PROJECT_DIR=%%~fI"

set "TARGET=%~1"
if not defined TARGET for %%I in ("%PROJECT_DIR%\..") do set "TARGET=%%~fI"

call "%SCRIPT_DIR%_java.cmd" --ui-selftest "%TARGET%"
exit /b %ERRORLEVEL%

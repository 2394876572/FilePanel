@echo off
rem ============================================================================
rem  FilePanel - launch the graphical interface.
rem
rem  Usage: scripts\run.cmd [folder]
rem         with no argument it opens the folder that contains this project,
rem         which is how the shipped product is meant to be used.
rem
rem  Runs in the foreground so console output stays visible; closing the window
rem  ends the command.
rem ============================================================================

chcp 65001 >nul

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PROJECT_DIR=%%~fI"

set "TARGET=%~1"
if not defined TARGET for %%I in ("%PROJECT_DIR%\..") do set "TARGET=%%~fI"

call "%SCRIPT_DIR%_java.cmd" --root "%TARGET%"
exit /b %ERRORLEVEL%

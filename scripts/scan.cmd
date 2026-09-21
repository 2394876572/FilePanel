@echo off
rem ============================================================================
rem  FilePanel - headless scan report.
rem
rem  Prints scan statistics to stdout so that acceptance criteria such as
rem  "does the exclusion list really turn thousands of files into ~112" are
rem  verifiable from the command line instead of by squinting at the GUI.
rem
rem  Usage: scripts\scan.cmd [folder]
rem         with no argument it scans the folder that contains this project.
rem ============================================================================

chcp 65001 >nul

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PROJECT_DIR=%%~fI"

set "TARGET=%~1"
if not defined TARGET for %%I in ("%PROJECT_DIR%\..") do set "TARGET=%%~fI"

call "%SCRIPT_DIR%_java.cmd" --scan "%TARGET%"
exit /b %ERRORLEVEL%

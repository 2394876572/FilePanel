@echo off
rem ============================================================================
rem  FilePanel - full build (compile + test + package).
rem
rem  Produces:
rem    target\filepanel.jar        application jar
rem    target\app\                 non-JavaFX runtime dependencies (classpath)
rem    target\javafx\              JavaFX win modules         (module-path)
rem  These three are exactly the inputs jpackage needs in M6.
rem ============================================================================

chcp 65001 >nul
call "%~dp0mvn.cmd" clean package
exit /b %ERRORLEVEL%

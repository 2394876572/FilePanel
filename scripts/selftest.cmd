@echo off
rem ============================================================================
rem  FilePanel - runtime environment self test.
rem
rem  Starts the JavaFX toolkit, validates the CSS resource, prints the
rem  environment (including WHICH javafx jar got loaded - proven from the jar
rem  file name, because JavaFX module-info and MANIFEST carry no version) and
rem  exits 0 or 1.
rem
rem  M6 reuses this exact check on a machine with no JDK to prove the
rem  self-contained distribution works.
rem ============================================================================

chcp 65001 >nul
call "%~dp0_java.cmd" --selftest
exit /b %ERRORLEVEL%

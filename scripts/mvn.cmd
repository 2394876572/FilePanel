@echo off
rem ============================================================================
rem  FilePanel - Maven wrapper
rem
rem  WHY THIS SCRIPT EXISTS
rem  1) This machine's ambient JAVA_HOME points to JDK 8 (C:\jdk-1.8).
rem     JavaFX 17 needs JDK 17, so JAVA_HOME is overridden here on purpose.
rem     Never remove this override, and never trust the ambient JAVA_HOME.
rem  2) There is no Maven on PATH. We prefer the project-local copy under
rem     tools\ (reproducible), and fall back to IntelliJ's bundled Maven.
rem  3) Console defaults to GBK (cp936) on this machine, which garbles Chinese
rem     output. Switching to UTF-8 keeps build logs readable.
rem
rem  Usage: scripts\mvn.cmd clean package
rem ============================================================================

chcp 65001 >nul
setlocal

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PROJECT_DIR=%%~fI"

rem ---- 1) JDK 17 ----------------------------------------------------------
set "JDK17=C:\jdk17"
if not exist "%JDK17%\bin\javac.exe" (
  echo [mvn.cmd] ERROR: JDK 17 not found at "%JDK17%".
  echo [mvn.cmd] Edit scripts\mvn.cmd and set JDK17 to a JDK 17+ installation.
  exit /b 1
)
set "JAVA_HOME=%JDK17%"
set "PATH=%JAVA_HOME%\bin;%PATH%"

rem ---- 2) Maven -----------------------------------------------------------
set "MAVEN_HOME=%PROJECT_DIR%\tools\apache-maven-3.9.9"
if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
  set "MAVEN_HOME=%USERPROFILE%\AppData\Local\Programs\IntelliJ IDEA\plugins\maven-plugin\lib\maven3"
  echo [mvn.cmd] WARN: project-local Maven missing, using IntelliJ bundled Maven.
  echo [mvn.cmd]       To restore it, run: node scripts\fetch-maven.mjs
)
if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
  echo [mvn.cmd] ERROR: no Maven found.
  echo [mvn.cmd] Run "node scripts\fetch-maven.mjs" first, or install Maven.
  exit /b 1
)

rem ---- 3) local repository -------------------------------------------------
rem  WHY A PROJECT-LOCAL REPO:
rem  a) The global repo (%USERPROFILE%\.m2\repository) is outside the project
rem     tree and is not writable in this environment, which makes Maven fail
rem     with "FileNotFoundException: _remote.repositories (Access is denied)".
rem  b) It also makes the build self-contained and reproducible: the exact
rem     dependency set travels with the project, nothing leaks to the machine.
rem  To use the global repository instead, delete the two lines below.
set "M2REPO=%PROJECT_DIR%\tools\m2repo"
if not exist "%M2REPO%" mkdir "%M2REPO%"

set "MAVEN_OPTS=-Dfile.encoding=UTF-8 -Xmx1024m"

echo [mvn.cmd] JAVA_HOME =%JAVA_HOME%
echo [mvn.cmd] MAVEN    =%MAVEN_HOME%
echo [mvn.cmd] M2REPO   =%M2REPO%

call "%MAVEN_HOME%\bin\mvn.cmd" -f "%PROJECT_DIR%\pom.xml" "-Dmaven.repo.local=%M2REPO%" %*
set "RC=%ERRORLEVEL%"
endlocal & exit /b %RC%

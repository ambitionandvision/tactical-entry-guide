@echo off
rem Run Gradle on Windows without WSL. Finds JDK 17 and sets JAVA_HOME.
rem   scripts\gradle_win.bat assembleDebug testDebugUnitTest
rem Search order: PLEOS_JDK17, %USERPROFILE%\.jdks, Adoptium, Microsoft, Oracle.
rem The Android Studio bundled JBR is skipped on purpose: AGP 8.8 needs JDK 17.
rem Keep this file ASCII only. cmd.exe misparses UTF-8 Korean text in batch files.
setlocal
set "APP=%~dp0..\android_pleos_app"
set "JDK="

if defined PLEOS_JDK17 set "JDK=%PLEOS_JDK17%"
if not defined JDK if exist "%USERPROFILE%\.jdks\temurin-17\bin\java.exe" set "JDK=%USERPROFILE%\.jdks\temurin-17"
if not defined JDK for /d %%d in ("%USERPROFILE%\.jdks\*17*") do set "JDK=%%~fd"
if not defined JDK for /d %%d in ("%ProgramFiles%\Eclipse Adoptium\jdk-17*") do set "JDK=%%~fd"
if not defined JDK for /d %%d in ("%ProgramFiles%\Microsoft\jdk-17*") do set "JDK=%%~fd"
if not defined JDK for /d %%d in ("%ProgramFiles%\Java\jdk-17*") do set "JDK=%%~fd"

if not defined JDK (
  echo [X] JDK 17 not found. Install Temurin 17 or set PLEOS_JDK17.
  exit /b 1
)
if not exist "%APP%\gradlew.bat" (
  echo [X] gradlew.bat not found in "%APP%"
  exit /b 1
)
set "JAVA_HOME=%JDK%"
pushd "%APP%"
call "%APP%\gradlew.bat" --console=plain %*
set "RC=%ERRORLEVEL%"
popd
exit /b %RC%

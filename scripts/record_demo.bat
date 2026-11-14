@echo off
rem 원클릭 시연 녹화(에뮬레이터 화면 녹화. ICE 는 실주행 로그, EV 는 합성 시뮬레이션 GPX).
rem 사용: record_demo.bat [--fuel ICE^|EV] [--secs N] [--size 1920x1080] [--bit-rate 6000000] [--user 10]
setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 >nul
set "ROOT=%~dp0.."
set "PKG=io.github.ambitionandvision.tactical"
set "FUEL=ICE"
set "SECS="
set "SIZE=1920x1080"
set "BR=6000000"
set "APPUSER=10"
:args
if "%~1"=="" goto argsdone
if /i "%~1"=="--fuel" ( set "FUEL=%~2" & shift & shift & goto args )
if /i "%~1"=="--secs" ( set "SECS=%~2" & shift & shift & goto args )
if /i "%~1"=="--size" ( set "SIZE=%~2" & shift & shift & goto args )
if /i "%~1"=="--bit-rate" ( set "BR=%~2" & shift & shift & goto args )
if /i "%~1"=="--user" ( set "APPUSER=%~2" & shift & shift & goto args )
echo 알 수 없는 옵션: %~1
exit /b 2
:argsdone
set "SDK=%ANDROID_SDK_ROOT%"
if not defined SDK set "SDK=%ANDROID_HOME%"
if not defined SDK set "SDK=%LOCALAPPDATA%\Android\Sdk"
set "ADB=%SDK%\platform-tools\adb.exe"
if not exist "%ADB%" ( echo [X] adb 를 찾지 못했습니다. & exit /b 1 )
if /i "%FUEL%"=="EV" ( set "LOW=ev" & set "GPX=%ROOT%\gis_data\sejong_costco_ev_drive.gpx" ) else ( set "FUEL=ICE" & set "LOW=ice" & set "GPX=%ROOT%\gis_data\sejong_costco_cn8_drive.gpx" )
if not defined SECS set "SECS=135"
if %SECS% GTR 180 ( echo screenrecord 는 최대 180초입니다. & exit /b 2 )
if not exist "%ROOT%\docs\demo" mkdir "%ROOT%\docs\demo"
python "%ROOT%\scripts\virtual_console_drive.py" --park-only >nul 2>&1
"%ADB%" shell rm -f /sdcard/pleos_demo.mp4
echo [i] 녹화 시작: %SECS%s %SIZE% %BR%bps fuel=%FUEL%
start "" /b "%ADB%" shell screenrecord --size %SIZE% --bit-rate %BR% --time-limit %SECS% /sdcard/pleos_demo.mp4
ping -n 3 127.0.0.1 >nul
"%ADB%" shell am force-stop --user %APPUSER% %PKG%
"%ADB%" shell am start --user %APPUSER% -n "%PKG%/.MainActivity" --es fuel %FUEL%
python "%ROOT%\scripts\virtual_console_drive.py" --follow-gpx "%GPX%"
rem 녹화 종료까지 대기
:waitrec
"%ADB%" shell pidof screenrecord >nul 2>&1
if not errorlevel 1 ( ping -n 3 127.0.0.1 >nul & goto waitrec )
"%ADB%" pull /sdcard/pleos_demo.mp4 "%ROOT%\docs\demo\tactical_entry_guide_%LOW%.mp4"
"%ADB%" shell rm -f /sdcard/pleos_demo.mp4
python "%ROOT%\scripts\virtual_console_drive.py" --park-only >nul 2>&1
echo [OK] docs\demo\tactical_entry_guide_%LOW%.mp4
endlocal

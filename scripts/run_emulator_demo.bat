@echo off
rem Pleos 에뮬레이터 부팅 - APK 설치 - 실행 - (옵션) GPX 주입.   사용: run_emulator_demo.bat [APK경로] [--inject] [--install-image] [--fuel ICE^|EV] [--drive-gpx] [--drive] [--user N]
setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 >nul
set "ROOT=%~dp0.."
set "PKG=io.github.ambitionandvision.tactical"
set "AVD=Pleos_Connect_API34"
set "IMG=system-images;android-34;pleos_car_emulator;x86_64"
set "APK="
set "INJECT=0"
set "INSTALL=0"
set "DRIVE=0"
set "APPUSER=10"
set "FUEL="
set "DRIVEGPX=0"
set "GPXFILE=%~dp0..\gis_data\sejong_costco_cn8_drive.gpx"
:args
if "%~1"=="" goto argsdone
if /i "%~1"=="--inject" ( set "INJECT=1" & shift & goto args )
if /i "%~1"=="--install-image" ( set "INSTALL=1" & shift & goto args )
if /i "%~1"=="--drive" ( set "DRIVE=1" & shift & goto args )
if /i "%~1"=="--fuel" ( set "FUEL=%~2" & shift & shift & goto args )
if /i "%~1"=="--drive-gpx" ( set "DRIVEGPX=1" & shift & goto args )
if /i "%~1"=="--user" ( set "APPUSER=%~2" & shift & shift & goto args )
set "APK=%~1"
shift
goto args
:argsdone
if /i "%FUEL%"=="EV" set "GPXFILE=%ROOT%\gis_data\sejong_costco_ev_drive.gpx"

rem --- SDK 탐색
set "SDK=%ANDROID_SDK_ROOT%"
if not defined SDK set "SDK=%ANDROID_HOME%"
if not defined SDK set "SDK=%LOCALAPPDATA%\Android\Sdk"
if not exist "%SDK%\platform-tools\adb.exe" ( echo [X] Android SDK 를 찾지 못했습니다. ANDROID_SDK_ROOT 를 지정하세요. & exit /b 1 )
set "ADB=%SDK%\platform-tools\adb.exe"
set "EMU=%SDK%\emulator\emulator.exe"
if not defined JAVA_HOME if exist "%USERPROFILE%\.jdks\temurin-17" set "JAVA_HOME=%USERPROFILE%\.jdks\temurin-17"

rem --- APK 탐색
if not defined APK for /f "delims=" %%F in ('dir /b /o-d "%ROOT%\apk\*_release.apk" 2^>nul') do if not defined APK set "APK=%ROOT%\apk\%%F"
if not defined APK for /f "delims=" %%F in ('dir /b /s /o-d "%ROOT%\android_pleos_app\app\build\outputs\apk\debug\*.apk" 2^>nul') do if not defined APK set "APK=%%F"
if not defined APK set "APK=%ROOT%\apk\NONE.apk"
echo [i] SDK=%SDK%  AVD=%AVD%  APK=%APK%

rem --- 이미 실행 중이면 재사용 (끄지 않는다)
set "RUNNING=0"
"%ADB%" devices | findstr /r /c:"emulator-.*device$" >nul
if not errorlevel 1 ( set "RUNNING=1" & echo [i] 실행 중인 에뮬레이터 재사용 )

rem --- 이미지 확인
set "IMGDIR=%SDK%\system-images\android-34\pleos_car_emulator\x86_64"
if "%RUNNING%"=="0" if not exist "%IMGDIR%" (
  if "%INSTALL%"=="1" (
    python "%ROOT%\scripts\pleos_avd_tool.py" install-image "%SDK%" || exit /b 3
  ) else (
    echo [X] Pleos 시스템 이미지가 없습니다: %IMGDIR%
    echo   방법 A: Android Studio ^> Settings ^> Android SDK ^> SDK Update Sites 탭 ^> Add 에 Pleos 공식 사이트 URL 추가 후 이미지 설치
    echo   방법 B: --install-image 옵션 ^(공식 zip 약 4GB, 디스크 25GB 이상, 42dot SDK License 동의로 간주^)
    exit /b 3
  )
)

rem --- AVD 확인/생성 + config.ini 덮어쓰기. 패키지 문자열은 ; 때문에 반드시 따옴표.
if "%RUNNING%"=="0" (
  "%EMU%" -list-avds | findstr /x /c:"%AVD%" >nul
  if errorlevel 1 (
    echo [i] AVD 생성: %AVD%
    echo no | "%SDK%\cmdline-tools\latest\bin\avdmanager.bat" create avd -n %AVD% -k "%IMG%" --force
  )
  python "%ROOT%\scripts\pleos_avd_tool.py" config "%USERPROFILE%\.android\avd\%AVD%.avd\config.ini"
  start "" "%EMU%" -avd %AVD% -no-snapshot-load -gpu host
)

"%ADB%" wait-for-device
:bootwait
for /f "delims=" %%B in ('"%ADB%" shell getprop sys.boot_completed 2^>nul') do set "BOOT=%%B"
if not "!BOOT:~0,1!"=="1" ( ping -n 3 127.0.0.1 >nul & goto bootwait )
echo [OK] 부팅 완료

if not exist "%APK%" (
  echo [i] APK 없음 - 설치·실행을 건너뜁니다.
) else (
  "%ADB%" install --user %APPUSER% -r -g "%APK%" || exit /b 6
  for %%P in (android.permission.ACCESS_FINE_LOCATION pleos.car.permission.FUSED_LOCATION pleos.car.permission.CAR_ENERGY pleos.car.permission.NAVI_ROUTE) do "%ADB%" shell pm grant --user %APPUSER% %PKG% %%P >nul 2>&1
)

set "EXTRAS="
if "%INJECT%"=="1" set "EXTRAS=!EXTRAS! --es source fused"
if defined FUEL set "EXTRAS=!EXTRAS! --es fuel %FUEL%"
"%ADB%" shell pm list packages --user %APPUSER% | findstr /x /c:"package:%PKG%" >nul
if not errorlevel 1 (
  "%ADB%" shell am force-stop --user %APPUSER% %PKG%
  "%ADB%" logcat -c
  "%ADB%" shell am start --user %APPUSER% -n "%PKG%/.MainActivity" !EXTRAS!
  echo [i] 앱 실행 extras:!EXTRAS!
)

if "%INJECT%"=="1" (
  echo [i] GPX 주입 ^(1Hz, 시뮬레이션^)
  start "" /b powershell -NoProfile -Command "[xml]$g=Get-Content -Raw '%GPXFILE%'; foreach($p in $g.gpx.trk.trkseg.trkpt){ & '%ADB%' emu geo fix $p.lon $p.lat | Out-Null; Start-Sleep -Seconds 1 }"
) else (
  echo [i] 앱 내장 GPX 재생 ^(GPX 주행 재생 - 시뮬레이션^) 을 사용합니다. 앱의 재생 버튼을 누르세요.
)
if "%DRIVEGPX%"=="1" python "%ROOT%\scripts\virtual_console_drive.py" --follow-gpx "%GPXFILE%" --watch-uxr
if "%DRIVE%"=="1" python "%ROOT%\scripts\virtual_console_drive.py" --gear Drive --speed 25 --hold 60 --watch-uxr
endlocal

@echo off
rem ------------------------------------------------------------
rem buildandcopy.bat  <output‑file>
rem Builds Helios.exe via Gradle → copies it to the name OpenBench wants
rem ------------------------------------------------------------
setlocal

if "%~1"=="" (
  echo Usage: buildandcopy.bat ^<output‑file^>
  exit /b 1
)

set "GRADLE=%~dp0gradlew.bat"
set "PACKED=build\dist\Helios.exe"
set "OUT=%~1"

echo === Building single‑file exe ===
call "%GRADLE%" --no-daemon --console=plain warpPack || exit /b 1

if not exist "%PACKED%" (
  echo ERROR: %PACKED% not found
  exit /b 1
)

echo === Copying to %OUT% ===
if not exist "%~dp1" mkdir "%~dp1"
copy /Y "%PACKED%" "%OUT%" >nul

rem If caller omitted ".exe", also write OUT.exe so Windows can run it
if /I not "%~x1"==".exe" copy /Y "%PACKED%" "%OUT%.exe" >nul

echo Done.
endlocal

@echo off
rem ------------------------------------------------------------
rem buildandcopy.bat  <output‑file>
rem Example:  buildandcopy.bat Helios-12345678
rem ------------------------------------------------------------
setlocal

if "%~1"=="" (
  echo Usage: buildandcopy.bat ^<output-file^>
  exit /b 1
)
set "OUT=%~1"
set "PACKED=build\dist\Helios.exe"

echo === Building single‑file exe with Gradle ===
call gradlew.bat --no-daemon --console=plain warpPack || exit /b 1

if not exist "%PACKED%" (
  echo ERROR: %PACKED% not found
  exit /b 1
)

echo === Copying to %OUT% ===
if not exist "%~dp1" mkdir "%~dp1"
copy /Y "%PACKED%" "%OUT%" >nul

rem If caller omitted .exe, also drop %OUT%.exe for Windows’ sake
if /I not "%~x1"==".exe" copy /Y "%PACKED%" "%OUT%.exe" >nul

echo Done.
endlocal

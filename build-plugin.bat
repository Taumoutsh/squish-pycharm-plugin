@echo off
rem Builds the PySquish plugin and prints the installable artifact path.
rem In PyCharm: Settings ^| Plugins ^| gear ^| "Install Plugin from Disk..." then
rem pick the .zip from build\distributions (or the copy in dist\).
rem
rem Usage:
rem   build-plugin.bat          clean build
rem   build-plugin.bat --fast   incremental build (no clean)
setlocal

cd /d "%~dp0"

set TASKS=clean buildPlugin
if "%1"=="--fast" set TASKS=buildPlugin

echo Running: gradlew %TASKS%
call gradlew.bat %TASKS%
if errorlevel 1 (
  echo ERROR: build failed. >&2
  exit /b 1
)

if not exist dist mkdir dist

set "ARTIFACT="
for /f "delims=" %%F in ('dir /b /o-d build\distributions\*.zip 2^>nul') do (
  if not defined ARTIFACT set "ARTIFACT=build\distributions\%%F"
)

if not defined ARTIFACT (
  echo ERROR: Build finished but no zip was found in build\distributions\. >&2
  exit /b 1
)

copy /y "%ARTIFACT%" dist\ >nul

echo.
echo ============================================================
echo  Plugin built successfully:
echo    %ARTIFACT%
echo.
echo  Install in PyCharm:
echo    Settings ^| Plugins ^| (gear) ^| Install Plugin from Disk...
echo    then pick the .zip above and restart the IDE.
echo ============================================================
endlocal

@echo off
REM Demo reset, part 2: moves every file out of the input folder into data\archive\<timestamp>\.
REM Run it together with ops\reset-demo-data.sql - otherwise the next job run registers the old
REM files again (they are no longer in the database) and they reappear in the demo.
REM Usage: ops\reset-demo-files.bat [input folder]     (default: data\input, as input.dir)
setlocal
cd /d "%~dp0.."
set IN=%~1
if "%IN%"=="" set IN=data\input
if not exist "%IN%\" (
  echo Input folder %IN% does not exist - nothing to do.
  exit /b 0
)
for /f %%t in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set TS=%%t
set DEST=data\archive\%TS%
mkdir "%DEST%" 2>nul
robocopy "%IN%" "%DEST%" /E /MOVE /NFL /NDL /NJH /NJS >nul
if %ERRORLEVEL% GEQ 8 (
  echo Moving the files failed.
  exit /b 1
)
mkdir "%IN%" 2>nul
echo Moved the contents of %IN% to %DEST%

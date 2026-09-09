@echo off
rem Load KEY=VALUE pairs from a private environment snapshot into the caller.
rem The file is intentionally not tracked; callers decide which file to load.
if "%~1"=="" exit /b 0
if not exist "%~1" exit /b 0
for /f "usebackq tokens=1,* delims==" %%A in (`findstr /r /b "[A-Za-z_][A-Za-z0-9_]*=" "%~1"`) do set "%%A=%%~B"
exit /b 0

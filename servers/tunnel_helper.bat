@echo off
setlocal EnableExtensions EnableDelayedExpansion
set "BORE=D:\ngrok\bore\bore.exe"
set "PORT=%~1"
set "LOGFILE=%~2"
set "URLFILE=%~3"
title bore-%PORT%

echo ==========================================
echo   bore tunnel helper - port %PORT%
echo   Log:  %LOGFILE%
echo   URL:  %URLFILE%
echo ==========================================

:restart
del /q "%LOGFILE%" 2>nul
echo [%date% %time%] Starting bore local %PORT% --to bore.pub...
set "NO_COLOR=1"
"%BORE%" local %PORT% --to bore.pub > "%LOGFILE%" 2>&1
echo [%date% %time%] bore exited (code: %ERRORLEVEL%)

:: Extract URL from this run's log
for /f "tokens=*" %%U in ('findstr /r "listening at bore.pub:" "%LOGFILE%" 2^>nul') do (
    set "LINE=%%U"
    set "LINE=!LINE:*listening at =!"
    echo http://!LINE!> "%URLFILE%"
    echo [%date% %time%] URL updated: http://!LINE!
)

echo [%date% %time%] Restarting in 5s...
timeout /t 5 /nobreak >nul
goto restart

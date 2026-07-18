@echo off
set "TEST_OUTPUT=%~dp0tunnel_test_url.txt"
echo Starting test tunnel...
start "lt-TEST" /min "%~dp0tunnel_helper.bat" 9999 "%TEST_OUTPUT%"
echo Waiting 10s...
timeout /t 10 /nobreak >nul
if exist "%TEST_OUTPUT%" (
    echo SUCCESS - URL found:
    type "%TEST_OUTPUT%"
) else (
    echo FAILED - no output file
)
pause

@echo off
echo Starting test tunnel...
start "lt-TEST" /min D:\Server\MeetingNotesApp\servers\tunnel_helper.bat 9999 "D:\Server\MeetingNotesApp\servers\tunnel_test5.txt"
echo Waiting 10s...
timeout /t 10 /nobreak >nul
if exist "D:\Server\MeetingNotesApp\servers\tunnel_test5.txt" (
    echo SUCCESS - URL found:
    type "D:\Server\MeetingNotesApp\servers\tunnel_test5.txt"
) else (
    echo FAILED - no output file
)
pause
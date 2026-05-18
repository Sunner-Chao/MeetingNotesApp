@echo off
setlocal EnableExtensions EnableDelayedExpansion
set "INTERVAL=%~1"
if "%INTERVAL%"=="" set "INTERVAL=120"
title tunnel-keepalive

echo [keepalive] Started (interval: %INTERVAL%s)

:loop
if exist "%~dp0tunnel_stt_url.txt" (
    for /f "tokens=*" %%U in (%~dp0tunnel_stt_url.txt) do (
        curl -s --connect-timeout 5 %%U/health >nul 2>&1
    )
)
if exist "%~dp0tunnel_backend_url.txt" (
    for /f "tokens=*" %%U in (%~dp0tunnel_backend_url.txt) do (
        curl -s --connect-timeout 5 %%U/health >nul 2>&1
        curl -s --connect-timeout 5 %%U/web >nul 2>&1
    )
)
timeout /t %INTERVAL% /nobreak >nul
goto loop

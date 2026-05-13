@echo off
setlocal EnableExtensions

cd /d "%~dp0"

if not exist "runtime" (
    echo [ERROR] runtime not found. Run ..\init-server.bat first.
    exit /b 1
)

call runtime\Scripts\activate.bat

echo [SENSEVOICE] Installing CUDA torch wheels (cu121)...
python -m pip install --upgrade pip
python -m pip install torch torchaudio --index-url https://download.pytorch.org/whl/cu121

echo [SENSEVOICE] Installing/refreshing funasr...
python -m pip install --upgrade funasr

echo [SENSEVOICE] Validating imports...
python -c "import torch; import funasr; print('torch=', torch.__version__, 'cuda=', torch.cuda.is_available())"
if errorlevel 1 (
    echo [ERROR] Validation failed. Check CUDA/driver/python compatibility.
    exit /b 1
)

echo [SENSEVOICE] Done.
echo [SENSEVOICE] You can switch with: switch-stt.bat sensevoice SenseVoiceSmall
endlocal
exit /b 0

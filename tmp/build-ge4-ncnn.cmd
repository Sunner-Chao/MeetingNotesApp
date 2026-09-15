@echo off
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul
if errorlevel 1 exit /b %errorlevel%
set "GE4_CMAKE=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe"
set "HTTPS_PROXY=http://127.0.0.1:7890"
set "HTTP_PROXY=http://127.0.0.1:7890"
"%GE4_CMAKE%" -S tmp/ge4-build/k2-fsa-sherpa-ncnn-c794e14 -B tmp/ge4-build/ncnn-build -G Ninja -DCMAKE_BUILD_TYPE=Release -DSHERPA_NCNN_ENABLE_PORTAUDIO=OFF -DSHERPA_NCNN_ENABLE_C_API=OFF -DSHERPA_NCNN_ENABLE_GENERATE_INT8_SCALE_TABLE=OFF -DNCNN_VULKAN=OFF -DNCNN_AVX=OFF -DNCNN_AVX2=OFF -DNCNN_AVX512=OFF -DNCNN_AVX512VNNI=OFF -DNCNN_AVX512BF16=OFF -DNCNN_AVX512FP16=OFF -DNCNN_XOP=OFF -DNCNN_FMA=OFF -DNCNN_F16C=OFF -DCMAKE_POLICY_VERSION_MINIMUM=3.5
if errorlevel 1 exit /b %errorlevel%
"%GE4_CMAKE%" --build tmp/ge4-build/ncnn-build --target ncnn-stream-probe --parallel 6

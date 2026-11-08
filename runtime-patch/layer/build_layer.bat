:: SPDX-License-Identifier: Apache-2.0
@echo off
setlocal enabledelayedexpansion

REM  build_layer.bat: compile rtxremix_patch_layer.dll, the mod's Vulkan layer for the runtime.
REM  Usage: build_layer.bat [x64]   Requires Visual Studio with the C++ workload and the Vulkan
REM  headers: VULKAN_SDK, or the copy under runtime-patch\layer\include when present.
REM  Output: build/native/win64/ beside remixbridge.dll, with the layer manifest.

set "ARCH=%~1"
if "%ARCH%"=="" set "ARCH=x64"
if /I not "%ARCH%"=="x64" (
    echo ERROR: only x64 is built until the layer has run on x64.
    exit /b 1
)

set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
for /f "usebackq tokens=*" %%i in (`"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VS_PATH=%%i"
if not defined VS_PATH (
    echo ERROR: No Visual Studio installation found.
    exit /b 1
)
call "%VS_PATH%\VC\Auxiliary\Build\vcvarsall.bat" x64 >nul 2>&1
if errorlevel 1 (
    echo ERROR: vcvarsall.bat failed.
    exit /b 1
)

set "VK_INCLUDE=%~dp0include"
if not exist "%VK_INCLUDE%\vulkan\vk_layer.h" set "VK_INCLUDE=%VULKAN_SDK%\Include"
if not exist "%VK_INCLUDE%\vulkan\vk_layer.h" (
    echo ERROR: vulkan\vk_layer.h not found. Set VULKAN_SDK.
    exit /b 1
)

set "OUT_DIR=%~dp0..\..\build\native\win64"
if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"

echo Compiling rtxremix_patch_layer.dll ...
cl /nologo /O2 /Zi /LD /EHsc /std:c++17 /DVK_USE_PLATFORM_WIN32_KHR ^
   /I"%VK_INCLUDE%" "%~dp0patch_layer.cpp" "%~dp0channel.cpp" ^
   /Fe"%OUT_DIR%\rtxremix_patch_layer.dll" /Fo"%OUT_DIR%\\" /Fd"%OUT_DIR%\rtxremix_patch_layer.pdb" ^
   /link /nologo /DEBUG
if errorlevel 1 (
    echo ERROR: Compilation failed.
    exit /b 1
)
copy /Y "%~dp0VkLayer_rtxremix_spirv_patch.json" "%OUT_DIR%\" >nul
echo rtxremix_patch_layer.dll built: %OUT_DIR%
endlocal
exit /b 0

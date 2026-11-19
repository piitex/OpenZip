@echo off
rem Builds this project's app with the RenGL packager. The settings are in rengl-package.properties.
rem Set RENGL_HOME to your RenGL folder, or keep RenGL next to this folder (..\RenGL).
rem Parameters pass through: package.cmd -Installer true   or   package.cmd -Platform linux-x64
if "%RENGL_HOME%"=="" set "RENGL_HOME=%~dp0..\RenGL"
if not exist "%RENGL_HOME%\rengl-packager\package-app.ps1" (
    echo RenGL was not found at "%RENGL_HOME%". Set RENGL_HOME to the folder you cloned it into.
    exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%RENGL_HOME%\rengl-packager\package-app.ps1" -Project "%~dp0." %*
exit /b %errorlevel%

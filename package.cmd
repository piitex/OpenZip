@echo off
rem Builds this project's app with the RenGL packager. The settings are in rengl-package.properties.
rem Parameters pass through: package.cmd -Installer true   or   package.cmd -Platform linux-x64
powershell -NoProfile -ExecutionPolicy Bypass -File "C:\Users\Hack\Desktop\Projects\RenGL\rengl-packager\package-app.ps1" %*
exit /b %errorlevel%

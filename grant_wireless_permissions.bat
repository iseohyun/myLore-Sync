@echo off
echo ===================================================
echo  myLore Sync - Grant Wireless ADB Permissions
echo ===================================================
echo.
set PACKAGE_NAME=com.iseohyun.mylore.sync

echo Setting MANAGE_EXTERNAL_STORAGE permission...
adb shell appops set %PACKAGE_NAME% MANAGE_EXTERNAL_STORAGE allow

echo Setting Notification Listener permission...
adb shell cmd notification allow_listener %PACKAGE_NAME%/.collector.MyloreNotificationListener

echo.
echo All wireless test permissions granted successfully!
pause

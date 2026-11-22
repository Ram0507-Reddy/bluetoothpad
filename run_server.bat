@echo off
title Bluetooth Laptop Remote Server
cd /d "%~dp0server"
echo ===================================================
echo   Starting Bluetooth Remote Server...
echo   Keep this window open while using the remote!
echo ===================================================
python -u server.py
pause

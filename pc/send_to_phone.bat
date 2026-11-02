@echo off
chcp 65001 >nul
py "%~dp0photo_sender.py" %*

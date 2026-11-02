@echo off
chcp 65001 >nul
cd /d "%~dp0"
rem если рядом лежит Clawd для ПК (github.com/f3403487-arch/clawd-pc) — запускаем и его
if exist clawd_desktop.py start "" pyw clawd_desktop.py
py photo_receiver.py
pause

@echo off
chcp 65001 >nul
echo 最新の基準価額・指数を取得します...
pwsh -NoProfile -ExecutionPolicy Bypass -File "%~dp0update.ps1"
if %errorlevel% neq 0 (
  echo.
  echo 取得に失敗しました。ネット接続を確認してください。
  pause
  exit /b 1
)
echo.
echo データ更新が完了しました。index.html を開いてください。
timeout /t 3 >nul

@echo off
echo ==================================================
echo 🚀 Rikkei Auto Solver - Installation Script
echo ==================================================

echo.
echo [1/4] Checking Python installation...
python --version >nul 2>&1
IF %ERRORLEVEL% NEQ 0 (
    echo [!] Python is not installed or not in PATH! Please install Python 3.10+ first.
    pause
    exit /b
)
echo [OK] Python is installed.

echo.
echo [2/4] Creating Virtual Environment (venv)...
if not exist "venv" (
    python -m venv venv
    echo [OK] Virtual Environment created.
) else (
    echo [OK] Virtual Environment already exists.
)

echo.
echo [3/4] Installing dependencies...
call venv\Scripts\activate
pip install -r requirements.txt

echo.
echo [4/4] Installing Playwright Chromium browser...
playwright install chromium

echo.
echo ==================================================
echo 🎉 Installation Complete!
echo ==================================================
echo.
if not exist ".env" (
    echo [!] File .env not found. Copying from .env.example...
    if exist ".env.example" (
        copy .env.example .env
        echo [OK] File .env created. Please edit it to add your API keys!
    ) else (
        echo [!] WARNING: .env.example not found! You must create .env manually.
    )
) else (
    echo [OK] File .env exists.
)

echo.
echo You can now run the tool by executing:
echo .\venv\Scripts\python.exe main.py
pause

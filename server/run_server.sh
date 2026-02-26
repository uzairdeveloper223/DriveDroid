#!/usr/bin/env bash

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "DriveDroidServer launcher"
echo

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is not installed. Please install Python 3 and try again."
  exit 1
fi

if ! python3 -c "import venv" >/dev/null 2>&1; then
  echo "Python venv module is missing. On Debian/Ubuntu, run:"
  echo "  sudo apt install python3-venv"
  exit 1
fi

if ! command -v wmctrl >/dev/null 2>&1; then
  echo "wmctrl not found. Attempting to install (requires sudo)..."
  if command -v sudo >/dev/null 2>&1; then
    sudo apt-get update && sudo apt-get install -y wmctrl || {
      echo "Failed to install wmctrl automatically. Please install it manually and re-run this script."
    }
  else
    echo "sudo is not available. Please install wmctrl manually (for example: sudo apt install wmctrl)."
  fi
fi

if [ ! -e /dev/uinput ]; then
  echo "/dev/uinput is not available. You may need to load the uinput module:"
  echo "  sudo modprobe uinput"
  echo "and ensure your user has permission to use it."
fi

if [ ! -d "venv" ]; then
  echo "Creating Python virtual environment in ./venv"
  python3 -m venv venv
fi

echo "Activating virtual environment and installing dependencies"
source venv/bin/activate

if [ -f "requirements.txt" ]; then
  pip install --upgrade pip
  pip install -r requirements.txt
else
  pip install websockets python-uinput
fi

echo
echo "Starting DriveDroidServer (may require sudo for uinput access)..."
echo

python server.py


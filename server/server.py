import asyncio
import socket
import logging
import json
import uinput
import websockets
from websockets.server import serve
import subprocess
import time

# Configure logging
logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger(__name__)

# Axis range for analog steering
ABS_MAX = 32767
ABS_MIN = -32768

# Map logical actions to uinput keys (for buttons / pedals)
KEY_MAP = {
    # Driving
    "ACCELERATE_W": uinput.KEY_W,
    "ACCELERATE_UP": uinput.KEY_UP,
    "BRAKE_S": uinput.KEY_S,
    "BRAKE_DOWN": uinput.KEY_DOWN,
    # Steering keyboard fallback
    "STEER_A": uinput.KEY_A,
    "STEER_LEFT": uinput.KEY_LEFT,
    "STEER_D": uinput.KEY_D,
    "STEER_RIGHT": uinput.KEY_RIGHT,
    # Other
    "HANDBRAKE": uinput.KEY_SPACE
}

class DriveDroidServer:
    def __init__(self, port=8765):
        self.port = port
        self.ip_address = self.get_local_ip()
        self.keyboard_device = None
        self.gamepad_device = None
        self.active_keys = set()
        self.target_window_id = None
        self.last_steer_value = 0
        
    def get_local_ip(self):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            s.close()
            return ip
        except Exception:
            return "127.0.0.1"

    def select_window(self):
        try:
            output = subprocess.check_output(["wmctrl", "-l"], text=True)
            windows = []
            for line in output.strip().split('\n'):
                parts = line.split(maxsplit=3)
                if len(parts) >= 4:
                    win_id, _, _, win_name = parts
                    windows.append({"id": win_id, "name": win_name})

            if not windows:
                print("No visible windows found. Input will go to currently focused window.")
                return

            print("\nAvailable Windows:")
            for i, win in enumerate(windows):
                print(f"[{i}] {win['name']}")
            print(f"[{len(windows)}] Disable targeting (Focus current window instead)")
            
            choice = input(f"\nSelect target window [0-{len(windows)}]: ")
            try:
                idx = int(choice)
                if 0 <= idx < len(windows):
                    self.target_window_id = windows[idx]['id']
                    print(f"Target selected: {windows[idx]['name']}")
                else:
                    print("Targeting disabled.")
            except ValueError:
                print("Invalid input. Targeting disabled.")

        except FileNotFoundError:
            print("Notice: 'wmctrl' not installed. Window targeting feature is disabled.")
            print("Inputs will go to whichever window has focus.")
            print("To enable window targeting, run: sudo apt install wmctrl")
        except subprocess.CalledProcessError:
            print("Warning: Could not fetch window list.")
            print("Inputs will go to whichever window has focus.")
            
    def focus_target_window(self):
        if self.target_window_id:
            try:
                subprocess.run(["wmctrl", "-i", "-a", self.target_window_id], check=False)
            except Exception as e:
                logger.error(f"Failed to focus window: {e}")
            
    def setup_uinput(self):
        logger.info("Setting up virtual devices via uinput...")
        
        try:
            # Create a SEPARATE keyboard device for pedal/button keys
            key_events = list(KEY_MAP.values())
            self.keyboard_device = uinput.Device(key_events, name="DriveDroid Keyboard")
            logger.info("  Virtual keyboard created (pedals/buttons).")
            
            # Create a SEPARATE gamepad device for analog steering
            gamepad_events = [
                uinput.ABS_X + (ABS_MIN, ABS_MAX, 0, 0),
            ]
            self.gamepad_device = uinput.Device(gamepad_events, name="DriveDroid Gamepad")
            time.sleep(0.5)  # uinput needs a moment after creation
            self.gamepad_device.emit(uinput.ABS_X, 0)  # Center
            logger.info("  Virtual gamepad created (analog steering axis).")
            
            return True
        except Exception as e:
            logger.error(f"Failed to create uinput device: {e}")
            logger.error("\n*** PERMISSION ERROR ***")
            logger.error("You likely need to run this script as root (sudo) OR add your user to the 'input' group.")
            logger.error("Run: sudo gpasswd -a $USER input")
            logger.error("For a quick test, run the server with sudo: sudo ./venv/bin/python server.py\n")
            return False

    async def handle_client(self, websocket):
        client_addr = websocket.remote_address
        logger.info(f"Client connected from {client_addr}")
        
        try:
            async for message in websocket:
                try:
                    data = json.loads(message)
                    self.process_command(data)
                except json.JSONDecodeError:
                    logger.warning(f"Received invalid JSON: {message}")
                except Exception as e:
                    logger.error(f"Error processing message: {e}")
                    
        except websockets.exceptions.ConnectionClosed:
            logger.info(f"Client {client_addr} disconnected.")
        finally:
            self.release_all_keys()
            self.gamepad_device.emit(uinput.ABS_X, 0)
            self.last_steer_value = 0
            
    def process_command(self, data):
        """
        Two types of commands:
        
        1. Analog steering (goes to gamepad device):
           {"action": "STEER", "value": 0.35}
           
        2. Button press/release (goes to keyboard device): 
           {"action": "ACCELERATE_W", "state": "DOWN"}
        """
        action = data.get("action")
        
        # --- Analog Steering -> Gamepad Device ---
        if action == "STEER":
            value = data.get("value", 0.0)
            value = max(-1.0, min(1.0, float(value)))
            axis_value = int(value * ABS_MAX)
            
            if abs(axis_value - self.last_steer_value) > 200:
                self.focus_target_window()
                self.gamepad_device.emit(uinput.ABS_X, axis_value)
                self.last_steer_value = axis_value
            return
        
        # --- Button Presses -> Keyboard Device ---
        state_str = data.get("state")
        
        if action not in KEY_MAP:
            logger.warning(f"Unknown action received: {action}")
            return
            
        key_code = KEY_MAP[action]
        
        if state_str == "DOWN":
            if key_code not in self.active_keys:
                self.focus_target_window()
                self.keyboard_device.emit(key_code, 1)
                self.active_keys.add(key_code)
        elif state_str == "UP":
            if key_code in self.active_keys:
                self.focus_target_window()
                self.keyboard_device.emit(key_code, 0)
                self.active_keys.remove(key_code)
        else:
            logger.warning(f"Unknown state: {state_str}")

    def release_all_keys(self):
        for key_code in list(self.active_keys):
            self.keyboard_device.emit(key_code, 0)
        self.active_keys.clear()
        logger.info("All virtual keys released.")

    async def start(self):
        if not self.setup_uinput():
            return
            
        self.select_window()
            
        server = await serve(self.handle_client, "0.0.0.0", self.port)
        
        print("\n" + "="*50)
        print("  DRIVEDROID SERVER RUNNING  ".center(50))
        print("="*50)
        print(f"Android App Connection Details:")
        print(f"  IP Address: {self.ip_address}")
        print(f"  Port:       {self.port}")
        print(f"\n  Keyboard: DriveDroid Keyboard (pedals/buttons)")
        print(f"  Gamepad:  DriveDroid Gamepad  (analog steering)")
        print(f"\nListening for commands...")
        print("Press Ctrl+C to stop.")
        print("="*50 + "\n")
        
        await asyncio.Future()

if __name__ == "__main__":
    try:
        server = DriveDroidServer()
        asyncio.run(server.start())
    except KeyboardInterrupt:
        logger.info("Server manually stopped.")

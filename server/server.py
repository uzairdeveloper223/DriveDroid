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


class PWMSteeringController:
    """
    Simulates analog steering over binary keyboard keys using PWM.

    How it works:
      - Each cycle is CYCLE_MS milliseconds long.
      - The key is held DOWN for (abs(steer_value) * CYCLE_MS) ms  → ON time.
      - The key is released for the remaining OFF time.
      - steer_value = 0.0  → key never pressed  (center)
      - steer_value = 0.5  → key pressed 50% of cycle (gradual turn)
      - steer_value = 1.0  → key held continuously (full lock)

    Speed Dreams physics engine sees rapid key taps and its steering 
    accumulates gradually, giving a proportional feel from binary keys.
    """

    # Total PWM cycle duration in seconds. 
    # 50ms = 20Hz refresh. Shorter = smoother but may miss inputs.
    CYCLE_S = 0.050

    # Below this abs value the key is never pressed (center zone)
    CENTER_THRESHOLD = 0.04

    # Above this abs value the key is held permanently (full lock)
    FULL_LOCK_THRESHOLD = 0.97

    # Minimum OFF time so the game always registers a key-up between pulses
    MIN_OFF_S = 0.008  # 8ms

    def __init__(self, keyboard_device, steer_keys: dict):
        """
        steer_keys: {"left": uinput.KEY_A, "right": uinput.KEY_D}
        """
        self.keyboard_device = keyboard_device
        self.left_key = steer_keys["left"]
        self.right_key = steer_keys["right"]

        # Thread-safe target: set from websocket handler, read by PWM loop
        self._target_value: float = 0.0
        self._active_key = None  # currently held key
        self._running = False
        self._task: asyncio.Task | None = None

    def set_value(self, value: float):
        """Called from websocket handler to update steering target (-1.0 … 1.0)."""
        self._target_value = max(-1.0, min(1.0, float(value)))

    async def start(self):
        self._running = True
        self._task = asyncio.create_task(self._pwm_loop())
        logger.info("PWM steering loop started.")

    async def stop(self):
        self._running = False
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
        self._release_current()
        logger.info("PWM steering loop stopped.")

    def _press(self, key):
        if self._active_key != key:
            self._release_current()
            self.keyboard_device.emit(key, 1)
            self._active_key = key

    def _release_current(self):
        if self._active_key is not None:
            self.keyboard_device.emit(self._active_key, 0)
            self._active_key = None

    async def _pwm_loop(self):
        while self._running:
            value = self._target_value
            abs_val = abs(value)

            # ── Center: release everything ──────────────────────────────────
            if abs_val < self.CENTER_THRESHOLD:
                self._release_current()
                await asyncio.sleep(self.CYCLE_S)
                continue

            key = self.left_key if value < 0 else self.right_key

            # ── Full lock: hold key continuously ────────────────────────────
            if abs_val >= self.FULL_LOCK_THRESHOLD:
                self._press(key)
                await asyncio.sleep(self.CYCLE_S)
                continue

            # ── Proportional PWM pulse ───────────────────────────────────────
            on_time  = self.CYCLE_S * abs_val
            off_time = self.CYCLE_S - on_time

            # Guarantee a minimum off gap so the game sees a key-up event
            if off_time < self.MIN_OFF_S:
                off_time = self.MIN_OFF_S
                on_time  = self.CYCLE_S - off_time

            self._press(key)
            await asyncio.sleep(on_time)

            self._release_current()
            await asyncio.sleep(off_time)


class DriveDroidServer:
    def __init__(self, port=8765):
        self.port = port
        self.ip_address = self.get_local_ip()
        self.keyboard_device = None
        self.gamepad_device = None
        self.active_keys = set()          # for non-steering buttons
        self.target_window_id = None

        # PWM controller – instantiated after uinput devices are ready
        self.pwm_ad: PWMSteeringController | None = None       # A / D
        self.pwm_arrows: PWMSteeringController | None = None   # ← / →
        self.active_pwm: PWMSteeringController | None = None   # whichever the app chose

        # Last gamepad axis value (for analog mode)
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
            print("To enable: sudo apt install wmctrl")
        except subprocess.CalledProcessError:
            print("Warning: Could not fetch window list.")

    def focus_target_window(self):
        if self.target_window_id:
            try:
                subprocess.run(["wmctrl", "-i", "-a", self.target_window_id], check=False)
            except Exception as e:
                logger.error(f"Failed to focus window: {e}")

    def setup_uinput(self):
        logger.info("Setting up virtual devices via uinput...")
        try:
            key_events = list(KEY_MAP.values())
            self.keyboard_device = uinput.Device(key_events, name="DriveDroid Keyboard")
            logger.info("  Virtual keyboard created.")

            gamepad_events = [
                uinput.ABS_X + (ABS_MIN, ABS_MAX, 0, 0),
                uinput.BTN_A,    # SDL2 requires at least one button to register as joystick
                uinput.BTN_B,
            ]
            self.gamepad_device = uinput.Device(gamepad_events, name="DriveDroid Gamepad")
            time.sleep(0.5)
            self.gamepad_device.emit(uinput.ABS_X, 0)
            logger.info("  Virtual gamepad created.")

            # Build both PWM controllers (the app will pick which one via STEER_MODE)
            self.pwm_ad = PWMSteeringController(
                self.keyboard_device, {"left": uinput.KEY_A, "right": uinput.KEY_D}
            )
            self.pwm_arrows = PWMSteeringController(
                self.keyboard_device, {"left": uinput.KEY_LEFT, "right": uinput.KEY_RIGHT}
            )
            return True

        except Exception as e:
            logger.error(f"Failed to create uinput device: {e}")
            logger.error("\n*** PERMISSION ERROR ***")
            logger.error("Run: sudo gpasswd -a $USER input")
            logger.error("Or for a quick test: sudo python server.py\n")
            return False

    async def handle_client(self, websocket):
        client_addr = websocket.remote_address
        logger.info(f"Client connected from {client_addr}")

        # Default to A/D PWM steering when a client connects
        if self.active_pwm is None:
            self.active_pwm = self.pwm_ad
            await self.active_pwm.start()

        try:
            async for message in websocket:
                try:
                    data = json.loads(message)
                    await self.process_command(data)
                except json.JSONDecodeError:
                    logger.warning(f"Invalid JSON: {message}")
                except Exception as e:
                    logger.error(f"Error processing message: {e}")

        except websockets.exceptions.ConnectionClosed:
            logger.info(f"Client {client_addr} disconnected.")
        finally:
            await self._cleanup_on_disconnect()

    async def _cleanup_on_disconnect(self):
        # Stop PWM and center steering
        if self.active_pwm:
            await self.active_pwm.stop()
            self.active_pwm = None
        # Release gamepad axis
        self.gamepad_device.emit(uinput.ABS_X, 0)
        self.last_steer_value = 0
        # Release all button keys
        self.release_all_keys()

    async def process_command(self, data: dict):
        """
        Command types:

        ┌─────────────────────────────────────────────────────────────────┐
        │ 1. Analog steering (GAMEPAD mode → gamepad axis)                │
        │    {"action": "STEER", "value": 0.35}                           │
        │                                                                  │
        │ 2. PWM steering (KEYBOARD_AD / KEYBOARD_ARROWS → PWM pulses)   │
        │    {"action": "STEER_PWM", "value": 0.35}                       │
        │                                                                  │
        │ 3. Steering mode switch (sent once on settings save)            │
        │    {"action": "STEER_MODE", "mode": "KEYBOARD_AD"}              │
        │                                                                  │
        │ 4. Button press/release (pedals, handbrake)                     │
        │    {"action": "ACCELERATE_W", "state": "DOWN"}                  │
        └─────────────────────────────────────────────────────────────────┘
        """
        action = data.get("action")

        # ── Gamepad analog axis ──────────────────────────────────────────
        if action == "STEER":
            value = max(-1.0, min(1.0, float(data.get("value", 0.0))))
            axis_value = int(value * ABS_MAX)
            if abs(axis_value - self.last_steer_value) > 200:
                self.focus_target_window()
                self.gamepad_device.emit(uinput.ABS_X, axis_value)
                self.last_steer_value = axis_value
            return

        # ── PWM keyboard steering ────────────────────────────────────────
        if action == "STEER_PWM":
            value = float(data.get("value", 0.0))
            if self.active_pwm:
                self.focus_target_window()
                self.active_pwm.set_value(value)
            return

        # ── Steering mode switch ─────────────────────────────────────────
        if action == "STEER_MODE":
            mode = data.get("mode", "KEYBOARD_AD")
            await self._switch_pwm_mode(mode)
            return

        # ── Button press / release ───────────────────────────────────────
        state_str = data.get("state")
        if action not in KEY_MAP:
            logger.warning(f"Unknown action: {action}")
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

    async def _switch_pwm_mode(self, mode: str):
        """Hot-swap PWM controller when the user changes steering mode in settings."""
        new_pwm = None
        if mode == "KEYBOARD_AD":
            new_pwm = self.pwm_ad
        elif mode == "KEYBOARD_ARROWS":
            new_pwm = self.pwm_arrows
        # GAMEPAD mode = no PWM controller needed

        if new_pwm is self.active_pwm:
            return  # nothing to change

        if self.active_pwm:
            await self.active_pwm.stop()

        self.active_pwm = new_pwm
        if self.active_pwm:
            await self.active_pwm.start()

        logger.info(f"Steering mode switched to: {mode}")

    def release_all_keys(self):
        for key_code in list(self.active_keys):
            self.keyboard_device.emit(key_code, 0)
        self.active_keys.clear()
        logger.info("All virtual keys released.")

    def get_windows(self):
        """Returns list of {id, name} dicts or empty list."""
        try:
            output = subprocess.check_output(["wmctrl", "-l"], text=True)
            windows = []
            for line in output.strip().split('\n'):
                parts = line.split(maxsplit=3)
                if len(parts) >= 4:
                    win_id, _, _, win_name = parts
                    windows.append({"id": win_id, "name": win_name})
            return windows
        except Exception:
            return []

    def print_status(self):
        target = "None (focused window)" if not self.target_window_id else self.target_window_id
        print(f"\n  Current target window : {target}")
        print("  Commands: [w] switch window  [s] status  [q] quit")
        print("  ─────────────────────────────────────────────────")

    async def stdin_command_loop(self):
        """
        Reads single keypresses from stdin while server is running.
        Lets you switch target window at any time without restarting.
        """
        loop = asyncio.get_event_loop()

        # Put stdin in non-blocking mode for asyncio
        import sys, os, termios, tty

        fd = sys.stdin.fileno()
        old_settings = termios.tcgetattr(fd)

        try:
            tty.setcbreak(fd)  # single keypress mode, no Enter needed
            while True:
                # Async wait for a character
                char = await loop.run_in_executor(None, sys.stdin.read, 1)
                char = char.lower().strip()

                if char == 'w':
                    # ── Switch window ──────────────────────────────────────
                    windows = self.get_windows()
                    if not windows:
                        print("\n  No windows found via wmctrl. Is wmctrl installed?")
                        print("  sudo apt install wmctrl")
                        continue

                    print("\n  ┌─ Available Windows ──────────────────────────")
                    for i, win in enumerate(windows):
                        marker = " ◀" if win["id"] == self.target_window_id else ""
                        print(f"  │ [{i}] {win['name']}{marker}")
                    print(f"  │ [{len(windows)}] Disable targeting")
                    print("  └──────────────────────────────────────────────")

                    # Temporarily restore normal input for the number prompt
                    termios.tcsetattr(fd, termios.TCSADRAIN, old_settings)
                    try:
                        choice = input(f"  Select [0-{len(windows)}]: ").strip()
                        idx = int(choice)
                        if 0 <= idx < len(windows):
                            self.target_window_id = windows[idx]['id']
                            print(f"  ✓ Target → {windows[idx]['name']}")
                        else:
                            self.target_window_id = None
                            print("  ✓ Targeting disabled.")
                    except (ValueError, EOFError):
                        print("  Invalid input, no change.")
                    finally:
                        tty.setcbreak(fd)

                elif char == 's':
                    self.print_status()

                elif char in ('q', '\x03'):  # q or Ctrl+C
                    print("\n  Shutting down...")
                    raise KeyboardInterrupt

        finally:
            termios.tcsetattr(fd, termios.TCSADRAIN, old_settings)

    async def start(self):
        if not self.setup_uinput():
            return

        # Initial window selection at startup
        self.select_window()

        server = await serve(self.handle_client, "0.0.0.0", self.port)

        print("\n" + "=" * 50)
        print("  DRIVEDROID SERVER RUNNING  ".center(50))
        print("=" * 50)
        print(f"  IP Address : {self.ip_address}")
        print(f"  Port       : {self.port}")
        print(f"\n  Steering   : PWM keyboard (proportional feel)")
        print(f"  Cycle time : {PWMSteeringController.CYCLE_S * 1000:.0f} ms")
        print("\n  ── Runtime Commands ──────────────────────────")
        print("  [w]  Switch target window anytime")
        print("  [s]  Show current status")
        print("  [q]  Quit server")
        print("  ──────────────────────────────────────────────")
        print("  Tip: Start your game AFTER server is running,")
        print("       then press [w] to target it.")
        print("=" * 50 + "\n")

        # Run websocket server + stdin loop concurrently
        await asyncio.gather(
            asyncio.Future(),       # keeps websocket server alive
            self.stdin_command_loop()
        )


if __name__ == "__main__":
    try:
        server = DriveDroidServer()
        asyncio.run(server.start())
    except KeyboardInterrupt:
        logger.info("Server manually stopped.")

<p align="center">
  <img src="./drive-droid-logo.svg" alt="DriveDroid Logo" width="120" />
</p>

## DriveDroid

[![License](https://img.shields.io/github/license/uzairdeveloper223/DriveDroid)](./LICENSE)
[![GitHub Repo](https://img.shields.io/badge/repo-uzairdeveloper223%2FDriveDroid-24292e?logo=github&logoColor=white)](https://github.com/uzairdeveloper223/DriveDroid)
[![Release workflow](https://img.shields.io/github/actions/workflow/status/uzairdeveloper223/DriveDroid/release.yml?label=release&logo=github)](https://github.com/uzairdeveloper223/DriveDroid/actions/workflows/release.yml)
[![Latest release](https://img.shields.io/github/v/release/uzairdeveloper223/DriveDroid?display_name=tag&sort=semver)](https://github.com/uzairdeveloper223/DriveDroid/releases)
[![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Built with](https://img.shields.io/badge/built_with-Kotlin-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)

DriveDroid turns your Android phone into a simple, low‑latency steering wheel and pedal controller for your PC racing games.  
The app connects to a small WebSocket server on your Linux machine and sends steering, acceleration, braking and handbrake commands in real time.

### What you get

- **Motion steering**: Hold your phone like a steering wheel. You can tweak sensitivity and deadzone from the in‑app settings.
- **Touch mode**: If you do not want motion, there are big left/right, race, brake and handbrake buttons on the screen.
- **PWM keyboard steering**: Instead of sending a binary key hold that locks steering fully, the server pulses the key on and off rapidly based on how much you are tilting. Small tilt means short pulses with long gaps. Full tilt means the key is just held. The game physics averages these pulses into proportional steering. This works with almost any racing game that takes keyboard input.
- **Analog gamepad steering**: If your game supports analog axes (like StuntRally, BeamNG, ETS2), the server exposes a virtual gamepad via uinput and DriveDroid sends raw float values directly to it. No PWM needed, just real analog steering.
- **Accelerometer low‑pass filter**: If your phone has no gyroscope the raw accelerometer is noisy. I added an exponential low‑pass filter on the sensor data so the steering stays smooth instead of jittering around.
- **Keyboard mapping**: Everything maps to common keys (W/S or arrows) so you can plug this into almost any PC racing game without touching any config.
- **Saved settings**: I store your control mode and steering configuration with DataStore so you do not have to re‑configure every time.

### What changed in v1.0.1

**PWM steering (the big one)**

In v1.0.0, keyboard mode just held the key down the moment you tilted past the deadzone. That meant full steering lock instantly. Not great.

Now the server runs a PWM loop at 20Hz. It calculates how long to hold the key vs how long to release it each cycle based on your tilt value. The result is that the game sees rapid key taps instead of a held key, and its physics integrates those taps into gradual proportional steering. Same concept as PWM in electronics, just with key events instead of voltage.

```
20% tilt  →  A ... A ... A ...    short press, long gap
50% tilt  →  AAAA .. AAAA ..      equal press and gap  
80% tilt  →  AAAAAAAA. AAAAAAAA.  long press, tiny gap
100% tilt →  AAAAAAAAAAAAAAAA     just hold
```

**SDL2 gamepad fix**

SDL2 (which StuntRally and many other games use internally) ignores uinput devices that have no buttons, even if the axis works fine in jstest. I added `BTN_A` and `BTN_B` to the virtual gamepad device so SDL2 actually registers it as a proper joystick.

**Runtime window switcher**

Previously you had to pick your target window at server startup and restart if you opened the game after. Now the server runs an async stdin loop alongside the websocket server. While it is running you can press:

- `[w]` to pull up the live window list and switch target at any time
- `[s]` to check current status
- `[q]` to shut down cleanly

The typical workflow is now: start the server, skip window selection, open your game, press `[w]`, pick the game window, connect your phone.

**Accelerometer low‑pass filter**

Added an exponential filter on the sensor side. The alpha value is automatically set based on which sensor your phone has. Rotation vector gets `0.6` since it is already gyro‑fused. Gravity sensor gets `0.35`. Raw accelerometer (no gyro) gets `0.15` which smooths aggressively to kill jitter.

### How to set it up

Most people will not need to build anything.  
You can grab the latest APK and server ZIP from the **[Releases page](https://github.com/uzairdeveloper223/DriveDroid/releases)** and start using it.

If you prefer to build or hack on the code yourself, the steps below explain how.

1. **Set up the PC server (Linux)**

Inside the `server/` folder I provide a small Python WebSocket server that receives commands from the app and sends real keyboard and gamepad events to your game using `uinput` and `websockets`.

The easiest way to start it is with the helper script included in the server ZIP:

```bash
cd server
chmod +x run_server.sh
./run_server.sh
```

The script will:

- Create `venv/` if it does not exist.
- Install Python dependencies from `requirements.txt`.
- Try to install `wmctrl` (needed for runtime window targeting).

Depending on your system you may still need to run it with `sudo` or adjust your `uinput` permissions.

If you prefer to do everything by hand:

```bash
cd server
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
sudo venv/bin/python server.py
```

**Important for games like StuntRally**: start the server first, then open your game, then press `[w]` in the server terminal to target the game window. The virtual gamepad device needs to exist before the game launches so SDL2 can enumerate it.

2. **Install the Android app and connect**

- Install `DriveDroid-<version>.apk` from the Releases page.
- Make sure the Python server is running and note the IP and port (default `8765`).
- Open DriveDroid, enter the IP and port, tap **CONNECT**.
- Open **Settings** in the top right and choose your control mode and key layout.

### Which games work

**Works great (keyboard PWM)**  
Any game that steers with keys. Speed Dreams, SuperTuxKart, GTA San Andreas, Trackmania in keyboard mode, most emulators.

**Works great (analog gamepad mode)**  
Games with proper analog axis support. StuntRally 3, BeamNG.drive, Euro Truck Simulator 2, Assetto Corsa. Switch the app to Gamepad mode and bind `DriveDroid Gamepad Axis 0` in the game's controller settings.

**Singleplayer only**  
GTA V works in singleplayer. Online modes with kernel‑level anticheat (EAC, BattlEye) will flag virtual uinput devices.

### Releases

For a tag like `v1.0.1`, the CI workflow builds:

- **Android APK**: `DriveDroid-v1.0.1.apk`
- **Server bundle**: `DriveDroidServer-v1.0.1.zip` (includes `server.py`, `requirements.txt`, and `run_server.sh`)

```bash
unzip DriveDroidServer-v1.0.1.zip
cd server
chmod +x run_server.sh
./run_server.sh
```

### WebSocket protocol (what the app sends)

If you want to integrate this with your own tooling or just see what is going over the wire:

- **Pedals / buttons**
  - `{"action": "ACCELERATE_W", "state": "DOWN"}`
  - `{"action": "ACCELERATE_W", "state": "UP"}`
  - `{"action": "BRAKE_S", "state": "DOWN"}`
  - `{"action": "HANDBRAKE", "state": "DOWN"}`
- **Steering analog (gamepad mode)**
  - `{"action": "STEER", "value": -1.0}` — full left
  - `{"action": "STEER", "value": 0.0}` — center
  - `{"action": "STEER", "value": 1.0}` — full right
- **Steering PWM (keyboard mode)**
  - `{"action": "STEER_PWM", "value": 0.35}` — server handles the pulsing
- **Steering mode switch (sent on settings save)**
  - `{"action": "STEER_MODE", "mode": "KEYBOARD_AD"}`

### Project links

- **Repository**: `https://github.com/uzairdeveloper223/DriveDroid`
- **Author GitHub**: `https://github.com/uzairdeveloper223`
- **Portfolio**: `https://uzair.is-a.dev`

### About me

Hi, I am Uzair. I built this because I wanted a simple way to use my phone as a steering wheel and pedal setup for PC games without buying extra hardware.

- **GitHub**: [@uzairdeveloper223](https://github.com/uzairdeveloper223)
- **Portfolio**: [uzair.is-a.dev](https://uzair.is-a.dev)

If this project helps you or you just like it and want to support my work, you can donate here (includes all my crypto addresses):

- `https://donate.uzair.ct.ws`

### License

This project is licensed under the **MIT License**.  
See [`LICENSE`](./LICENSE) for full details.

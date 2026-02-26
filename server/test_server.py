from server import DriveDroidServer


class MockKeyboardDevice:
    def emit(self, key, value):
        print(f"MockKeyboardDevice.emit: key={key}, value={value}")


def main():
    srv = DriveDroidServer()
    # Bypass real uinput setup; inject mock keyboard only
    srv.keyboard_device = MockKeyboardDevice()

    print("Testing DOWN")
    srv.process_command({"action": "ACCELERATE_W", "state": "DOWN"})

    print("Testing UP")
    srv.process_command({"action": "ACCELERATE_W", "state": "UP"})

    print("Testing Unknown action")
    srv.process_command({"action": "INVALID_KEY", "state": "DOWN"})


if __name__ == "__main__":
    main()


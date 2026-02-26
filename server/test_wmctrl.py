from server import DriveDroidServer


def main():
    srv = DriveDroidServer()
    print("\n--- Testing window selection ---")
    srv.select_window()


if __name__ == "__main__":
    main()


import os

TARGET_CMD_SOCK_PATH = os.environ.get("TARGET_CMD_SOCK_PATH") or "ipc:///tmp/target.cmd"

TARGET_SERVO_BOTTOM_PIN = int(os.environ.get("TARGET_SERVO_BOTTOM_PIN") or "18")
TARGET_SERVO_TOP_PIN = int(os.environ.get("TARGET_SERVO_TOP_PIN") or "19")

DEBUG_LOGGING = (os.environ.get("DEBUG_LOGGING") or "false") == "true"
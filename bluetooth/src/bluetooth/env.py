

import os

# These defaults MUST match the UUIDs hardcoded in the Android app
# (app/.../ble/BleManager.kt) and the Tiltfile. Falling back to a random
# uuid4() here silently breaks the app: it can still connect (the scan has no
# service filter) but the characteristic lookup fails, so commands never send.
DEFAULT_SERVICE_UUID = "d6de50b6-456a-4e49-a172-a109443a83dc"
DEFAULT_RECEIVE_CMD_CHARACTERISTIC_UUID = "1696cc02-2f9d-4514-b673-5bd31938622f"
DEFAULT_SEND_NOTIF_CHARACTERISTIC_UUID = "37f0faa0-b94e-4309-84ba-980d2a9bebc6"

SERVICE_UUID = os.environ.get("SERVICE_UUID") or DEFAULT_SERVICE_UUID
RECEIVE_CMD_CHARACTERISTIC_UUID = os.environ.get(
    "RECEIVE_CMD_CHARACTERISTIC_UUID") or DEFAULT_RECEIVE_CMD_CHARACTERISTIC_UUID
SEND_NOTIF_CHARACTERISTIC_UUID = os.environ.get(
    "SEND_NOTIF_CHARACTERISTIC_UUID") or DEFAULT_SEND_NOTIF_CHARACTERISTIC_UUID

BLE_CMD_SOCK_PATH = os.environ.get("BLE_CMD_SOCK_PATH") or "ipc:///tmp/ble.cmd"
BLE_NOTIF_SOCK_PATH = os.environ.get(
    "BLE_NOTIF_SOCK_PATH") or "ipc:///tmp/ble.notif"

DEBUG_LOGGING = (os.environ.get("DEBUG_LOGGING") or "false") == "true"

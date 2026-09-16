export const BLE_CMD_OUT_SOCKET_PATH =
	process.env.BLE_CMD_OUT_SOCKET_PATH ?? "ipc:///tmp/ble.cmd"
export const BLE_NOTIF_IN_SOCKET_PATH =
	process.env.BLE_NOTIF_IN_SOCKET_PATH ?? "ipc:///tmp/ble.notif"
export const TARGET_CMD_SOCKET_PATH =
	process.env.TARGET_CMD_SOCKET_PATH ?? "ipc:///tmp/target.cmd"
export const DEBUG_LOGGING = process.env.DEBUG_LOGGING === "true"
export const FRAME_GAP_TOLERANCE = Number(process.env.FRAME_GAP_TOLERANCE ?? 3)
export const VIRTUAL_WALL_LENGTH = Number(
	process.env.VIRTUAL_WALL_LENGTH ?? 300,
)
export const VIRTUAL_WALL_HEIGHT = Number(
	process.env.VIRTUAL_WALL_HEIGHT ?? 200,
)
export const VIRTUAL_WALL_LASER_MARGIN = Number(
	process.env.VIRTUAL_WALL_LASER_MARGIN ?? 20,
)
export const TEN_MODE_VIRTUAL_MAX_DISTANCE = Number(
	process.env.TEN_MODE_VIRTUAL_MAX_DISTANCE ?? 40,
)

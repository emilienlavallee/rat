import * as zmq from "zeromq"
import { BLE_CMD_OUT_SOCKET_PATH, BLE_NOTIF_IN_SOCKET_PATH } from "./env"

export const createBleSockets = async () => {
	const bleCmdOutSock = new zmq.Push()
	const bleNotifInSock = new zmq.Pull()
	bleCmdOutSock.connect(BLE_CMD_OUT_SOCKET_PATH)
	await bleNotifInSock.bind(BLE_NOTIF_IN_SOCKET_PATH)
	return {
		bleCmdOutSock,
		bleNotifInSock,
	}
}

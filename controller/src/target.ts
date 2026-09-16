import * as zmq from "zeromq"
import { TARGET_CMD_SOCKET_PATH } from "./env"

export const createTargetSockets = async () => {
	const targetCmdOutSock = new zmq.Push()
	targetCmdOutSock.connect(TARGET_CMD_SOCKET_PATH)
	return {
		targetCmdOutSock,
	}
}

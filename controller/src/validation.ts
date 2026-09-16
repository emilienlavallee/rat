import fs from "node:fs"
import Ajv2020 from "ajv/dist/2020"
import type { AppMessage } from "./messages"

const schemasDir = new URL("../../schemas/", import.meta.url)

const appMessageSchema = JSON.parse(
	fs.readFileSync(new URL("app-message.schema.json", schemasDir), "utf8"),
)

const ajv = new Ajv2020()
// Only inbound messages need validating, and everything inbound arrives on the
// BLE socket. Target messages are outbound only.
const validateAppMessage = ajv.compile<AppMessage>(appMessageSchema)

export { validateAppMessage }

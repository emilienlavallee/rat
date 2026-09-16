import {Logger} from "tslog"
import {DEBUG_LOGGING} from "./env"

/*
 * Lives in its own module rather than in index.ts so that importing a unit
 * under test (detector, poller) does not pull in index.ts, whose top-level
 * `await main()` would start the whole controller.
 */
export const logger = new Logger({minLevel: DEBUG_LOGGING ? "DEBUG" : "INFO"})

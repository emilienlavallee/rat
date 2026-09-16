import { Effect } from "effect"
import { createBleSockets } from "./ble"
import { SocketPoller } from "./poller"
import { createTargetSockets } from "./target"
import type { AppMessage } from "./messages"
import type { TargetMessage } from "./target-messages"
import {
	getCornerPoints,
	getTargetTransform,
	getWallTransform,
	type TargetCalibrationPoint,
	type WallCalibrationPoint,
} from "./calibration"
import { Detector } from "./detector"
import { logger } from "./logger"
import {
	FRAME_GAP_TOLERANCE,
	TEN_MODE_VIRTUAL_MAX_DISTANCE,
	VIRTUAL_WALL_HEIGHT,
	VIRTUAL_WALL_LASER_MARGIN,
	VIRTUAL_WALL_LENGTH,
} from "./env"

export { logger }

type GameMode = "endless" | "ten"

async function main() {
	const { bleCmdOutSock, bleNotifInSock } = await createBleSockets()
	const { targetCmdOutSock } = await createTargetSockets()

	let currentPan = 90
	let currentTilt = 90
	const calibrationPoints: TargetCalibrationPoint[] = []
	let targetTransform: ReturnType<typeof getTargetTransform> | undefined =
		undefined
	let wallTransform: ReturnType<typeof getWallTransform> | undefined = undefined
	let wallToPixelTransform: ReturnType<typeof getWallTransform> | undefined =
		undefined

	let currentTargetVirtualX = -1
	let currentTargetVirtualY = -1
	// max distance the ball could be from the target while inside the range.
	// used for score calculation
	// multiplied by 1.5 for some slack
	const maxDelta = Math.hypot(VIRTUAL_WALL_HEIGHT, VIRTUAL_WALL_LENGTH) * 1.5
	let scores: number[] = []
	let currentHitCount = 0
	let currentAttemptCount = 0
	let currentMode: GameMode = "endless"

	const sendScore = async (score: number) => {
		const scoreMessage: AppMessage = { kind: "set-score", score }
		await bleCmdOutSock.send(JSON.stringify(scoreMessage))
	}

	const resetRun = () => {
		scores = []
		currentHitCount = 0
		currentAttemptCount = 0
	}

	const aimAt = async (point: { x: number; y: number }) => {
		if (!targetTransform) {
			logger.warn("No calibration registered, cannot aim")
			return
		}
		if (!wallToPixelTransform) {
			logger.error("No wallToPixelTransform found")
			return
		}
		const pixelTarget = wallToPixelTransform(point)
		if (!pixelTarget) {
			logger.error("There was an error getting the pixel target")
			return
		}
		const result = targetTransform(pixelTarget)
		if (!result) {
			logger.warn(
				`Point x=${point.x}, y=${point.y} has no valid servo angle, cannot aim`,
			)
			return
		}
		logger.info(
			`result of homography is pan=${result.pan}, tilt=${result.tilt}`,
		)
		const panMessage: TargetMessage = {
			kind: "set-horizontal-servo",
			angle: result.pan,
		}
		const tiltMessage: TargetMessage = {
			kind: "set-vertical-servo",
			angle: result.tilt,
		}
		await targetCmdOutSock.send(JSON.stringify(panMessage))
		await targetCmdOutSock.send(JSON.stringify(tiltMessage))
	}

	const nextTarget = async () => {
		currentTargetVirtualX =
			Math.random() * (VIRTUAL_WALL_LENGTH - VIRTUAL_WALL_LASER_MARGIN * 2) +
			VIRTUAL_WALL_LASER_MARGIN
		currentTargetVirtualY =
			Math.random() * (VIRTUAL_WALL_HEIGHT - VIRTUAL_WALL_LASER_MARGIN * 2) +
			VIRTUAL_WALL_LASER_MARGIN
		const message: AppMessage = {
			kind: "set-target-virtual-position",
			x: currentTargetVirtualX,
			y: currentTargetVirtualY,
		}
		await aimAt({ x: currentTargetVirtualX, y: currentTargetVirtualY })
		await bleCmdOutSock.send(JSON.stringify(message))
	}

	const poller = new SocketPoller([{ name: "bleNotif", sock: bleNotifInSock }])

	const detector = new Detector({
		parameters: { frameGapTolerance: FRAME_GAP_TOLERANCE },
	})

	while (true) {
		const messages = await Effect.runPromise(poller.poll(500))

		for (const msg of messages) {
			const bleMsg = msg.message
			switch (bleMsg.kind) {
				case "im-alive":
					break
				case "setup-done":
					resetRun()
					detector.reset()
					await nextTarget()
					break
				case "set-horizontal-servo":
				case "set-vertical-servo":
					if (bleMsg.kind === "set-horizontal-servo") currentPan = bleMsg.angle
					else currentTilt = bleMsg.angle
					const targetMsg: TargetMessage = bleMsg
					await targetCmdOutSock.send(JSON.stringify(targetMsg))
					logger.info(
						`Forwarded ${bleMsg.kind} angle=${bleMsg.angle} to target`,
					)
					break
				case "register-calibration":
					if (calibrationPoints.length >= 4) {
						logger.info(
							`Registering calibration with ${calibrationPoints.length} points...`,
						)
						targetTransform =
							getTargetTransform({ calibrationPoints }) ?? undefined
						const cornerPoints = getCornerPoints({
							points: calibrationPoints.map((p) => ({ x: p.x, y: p.y })),
						})
						if (!targetTransform) {
							logger.error(
								"Calibration points are degenerate, could not fit a homography",
							)
						} else if (cornerPoints === null) {
							logger.error(
								"Failed to find corner points, this is a critical calibration error",
							)
						} else {
							const wallPoints: WallCalibrationPoint[] = [
								{
									...cornerPoints.topLeft,
									u: 0,
									v: 0,
								},
								{
									...cornerPoints.topRight,
									u: VIRTUAL_WALL_LENGTH,
									v: 0,
								},
								{
									...cornerPoints.bottomLeft,
									u: 0,
									v: VIRTUAL_WALL_HEIGHT,
								},
								{
									...cornerPoints.bottomRight,
									u: VIRTUAL_WALL_LENGTH,
									v: VIRTUAL_WALL_HEIGHT,
								},
							]
							wallTransform =
								getWallTransform({ points: wallPoints }) ?? undefined
							wallToPixelTransform =
								getWallTransform({
									points: wallPoints.map((p) => ({
										x: p.u,
										y: p.v,
										u: p.x,
										v: p.y,
									})),
								}) ?? undefined
							if (!wallTransform || !wallToPixelTransform) {
								logger.error(
									"Corner points are degenerate, could not fit a wall homography",
								)
							} else {
								logger.info("Calibration registered")
								await nextTarget()
							}
						}
					} else {
						logger.warn(
							`Need at least 4 calibration points, have ${calibrationPoints.length}`,
						)
					}
					break
				case "set-calibration-point":
					const point = {
						x: bleMsg.x,
						y: bleMsg.y,
						pan: currentPan,
						tilt: currentTilt,
					}
					calibrationPoints[bleMsg.index] = point
					logger.info({ point }, "Calibration point received")
					break
				case "inference-result":
					const { x, y } = bleMsg
					logger.debug({ x, y }, "Inference result")
					const hit =
						x === -1 && y === -1
							? detector.pushFrame()
							: detector.pushFrame({ point: { x, y } })
					if (!hit) continue
					if (wallTransform === undefined) {
						logger.error(
							"Detector found a hit but no wall transform was available. This shouldn't happen. Was calibration successful?",
						)
						continue
					}
					const result = wallTransform({ x: hit.x, y: hit.y })
					if (result) {
						const message: AppMessage = {
							kind: "set-ball-virtual-position",
							x: result.x,
							y: result.y,
						}
						await bleCmdOutSock.send(JSON.stringify(message))

						const distance = Math.hypot(
							result.x - currentTargetVirtualX,
							result.y - currentTargetVirtualY,
						)

						if (currentMode === "ten") {
							if (currentHitCount >= 10) break
							currentAttemptCount += 1
							const isHit = distance <= TEN_MODE_VIRTUAL_MAX_DISTANCE
							if (isHit) currentHitCount += 1
							await sendScore(currentHitCount / currentAttemptCount)
							logger.info(
								`Ten mode: ${isHit ? "hit" : "miss"} at ${distance.toFixed(1)}cm, ${currentHitCount}/10 in ${currentAttemptCount} attempts`,
							)
							if (isHit && currentHitCount < 10) await nextTarget()
						} else {
							const score = Math.max(0, maxDelta - distance) / maxDelta
							scores.push(score)
							await sendScore(
								scores.reduce((prev, next) => prev + next) / scores.length,
							)
							await nextTarget()
						}
					}
					break
				case "next-target":
					logger.info("Next target requested")
					await nextTarget()
					break
				case "set-game-mode":
					currentMode = bleMsg.mode
					resetRun()
					detector.reset()
					logger.info(`Game mode set to ${currentMode}`)
					await nextTarget()
					break

				default:
					break
			}
		}
	}
}

await main()

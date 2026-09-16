/*
 * Class to track the ball trajectory going forward and then backward
 */

import { logger } from "./logger"
import {
	estimateQuadFromTwoPoints,
	getClosestMiddlePoint,
	getQuadReg,
} from "./calibration"

type Mode = "goingForwards" | "goingBackwards"

/**
 * Tracks the trajectory of the ball based on a sequence of inferred (x,y) coordinates
 */
export class Detector {
	private readonly frameGapTolerance: number // how many frames without detection to tolerate before dropping
	private currentMode: Mode
	private forwardPoints: { x: number; y: number }[] = []
	private backwardPoints: { x: number; y: number }[] = []
	private lastPoint: { x: number; y: number } | null = null
	private framesSinceLastPoint: number = 0

	constructor(props?: {
		parameters: {
			frameGapTolerance?: number
		}
	}) {
		this.frameGapTolerance = props?.parameters.frameGapTolerance ?? 10
		this.currentMode = "goingForwards"
		logger.debug(
			`Constructed Detector with frameGapTolerance=${this.frameGapTolerance}`,
		)
	}

	public reset() {
		this.forwardPoints = []
		this.backwardPoints = []
		this.lastPoint = null
		this.framesSinceLastPoint = 0
		this.currentMode = "goingForwards"
		logger.debug("Detector reset")
	}

	/**
	 * Tells the detector that a frame has passed.
	 *
	 * @param props.point - an optional point that was detected during CV inference. This should be passed even if no ball was detected, so that the detetor can count frames between successful detections.
	 * @returns x and y coordinates for the best guess of where the ball hit the wall, if the full loop is done. Otherwise returns null
	 *
	 */
	public pushFrame(props?: { point: { x: number; y: number } }) {
		// no frame was provided
		if (!props) {
			logger.debug("No inference result was provided")
			this.framesSinceLastPoint += 1
			if (this.framesSinceLastPoint > this.frameGapTolerance) {
				if (
					this.forwardPoints.length >= 2 &&
					this.backwardPoints.length >= 2 &&
					this.forwardPoints.length + this.backwardPoints.length >= 5 &&
					this.currentMode == "goingBackwards"
				) {
					const forwardQuad =
						this.forwardPoints.length > 2
							? getQuadReg({ points: this.forwardPoints })
							: estimateQuadFromTwoPoints({ points: this.forwardPoints })
					const backwardQuad =
						this.backwardPoints.length > 2
							? getQuadReg({ points: this.backwardPoints })
							: estimateQuadFromTwoPoints({ points: this.backwardPoints })
					const forwardStartX = this.forwardPoints.at(0)?.x
					const backwardStartX = this.backwardPoints.at(-1)?.x
					const forwardEndX = this.forwardPoints.at(-1)?.x
					const backwardEndX = this.backwardPoints.at(0)?.x
					if (
						!forwardQuad ||
						!backwardQuad ||
						forwardStartX === undefined ||
						backwardStartX === undefined ||
						forwardEndX === undefined ||
						backwardEndX === undefined
					) {
						this.reset()
						return null
					}
					const midPoint = getClosestMiddlePoint({
						reg1: forwardQuad,
						reg2: backwardQuad,
						range: {
							startX: Math.min(forwardStartX, backwardStartX),
							endX: Math.max(forwardEndX, backwardEndX),
						},
					})
					logger.info(
						`Detector found ball wall hit at x=${midPoint.x}, ${midPoint.y}`,
					)
					// Reset after reporting: without this the track stays loaded and
					// every further empty frame re-reports the same hit.
					this.reset()
					return midPoint
				}
				this.reset()
				return null
			}
			return null
		}

		this.framesSinceLastPoint = 0
		const { x, y } = props.point
		logger.debug({ x, y }, "X and Y coordinates received")
		const isFurtherRight = this.lastPoint === null || x > this.lastPoint.x
		const mode = this.currentMode
		logger.debug({ isFurtherRight, mode }, "Computed details")

		if (mode === "goingForwards") {
			if (isFurtherRight) {
				// Begin or continue going forwards
				this.forwardPoints.push({ x, y })
			} else {
				// Switch current direction
				if (this.lastPoint) this.backwardPoints.push(this.lastPoint)
				this.backwardPoints.push({ x, y })
				this.currentMode = "goingBackwards"
			}
		} else {
			if (isFurtherRight) {
				// Ball can't go forward from going backwards
				this.reset()
				return null
			}
			// Continue going backwards
			this.backwardPoints.push({ x, y })
		}
		this.lastPoint = { x, y }
		return null
	}
}

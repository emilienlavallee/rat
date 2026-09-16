import { inverse, Matrix, SingularValueDecomposition } from "ml-matrix"

export type TargetCalibrationPoint = {
	x: number
	y: number
	pan: number
	tilt: number
}

export type WallCalibrationPoint = {
	x: number
	y: number
	u: number // x on the screen
	v: number // y on the screen
}

const DEGENERACY_TOLERANCE = 1e-12

function findHomography(
	src: { x: number; y: number }[],
	dst: { u: number; v: number }[],
): Matrix | null {
	const n = src.length
	if (n < 4) return null

	const rows: number[][] = []
	for (let i = 0; i < n; i++) {
		const { x, y } = src[i]!
		const { u, v } = dst[i]!
		if (![x, y, u, v].every(Number.isFinite)) return null
		rows.push([x, y, 1, 0, 0, 0, -u * x, -u * y, -u])
		rows.push([0, 0, 0, x, y, 1, -v * x, -v * y, -v])
	}
	while (rows.length < 9) rows.push(new Array(9).fill(0))

	try {
		const svd = new SingularValueDecomposition(new Matrix(rows))
		const singularValues = svd.diagonal

		const largest = singularValues[0]!
		const smallestNonNull = singularValues[7]!
		if (!(largest > 0)) return null
		if (smallestNonNull / largest < DEGENERACY_TOLERANCE) return null

		const h = svd.rightSingularVectors.getColumn(8) as number[]
		if (h.length !== 9 || !h.every(Number.isFinite)) return null
		return Matrix.from1DArray(3, 3, h)
	} catch {
		return null
	}
}

function perspectiveTransform(H: Matrix, x: number, y: number) {
	const dst = H.mmul(new Matrix([[x], [y], [1]]))
	const w = dst.get(2, 0)
	if (!Number.isFinite(w) || w === 0) return null
	const u = dst.get(0, 0) / w
	const v = dst.get(1, 0) / w
	if (!Number.isFinite(u) || !Number.isFinite(v)) return null
	return { u, v }
}

/**
 * Builds the pixel-to-servo-angle mapping for the recorded calibration points.
 *
 * @param props.calibrationPoints - the recorded points. At least 4 are needed,
 * and they must not be degenerate (all coincident, or all on one line).
 *
 * @returns the transform, or null if no homography could be fitted. The
 * transform itself returns null for points that map outside the projection.
 */
export function getTargetTransform(props: {
	calibrationPoints: TargetCalibrationPoint[]
}) {
	const uvPoints = props.calibrationPoints.map(({ x, y, pan, tilt }) => {
		const panRad = ((pan - 90) * Math.PI) / 180
		const tiltRad = ((tilt - 90) * Math.PI) / 180
		const u = Math.tan(panRad)
		const v = Math.tan(tiltRad) / Math.cos(panRad)
		return { x, y, u, v }
	})

	const H = findHomography(
		uvPoints.map(({ x, y }) => ({ x, y })),
		uvPoints.map(({ u, v }) => ({ u, v })),
	)
	if (H === null) return null

	return (point: { x: number; y: number }) => {
		const transformed = perspectiveTransform(H, point.x, point.y)
		if (transformed === null) return null
		const { u, v } = transformed
		const panRad = Math.atan(u)
		const tiltRad = Math.atan(v * Math.cos(panRad))
		return {
			pan: Math.round((panRad * 180) / Math.PI) + 90,
			tilt: Math.round((tiltRad * 180) / Math.PI) + 90,
		}
	}
}

/**
 * Builds the wall-to-screen mapping for the recorded calibration points.
 *
 * @param props.points - the recorded points. At least 4 are needed, and they
 * must not be degenerate (all coincident, or all on one line).
 *
 * @returns the transform, or null if no homography could be fitted. The
 * transform itself returns null for points that map outside the projection.
 */
export function getWallTransform(props: { points: WallCalibrationPoint[] }) {
	const points = props.points
	const H = findHomography(
		points.map((point) => ({ x: point.x, y: point.y })),
		points.map((point) => ({ u: point.u, v: point.v })),
	)
	if (H === null) return null

	return (point: { x: number; y: number }) => {
		const transformed = perspectiveTransform(H, point.x, point.y)
		if (transformed === null) return null
		return {
			x: transformed.u,
			y: transformed.v,
		}
	}
}

/**
 * Finds a quadratic regression of the form ax^2 + bx + c = y from an array of points. Uses the least squares method.
 *
 * @param props.points - the points through which to fit the curve. Length of the array must be at least 3.
 *
 * @returns the quadratic coeffeicients a, b, c, or null if the points do not
 * determine a parabola (fewer than 3 of them, or fewer than 3 distinct x values)
 */
export function getQuadReg(props: { points: { x: number; y: number }[] }): {
	a: number
	b: number
	c: number
} | null {
	const { points } = props
	if (points.length < 3) return null
	if (!points.every((point) => Number.isFinite(point.x) && Number.isFinite(point.y))) {
		return null
	}
	if (new Set(points.map((point) => point.x)).size < 3) return null

	const Arows: number[][] = []
	const Yrows: number[][] = []

	for (const point of points) {
		const { x, y } = point
		Arows.push([x * x, x, 1])
		Yrows.push([y])
	}

	const A = new Matrix(Arows)
	const Y = new Matrix(Yrows)
	const AT = A.transpose()

	try {
		const ATAinv = inverse(AT.mmul(A))
		const result = ATAinv.mmul(AT).mmul(Y)
		const [a, b, c] = [result.get(0, 0), result.get(1, 0), result.get(2, 0)]
		if (![a, b, c].every(Number.isFinite)) return null
		return {
			a,
			b,
			c,
		}
	} catch {
		return null
	}
}

/**
 * Walking algorithm in order to prevent finding a closest point out of bounds
 *
 * @param props.reg1 - the first quadratic function
 * @param props.reg2 - the second quadratic function
 * @param props.range - the full range to walk to find the closest point. it is guaranteed that the middle point will have startX <= x <= endX
 * @param props.step - the amount by which to step to search
 * @param props.rangeSlack - how far to extend before and after the startX and endX
 *
 * @returns a point object containing x and y coordinates for the midpoint
 *
 */
export function getClosestMiddlePoint(props: {
	reg1: { a: number; b: number; c: number }
	reg2: { a: number; b: number; c: number }
	range: { startX: number; endX: number }
	step?: number
	rangeSlack?: number
}) {
	const { a: a1, b: b1, c: c1 } = props.reg1
	const { a: a2, b: b2, c: c2 } = props.reg2
	const { startX, endX } = props.range
	const step = props.step ?? 0.1
	const rangeSlack = props.rangeSlack ?? 50

	let x = startX - rangeSlack
	let bestPoint: { x: number; y: number } = { x: 0, y: 0 }
	let bestDistance = Number.POSITIVE_INFINITY
	do {
		const y1 = a1 * Math.pow(x, 2) + b1 * x + c1
		const y2 = a2 * Math.pow(x, 2) + b2 * x + c2
		const distance = Math.abs(y1 - y2)
		if (distance < bestDistance) {
			bestDistance = distance
			bestPoint = {
				x,
				y: (y1 + y2) / 2,
			}
		}
		x += step
	} while (x <= endX + rangeSlack)
	return bestPoint
}

/**
 * Takes two points, and returns a quadratic function that estimates the
 * trajectory from those two points. We do this basically all ball trajectories
 * follow a downward (a < 0) parabola, which we can get by using the midpoint of two other points.
 *
 * @param props.points - the array of points to use. meant to be used with two points, but handles other cases too.
 * @returns null if no quadratic function was found, and the coefficients a, b, or c otherwise
 */
export function estimateQuadFromTwoPoints(props: {
	points: { x: number; y: number }[]
}) {
	const points = props.points
	if (points.length < 2) {
		return null
	}
	if (points.length > 2) {
		return getQuadReg({ points })
	}

	const midPoint = {
		x: (points.at(0)!.x + points.at(1)!.x) * 0.8,
		y: (points.at(0)!.y + points.at(1)!.y) * 0.8,
	}
	return getQuadReg({ points: [...points, midPoint] })
}

/**
 *
 * Takes an array of points in image coordinates (x right, y down). Finds the
 * top-right, top-left, bottom-right and bottom-left points, if they exist
 *
 * @param props.points - the points to use
 * @return an object containing the specified corner points, null if not enough points
 *
 */
export function getCornerPoints(props: { points: { x: number; y: number }[] }) {
	const points = props.points
	if (points.length < 4) return null

	const pick = (score: (point: { x: number; y: number }) => number) => {
		let bestPoint = points[0]!
		let bestScore = score(bestPoint)
		for (const point of points) {
			const currentScore = score(point)
			if (currentScore < bestScore) {
				bestScore = currentScore
				bestPoint = point
			}
		}
		return bestPoint
	}

	return {
		topLeft: pick(({ x, y }) => x + y),
		topRight: pick(({ x, y }) => y - x),
		bottomLeft: pick(({ x, y }) => x - y),
		bottomRight: pick(({ x, y }) => -x - y),
	}
}

import {describe, expect, it} from "vitest"
import {
  estimateQuadFromTwoPoints,
  getClosestMiddlePoint,
  getCornerPoints,
  getQuadReg,
  getTargetTransform,
  getWallTransform,
  type TargetCalibrationPoint,
  type WallCalibrationPoint,
} from "./calibration"

// Calibration points use real servo angles (0–180°), matching what the sliders send.
// pan=90 / tilt=90 is center. These are plausible real-world values for a 4-corner calibration.
const CORNERS: TargetCalibrationPoint[] = [
  {x: 0, y: 0, pan: 60, tilt: 120},
  {x: 640, y: 0, pan: 120, tilt: 120},
  {x: 0, y: 480, pan: 60, tilt: 60},
  {x: 640, y: 480, pan: 120, tilt: 60},
]

// The transforms are nullable now, and so are their results. Well-conditioned
// fixtures should never hit either case, so unwrap once and fail loudly.
const targetTransformOf = (points: TargetCalibrationPoint[]) => {
  const transform = getTargetTransform({calibrationPoints: points})
  expect(transform).not.toBeNull()
  return (point: {x: number, y: number}) => {
    const result = transform!(point)
    expect(result).not.toBeNull()
    return result!
  }
}

const wallTransformOf = (points: WallCalibrationPoint[]) => {
  const transform = getWallTransform({points})
  expect(transform).not.toBeNull()
  return (point: {x: number, y: number}) => {
    const result = transform!(point)
    expect(result).not.toBeNull()
    return result!
  }
}

describe("getTargetTransform", () => {
  it("round-trips calibration points back to their recorded servo angles", async () => {
    const transform = targetTransformOf(CORNERS)
    for (const {x, y, pan, tilt} of CORNERS) {
      const result = transform({x, y})
      expect(result.pan).toBeCloseTo(pan, 0)
      expect(result.tilt).toBeCloseTo(tilt, 0)
    }
  })

  it("maps the image center to approximately the center servo angle (90°)", async () => {
    const transform = targetTransformOf(CORNERS)
    const {pan, tilt} = transform({x: 320, y: 240})
    expect(pan).toBeCloseTo(90, 0)
    expect(tilt).toBeCloseTo(90, 0)
  })

  it("outputs are always within valid servo range [0, 180] for interior points", async () => {
    const transform = targetTransformOf(CORNERS)
    const testPoints = [
      {x: 160, y: 120},
      {x: 320, y: 240},
      {x: 480, y: 360},
      {x: 100, y: 400},
      {x: 540, y: 80},
    ]
    for (const point of testPoints) {
      const {pan, tilt} = transform(point)
      expect(pan).toBeGreaterThanOrEqual(0)
      expect(pan).toBeLessThanOrEqual(180)
      expect(tilt).toBeGreaterThanOrEqual(0)
      expect(tilt).toBeLessThanOrEqual(180)
    }
  })

  it("pan increases monotonically as target moves right across the image", async () => {
    const transform = targetTransformOf(CORNERS)
    const pans = [0, 128, 256, 384, 512, 640].map(
      (x) => transform({x, y: 240}).pan,
    )
    const diffs = pans.slice(1).map((p, i) => p - pans[i]!)
    expect(diffs.every((d) => d > 0)).toBe(true)
  })

  it("tilt changes monotonically as target moves down the image", async () => {
    const transform = targetTransformOf(CORNERS)
    const tilts = [0, 96, 192, 288, 384, 480].map(
      (y) => transform({x: 320, y}).tilt,
    )
    const diffs = tilts.slice(1).map((t, i) => t - tilts[i]!)
    const allSameSign = diffs.every((d) => d < 0) || diffs.every((d) => d > 0)
    expect(allSameSign).toBe(true)
  })
})

describe("getQuadReg", () => {
  it("gets the exact quadratic equation for three points", () => {
    const result = getQuadReg({
      points: [
        {x: 1, y: 0},
        {x: 3, y: - 4},
        {x: 5, y: 0}
      ]
    })
    expect(result).toBeTruthy()
    const {a, b, c} = result!
    expect(a).toBeCloseTo(1)
    expect(b).toBeCloseTo(-6)
    expect(c).toBeCloseTo(5)
  })

  it("computes a quadratic regression accurately based on a source of truth", () => {
    // https://www.desmos.com/calculator/qsdscncgi6
    const points: {x: number, y: number}[] = [
      {x: 10, y: 0.1245},
      {x: 20, y: 0.185},
      {x: 30, y: 0.2285},
      {x: 40, y: 0.265},
      {x: 50, y: 0.297},
      {x: 60, y: 0.3285}
    ]

    const result = getQuadReg({points})
    expect(result).toBeTruthy()
    const {a, b, c} = result!
    expect(a).toBeCloseTo(-0.000038125)
    expect(b).toBeCloseTo(0.00671161)
    expect(c).toBeCloseTo(0.06025, 1)

    const points2: {x: number, y: number}[] = [
      {x: -5, y: 5},
      {x: 3, y: 2},
      {x: 5, y: 1},
      {x: 10, y: 0.2},
      {x: 14, y: -1},
    ]

    const result2 = getQuadReg({points: points2})

    expect(result).toBeTruthy()
    const {a: a2, b: b2, c: c2} = result2!
    expect(a2).toBeCloseTo(0.00687789)
    expect(b2).toBeCloseTo(-0.371088)
    expect(c2).toBeCloseTo(2.95555)
  })

  it("returns null when less than 3 points are provided", () => {
    const result = getQuadReg({
      points: [
        {x: 1, y: 5},
        {x: 4, y: 9}
      ]
    })
    expect(result).toBeNull()
  })
})

describe("estimateQuadFromTwoPoints", () => {
  it("returns null when fewer than two points are provided", () => {
    expect(estimateQuadFromTwoPoints({points: []})).toBeNull()
    expect(estimateQuadFromTwoPoints({points: [{x: 3, y: 7}]})).toBeNull()
  })

  it("delegates to plain quadratic regression once there are three or more points", () => {
    const points = [
      {x: 1, y: 0},
      {x: 3, y: -4},
      {x: 5, y: 0},
    ]
    const result = estimateQuadFromTwoPoints({points})
    expect(result).toEqual(getQuadReg({points}))

    const {a, b, c} = result!
    expect(a).toBeCloseTo(1)
    expect(b).toBeCloseTo(-6)
    expect(c).toBeCloseTo(5)
  })

  it("passes exactly through both input points", () => {
    const p1 = {x: 1, y: 2}
    const p2 = {x: 5, y: -3}
    const result = estimateQuadFromTwoPoints({points: [p1, p2]})
    expect(result).toBeTruthy()
    expect(evalQuad(result!, p1.x)).toBeCloseTo(p1.y, 6)
    expect(evalQuad(result!, p2.x)).toBeCloseTo(p2.y, 6)
  })

  it("is insensitive to the order of the two points", () => {
    const p1 = {x: -4, y: 9}
    const p2 = {x: 6, y: 1}
    const forward = estimateQuadFromTwoPoints({points: [p1, p2]})!
    const reversed = estimateQuadFromTwoPoints({points: [p2, p1]})!
    expect(forward.a).toBeCloseTo(reversed.a, 9)
    expect(forward.b).toBeCloseTo(reversed.b, 9)
    expect(forward.c).toBeCloseTo(reversed.c, 9)
  })

  it("produces real curvature, not a straight line", () => {
    const cases = [
      [{x: 1, y: 2}, {x: 5, y: -3}],
      [{x: -7, y: 4}, {x: 2, y: 4}],
      [{x: 300, y: 300}, {x: 400, y: 250}],
    ]
    for (const points of cases) {
      const {a} = estimateQuadFromTwoPoints({points})!
      expect(Math.abs(a)).toBeGreaterThan(1e-6)
    }
  })

  it("bends away from the straight line between the two points", () => {
    const p1 = {x: 300, y: 300}
    const p2 = {x: 400, y: 250}
    const quad = estimateQuadFromTwoPoints({points: [p1, p2]})!

    // Halfway along, the fit sits off the chord — the whole point of adding a
    // third point rather than fitting a line through the two.
    const slope = (p2.y - p1.y) / (p2.x - p1.x)
    const midX = (p1.x + p2.x) / 2
    const onChord = p1.y + slope * (midX - p1.x)
    expect(evalQuad(quad, midX)).not.toBeCloseTo(onChord, 3)
  })

  it("passes through the synthesised third point as well", () => {
    // With three distinct x-values the regression interpolates exactly, so the
    // constructed point is on the curve too.
    const p1 = {x: 1, y: 2}
    const p2 = {x: 5, y: -3}
    const third = {x: (p1.x + p2.x) * 0.8, y: (p1.y + p2.y) * 0.8}
    const quad = estimateQuadFromTwoPoints({points: [p1, p2]})!
    expect(evalQuad(quad, third.x)).toBeCloseTo(third.y, 6)
  })

  // Two points sharing an x give a vertical segment, which no y = f(x)
  // parabola passes through. The synthesised midpoint adds a third x, so the
  // system is solvable but the fit matches neither input — better to say so.
  it("returns null for two points with the same x", () => {
    expect(
      estimateQuadFromTwoPoints({points: [{x: 2, y: 0}, {x: 2, y: 9}]}),
    ).toBeNull()
    expect(
      estimateQuadFromTwoPoints({points: [{x: 2, y: 3}, {x: 2, y: 3}]}),
    ).toBeNull()
  })
})

describe("degenerate matrix inputs", () => {
  it("returns null instead of throwing when the quadratic is underdetermined", () => {
    // A ball tracked straight down the frame has a single x, which leaves the
    // normal equations singular — ml-matrix's LU throws on that.
    expect(
      getQuadReg({points: [{x: 2, y: 1}, {x: 2, y: 5}, {x: 2, y: 9}]}),
    ).toBeNull()
    expect(
      getQuadReg({points: [{x: 1, y: 1}, {x: 1, y: 2}, {x: 3, y: 4}]}),
    ).toBeNull()
    expect(
      getQuadReg({points: [{x: 1, y: 1}, {x: 1, y: 1}, {x: 1, y: 1}]}),
    ).toBeNull()
  })

  it("returns null for non-finite quadratic inputs", () => {
    expect(
      getQuadReg({points: [{x: NaN, y: 1}, {x: 2, y: 2}, {x: 3, y: 5}]}),
    ).toBeNull()
    expect(
      getQuadReg({points: [{x: 1, y: 0}, {x: 2, y: Infinity}, {x: 3, y: 5}]}),
    ).toBeNull()
  })

  it("returns null when there are too few points to fit a homography", () => {
    expect(getWallTransform({points: []})).toBeNull()
    expect(getWallTransform({points: WALL.slice(0, 3)})).toBeNull()
    expect(
      getTargetTransform({calibrationPoints: CORNERS.slice(0, 3)}),
    ).toBeNull()
  })

  it("returns null for degenerate homography point sets", () => {
    const collinear: WallCalibrationPoint[] = [0, 1, 2, 3].map((i) => ({
      x: i,
      y: i,
      u: i,
      v: i,
    }))
    expect(getWallTransform({points: collinear})).toBeNull()

    const identical: WallCalibrationPoint[] = Array.from({length: 4}, () => ({
      x: 1,
      y: 1,
      u: 1,
      v: 1,
    }))
    expect(getWallTransform({points: identical})).toBeNull()

    // Three of the four source points coincide, so only two constraints are
    // real. The determinant of the fitted matrix looks healthy here, which is
    // why the rank check works off the singular values instead.
    const coincident: WallCalibrationPoint[] = [
      {x: 0, y: 0, u: 0, v: 0},
      {x: 0, y: 0, u: 0, v: 0},
      {x: 5, y: 1, u: 5, v: 1},
      {x: 1, y: 5, u: 1, v: 5},
    ]
    expect(getWallTransform({points: coincident})).toBeNull()

    expect(
      getTargetTransform({
        calibrationPoints: Array.from({length: 4}, () => ({
          x: 5,
          y: 5,
          pan: 90,
          tilt: 90,
        })),
      }),
    ).toBeNull()
  })

  it("returns null for non-finite homography inputs", () => {
    expect(
      getWallTransform({
        points: [{x: NaN, y: 0, u: 0, v: 0}, ...WALL.slice(1)],
      }),
    ).toBeNull()
  })

  it("still fits well-conditioned point sets unchanged", () => {
    const transform = wallTransformOf(TRAPEZOID)
    for (const {x, y, u, v} of TRAPEZOID) {
      const result = transform({x, y})
      expect(result.x).toBeCloseTo(u, 6)
      expect(result.y).toBeCloseTo(v, 6)
    }
  })

  it("never yields non-finite coordinates, even beyond the vanishing line", () => {
    // Points on the vanishing line have w = 0 and used to divide through to
    // Infinity or NaN. Everything must now be a finite point or an explicit null.
    const transform = getWallTransform({points: TRAPEZOID})!
    for (let x = -400; x <= 400; x += 7) {
      for (let y = -400; y <= 400; y += 7) {
        const result = transform({x, y})
        if (result === null) continue
        expect(Number.isFinite(result.x)).toBe(true)
        expect(Number.isFinite(result.y)).toBe(true)
      }
    }
  })

  it("returns null rather than dividing by a zero w", () => {
    // A fit whose last row is [0, 0, 0] sends every point to w = 0.
    const flattening: WallCalibrationPoint[] = [
      {x: 0, y: 0, u: 0, v: 0},
      {x: 1, y: 0, u: 1, v: 0},
      {x: 0, y: 1, u: 0, v: 1},
      {x: 1, y: 1, u: 1, v: 1},
      {x: 2, y: 2, u: 2, v: 2},
    ]
    const transform = getWallTransform({points: flattening})
    if (transform !== null) {
      const result = transform({x: 0.5, y: 0.5})
      if (result !== null) {
        expect(Number.isFinite(result.x)).toBe(true)
        expect(Number.isFinite(result.y)).toBe(true)
      }
    }
  })
})

type Quad = {a: number, b: number, c: number}

const evalQuad = (q: Quad, x: number) => q.a * x * x + q.b * x + q.c

// |y1 - y2| is minimised at the vertex of the difference parabola
// d(x) = (a1-a2)x^2 + (b1-b2)x + (c1-c2), i.e. at x = (b2-b1) / (2*(a1-a2)).
// Only valid when a1 !== a2 and the parabolas do not intersect.
const analyticClosestX = (r1: Quad, r2: Quad) =>
  (r2.b - r1.b) / (2 * (r1.a - r2.a))

describe("getClosestMiddlePoint", () => {
  it("finds the closest approach between two non-intersecting parabolas", () => {
    // y = x^2 and y = -x^2 - 2 never meet. They are closest at x = 0, where
    // the midpoint between y1 = 0 and y2 = -2 is -1.
    const result = getClosestMiddlePoint({
      reg1: {a: 1, b: 0, c: 0},
      reg2: {a: -1, b: 0, c: -2},
      range: {startX: -2, endX: 2},
      rangeSlack: 0,
    })
    expect(result.x).toBeCloseTo(0)
    expect(result.y).toBeCloseTo(-1)
  })

  it("returns the crossing point when the curves intersect", () => {
    // y = x^2 and y = 2x - 1 touch at exactly one point: (1, 1).
    const result = getClosestMiddlePoint({
      reg1: {a: 1, b: 0, c: 0},
      reg2: {a: 0, b: 2, c: -1},
      range: {startX: -1, endX: 3},
      rangeSlack: 0,
    })
    expect(result.x).toBeCloseTo(1)
    expect(result.y).toBeCloseTo(1)
  })

  it("agrees with the closed-form vertex of the difference parabola", () => {
    const reg1: Quad = {a: 1, b: 0, c: 0}
    const reg2: Quad = {a: -1, b: 4, c: -10}
    const step = 0.01

    const result = getClosestMiddlePoint({
      reg1,
      reg2,
      range: {startX: -5, endX: 5},
      step,
      rangeSlack: 0,
    })

    const expectedX = analyticClosestX(reg1, reg2)
    expect(Math.abs(result.x - expectedX)).toBeLessThanOrEqual(step)
    expect(result.y).toBeCloseTo(
      (evalQuad(reg1, expectedX) + evalQuad(reg2, expectedX)) / 2,
      1,
    )
  })

  it("converges on the true optimum as the step shrinks", () => {
    // Closest approach sits at x = 1.25, which a step of 1 cannot land on.
    const reg1: Quad = {a: 1, b: 0, c: 0}
    const reg2: Quad = {a: -1, b: 5, c: -10}
    const range = {startX: 0, endX: 3}
    const expectedX = analyticClosestX(reg1, reg2)

    const coarse = getClosestMiddlePoint({reg1, reg2, range, step: 1, rangeSlack: 0})
    const fine = getClosestMiddlePoint({reg1, reg2, range, step: 0.01, rangeSlack: 0})

    const coarseError = Math.abs(coarse.x - expectedX)
    const fineError = Math.abs(fine.x - expectedX)

    expect(fineError).toBeLessThan(coarseError)
    expect(fineError).toBeLessThanOrEqual(0.01)
  })

  it("only searches outside the range when rangeSlack allows it", () => {
    const reg1: Quad = {a: 1, b: 0, c: 0}
    const reg2: Quad = {a: -1, b: 5, c: -10}
    const range = {startX: 5, endX: 6}
    const expectedX = analyticClosestX(reg1, reg2) // 1.25, well below the range

    const clamped = getClosestMiddlePoint({reg1, reg2, range, rangeSlack: 0})
    const widened = getClosestMiddlePoint({reg1, reg2, range, rangeSlack: 5})

    // Without slack the scan never reaches the optimum and settles on the
    // range's left edge, where the curves are closest among those sampled.
    expect(clamped.x).toBeCloseTo(5)
    expect(Math.abs(widened.x - expectedX)).toBeLessThanOrEqual(0.1)
  })
})

// A 100x50 rectangle on the wall, mapped onto a 640x480 screen.
const WALL: WallCalibrationPoint[] = [
  {x: 0, y: 0, u: 0, v: 0},
  {x: 100, y: 0, u: 640, v: 0},
  {x: 0, y: 50, u: 0, v: 480},
  {x: 100, y: 50, u: 640, v: 480},
]

// The same screen rectangle, but the wall quad is a trapezoid — what an
// off-axis view of the wall actually looks like. Only a true projective map
// can fit this; an affine one cannot.
const TRAPEZOID: WallCalibrationPoint[] = [
  {x: 20, y: 0, u: 0, v: 0},
  {x: 80, y: 0, u: 640, v: 0},
  {x: 0, y: 50, u: 0, v: 480},
  {x: 100, y: 50, u: 640, v: 480},
]

type Pt = {x: number, y: number}

// |cross product| of the two edge vectors, normalised by their lengths, so the
// result is sin(angle) and comparable against a fixed tolerance regardless of
// how far apart the points are. Zero means the three points are collinear.
const collinearity = (p1: Pt, p2: Pt, p3: Pt) => {
  const ax = p2.x - p1.x, ay = p2.y - p1.y
  const bx = p3.x - p1.x, by = p3.y - p1.y
  return Math.abs(ax * by - ay * bx) / (Math.hypot(ax, ay) * Math.hypot(bx, by))
}

describe("getWallTransform", () => {
  it("maps every calibration corner onto its recorded screen point", () => {
    const transform = wallTransformOf(WALL)
    for (const {x, y, u, v} of WALL) {
      const result = transform({x, y})
      expect(result.x).toBeCloseTo(u, 6)
      expect(result.y).toBeCloseTo(v, 6)
    }
  })

  it("maps the centre of the wall rectangle to the centre of the screen", () => {
    const transform = wallTransformOf(WALL)
    const result = transform({x: 50, y: 25})
    expect(result.x).toBeCloseTo(320, 6)
    expect(result.y).toBeCloseTo(240, 6)
  })

  it("fits a perspective-distorted quad exactly", () => {
    const transform = wallTransformOf(TRAPEZOID)
    for (const {x, y, u, v} of TRAPEZOID) {
      const result = transform({x, y})
      expect(result.x).toBeCloseTo(u, 6)
      expect(result.y).toBeCloseTo(v, 6)
    }
    // Midpoint of the trapezoid's short edge sits on the screen's top edge.
    const topMiddle = transform({x: 50, y: 0})
    expect(topMiddle.x).toBeCloseTo(320, 6)
    expect(topMiddle.y).toBeCloseTo(0, 6)
  })

  it("keeps straight lines straight", () => {
    // Collinearity is the defining invariant of a projective map: whatever the
    // perspective distortion, points on a line must stay on a line.
    const transform = wallTransformOf(TRAPEZOID)
    const from = {x: 0, y: 10}
    const to = {x: 100, y: 40}
    const imaged = [0, 0.25, 0.5, 0.75, 1].map((t) =>
      transform({
        x: from.x + t * (to.x - from.x),
        y: from.y + t * (to.y - from.y),
      }),
    )
    for (let i = 2; i < imaged.length; i++) {
      expect(collinearity(imaged[0]!, imaged[1]!, imaged[i]!)).toBeLessThan(1e-9)
    }
  })

  it("preserves the cross-ratio of four collinear points", () => {
    // Distances and midpoints are not preserved under perspective, but the
    // cross-ratio is — it is *the* projective invariant, so this pins the
    // behaviour down without hardcoding any expected coordinates.
    const transform = wallTransformOf(TRAPEZOID)
    const from = {x: 5, y: 5}
    const to = {x: 95, y: 45}

    const source = [0.1, 0.35, 0.6, 0.9].map((t) => ({
      x: from.x + t * (to.x - from.x),
      y: from.y + t * (to.y - from.y),
    }))
    const imaged = source.map(transform)

    // Signed position along the line, measured from the first point. Any
    // affine parameterisation works, since the cross-ratio ignores rescaling.
    const along = (pts: Pt[]) => {
      const dx = pts[3]!.x - pts[0]!.x
      const dy = pts[3]!.y - pts[0]!.y
      const len = Math.hypot(dx, dy)
      return pts.map(
        (p) => ((p.x - pts[0]!.x) * dx + (p.y - pts[0]!.y) * dy) / len,
      )
    }
    const crossRatio = ([p1, p2, p3, p4]: number[]) =>
      ((p3! - p1!) * (p4! - p2!)) / ((p3! - p2!) * (p4! - p1!))

    expect(crossRatio(along(imaged))).toBeCloseTo(crossRatio(along(source)), 6)
  })

  it("inverts when the correspondences are swapped", () => {
    const forward = wallTransformOf(TRAPEZOID)
    const backward = wallTransformOf(
      TRAPEZOID.map(({x, y, u, v}) => ({x: u, y: v, u: x, v: y})),
    )
    for (const point of [{x: 50, y: 25}, {x: 10, y: 45}, {x: 90, y: 5}]) {
      const roundTripped = backward(forward(point))
      expect(roundTripped.x).toBeCloseTo(point.x, 6)
      expect(roundTripped.y).toBeCloseTo(point.y, 6)
    }
  })
})

// Every ordering of a 4-point set, so order-dependence shows up as a failure.
const permutations = <T,>(items: T[]): T[][] =>
  items.length <= 1
    ? [items]
    : items.flatMap((item, i) =>
        permutations([...items.slice(0, i), ...items.slice(i + 1)]).map(
          (rest) => [item, ...rest],
        ),
      )

const samePoint = (a: Pt, b: Pt) => a.x === b.x && a.y === b.y

describe("getCornerPoints", () => {
  const SQUARE: Pt[] = [
    {x: 0, y: 0},
    {x: 10, y: 0},
    {x: 0, y: 10},
    {x: 10, y: 10},
  ]

  it("returns null when there are fewer than four points", () => {
    expect(getCornerPoints({points: []})).toBeNull()
    expect(getCornerPoints({points: [{x: 1, y: 1}]})).toBeNull()
    expect(getCornerPoints({points: SQUARE.slice(0, 3)})).toBeNull()
    expect(getCornerPoints({points: SQUARE})).not.toBeNull()
  })

  it("labels the four corners of an axis-aligned square", () => {
    const corners = getCornerPoints({points: SQUARE})!
    expect(corners.topLeft).toEqual({x: 0, y: 0})
    expect(corners.topRight).toEqual({x: 10, y: 0})
    expect(corners.bottomLeft).toEqual({x: 0, y: 10})
    expect(corners.bottomRight).toEqual({x: 10, y: 10})
  })

  it("gives the same answer for every input ordering", () => {
    const expected = getCornerPoints({points: SQUARE})!
    for (const points of permutations(SQUARE)) {
      expect(getCornerPoints({points})).toEqual(expected)
    }
  })

  it("returns points taken from the input, never synthesised ones", () => {
    const points: Pt[] = [
      {x: 100, y: 100},
      {x: 200, y: 100},
      {x: 100, y: 200},
      {x: 200, y: 200},
      {x: 150, y: 150},
    ]
    const corners = getCornerPoints({points})!
    for (const corner of Object.values(corners)) {
      expect(points.some((p) => samePoint(p, corner))).toBe(true)
    }
  })

  it("ignores points in the interior of the hull", () => {
    const withInterior = [...SQUARE, {x: 5, y: 5}, {x: 3, y: 7}, {x: 8, y: 2}]
    expect(getCornerPoints({points: withInterior})).toEqual(
      getCornerPoints({points: SQUARE}),
    )
  })

  it("handles a trapezoid whose top edge is narrower than its bottom", () => {
    const corners = getCornerPoints({
      points: [
        {x: 2, y: 0},
        {x: 8, y: 0},
        {x: 0, y: 10},
        {x: 10, y: 10},
      ],
    })!
    expect(corners.topLeft).toEqual({x: 2, y: 0})
    expect(corners.topRight).toEqual({x: 8, y: 0})
    expect(corners.bottomLeft).toEqual({x: 0, y: 10})
    expect(corners.bottomRight).toEqual({x: 10, y: 10})
  })

  it("collapses to the single point when every point is identical", () => {
    const corners = getCornerPoints({
      points: Array.from({length: 4}, () => ({x: 1, y: 1})),
    })!
    for (const corner of Object.values(corners)) {
      expect(corner).toEqual({x: 1, y: 1})
    }
  })

  it("assigns four distinct corners to a rotated quad", () => {
    const corners = getCornerPoints({
      points: [
        {x: 1, y: 0},
        {x: 9, y: 2},
        {x: 8, y: 10},
        {x: 0, y: 8},
      ],
    })!
    expect(corners.topLeft).toEqual({x: 1, y: 0})
    expect(corners.topRight).toEqual({x: 9, y: 2})
    expect(corners.bottomLeft).toEqual({x: 0, y: 8})
    expect(corners.bottomRight).toEqual({x: 8, y: 10})
  })

  it("keeps the corners consistently oriented for a perspective-skewed quad", () => {
    const corners = getCornerPoints({
      points: [
        {x: 120, y: 40},
        {x: 500, y: 90},
        {x: 60, y: 400},
        {x: 560, y: 330},
      ],
    })!
    expect(corners.topLeft).toEqual({x: 120, y: 40})
    expect(corners.topRight).toEqual({x: 500, y: 90})
    expect(corners.bottomLeft).toEqual({x: 60, y: 400})
    expect(corners.bottomRight).toEqual({x: 560, y: 330})

    expect(corners.topLeft.y).toBeLessThan(corners.bottomLeft.y)
    expect(corners.topRight.y).toBeLessThan(corners.bottomRight.y)
    expect(corners.topLeft.x).toBeLessThan(corners.topRight.x)
    expect(corners.bottomLeft.x).toBeLessThan(corners.bottomRight.x)
  })
})












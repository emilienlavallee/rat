import {describe, expect, it} from "vitest"
import {Detector} from "./detector"

/*
 * The ball flies out (x increasing) along one parabola, hits the wall, and
 * comes back (x decreasing) along another. The detector should accumulate both
 * arcs and, once detections stop for longer than frameGapTolerance, report the
 * x where the two arcs are closest together — the wall hit.
 *
 * The two arcs are chosen to meet exactly at the turnaround (x=300, y=200),
 * which is both physically right and necessary for the fit: on the direction
 * switch the detector copies the last forward point into backwardPoints, so
 * that point has to sit on the backward curve too or it skews the regression.
 * They cross transversally there (not tangentially), so the minimum-distance
 * search is well conditioned.
 */

type Point = {x: number, y: number}

const TURNAROUND = {x: 300, y: 200}

const forwardArc = (x: number) => 0.01 * x * x - 4 * x + 500
const backwardArc = (x: number) => 0.005 * x * x - 2.5 * x + 500

const arcPoints = (arc: (x: number) => number, xs: number[]): Point[] =>
  xs.map((x) => ({x, y: arc(x)}))

// Out along the forward arc, ending exactly at the turnaround.
const FORWARD = arcPoints(forwardArc, [100, 150, 200, 250, 300])
// Back along the backward arc, starting just inside the turnaround.
const BACKWARD = arcPoints(backwardArc, [290, 240, 190, 140, 90])

function pushAll(detector: Detector, points: Point[]) {
  return points.map((point) => detector.pushFrame({point}))
}

describe("Detector", () => {
  it("accumulates a forward-then-backward track and reports the wall hit", () => {
    const detector = new Detector()

    for (const result of pushAll(detector, [...FORWARD, ...BACKWARD])) {
      expect(result).toBeNull()
    }

    // First empty frame is within tolerance (default 1); the second exceeds it.
    expect(detector.pushFrame()).toBeNull()
    const hit = detector.pushFrame()

    expect(hit).not.toBeNull()
    expect(hit!.x).toBeCloseTo(TURNAROUND.x, 0)
    expect(hit!.y).toBeCloseTo(TURNAROUND.y, 0)
  })

  it("returns null while the track is still being built", () => {
    const detector = new Detector()
    for (const result of pushAll(detector, FORWARD)) {
      expect(result).toBeNull()
    }
  })

  it("reports the hit only once, not on every subsequent empty frame", () => {
    const detector = new Detector()
    pushAll(detector, [...FORWARD, ...BACKWARD])

    detector.pushFrame()
    expect(detector.pushFrame()).not.toBeNull()
    // Track was consumed, so further empty frames must stay quiet.
    expect(detector.pushFrame()).toBeNull()
    expect(detector.pushFrame()).toBeNull()
  })

  it("does not report a hit when the ball only ever goes forwards", () => {
    const detector = new Detector()
    pushAll(detector, FORWARD)
    detector.pushFrame()
    // Still goingForwards, so there is no backward arc to intersect.
    expect(detector.pushFrame()).toBeNull()
  })

  it("does not report a hit when either arc has fewer than 3 points", () => {
    const detector = new Detector()
    pushAll(detector, [
      {x: 100, y: forwardArc(100)},
      {x: 300, y: forwardArc(300)},
      {x: 290, y: backwardArc(290)},
    ])
    detector.pushFrame()
    expect(detector.pushFrame()).toBeNull()
  })

  it("resets and recovers when the ball moves forward again while going backwards", () => {
    const detector = new Detector()

    detector.pushFrame({point: {x: 100, y: 10}})
    detector.pushFrame({point: {x: 200, y: 20}})
    detector.pushFrame({point: {x: 150, y: 30}}) // switches to goingBackwards
    // Forward again from goingBackwards is impossible for a real ball -> reset.
    expect(detector.pushFrame({point: {x: 250, y: 40}})).toBeNull()

    // reset() must restore currentMode, otherwise the detector is wedged in
    // goingBackwards forever and can never accumulate another track.
    pushAll(detector, [...FORWARD, ...BACKWARD])
    detector.pushFrame()
    const hit = detector.pushFrame()

    expect(hit).not.toBeNull()
    expect(hit!.x).toBeCloseTo(TURNAROUND.x, 0)
  })

  it("tolerates a detection gap up to frameGapTolerance without dropping the track", () => {
    const detector = new Detector({parameters: {frameGapTolerance: 3}})

    pushAll(detector, FORWARD)
    // Three missed frames is within tolerance, so the track survives...
    expect(detector.pushFrame()).toBeNull()
    expect(detector.pushFrame()).toBeNull()
    expect(detector.pushFrame()).toBeNull()

    pushAll(detector, BACKWARD)
    // ...and the hit lands on the frame that finally exceeds tolerance.
    expect(detector.pushFrame()).toBeNull()
    expect(detector.pushFrame()).toBeNull()
    expect(detector.pushFrame()).toBeNull()
    const hit = detector.pushFrame()

    expect(hit).not.toBeNull()
    expect(hit!.x).toBeCloseTo(TURNAROUND.x, 0)
  })

  it("handles a detection at x = 0 without discarding the track", () => {
    const detector = new Detector()

    // Meets forwardArc at the turnaround like backwardArc does, but its other
    // crossing sits at x=-200, outside the search range. backwardArc's other
    // crossing is at x=0, which would win here and mask what this test checks.
    const steepBackwardArc = (x: number) => 0.006 * x * x - 3.6 * x + 740

    // The leftmost point sits exactly at the frame edge. A falsy guard on
    // forwardStartX would treat 0 as missing and reset instead of reporting.
    pushAll(detector, [
      ...arcPoints(forwardArc, [0, 75, 150, 225, 300]),
      ...arcPoints(steepBackwardArc, [290, 215, 140, 65, 0]),
    ])
    detector.pushFrame()

    const hit = detector.pushFrame()
    expect(hit).not.toBeNull()
    expect(hit!.x).toBeCloseTo(TURNAROUND.x, 0)
  })
})

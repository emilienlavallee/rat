package sh.emilien.RATapp.ble

/**
 * A point on the virtual wall, in centimetres: x in 0..300, y in 0..200, origin
 * top-left. Pushed by the controller during a run so the phone can draw where the
 * target is aiming and where the ball is.
 */
data class VirtualPosition(val kind: String, val x: Float, val y: Float)

const val KIND_TARGET_VIRTUAL_POSITION = "set-target-virtual-position"
const val KIND_BALL_VIRTUAL_POSITION = "set-ball-virtual-position"

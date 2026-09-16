package sh.emilien.RATapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

private val WALL_BACKGROUND = Color(0xFF10151C)
private val WALL_GRID = Color(0x1AFFFFFF)
private val TARGET_COLOR = Color(0xFFFFC107)
private val BALL_COLOR = Color(0xFF4CAF50)

/** Gridline spacing, in virtual centimetres. */
private const val GRID_STEP_CM = 50f

/**
 * The wall as the controller sees it: a 3:2 rectangle standing in for the real
 * 300cm x 200cm surface, with the current target and the ball drawn on it.
 *
 * Both points arrive from the controller in centimetres with the origin at the
 * top-left, so mapping to the box is a straight proportional scale.
 */
@Composable
fun VirtualWall(
    target: VirtualPoint?,
    ball: VirtualPoint?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(VIRTUAL_WALL_WIDTH_CM / VIRTUAL_WALL_HEIGHT_CM)
            .clip(MaterialTheme.shapes.medium)
            .background(WALL_BACKGROUND)
            .border(
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = MaterialTheme.shapes.medium,
            ),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            fun toCanvas(point: VirtualPoint) = Offset(
                x = point.x / VIRTUAL_WALL_WIDTH_CM * size.width,
                y = point.y / VIRTUAL_WALL_HEIGHT_CM * size.height,
            )

            var cm = GRID_STEP_CM
            while (cm < VIRTUAL_WALL_WIDTH_CM) {
                val x = cm / VIRTUAL_WALL_WIDTH_CM * size.width
                drawLine(WALL_GRID, Offset(x, 0f), Offset(x, size.height))
                cm += GRID_STEP_CM
            }
            cm = GRID_STEP_CM
            while (cm < VIRTUAL_WALL_HEIGHT_CM) {
                val y = cm / VIRTUAL_WALL_HEIGHT_CM * size.height
                drawLine(WALL_GRID, Offset(0f, y), Offset(size.width, y))
                cm += GRID_STEP_CM
            }

            if (target != null) {
                val center = toCanvas(target)
                val outer = size.minDimension * 0.06f
                drawCircle(TARGET_COLOR, radius = outer, center = center, style = Stroke(3f))
                drawCircle(TARGET_COLOR, radius = outer / 2f, center = center, style = Stroke(3f))
                drawLine(
                    TARGET_COLOR,
                    Offset(center.x - outer * 1.4f, center.y),
                    Offset(center.x + outer * 1.4f, center.y),
                    strokeWidth = 2f,
                )
                drawLine(
                    TARGET_COLOR,
                    Offset(center.x, center.y - outer * 1.4f),
                    Offset(center.x, center.y + outer * 1.4f),
                    strokeWidth = 2f,
                )
            }

            if (ball != null) {
                val center = toCanvas(ball)
                val radius = size.minDimension * 0.025f
                drawCircle(BALL_COLOR, radius = radius, center = center)
                drawCircle(Color.White, radius = radius, center = center, style = Stroke(2f))
            }
        }
    }
}

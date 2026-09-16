package sh.emilien.RATapp.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMessageTest {

    @Test
    fun outbound_messages_encode_bare_with_a_kind_discriminator() {
        assertEquals("""{"kind":"im-alive"}""", encodeAppMessage(AppMessage.ImAlive))
        assertEquals("""{"kind":"setup-done"}""", encodeAppMessage(AppMessage.SetupDone))
        assertEquals("""{"kind":"next-target"}""", encodeAppMessage(AppMessage.NextTarget))
        assertEquals(
            """{"kind":"set-horizontal-servo","angle":90}""",
            encodeAppMessage(AppMessage.SetHorizontalServo(90)),
        )
        assertEquals(
            """{"kind":"set-calibration-point","index":2,"x":0.25,"y":0.5}""",
            encodeAppMessage(AppMessage.SetCalibrationPoint(2, 0.25f, 0.5f)),
        )
        assertEquals(
            """{"kind":"inference-result","x":320.5,"y":240.5}""",
            encodeAppMessage(AppMessage.InferenceResult(320.5, 240.5)),
        )
        assertEquals(
            """{"kind":"set-game-mode","mode":"endless"}""",
            encodeAppMessage(AppMessage.SetGameMode(GameMode.ENDLESS)),
        )
        assertEquals(
            """{"kind":"set-game-mode","mode":"ten"}""",
            encodeAppMessage(AppMessage.SetGameMode(GameMode.TEN)),
        )
    }

    @Test
    fun inbound_messages_decode() {
        assertEquals(
            AppMessage.SetTargetVirtualPosition(150.5f, 100.25f),
            decodeAppMessage("""{"kind":"set-target-virtual-position","x":150.5,"y":100.25}"""),
        )
        assertEquals(
            AppMessage.SetBallVirtualPosition(0f, 200f),
            decodeAppMessage("""{"kind":"set-ball-virtual-position","x":0,"y":200}"""),
        )
        assertEquals(
            AppMessage.SetScore(12.5f),
            decodeAppMessage("""{"kind":"set-score","score":12.5}"""),
        )
    }

    @Test
    fun unknown_kinds_and_garbage_decode_to_null() {
        assertNull(decodeAppMessage("""{"kind":"image","id":"a","data":"xx"}"""))
        assertNull(decodeAppMessage("not json"))
        assertNull(decodeAppMessage("{}"))
    }

    /** The envelope is gone from the wire; a stale sender must not be half-parsed. */
    @Test
    fun the_old_envelope_form_is_rejected() {
        assertNull(decodeAppMessage("""{"type":"bluetooth","data":{"message":"im_alive"}}"""))
        assertNull(
            decodeAppMessage(
                """{"type":"bluetooth","data":{"message":{"kind":"set-score","score":1}}}""",
            ),
        )
    }

    /**
     * The write budget is the reason coordinates are rounded to one decimal, so it is
     * asserted rather than assumed. Worst case is a 4-digit coordinate on both axes.
     */
    @Test
    fun an_inference_result_stays_inside_the_att_write_budget() {
        val worstCase = encodeAppMessage(
            AppMessage.InferenceResult((-1234.5f).round1(), 1234.5f.round1()),
        )
        assertTrue(
            "encoded to ${worstCase.length}B: $worstCase",
            worstCase.toByteArray(Charsets.UTF_8).size <= 64,
        )
    }

    @Test
    fun round1_keeps_one_decimal() {
        assertEquals(320.5, 320.46f.round1(), 0.0)
        assertEquals(-1.0, (-1f).round1(), 0.0)
        assertEquals(0.0, 0f.round1(), 0.0)
    }
}

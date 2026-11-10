package dev.stance.deck

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import dev.stance.deck.core.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Screenshots of every screen state, written to build/outputs/roborazzi. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel6, sdk = [34])
class ScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val k1 = B64Url.encode(ByteArray(32) { 3 })
    private val k2 = B64Url.encode(ByteArray(32) { 9 })
    private val pcs = listOf(
        PcRecord(k1, "H0dZ desktop", listOf("192.168.1.26", "10.0.0.5"), 47800, ""),
        PcRecord(k2, "Laptop", listOf("127.0.0.1"), 47800, ""),
    )
    private val buttons = listOf(
        Button("night", "Night light", "nightlight", true),
        Button("work", "Mode: work", "work", null),
        Button("tv", "Mode: tv", "tv", null),
        Button("b50", "Brightness 50 % on a long monitor name", "brightness_medium", null),
        Button("keep", "Keep", "check", null),
        Button("revert", "Revert", "undo", null),
    )
    private val hello = ServerMsg.Hello(1, "H0dZ desktop", listOf("192.168.1.26"), 47800, buttons, DeckState(3, "work", null, 3500))

    private fun shot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent { StanceTheme { androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) { content() } } }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun now() = System.nanoTime() / 1_000_000

    @Test fun home_empty() = shot("home-empty") { HomeContent(emptyList(), {}, {}, {}) }
    @Test fun home_two() = shot("home-two") { HomeContent(pcs, {}, {}, {}) }

    @Test fun pair_scan() = shot("pair-camera-denied") {
        PairContent(false, null, null, {}, {}, {}, {}, {}) { }
    }

    @Test fun pair_confirm() = shot("pair-confirm") {
        val qr = QrPayload(listOf("192.168.1.26"), 47800, ByteArray(32) { 3 }, ByteArray(32), "H0dZ desktop")
        PairContent(false, PairRequest(qr), null, {}, {}, {}, {}, {}) { }
    }

    @Test fun pair_error() = shot("pair-error") {
        val qr = QrPayload(listOf("192.168.1.26"), 47800, ByteArray(32) { 3 }, ByteArray(32), "H0dZ desktop")
        PairContent(false, PairRequest(qr, false, "The PC refused: the code was already used or has expired."), null, {}, {}, {}, {}, {}) { }
    }

    @Test fun pc_buttons() = shot("pc-buttons") {
        PcContent("H0dZ desktop", DeckView(Link.Connected, hello, hello.state, now()), SnackbarHostState(), {}, {}, {}, {}, {}, {})
    }

    @Test fun pc_pending() = shot("pc-pending") {
        val st = DeckState(4, "work", Pending("2560x1440@180 on DP-1", 9000), 3500)
        PcContent("H0dZ desktop", DeckView(Link.Connected, hello, st, now(), setOf("work")), SnackbarHostState(), {}, {}, {}, {}, {}, {})
    }

    @Test fun pc_waiting() = shot("pc-waiting") {
        PcContent("H0dZ desktop", DeckView(Link.Waiting("cannot connect", 4), hello, hello.state, now()), SnackbarHostState(), {}, {}, {}, {}, {}, {})
    }

    @Test fun pc_unpaired() = shot("pc-unpaired") {
        PcContent("H0dZ desktop", DeckView(Link.Refused, hello, hello.state, now()), SnackbarHostState(), {}, {}, {}, {}, {}, {})
    }

    @Test fun pc_connecting() = shot("pc-connecting") {
        PcContent("H0dZ desktop", DeckView(Link.Connecting(1)), SnackbarHostState(), {}, {}, {}, {}, {}, {})
    }
}

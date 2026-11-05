package dev.patchbay.ui.screens

import androidx.compose.ui.graphics.Color
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.ControlPath
import dev.patchbay.core.model.Device
import dev.patchbay.core.model.DeviceKind
import dev.patchbay.core.model.DeviceSnapshot
import dev.patchbay.core.model.Home
import dev.patchbay.core.model.PlaybackStatus
import dev.patchbay.core.model.PrintStage
import dev.patchbay.core.model.Room
import dev.patchbay.ui.designsystem.Fmt
import dev.patchbay.ui.designsystem.GlyphState
import dev.patchbay.ui.designsystem.PatchbayColors
import kotlin.math.ln
import kotlin.math.pow

/** Pure mapping from domain state to what the UI shows. No vendor names are consulted. */
internal object DeviceViews {

    fun glyphState(d: Device, s: DeviceSnapshot?): GlyphState {
        if (!d.controllable) return GlyphState.UNMANAGED
        val power = s?.state<CapabilityState.PowerState>()
        val media = s?.state<CapabilityState.MediaState>()
        val job = s?.state<CapabilityState.PrintJobState>()
        return when {
            power != null -> if (power.on) GlyphState.ON else GlyphState.OFF
            job != null -> if (job.stage == PrintStage.PRINTING || job.stage == PrintStage.PREPARING) GlyphState.ON else GlyphState.OFF
            media != null -> if (media.status == PlaybackStatus.PLAYING) GlyphState.ON else GlyphState.OFF
            else -> GlyphState.PASSIVE
        }
    }

    /** The light's real output colour (RGB, or the CCT converted to sRGB). Null when not emitting. */
    fun emittedColor(d: Device, s: DeviceSnapshot?, colors: PatchbayColors): Color? {
        if (d.kind != DeviceKind.LIGHT || s == null) return null
        if (s.state<CapabilityState.PowerState>()?.on != true) return null
        val rgb = s.state<CapabilityState.RgbState>()?.colour
        val base = when {
            rgb != null -> Color(rgb.r, rgb.g, rgb.b)
            else -> s.state<CapabilityState.ColourTemperatureState>()?.let { kelvinToColor(it.kelvin.value) } ?: return null
        }
        // Night theme dims real colours too, so the screen stays dark in a bedroom.
        return if (colors.name == "night") Color(base.red * 0.55f, base.green * 0.5f, base.blue * 0.4f) else base
    }

    /** Tanner Helland's black-body approximation, good to a few percent over 1000–40000 K. */
    fun kelvinToColor(kelvin: Int): Color {
        val t = kelvin / 100.0
        val r = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
        val g = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661 else 288.1221695283 * (t - 60).pow(-0.0755148492)
        val b = when {
            t >= 66 -> 255.0
            t <= 19 -> 0.0
            else -> 138.5177312231 * ln(t - 10) - 305.0447927307
        }
        fun ch(v: Double) = v.coerceIn(0.0, 255.0).toFloat() / 255f
        return Color(ch(r), ch(g), ch(b))
    }

    data class Exposure(val local: Int, val afterSetup: Int, val cloudOnly: Int, val unknown: Int)

    fun exposure(home: Home): Exposure = Exposure(
        local = home.devices.count { it.controlPath == ControlPath.LOCAL },
        afterSetup = home.devices.count { it.controlPath == ControlPath.LOCAL_AFTER_SETUP },
        cloudOnly = home.devices.count { it.controlPath == ControlPath.CLOUD_ONLY },
        unknown = home.devices.count { it.controlPath == ControlPath.UNKNOWN },
    )

    /** The one value worth showing on the plan for a room, if any (temperature first, then print progress). */
    fun roomHeadline(home: Home, room: Room): String? {
        val devices = home.devicesIn(room.id)
        devices.firstNotNullOfOrNull { home.snapshot(it.id)?.state<CapabilityState.PrintJobState>() }
            ?.takeIf { it.stage == PrintStage.PRINTING }
            ?.let { return "${Fmt.percent(it.progress.value)} %" }
        devices.firstNotNullOfOrNull { home.snapshot(it.id)?.state<CapabilityState.TemperatureReading>() }
            ?.let { return "${Fmt.celsius(it.temperature.value)} °C" }
        return null
    }

    fun activeOutputs(home: Home, room: Room): Int = home.devicesIn(room.id).count { glyphState(it, home.snapshot(it.id)) == GlyphState.ON }

    fun controlPathLabel(p: ControlPath): String = when (p) {
        ControlPath.LOCAL -> "Local"
        ControlPath.LOCAL_AFTER_SETUP -> "Local (after setup)"
        ControlPath.CLOUD_ONLY -> "Cloud-only · untouched"
        ControlPath.UNKNOWN -> "Not identified"
    }
}

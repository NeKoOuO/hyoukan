// Runs the REAL provider gate (compiled straight from
// app/.../viewer/webgpu/WebGpuOffer.kt and WebGpuHealthPolicy.kt).
//
// This is the decision behind "the WebGPU reader keeps falling back": which gate stopped the
// renderer, and in what order. The order is load-bearing - the switch outranks the health latch,
// the latch outranks the renderer probe, and the probe must not run at all when either of the first
// two has already decided, because reading `WebGpuRenderer.isAvailable` initialises the GPU device.

import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuHealthPolicy
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuOffer
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.webGpuOfferFor

private var failures = 0

private fun check(label: String, cond: Boolean) {
    if (!cond) {
        failures++
        println("FAIL: $label")
    } else {
        println("ok: $label")
    }
}

private var probed = false

private fun offer(
    wantsHighQuality: Boolean = true,
    strikes: Int = 0,
    available: Boolean = true,
): WebGpuOffer = webGpuOfferFor(wantsHighQuality, strikes) {
    probed = true
    available
}

fun main() {
    // --- the happy path ----------------------------------------------------------------------
    probed = false
    check("a wanted, healthy, available renderer is offered", offer() == WebGpuOffer.OFFERED)
    check("the offered path probes the renderer", probed)

    // --- the switch ------------------------------------------------------------------------
    probed = false
    check("the switch being off declines", offer(wantsHighQuality = false) == WebGpuOffer.SWITCH_OFF)
    check("the switch-off path does not probe the renderer", !probed)
    check(
        "the switch outranks a disabled health record",
        offer(wantsHighQuality = false, strikes = 99, available = false) == WebGpuOffer.SWITCH_OFF,
    )

    // --- the health latch ------------------------------------------------------------------
    probed = false
    check(
        "two strikes decline",
        offer(strikes = WebGpuHealthPolicy.DISABLE_STRIKES) == WebGpuOffer.HEALTH_DISABLED,
    )
    check("the health decline does not probe the renderer", !probed)
    check(
        "one strike below the threshold still reaches the probe",
        offer(strikes = WebGpuHealthPolicy.DISABLE_STRIKES - 1) == WebGpuOffer.OFFERED,
    )
    check(
        "the health verdict outranks an unavailable renderer",
        offer(strikes = 2, available = false) == WebGpuOffer.HEALTH_DISABLED,
    )

    // --- the renderer probe ----------------------------------------------------------------
    probed = false
    check("an unavailable renderer declines", offer(available = false) == WebGpuOffer.RENDERER_UNAVAILABLE)
    check("the unavailable path probes the renderer", probed)

    // --- defensive -------------------------------------------------------------------------
    check("a negative strike count is treated as none", offer(strikes = -1) == WebGpuOffer.OFFERED)
    check(
        "a very large strike count is still just disabled",
        offer(strikes = Int.MAX_VALUE) == WebGpuOffer.HEALTH_DISABLED,
    )

    if (failures == 0) {
        println("ALL PASS")
    } else {
        println("$failures FAILURES")
        kotlin.system.exitProcess(1)
    }
}

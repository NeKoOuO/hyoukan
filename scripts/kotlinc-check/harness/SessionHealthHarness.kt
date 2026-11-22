// Runs the REAL session-health decision (compiled straight from
// app/.../viewer/webgpu/WebGpuSessionHealth.kt and WebGpuHealthPolicy.kt).
//
// This is the rule that decides whether a closed reader session records a strike against the WebGPU
// renderer, and it is where the "reader silently falls back to the legacy viewer on every device"
// report came from: the black-screen test used to read the page the reader was on at exit, and a
// chapter transition is not a ViewerReaderPage - so a chapter finished before closing counted as a
// dead display. Two of those disabled the renderer for good.
//
// The file is pure (no Android import), which is why this harness can link the real decision rather
// than a copy. It also pins the precedence and the two inclusive thresholds.

import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.SessionFacts
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.SessionVerdict
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuHealthPolicy
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.sessionVerdict

private var failures = 0

private fun check(label: String, cond: Boolean) {
    if (!cond) {
        failures++
        println("FAIL: $label")
    } else {
        println("ok: $label")
    }
}

private fun facts(
    deadAtExit: Boolean = false,
    surfaceParks: Int = 0,
    decodedPages: Int = 10,
    durationMs: Long = 60_000L,
    anyPageDrawn: Boolean = true,
    presentedFrames: Int = 600,
    deviceLosses: Int = 0,
) = SessionFacts(
    deadAtExit = deadAtExit,
    surfaceParks = surfaceParks,
    decodedPages = decodedPages,
    durationMs = durationMs,
    anyPageDrawn = anyPageDrawn,
    presentedFrames = presentedFrames,
    deviceLosses = deviceLosses,
)

fun main() {
    // --- the regression: a chapter finished before closing is a healthy session ---------------
    check(
        "a session ending on a chapter transition is healthy",
        sessionVerdict(facts(anyPageDrawn = true)) == SessionVerdict.HEALTHY,
    )
    check(
        "a long healthy session is healthy",
        sessionVerdict(facts(decodedPages = 100, durationMs = 3_600_000L)) == SessionVerdict.HEALTHY,
    )

    // --- the black-screen shape ---------------------------------------------------------------
    check(
        "pages decoded and never drawn is the black screen",
        sessionVerdict(facts(anyPageDrawn = false)) == SessionVerdict.NEVER_DREW,
    )
    // The 2026-11-22 shape: the walk reported a page on screen, the renderer never presented a
    // frame. `anyPageDrawn` alone read that as healthy and struck nothing.
    check(
        "a page in the drawn set with zero presented frames is the black screen",
        sessionVerdict(facts(anyPageDrawn = true, presentedFrames = 0)) == SessionVerdict.NEVER_DREW,
    )
    check(
        "frames presented with no page drawn is still the black screen",
        sessionVerdict(facts(anyPageDrawn = false, presentedFrames = 600)) == SessionVerdict.NEVER_DREW,
    )
    check(
        "one decoded page is not enough to judge",
        sessionVerdict(facts(decodedPages = 1, anyPageDrawn = false)) == SessionVerdict.HEALTHY,
    )
    check(
        "no decoded pages is not enough to judge",
        sessionVerdict(facts(decodedPages = 0, anyPageDrawn = false)) == SessionVerdict.HEALTHY,
    )
    check(
        "under five seconds is not enough to judge",
        sessionVerdict(facts(durationMs = 4_999L, anyPageDrawn = false)) == SessionVerdict.HEALTHY,
    )
    check(
        "the thresholds are inclusive",
        sessionVerdict(facts(decodedPages = 2, durationMs = 5_000L, anyPageDrawn = false)) ==
            SessionVerdict.NEVER_DREW,
    )

    // --- the stronger signals -----------------------------------------------------------------
    check(
        "a dead renderer at a deliberate exit is degraded",
        sessionVerdict(facts(deadAtExit = true)) == SessionVerdict.DEAD_AT_EXIT,
    )
    check(
        "a parked swapchain is degraded",
        sessionVerdict(facts(surfaceParks = 2)) == SessionVerdict.PARKED,
    )
    check(
        "loss thrashing is degraded at the threshold",
        sessionVerdict(facts(deviceLosses = WebGpuHealthPolicy.SESSION_LOSS_STRIKE_THRESHOLD)) ==
            SessionVerdict.LOSS_THRASH,
    )
    check(
        "one loss below the threshold is healthy",
        sessionVerdict(facts(deviceLosses = WebGpuHealthPolicy.SESSION_LOSS_STRIKE_THRESHOLD - 1)) ==
            SessionVerdict.HEALTHY,
    )

    // --- precedence ---------------------------------------------------------------------------
    check(
        "a dead renderer outranks a park and losses",
        sessionVerdict(facts(deadAtExit = true, surfaceParks = 3, deviceLosses = 9)) ==
            SessionVerdict.DEAD_AT_EXIT,
    )
    check(
        "a park outranks loss thrashing and never-drew",
        sessionVerdict(facts(surfaceParks = 1, deviceLosses = 9, anyPageDrawn = false)) ==
            SessionVerdict.PARKED,
    )
    check(
        "loss thrashing outranks never-drew",
        sessionVerdict(facts(deviceLosses = 5, anyPageDrawn = false)) ==
            SessionVerdict.LOSS_THRASH,
    )

    // --- defensive readings -------------------------------------------------------------------
    check(
        "a negative park count is not a park",
        sessionVerdict(facts(surfaceParks = -1)) == SessionVerdict.HEALTHY,
    )
    check(
        "a negative duration is not the black screen",
        sessionVerdict(facts(durationMs = -1L, anyPageDrawn = false)) == SessionVerdict.HEALTHY,
    )

    // --- the caller's contract ----------------------------------------------------------------
    check(
        "HEALTHY is the first and only clean verdict",
        SessionVerdict.entries.first() == SessionVerdict.HEALTHY &&
            SessionVerdict.entries.count { it == SessionVerdict.HEALTHY } == 1,
    )

    if (failures == 0) {
        println("ALL PASS")
    } else {
        println("$failures FAILURES")
        kotlin.system.exitProcess(1)
    }
}

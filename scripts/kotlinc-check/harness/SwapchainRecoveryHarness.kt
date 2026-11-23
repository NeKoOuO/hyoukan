// Runs the REAL swapchain recovery table (compiled straight from the library's
// SwapchainRecoveryPolicy.kt).
//
// This is the order that decides whether a surface which cannot produce an image gets fixed or
// gives up, and it was born from the 2026-11-22 PowerVR dump: the swapchain returned Error from the
// first frame, a surface rebuild reproduced it, and the old renderer had nothing above that rung.
// The file is pure (no Android import), which is why this harness links the real table rather than
// a copy, and why the boundaries can be pinned without a device.

import ca.mpreg.webgpuviewer.renderer.SwapchainRecoveryPolicy
import ca.mpreg.webgpuviewer.renderer.SwapchainRecoveryStep

private var failures = 0

private fun check(label: String, cond: Boolean) {
    if (!cond) {
        failures++
        println("FAIL: $label")
    } else {
        println("ok: $label")
    }
}

fun main() {
    // --- the boundaries are exact -------------------------------------------------------------
    check(
        "the first failure only retries",
        SwapchainRecoveryPolicy.stepFor(1) == SwapchainRecoveryStep.RETRY,
    )
    check(
        "one frame before the surface rebuild still retries",
        SwapchainRecoveryPolicy.stepFor(SwapchainRecoveryPolicy.RECREATE_SURFACE_AT - 1) ==
            SwapchainRecoveryStep.RETRY,
    )
    check(
        "the surface rung fires at its boundary",
        SwapchainRecoveryPolicy.stepFor(SwapchainRecoveryPolicy.RECREATE_SURFACE_AT) ==
            SwapchainRecoveryStep.RECREATE_SURFACE,
    )
    check(
        "the device rung fires at its boundary",
        SwapchainRecoveryPolicy.stepFor(SwapchainRecoveryPolicy.REBUILD_DEVICE_AT) ==
            SwapchainRecoveryStep.REBUILD_DEVICE,
    )
    check(
        "the backend rung fires at its boundary",
        SwapchainRecoveryPolicy.stepFor(SwapchainRecoveryPolicy.SWITCH_BACKEND_AT) ==
            SwapchainRecoveryStep.SWITCH_BACKEND,
    )
    check(
        "the give-up fires at its boundary",
        SwapchainRecoveryPolicy.stepFor(SwapchainRecoveryPolicy.GIVE_UP_AT) ==
            SwapchainRecoveryStep.PARK,
    )

    // --- between the rungs nothing repeats ----------------------------------------------------
    check(
        "after the surface rung the next frame retries",
        SwapchainRecoveryPolicy.stepFor(SwapchainRecoveryPolicy.RECREATE_SURFACE_AT + 1) ==
            SwapchainRecoveryStep.RETRY,
    )
    check(
        "after the device rung the next frame retries",
        SwapchainRecoveryPolicy.stepFor(SwapchainRecoveryPolicy.REBUILD_DEVICE_AT + 1) ==
            SwapchainRecoveryStep.RETRY,
    )
    check(
        "after the backend rung the next frame retries",
        SwapchainRecoveryPolicy.stepFor(SwapchainRecoveryPolicy.SWITCH_BACKEND_AT + 1) ==
            SwapchainRecoveryStep.RETRY,
    )

    // --- the order is the fix -----------------------------------------------------------------
    check(
        "the rungs are reached in surface, device, backend order",
        SwapchainRecoveryPolicy.RECREATE_SURFACE_AT <
            SwapchainRecoveryPolicy.REBUILD_DEVICE_AT &&
            SwapchainRecoveryPolicy.REBUILD_DEVICE_AT <
            SwapchainRecoveryPolicy.SWITCH_BACKEND_AT &&
            SwapchainRecoveryPolicy.SWITCH_BACKEND_AT <
            SwapchainRecoveryPolicy.GIVE_UP_AT,
    )

    // --- degenerate and past-the-end streaks --------------------------------------------------
    check(
        "zero and negative streaks are not a recovery",
        SwapchainRecoveryPolicy.stepFor(0) == SwapchainRecoveryStep.RETRY &&
            SwapchainRecoveryPolicy.stepFor(-5) == SwapchainRecoveryStep.RETRY,
    )
    check(
        "a streak past the ceiling stays parked",
        SwapchainRecoveryPolicy.stepFor(SwapchainRecoveryPolicy.GIVE_UP_AT + 1) ==
            SwapchainRecoveryStep.PARK &&
            SwapchainRecoveryPolicy.stepFor(Int.MAX_VALUE) == SwapchainRecoveryStep.PARK,
    )
    check(
        "a streak past a rung does not repeat that rung",
        SwapchainRecoveryPolicy.stepFor(SwapchainRecoveryPolicy.REBUILD_DEVICE_AT + 7) ==
            SwapchainRecoveryStep.RETRY &&
            SwapchainRecoveryPolicy.stepFor(SwapchainRecoveryPolicy.RECREATE_SURFACE_AT + 1) ==
            SwapchainRecoveryStep.RETRY,
    )

    // --- every rung is reachable from a contiguous run ---------------------------------------
    val seen = (0..SwapchainRecoveryPolicy.GIVE_UP_AT)
        .map { SwapchainRecoveryPolicy.stepFor(it) }
        .toSet()
    check(
        "a full streak visits every step",
        seen == SwapchainRecoveryStep.entries.toSet(),
    )

    if (failures == 0) {
        println("ALL PASS")
    } else {
        println("$failures FAILURE(S)")
        kotlin.system.exitProcess(1)
    }
}

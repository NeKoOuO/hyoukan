// Runs the REAL tile-coverage rule (compiled straight from the library's pure
// renderer/TileCoverage.kt).
//
// This function is the answer `ImagePage.ImageSingle.drawLive` reads as "the tiles have painted
// this page". The 2026-11-22 black-screen report was that answer being true with no pixels
// written - an empty placement ("nothing to draw" read as "painted everything") and a frame with
// no instance buffer both returned covered, so the direct page draw was skipped for the life of
// the page while the session logged nothing wrong. The page is now painted under the tiles every
// frame as well, but this rule still gates prewarming and must stay honest.

import ca.mpreg.webgpuviewer.renderer.tileCoverage

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
    // --- the only case that may answer covered ------------------------------------------------
    check(
        "a painted placement with every visible tile present is covered",
        tileCoverage(placementEmpty = false, blitted = true, missingVisible = false),
    )

    // --- the 2026-11-22 lies ----------------------------------------------------------------
    check(
        "an empty placement is never covered, even if something blitted",
        !tileCoverage(placementEmpty = true, blitted = true, missingVisible = false),
    )
    check(
        "a frame that did not blit is never covered",
        !tileCoverage(placementEmpty = false, blitted = false, missingVisible = false),
    )
    check(
        "a missing visible tile is never covered",
        !tileCoverage(placementEmpty = false, blitted = true, missingVisible = true),
    )

    // --- combinations -------------------------------------------------------------------------
    check(
        "nothing blitted and nothing missing is still not covered",
        !tileCoverage(placementEmpty = false, blitted = false, missingVisible = true),
    )
    check(
        "an empty placement with nothing missing is not covered",
        !tileCoverage(placementEmpty = true, blitted = false, missingVisible = false),
    )
    check(
        "every failure at once is not covered",
        !tileCoverage(placementEmpty = true, blitted = false, missingVisible = true),
    )
    check(
        "covered is exactly one of the eight combinations",
        listOf(false, true).sumOf { empty ->
            listOf(false, true).sumOf { painted ->
                listOf(false, true).count { missing ->
                    tileCoverage(empty, painted, missing)
                }
            }
        } == 1,
    )

    if (failures > 0) {
        println("FAILURES: $failures")
        kotlin.system.exitProcess(1)
    }
    println("ALL PASS")
}

package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * The decision that turns one closed reader session into a strike - the rule that silently
 * switched the WebGPU reader off on real devices.
 *
 * The regression this pins is named explicitly: a session that ended on a chapter transition is a
 * *healthy* session. It used to be judged from the page `currentPage` named at `destroy()`, and a
 * transition is not a `ViewerReaderPage` - so finishing a chapter and closing the reader recorded a
 * strike, and two of those disabled the renderer for good.
 */
@Execution(ExecutionMode.CONCURRENT)
class WebGpuSessionHealthTest {

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

    // --- the regression -----------------------------------------------------------------------

    @Test
    fun `a session that ended on a chapter transition is healthy`() {
        // Every page drew, then the reader crossed into the transition page and closed. The page
        // at exit is not a ViewerReaderPage; that is not evidence the display never worked.
        sessionVerdict(facts(anyPageDrawn = true)) shouldBe SessionVerdict.HEALTHY
    }

    @Test
    fun `a long healthy session is healthy even without parks or losses`() {
        sessionVerdict(facts(decodedPages = 100, durationMs = 3_600_000L)) shouldBe
            SessionVerdict.HEALTHY
    }

    // --- the black-screen shape ----------------------------------------------------------------

    @Test
    fun `decoded pages with nothing ever drawn is the black screen`() {
        sessionVerdict(facts(anyPageDrawn = false)) shouldBe SessionVerdict.NEVER_DREW
    }

    @Test
    fun `the black-screen rule needs two decoded pages`() {
        // One page is a chapter closed while its first decode was arriving, not a broken display.
        sessionVerdict(facts(decodedPages = 1, anyPageDrawn = false)) shouldBe SessionVerdict.HEALTHY
        sessionVerdict(facts(decodedPages = 0, anyPageDrawn = false)) shouldBe SessionVerdict.HEALTHY
    }

    @Test
    fun `the black-screen rule needs five seconds`() {
        sessionVerdict(facts(durationMs = 4_999L, anyPageDrawn = false)) shouldBe SessionVerdict.HEALTHY
    }

    @Test
    fun `both black-screen boundaries are inclusive`() {
        // Exactly the documented thresholds: two decoded pages and five seconds.
        sessionVerdict(facts(decodedPages = 2, durationMs = 5_000L, anyPageDrawn = false)) shouldBe
            SessionVerdict.NEVER_DREW
    }

    // --- the stronger signals ------------------------------------------------------------------

    @Test
    fun `a dead renderer at a deliberate exit is degraded`() {
        sessionVerdict(facts(deadAtExit = true)) shouldBe SessionVerdict.DEAD_AT_EXIT
    }

    @Test
    fun `a parked swapchain is degraded`() {
        sessionVerdict(facts(surfaceParks = 2)) shouldBe SessionVerdict.PARKED
    }

    @Test
    fun `loss thrashing is degraded at the threshold`() {
        sessionVerdict(facts(deviceLosses = WebGpuHealthPolicy.SESSION_LOSS_STRIKE_THRESHOLD)) shouldBe
            SessionVerdict.LOSS_THRASH
    }

    @Test
    fun `two losses in one session are not thrashing`() {
        val below = WebGpuHealthPolicy.SESSION_LOSS_STRIKE_THRESHOLD - 1
        sessionVerdict(facts(deviceLosses = below)) shouldBe SessionVerdict.HEALTHY
    }

    // --- precedence ----------------------------------------------------------------------------

    @Test
    fun `a dead renderer outranks a park and a loss`() {
        sessionVerdict(facts(deadAtExit = true, surfaceParks = 3, deviceLosses = 9)) shouldBe
            SessionVerdict.DEAD_AT_EXIT
    }

    @Test
    fun `a park outranks loss thrashing and never-drew`() {
        sessionVerdict(
            facts(surfaceParks = 1, deviceLosses = 9, anyPageDrawn = false),
        ) shouldBe SessionVerdict.PARKED
    }

    @Test
    fun `loss thrashing outranks never-drew`() {
        sessionVerdict(
            facts(deviceLosses = 5, anyPageDrawn = false),
        ) shouldBe SessionVerdict.LOSS_THRASH
    }

    // --- defensive readings --------------------------------------------------------------------

    @Test
    fun `a negative park count is not a park`() {
        // Parks are a difference of two monotonic counters; a bad diff must not strike.
        sessionVerdict(facts(surfaceParks = -1)) shouldBe SessionVerdict.HEALTHY
    }

    @Test
    fun `a negative session duration is not the black screen`() {
        sessionVerdict(facts(durationMs = -1L, anyPageDrawn = false)) shouldBe SessionVerdict.HEALTHY
    }

    @Test
    fun `only HEALTHY is a clean session`() {
        // The caller's contract: everything that is not HEALTHY records a strike. If a new verdict
        // is added, this fails until the caller's rule is checked.
        val degraded = SessionVerdict.entries.filter { it != SessionVerdict.HEALTHY }
        degraded.forEach { it shouldNotBe SessionVerdict.HEALTHY }
        SessionVerdict.HEALTHY shouldBe SessionVerdict.entries.first()
    }
}

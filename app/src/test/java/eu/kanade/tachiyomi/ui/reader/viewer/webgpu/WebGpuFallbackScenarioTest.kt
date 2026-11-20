package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * The reported bug as a sequence of reader sessions, driven through both halves of the policy:
 * [sessionVerdict] decides a session, [WebGpuHealthPolicy] carries the strike record into the next
 * one, and `WebGpuOffer`/`WebGpuProvider` reads [WebGpuHealthPolicy.isDisabled].
 *
 * The scenario is the programmer's report: "it falls back to the legacy reader on all devices".
 * A user reads chapters to the end and closes the reader — the session ends on a chapter
 * transition. That must never disable the renderer, however many times it happens.
 */
@Execution(ExecutionMode.CONCURRENT)
class WebGpuFallbackScenarioTest {

    private val build = "1.25.3-12000"

    /** One closed reader session, applied to the record exactly as `WebGpuViewer.destroy` does. */
    private fun apply(record: WebGpuHealthPolicy.Record, facts: SessionFacts): WebGpuHealthPolicy.Record =
        if (sessionVerdict(facts) == SessionVerdict.HEALTHY) {
            WebGpuHealthPolicy.afterHealthySession(record)
        } else {
            WebGpuHealthPolicy.afterStrike(record, build)
        }

    /** A chapter read from start to end, then closed on the transition page. Every page drew. */
    private fun chapterToTheEnd() = SessionFacts(
        deadAtExit = false,
        surfaceParks = 0,
        decodedPages = 40,
        durationMs = 600_000L,
        anyPageDrawn = true,
        deviceLosses = 0,
    )

    /** A genuine black screen: pages decoded, the display never presented one. */
    private fun blackScreen() = SessionFacts(
        deadAtExit = false,
        surfaceParks = 0,
        decodedPages = 6,
        durationMs = 9_000L,
        anyPageDrawn = false,
        deviceLosses = 0,
    )

    private fun offered(record: WebGpuHealthPolicy.Record, at: String = build): Boolean =
        webGpuOfferFor(
            wantsHighQuality = true,
            strikes = WebGpuHealthPolicy.strikesForBuild(record, at),
            rendererAvailable = { true },
        ) == WebGpuOffer.OFFERED

    @Test
    fun `reading many chapters to the end never disables the renderer`() {
        var record = WebGpuHealthPolicy.EMPTY
        repeat(50) {
            record = apply(record, chapterToTheEnd())
            // The bug this pins: the old exit-page check counted each of these as a strike.
            offered(record).shouldBeTrue()
        }
    }

    @Test
    fun `two real black screens disable the renderer`() {
        var record = apply(WebGpuHealthPolicy.EMPTY, blackScreen())
        offered(record).shouldBeTrue()
        record = apply(record, blackScreen())
        offered(record).shouldBeFalse()
    }

    @Test
    fun `a healthy session between two black screens keeps the renderer`() {
        // "Consecutive" is the promise: one bad session is forgiven by the next good one.
        var record = apply(WebGpuHealthPolicy.EMPTY, blackScreen())
        record = apply(record, chapterToTheEnd())
        record = apply(record, blackScreen())
        offered(record).shouldBeTrue()
    }

    @Test
    fun `a new build releases a disabled device`() {
        var record = WebGpuHealthPolicy.EMPTY
        record = apply(record, blackScreen())
        record = apply(record, blackScreen())
        offered(record).shouldBeFalse()
        offered(record, at = "1.25.3-12001").shouldBeTrue()
    }

    @Test
    fun `an untagged historical latch releases on the next read`() {
        // Every device struck before the tag existed: the count reads as zero even before the
        // viewer gets a chance to overwrite it.
        val legacy = WebGpuHealthPolicy.Record(strikes = 2, build = null)
        offered(legacy).shouldBeTrue()
    }

    @Test
    fun `a disabled device stays disabled for the rest of its own build`() {
        var record = apply(WebGpuHealthPolicy.EMPTY, blackScreen())
        record = apply(record, blackScreen())
        offered(record).shouldBeFalse()
        offered(record).shouldBeFalse()
        WebGpuHealthPolicy.strikesForBuild(record, build) shouldBe WebGpuHealthPolicy.DISABLE_STRIKES
    }
}

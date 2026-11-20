package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * The provider's gate order - the single decision behind "the reader falls back to the legacy
 * viewer on every device".
 *
 * Precedence is the part that matters: the user's switch outranks the device's health verdict, and
 * the health verdict outranks the renderer probe because probing initialises the GPU device. A
 * reordering that probed first would create a device for a reader that was never going to use it.
 */
// The probe flag is instance state shared by the helper below, so methods of this class run on one
// thread; the policy itself is pure and needs no isolation from other classes.
@Execution(ExecutionMode.SAME_THREAD)
class WebGpuOfferTest {

    private var probed = false

    private fun offer(
        wantsHighQuality: Boolean = true,
        strikes: Int = 0,
        available: Boolean = true,
    ) = webGpuOfferFor(wantsHighQuality, strikes) {
        probed = true
        available
    }

    @Test
    fun `a wanted, healthy, available renderer is offered`() {
        probed = false
        offer() shouldBe WebGpuOffer.OFFERED
        probed.shouldBeTrue()
    }

    @Test
    fun `the switch being off declines without probing the renderer`() {
        probed = false
        offer(wantsHighQuality = false) shouldBe WebGpuOffer.SWITCH_OFF
        // The probe initialises the device; a reader that will not use it must not create one.
        probed.shouldBeFalse()
    }

    @Test
    fun `a health-disabled device declines without probing the renderer`() {
        probed = false
        offer(strikes = WebGpuHealthPolicy.DISABLE_STRIKES) shouldBe WebGpuOffer.HEALTH_DISABLED
        probed.shouldBeFalse()
    }

    @Test
    fun `one strike below the threshold still reaches the probe`() {
        probed = false
        offer(strikes = WebGpuHealthPolicy.DISABLE_STRIKES - 1) shouldBe WebGpuOffer.OFFERED
        probed.shouldBeTrue()
    }

    @Test
    fun `an unavailable renderer is the last gate`() {
        probed = false
        offer(available = false) shouldBe WebGpuOffer.RENDERER_UNAVAILABLE
        probed.shouldBeTrue()
    }

    @Test
    fun `the switch outranks a disabled health record`() {
        // Turning the renderer off must not depend on the device's history.
        offer(wantsHighQuality = false, strikes = 99, available = false) shouldBe WebGpuOffer.SWITCH_OFF
    }

    @Test
    fun `the health verdict outranks an unavailable renderer`() {
        // Both are declines; the log must name the latch first, because that is the actionable one.
        offer(strikes = 2, available = false) shouldBe WebGpuOffer.HEALTH_DISABLED
    }

    @Test
    fun `a negative strike count is treated as none`() {
        offer(strikes = -1) shouldBe WebGpuOffer.OFFERED
    }
}

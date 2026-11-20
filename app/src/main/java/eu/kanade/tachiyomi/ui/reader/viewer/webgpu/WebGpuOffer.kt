// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

/**
 * Why the WebGPU viewer was offered, or which gate stopped it first.
 *
 * This is the entire "the reader keeps falling back" decision, extracted from `WebGpuProvider` so
 * it can be asserted offline (`WebGpuOfferHarness`, `WebGpuOfferTest`). The order is load-bearing:
 * the switch is the user's choice and outranks everything, the health latch is the device's verdict
 * and outranks the renderer probe, and the probe itself is the last gate because **running it
 * initialises the GPU device** - asking "is WebGPU available?" on a reader that was never going to
 * use it is exactly the kind of hidden cost this ordering avoids.
 */
internal enum class WebGpuOffer {
    /** The renderer can be used; `WebGpuProvider` builds the viewer. */
    OFFERED,

    /** The user's high-quality switch is off. Not a failure - the switch doing its job. */
    SWITCH_OFF,

    /** This build recorded [WebGpuHealthPolicy.DISABLE_STRIKES] degraded sessions on this device. */
    HEALTH_DISABLED,

    /** The renderer cannot initialise or has lost its device (`unavailableReason` says which). */
    RENDERER_UNAVAILABLE,
}

/**
 * The provider's gate, in order.
 *
 * [rendererAvailable] is a lambda rather than a value so it is only evaluated when the first two
 * gates let the decision reach it: probing calls `WebGpuRenderer.isAvailable`, whose first read
 * initialises the whole GPU device.
 */
internal inline fun webGpuOfferFor(
    wantsHighQuality: Boolean,
    strikes: Int,
    rendererAvailable: () -> Boolean,
): WebGpuOffer = when {
    !wantsHighQuality -> WebGpuOffer.SWITCH_OFF
    strikes >= WebGpuHealthPolicy.DISABLE_STRIKES -> WebGpuOffer.HEALTH_DISABLED
    !rendererAvailable() -> WebGpuOffer.RENDERER_UNAVAILABLE
    else -> WebGpuOffer.OFFERED
}
// KMK <--

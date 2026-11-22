// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

/**
 * What a closing reader session did, as the strike rule needs it.
 *
 * Every field is a fact accumulated over the session, never a sample of the reader's state at
 * `destroy()`. That distinction is the bug this file exists to make impossible to reintroduce: the
 * black-screen test used to look at the page `currentPage` named at exit, and a session closed on a
 * chapter transition (where `currentPage` is a `ViewerTransitionPage`) reported itself as having
 * never drawn anything. Two chapters read to the end disabled the renderer on that device for good.
 */
internal data class SessionFacts(
    /** The renderer was still dead when a deliberate exit closed the session. */
    val deadAtExit: Boolean,
    /** Swapchain parks this session (see `WebGpuRenderer.surfaceParkCount`). */
    val surfaceParks: Int,
    /** Pages that finished a decode, of any outcome. */
    val decodedPages: Int,
    /** How long the session was open, milliseconds. */
    val durationMs: Long,
    /** Whether any page was ever on screen - a session-wide fact, not the exit page. */
    val anyPageDrawn: Boolean,
    /**
     * Frames the renderer actually submitted and presented this session
     * (`WebGpuRenderer.presentedFrames`, diffed against the viewer's construction-time snapshot).
     *
     * This is the one fact that separates the two black-screen shapes. `anyPageDrawn` is set by
     * the render *walk* before a frame is submitted, so a session that never reached the display
     * at all still reports it: the 2026-11-22 Mali field log showed `drawn=true`, twelve decodes,
     * no error and no park - and a black screen. `frames=0` means the surface was never written
     * (renderer/surface defect); `frames>0` with nothing drawn means the draw or the compositing
     * is at fault. Without it the rule below could only ever see the walk's optimistic flag.
     */
    val presentedFrames: Int,
    /** Device losses this session, recovered or not. */
    val deviceLosses: Int,
)

/**
 * The session's verdict, in the order the rule asks.
 *
 * An enum rather than a boolean so the strike log names *why* - `parked` and `neverDrew` are
 * different defects with the same consequence, and a bare `true` made them indistinguishable in the
 * one line a field report carries.
 */
internal enum class SessionVerdict {
    HEALTHY,
    DEAD_AT_EXIT,
    PARKED,
    LOSS_THRASH,
    NEVER_DREW,
}

/**
 * Two decoded pages and five seconds keep a session that was opened and closed again (or closed
 * while its first page was still arriving) out of the black-screen rule: the shape being struck is
 * someone reading for a while with nothing on screen.
 */
internal const val NEVER_DREW_MIN_DECODES: Int = 2
internal const val NEVER_DREW_MIN_MS: Long = 5_000L

/**
 * The one place a session is judged.
 *
 * Precedence is deliberate and matches the original conditions' reading order: a dead renderer or a
 * parked swapchain is proof on its own, a loss-thrashing session is next, and "nothing was ever
 * drawn" only counts when the session ran long enough and decoded enough to have drawn something.
 */
internal fun sessionVerdict(facts: SessionFacts): SessionVerdict = when {
    facts.deadAtExit -> SessionVerdict.DEAD_AT_EXIT
    facts.surfaceParks > 0 -> SessionVerdict.PARKED
    facts.deviceLosses >= WebGpuHealthPolicy.SESSION_LOSS_STRIKE_THRESHOLD -> SessionVerdict.LOSS_THRASH
    // Both halves are required: the walk's flag says a page was in the drawn set, and the
    // presenter's count says the display was ever written. Either being absent with pages
    // decoded and time elapsed is a session with nothing on screen - and the walk's flag alone
    // was exactly what made the 2026-11-22 black screen close as healthy.
    facts.decodedPages >= NEVER_DREW_MIN_DECODES &&
        facts.durationMs >= NEVER_DREW_MIN_MS &&
        !(facts.anyPageDrawn && facts.presentedFrames > 0) -> SessionVerdict.NEVER_DREW

    else -> SessionVerdict.HEALTHY
}
// KMK <--

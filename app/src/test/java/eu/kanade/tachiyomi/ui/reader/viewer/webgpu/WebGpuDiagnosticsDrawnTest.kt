package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * The session-level "anything was drawn" fact the strike rule now reads.
 *
 * `WebGpuDiagnostics` is a process-wide singleton, so this class runs its methods on one thread -
 * a concurrent test of a shared recorder would race itself.
 */
@Execution(ExecutionMode.SAME_THREAD)
class WebGpuDiagnosticsDrawnTest {

    @Test
    fun `a fresh session has drawn nothing`() {
        WebGpuDiagnostics.beginSession("test")
        WebGpuDiagnostics.anyPageDrawn().shouldBeFalse()
    }

    @Test
    fun `recording a drawn page is visible to the close-out`() {
        WebGpuDiagnostics.beginSession("test")
        WebGpuDiagnostics.recordPageDrawn()
        WebGpuDiagnostics.anyPageDrawn().shouldBeTrue()
    }

    @Test
    fun `recording is idempotent`() {
        WebGpuDiagnostics.beginSession("test")
        WebGpuDiagnostics.recordPageDrawn()
        WebGpuDiagnostics.recordPageDrawn()
        WebGpuDiagnostics.anyPageDrawn().shouldBeTrue()
    }

    @Test
    fun `a new session clears the previous session's answer`() {
        WebGpuDiagnostics.beginSession("first")
        WebGpuDiagnostics.recordPageDrawn()
        WebGpuDiagnostics.anyPageDrawn().shouldBeTrue()
        WebGpuDiagnostics.beginSession("second")
        WebGpuDiagnostics.anyPageDrawn().shouldBeFalse()
    }

    @Test
    fun `the rendered report names the fact`() {
        WebGpuDiagnostics.beginSession("test")
        WebGpuDiagnostics.render().contains("drawn=false") shouldBe true
        WebGpuDiagnostics.recordPageDrawn()
        WebGpuDiagnostics.render().contains("drawn=true") shouldBe true
    }

    @Test
    fun `drawing alone does not make a session notable`() {
        // Drawing is the healthy case; only an anomaly should write to the in-app log.
        WebGpuDiagnostics.beginSession("test")
        WebGpuDiagnostics.recordPageDrawn()
        WebGpuDiagnostics.recordDecode("decoded", 10L, "ch=1/i=0", downsampled = false)
        WebGpuDiagnostics.isNotable().shouldBeFalse()
    }
}

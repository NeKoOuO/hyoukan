package ca.mpreg.webgpuviewer.viewer

/**
 * Offline stand-in for the library's render probe. The harness links the app's
 * WebGpuDiagnostics, which names this object; the real one lives in the webgpuviewer module
 * and cannot be compiled off-device.
 */
object RenderProbe {
    @Volatile
    var pages: Int = 0

    @Volatile
    var lastPage: String = "none"

    fun beginFrame() {
        pages = 0
    }

    fun notePage(description: String) {
        pages++
        lastPage = description
    }

    fun noteImage(rect: String) = Unit

    fun noteSkippedNoMips() = Unit

    fun summary(): String = "pages=$pages quads=0 skippedNoMips=0 last=none rect=none"
}

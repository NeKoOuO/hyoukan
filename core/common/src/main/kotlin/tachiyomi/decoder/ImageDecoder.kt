package tachiyomi.decoder

import android.graphics.Bitmap
import android.graphics.Rect
import tachiyomi.core.common.util.system.cropBorders
import java.io.InputStream
import ca.mpreg.imagedecoder.ImageDecoder as MpregImageDecoder

/**
 * Compatibility bridge for the tiled decoder of `com.github.tachiyomiorg:subsampling-scale-image-view`
 * (the SSIV fork the reader uses).
 *
 * That library's `com.davemorrissey.labs.subscaleview.decoder.Decoder` - the only
 * [com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder] its `TilesInitTask` will build -
 * is compiled against `tachiyomi.decoder.ImageDecoder` and constructs it from the page's stream.
 * The app used to ship that artifact, so the reference resolved; when the app moved to
 * `ca.mpreg:imagedecoder` as its only decoder (both AARs ship a native library with the same
 * `libimagedecoder.so` soname, so exactly one can be in the APK), the class disappeared and every
 * page big enough for SSIV to tile crashed inside `TilesInitTask` with:
 *
 * ```
 * java.lang.NoClassDefFoundError: Failed resolution of: Ltachiyomi/decoder/ImageDecoder;
 *     at com.davemorrissey.labs.subscaleview.decoder.Decoder.init(...)
 * ```
 *
 * Small pages never touched this path, which is why the reader worked until a tall or detailed page
 * arrived. This class supplies exactly the API the library's bytecode calls - the companion
 * `newInstance(stream, cropBorders, displayProfile)` factory, `width`/`height`, `decode(Rect, Int)`
 * and `recycle()` - backed by the upstream decoder the app actually ships. It is a bridge for a
 * binary dependency, not a second decoder: every byte still goes through
 * [MpregImageDecoder].
 *
 * `displayProfile` is deliberately ignored: it was HDR display metadata for the old native path,
 * and the upstream decoder handles HDR itself. `cropBorders` is honoured by decoding the whole page
 * once and trimming it with the app's own [cropBorders] helper - the same replacement the Coil and
 * WebGPU paths already make, because upstream has no native trim.
 */
class ImageDecoder private constructor(
    private val decoder: MpregImageDecoder?,
    private val trimmed: Bitmap?,
) {
    private val recycled = java.util.concurrent.atomic.AtomicBoolean(false)

    val width: Int = trimmed?.width ?: requireNotNull(decoder).width

    val height: Int = trimmed?.height ?: requireNotNull(decoder).height

    /** Read by SSIV's `Decoder.isReady`/`recycle` guards. */
    fun isRecycled(): Boolean = recycled.get()

    /**
     * Decodes [rect] at [sampleSize], the contract SSIV's tile loader expects.
     *
     * When borders were trimmed there is no native decoder left to ask - the page is a resident
     * bitmap by then - so the same region is cut from it. Only the crop-borders path pays for that;
     * every other page decodes regions natively.
     */
    fun decode(rect: Rect, sampleSize: Int): Bitmap {
        val source = trimmed
        if (source == null) {
            return requireNotNull(decoder).decodeToBitmap(rect, sampleSize)
        }

        val left = rect.left.coerceIn(0, source.width - 1)
        val top = rect.top.coerceIn(0, source.height - 1)
        val right = rect.right.coerceIn(left + 1, source.width)
        val bottom = rect.bottom.coerceIn(top + 1, source.height)
        val region = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        if (sampleSize <= 1) return region

        val scaled = Bitmap.createScaledBitmap(
            region,
            (region.width / sampleSize).coerceAtLeast(1),
            (region.height / sampleSize).coerceAtLeast(1),
            true,
        )
        if (scaled !== region) region.recycle()
        return scaled
    }

    fun recycle() {
        if (!recycled.compareAndSet(false, true)) return
        trimmed?.let { if (!it.isRecycled) it.recycle() }
        decoder?.let { runCatching { it.close() } }
    }

    companion object {
        /**
         * Matches the JVM signature the SSIV fork calls:
         * `ImageDecoder$Companion.newInstance(InputStream, Z, [B)Ltachiyomi/decoder/ImageDecoder;`.
         */
        fun newInstance(stream: InputStream, cropBorders: Boolean, displayProfile: ByteArray): ImageDecoder {
            val decoder = MpregImageDecoder.open(stream)
            if (!cropBorders) return ImageDecoder(decoder, null)

            val full = try {
                decoder.decodeToBitmap(null, 1)
            } catch (e: Throwable) {
                runCatching { decoder.close() }
                throw e
            }
            runCatching { decoder.close() }
            // cropBorders() recycles its receiver when it actually trims, and returns it untouched
            // when the page has no uniform frame.
            return ImageDecoder(null, full.cropBorders())
        }
    }
}

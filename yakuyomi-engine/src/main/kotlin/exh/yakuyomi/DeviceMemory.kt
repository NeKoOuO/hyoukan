package exh.yakuyomi

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Device capability checks used to gate memory-hungry features.
 *
 * The AI translation pipeline loads three native models at once (DBNet detector, ONNX OCR and
 * AOT-GAN inpainter) plus large page bitmaps; on low-RAM devices the native allocation fails and
 * crashes the process with a SIGSEGV from `libyakuyomi_ncnn.so` that cannot be caught in Kotlin.
 * It is still gated behind a total-RAM check so those devices degrade gracefully instead of dying.
 *
 * The WebGPU renderer used to share that gate. It no longer does: the reader offers the renderer
 * wherever WebGPU itself is available, because a low-tier device can still draw pages and the
 * reader's own budget arithmetic already sizes its work to the device. A device that genuinely
 * cannot hold GPU-visible memory is caught at runtime by `WebGpuHealth`, which falls back to the
 * legacy viewer after repeated dead sessions instead of refusing the renderer up front.
 */
object DeviceMemory {

    // KMK --> The RAM gate used to be this module's own byte threshold (`totalRam >= 3GB`),
    // re-implemented in the stub module as well. It now delegates to the shared device tier, which
    // is the same answer the reader, Coil and the JXL cap use - so a device cannot be "big enough
    // for MTL" here and "low memory" everywhere else.
    //
    // The constant is kept, but as the *byte equivalent of the tier boundary* rather than the
    // thing the check compares against. A caller that wants the old number still compiles; the
    // gate itself is the tier, because the tier also folds in the vendor low-RAM mark and the
    // 32-bit ABI cap, which a byte comparison cannot see.
    // KMK <--

    /** Minimum total RAM required to run the on-device AI translation pipeline (3 GiB). */
    const val MTL_MIN_RAM_BYTES: Long = 3L * 1024 * 1024 * 1024

    private const val BYPASS_PREFS = "ram_gate_bypass"
    private const val BYPASS_KEY = "disabled"

    /** Whether the user has permanently disabled the RAM gate via the About screen easter-egg. */
    fun isRamGateDisabled(context: Context): Boolean = runCatching {
        context.getSharedPreferences(BYPASS_PREFS, Context.MODE_PRIVATE).getBoolean(BYPASS_KEY, false)
    }.getOrDefault(false)

    fun setRamGateDisabled(context: Context, disabled: Boolean) {
        runCatching {
            context.getSharedPreferences(BYPASS_PREFS, Context.MODE_PRIVATE).edit().putBoolean(BYPASS_KEY, disabled).apply()
        }
    }

    /** Total physical RAM in bytes, or 0 when the system service is unavailable. */
    fun totalRamBytes(context: Context): Long {
        return runCatching {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memoryInfo)
            memoryInfo.totalMem
        }.getOrDefault(0L)
    }

    fun hasSufficientRam(context: Context, minBytes: Long): Boolean {
        val total = totalRamBytes(context)
        // Unknown memory size (service missing): be conservative and deny the heavy features.
        return total >= minBytes
    }

    // KMK --> The gate now asks the shared tier rather than comparing bytes. The tier is the one
    // answer that also accounts for the vendor low-RAM mark and the 32-bit ABI cap, so a device
    // cannot be admitted here and demoted by the reader.
    //
    // `isRamGateDisabled` is folded into the shared implementation, so it is no longer repeated at
    // each call site - which is what kept the bypass and the check in separate places before.
    //
    // The `context` parameter is kept even though the tier is cached, because every existing caller
    // passes one and an overload would be a second answer to the same question.
    /** Whether this device has enough RAM for the on-device AI translation pipeline. */
    fun isMtlSupported(context: Context): Boolean =
        tachiyomi.core.common.util.system.AppMemoryMonitor.isMtlSupported(context)
    // KMK <--

    /** Whether the device runs a 64-bit ABI (32-bit processes have a much smaller address space). */
    fun is64Bit(): Boolean = Build.SUPPORTED_ABIS.any { it.contains("64") }

    /**
     * Normalized SoC manufacturer for NPU selection: "qualcomm" (Snapdragon), "mediatek"
     * (Dimensity/Helio), "samsung" (Exynos), "huawei" (Kirin), or "unknown".
     */
    fun socManufacturer(): String {
        val soc = "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL} ${Build.HARDWARE} ${Build.BOARD}".lowercase()
        return when {
            "qcom" in soc || "qualcomm" in soc || "snapdragon" in soc -> "qualcomm"
            "mtk" in soc || "mediatek" in soc || "dimensity" in soc || "helio" in soc -> "mediatek"
            "exynos" in soc || "samsung" in soc -> "samsung"
            "kirin" in soc || "hisilicon" in soc || "huawei" in soc -> "huawei"
            else -> "unknown"
        }
    }

    /** Whether [soc] matches a catalog [required] SoC family. */
    fun matchesSoc(soc: String, required: String): Boolean =
        soc.equals(required, ignoreCase = true)
}

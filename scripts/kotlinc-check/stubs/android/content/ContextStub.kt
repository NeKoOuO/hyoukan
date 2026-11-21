package android.content

import android.view.WindowManager

/** Offline stub for check.sh: only the surface the mtl.* fingerprint path touches. */
open class Context {
    companion object {
        const val ACTIVITY_SERVICE: String = "activity"
        const val WINDOW_SERVICE: String = "window"
        const val MODE_PRIVATE: Int = 0
    }

    fun getSystemService(name: String): Any? = null

    /** Enough of AssetManager for the upscaling bootstrap's manifest reader. */
    val assets: AssetManager = AssetManager()

    /** Enough of PackageManager for the upscaling device probe (Vulkan feature check). */
    fun getPackageManager(): PackageManager = PackageManager()

    val packageManager: PackageManager
        get() = PackageManager()

    /** Enough of the preference API for `WebGpuHealth`'s strike bookkeeping. */
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences = SharedPreferences()

    /** Enough of the storage API for `DatabaseMaintenanceManager`'s file paths. */
    val filesDir: java.io.File = java.io.File("/tmp/kotlinc-check-files")

    fun getDatabasePath(name: String): java.io.File = java.io.File("/tmp/kotlinc-check-db", name)
}

/** Offline stub for check.sh. */
class SharedPreferences {
    fun getInt(key: String, defValue: Int): Int = defValue

    fun getString(key: String, defValue: String?): String? = defValue

    fun getBoolean(key: String, defValue: Boolean): Boolean = defValue

    fun edit(): Editor = Editor()

    /** Offline stub for check.sh. */
    class Editor {
        fun putInt(key: String, value: Int): Editor = this

        fun putString(key: String, value: String?): Editor = this

        fun putBoolean(key: String, value: Boolean): Editor = this

        fun remove(key: String): Editor = this

        fun apply() { }
    }
}

/** Offline stub for check.sh. */
class AssetManager {
    fun open(name: String): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))
}

/** Offline stub for check.sh. */
class PackageManager {
    fun hasSystemFeature(name: String): Boolean = false
}

/** Offline stub for check.sh. */
class WindowManager {
    @Deprecated("Deprecated in Java")
    val defaultDisplay: Display? = null
}

/** Offline stub for check.sh. */
class Display {
    fun getMetrics(outMetrics: android.util.DisplayMetrics) { }
}


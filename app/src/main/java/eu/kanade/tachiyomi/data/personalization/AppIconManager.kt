package eu.kanade.tachiyomi.data.personalization

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.icerock.moko.resources.StringResource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.main.MainActivity
import exh.log.xLogE
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.i18n.kmk.KMR
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The app-identity framework: built-in launcher icons and the custom shortcut fallback.
 *
 * ### Why built-in icons are activity aliases
 *
 * Android resolves an app's icon and label from the manifest component the launcher starts. Neither
 * can be changed at runtime. The supported mechanism is a set of `<activity-alias>` entries, one per
 * icon, of which exactly one is enabled. This manager owns that invariant.
 *
 * ### Why a custom icon is a pinned shortcut
 *
 * An arbitrary user image cannot become the installed app's launcher icon: the launcher reads a
 * resource from the APK, and an app cannot rewrite its own resources. What *can* carry an arbitrary
 * bitmap is a pinned launcher shortcut, which is what [pinCustomShortcut] creates. The UI must say so
 * rather than imply the app icon itself changed.
 *
 * Adding a built-in icon later: add a `<activity-alias>` in `AndroidManifest.xml`, a mipmap resource,
 * and one entry in [builtInIcons]. Nothing else reads the list by name.
 */
@SingleIn(AppScope::class)
@Inject
class AppIconManager(
    private val context: Context,
    private val preferenceStore: PreferenceStore,
) {

    data class BuiltInIcon(
        val id: String,
        val component: ComponentName,
        @DrawableRes val preview: Int,
        val labelRes: StringResource,
        val defaultEnabled: Boolean = false,
    )

    /** How a picked image is turned into the square custom-shortcut icon. */
    enum class CustomIconScaleMode(val id: String) {
        /** Fill the square by trimming the overflowing edges (the pre-existing behaviour). */
        CROP("crop"),

        /** Keep the whole image, centred on a transparent square. */
        FIT("fit"),
        ;

        companion object {
            fun fromId(id: String): CustomIconScaleMode = entries.firstOrNull { it.id == id } ?: CROP
        }
    }

    private val packageManager: PackageManager
        get() = context.packageManager

    val selectedIconId = preferenceStore.getString(
        AppIdentitySettingKeys.selectedIcon.key,
        AppIdentitySettingKeys.selectedIcon.default,
    )

    /**
     * Empty means "use the app's real name". Stored separately from the launcher label because the
     * label of an activity alias is fixed at build time - see the class comment.
     */
    val customAppName = preferenceStore.getString(
        AppIdentitySettingKeys.customName.key,
        AppIdentitySettingKeys.customName.default,
    )

    /** Stored as the enum id so the preference stays a plain backup-friendly string. */
    val customIconScale = preferenceStore.getString(
        AppIdentitySettingKeys.customIconScale.key,
        AppIdentitySettingKeys.customIconScale.default,
    )

    val customIconScaleMode: CustomIconScaleMode
        get() = CustomIconScaleMode.fromId(customIconScale.get())

    // The alias class names live under the app's Kotlin namespace (`eu.kanade.tachiyomi…`, here
    // `eu.kanade.tachiyomi.ui.main`), not the runtime applicationId (`app.houri` and its suffixes):
    // a ComponentName's package is the applicationId, but its class name is the manifest
    // component's fully-qualified name.
    //
    // Derived from the class *name*, never `Class.getPackageName()`: that is a Java 9 API Android's
    // runtime does not implement (field crash on API 30: `NoSuchMethodError: ... getPackageName()
    // in class java.lang.Class`). `name` is stable on every API level and the aliases are declared
    // beside MainActivity in the manifest.
    private val aliasPackage = MainActivity::class.java.name.substringBeforeLast('.')

    val builtInIcons: List<BuiltInIcon> = listOf(
        BuiltInIcon(
            id = DEFAULT_ICON_ID,
            component = ComponentName(context, "$aliasPackage.MainActivityDefault"),
            preview = R.mipmap.ic_launcher,
            labelRes = KMR.strings.pref_app_icon_default,
            defaultEnabled = true,
        ),
        BuiltInIcon(
            id = "midnight",
            component = ComponentName(context, "$aliasPackage.MainActivityMidnight"),
            preview = R.mipmap.ic_launcher_midnight,
            labelRes = KMR.strings.pref_app_icon_midnight,
        ),
        BuiltInIcon(
            id = "sakura",
            component = ComponentName(context, "$aliasPackage.MainActivitySakura"),
            preview = R.mipmap.ic_launcher_sakura,
            labelRes = KMR.strings.pref_app_icon_sakura,
        ),
        BuiltInIcon(
            id = "ocean",
            component = ComponentName(context, "$aliasPackage.MainActivityOcean"),
            preview = R.mipmap.ic_launcher_ocean,
            labelRes = KMR.strings.pref_app_icon_ocean,
        ),
        BuiltInIcon(
            id = "ember",
            component = ComponentName(context, "$aliasPackage.MainActivityEmber"),
            preview = R.mipmap.ic_launcher_ember,
            labelRes = KMR.strings.pref_app_icon_ember,
        ),
        BuiltInIcon(
            id = "paper",
            component = ComponentName(context, "$aliasPackage.MainActivityPaper"),
            preview = R.mipmap.ic_launcher_paper,
            labelRes = KMR.strings.pref_app_icon_paper,
        ),
    )

    /** The icon the launcher is actually showing, which may differ from the preference after an update. */
    fun activeIcon(): BuiltInIcon {
        val selected = builtInIcons.firstOrNull { it.id == selectedIconId.get() }
        if (selected != null && isEnabled(selected)) return selected
        return builtInIcons.firstOrNull(::isEnabled) ?: builtInIcons.first()
    }

    fun apply(icon: BuiltInIcon): Boolean {
        return runCatching {
            // Enable first, then disable the rest: doing it the other way round leaves a window in
            // which no launcher component exists, and some launchers drop the shortcut permanently.
            setEnabled(icon.component, true)
            builtInIcons.filterNot { it.id == icon.id }.forEach { setEnabled(it.component, false) }
            selectedIconId.set(icon.id)
            true
        }.getOrElse {
            xLogE("AppIconManager: failed to switch icon to ${icon.id}", it)
            false
        }
    }

    /**
     * Repairs the "exactly one launcher alias" invariant after an update, a restore, or a process
     * death between enabling and disabling components. Safe to call on every app start.
     */
    fun sync() {
        runCatching {
            val enabled = builtInIcons.filter(::isEnabled)
            val preferred = builtInIcons.firstOrNull { it.id == selectedIconId.get() }
                ?: builtInIcons.first()
            if (enabled.size == 1 && enabled.first().id == preferred.id) return

            setEnabled(preferred.component, true)
            builtInIcons.filterNot { it.id == preferred.id }.forEach { setEnabled(it.component, false) }
            selectedIconId.set(preferred.id)
        }.onFailure { xLogE("AppIconManager: sync failed", it) }
    }

    private fun isEnabled(icon: BuiltInIcon): Boolean {
        val state = packageManager.getComponentEnabledSetting(icon.component)
        return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && icon.defaultEnabled)
    }

    private fun setEnabled(component: ComponentName, enabled: Boolean) {
        packageManager.setComponentEnabledSetting(
            component,
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP,
        )
    }

    // ------------------------------------------------------------------ custom icon

    fun customIconFile(): File = File(context.filesDir, "personalization/launcher_icon.png")

    /** The picked image before squaring, kept so a crop/fit change need not re-pick the image. */
    private fun customIconSourceFile(): File =
        File(context.filesDir, "personalization/launcher_icon_source.png")

    fun hasCustomIcon(): Boolean = customIconFile().isFile

    /**
     * Copies the picked image into app storage at a size a launcher icon can actually use, then
     * derives the square icon from it using the current [CustomIconScaleMode]. Returns false when
     * the stream is not a decodable image, so the UI can say the pick failed instead of silently
     * keeping the previous one.
     */
    fun importCustomIcon(uri: Uri): Boolean {
        return runCatching {
            val decoded = decodeBounded(uri, MAX_CUSTOM_ICON_SOURCE_PX) ?: return false
            val source = decoded.scaledToFit(MAX_CUSTOM_ICON_SOURCE_PX)
            writePng(source, customIconSourceFile())
            writeSquareIcon(source)
            if (source !== decoded) decoded.recycle()
            source.recycle()
            true
        }.getOrElse {
            xLogE("AppIconManager: custom icon import failed", it)
            false
        }
    }

    /**
     * Persists [mode] and rebuilds the square icon from the stored source, so switching between
     * crop and fit is reversible without picking the image again. True when the stored icon now
     * reflects the mode (also when no custom icon exists yet - the next import uses it).
     */
    fun applyCustomIconScaleMode(mode: CustomIconScaleMode): Boolean {
        return runCatching {
            customIconScale.set(mode.id)
            val sourceFile = customIconSourceFile().takeIf { it.isFile } ?: return@runCatching true
            val source = BitmapFactory.decodeFile(sourceFile.path) ?: return@runCatching true
            writeSquareIcon(source, mode)
            source.recycle()
            true
        }.getOrElse {
            xLogE("AppIconManager: rebuilding the custom icon for ${mode.id} failed", it)
            false
        }
    }

    private fun writeSquareIcon(source: Bitmap, mode: CustomIconScaleMode = customIconScaleMode) {
        val square = source.toSquareIcon(MAX_CUSTOM_ICON_PX, mode)
        writePng(square, customIconFile())
        if (square !== source) square.recycle()
    }

    private fun writePng(bitmap: Bitmap, target: File) {
        target.parentFile?.mkdirs()
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * Decodes the picker's image at a bounded size. A launcher icon is 512 px at most, and
     * `BitmapFactory` on a full-resolution phone photo would allocate hundreds of megabytes before
     * the scale-down ever ran.
     */
    private fun decodeBounded(uri: Uri, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > maxPx * 2 ||
            bounds.outHeight / sample > maxPx * 2
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    fun clearCustomIcon() {
        runCatching {
            customIconFile().delete()
            customIconSourceFile().delete()
        }
    }

    fun customIconBitmap(): Bitmap? = runCatching {
        customIconFile().takeIf { it.isFile }?.let { file -> BitmapFactory.decodeFile(file.path) }
    }.getOrNull()

    /**
     * Pins a launcher shortcut carrying the custom image and/or name.
     *
     * Returns true when the request was handed to the launcher. That is not a confirmation that the
     * user accepted it - Android deliberately gives the app no answer to that question.
     */
    fun pinCustomShortcut(label: String): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        return runCatching {
            val icon = customIconBitmap()?.let { IconCompat.createWithAdaptiveBitmap(it) }
                ?: IconCompat.createWithResource(context, R.mipmap.ic_launcher)
            val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
                .setShortLabel(label)
                .setLongLabel(label)
                .setIcon(icon)
                .setIntent(
                    Intent(context, MainActivity::class.java).apply {
                        action = Intent.ACTION_MAIN
                        addCategory(Intent.CATEGORY_LAUNCHER)
                    },
                )
                .build()
            ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
        }.getOrElse {
            xLogE("AppIconManager: pinning the custom shortcut failed", it)
            false
        }
    }

    /**
     * CROP fills the square by cutting the overflowing edges; FIT keeps the whole image and pads
     * the shorter side with transparency. Both paths scale smoothly instead of nearest-neighbour,
     * because a launcher icon is displayed at a fraction of the source resolution.
     */
    private fun Bitmap.toSquareIcon(maxPx: Int, mode: CustomIconScaleMode): Bitmap {
        if (mode == CustomIconScaleMode.FIT) {
            val scale = min(maxPx.toFloat() / width, maxPx.toFloat() / height)
            val scaledWidth = (width * scale).roundToInt().coerceIn(1, maxPx)
            val scaledHeight = (height * scale).roundToInt().coerceIn(1, maxPx)
            val scaled = Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)
            val square = Bitmap.createBitmap(maxPx, maxPx, Bitmap.Config.ARGB_8888)
            Canvas(square).drawBitmap(
                scaled,
                (maxPx - scaledWidth) / 2f,
                (maxPx - scaledHeight) / 2f,
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            if (scaled !== this) scaled.recycle()
            return square
        }

        val side = min(width, height)
        val left = (width - side) / 2
        val top = (height - side) / 2
        val square = Bitmap.createBitmap(this, left, top, side, side)
        if (square === this) {
            return Bitmap.createScaledBitmap(this, maxPx, maxPx, true)
        }
        val scaled = Bitmap.createScaledBitmap(square, maxPx, maxPx, true)
        square.recycle()
        return scaled
    }

    /** Bounds the stored source; it only needs enough pixels to re-derive a 512 px icon. */
    private fun Bitmap.scaledToFit(maxPx: Int): Bitmap {
        val longest = maxOf(width, height)
        if (longest <= maxPx) return this
        val scale = maxPx.toFloat() / longest
        return Bitmap.createScaledBitmap(
            this,
            (width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    companion object {
        const val DEFAULT_ICON_ID = "default"
        private const val SHORTCUT_ID = "houri_custom_launcher"
        private const val MAX_CUSTOM_ICON_PX = 512
        private const val MAX_CUSTOM_ICON_SOURCE_PX = 1024
    }
}

package eu.kanade.tachiyomi.data.personalization

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

    // The alias class names live under the app's Kotlin namespace (`eu.kanade.tachiyomi`), not the
    // runtime applicationId (`app.houri` and its suffixes): a ComponentName's package is the
    // applicationId, but its class name is the manifest component's fully-qualified name.
    private val aliasPackage = MainActivity::class.java.packageName

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

    fun hasCustomIcon(): Boolean = customIconFile().isFile

    /**
     * Copies the picked image into app storage at a size a launcher icon can actually use. Returns
     * false when the stream is not a decodable image, so the UI can say the pick failed instead of
     * silently keeping the previous one.
     */
    fun importCustomIcon(uri: Uri): Boolean {
        return runCatching {
            val bitmap = decodeBounded(uri) ?: return false
            val scaled = bitmap.scaledToSquare(MAX_CUSTOM_ICON_PX)
            val target = customIconFile().apply { parentFile?.mkdirs() }
            target.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (scaled !== bitmap) bitmap.recycle()
            scaled.recycle()
            true
        }.getOrElse {
            xLogE("AppIconManager: custom icon import failed", it)
            false
        }
    }

    /**
     * Decodes the picker's image at a bounded size. A launcher icon is 512 px at most, and
     * `BitmapFactory` on a full-resolution phone photo would allocate hundreds of megabytes before
     * the scale-down ever ran.
     */
    private fun decodeBounded(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > MAX_CUSTOM_ICON_PX * 2 ||
            bounds.outHeight / sample > MAX_CUSTOM_ICON_PX * 2
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    fun clearCustomIcon() {
        runCatching { customIconFile().delete() }
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

    private fun Bitmap.scaledToSquare(maxPx: Int): Bitmap {
        val side = minOf(width, height)
        val left = (width - side) / 2
        val top = (height - side) / 2
        val square = Bitmap.createBitmap(this, left, top, side, side)
        if (square === this) {
            return Bitmap.createScaledBitmap(this, maxPx, maxPx, true)
        }
        val scaled = Bitmap.createScaledBitmap(square, maxPx, maxPx, true)
        if (square !== this) square.recycle()
        return scaled
    }

    companion object {
        const val DEFAULT_ICON_ID = "default"
        private const val SHORTCUT_ID = "houri_custom_launcher"
        private const val MAX_CUSTOM_ICON_PX = 512
    }
}

package eu.kanade.tachiyomi.data.personalization

import tachiyomi.core.common.preference.SettingHost
import tachiyomi.core.common.preference.SettingKey
import tachiyomi.core.common.preference.SettingsRegistry

/**
 * Persisted keys for the app-identity screen. Kept here rather than inline in [AppIconManager]
 * because the settings framework treats the exact key string as part of the backup contract:
 * changing it needs a migration, and [SettingsRegistry] should know the key exists.
 */
object AppIdentitySettingKeys {

    val selectedIcon = SettingKey("pref_app_icon_id", AppIconManager.DEFAULT_ICON_ID)
    val customName = SettingKey("pref_app_display_name", "")

    val all = listOf(selectedIcon, customName)
}

/** Registers the identity keys so the global uniqueness check covers them. */
object AppIdentitySettingsHost : SettingHost {
    override val settingKeys = AppIdentitySettingKeys.all

    init {
        SettingsRegistry.register(this)
    }
}

package eu.kanade.presentation.more.settings.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.navigation3.runtime.NavKey
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.PrefsHorizontalPadding
import eu.kanade.tachiyomi.data.personalization.AppIconManager
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/**
 * Launcher identity: the built-in icon aliases plus the custom pinned-shortcut escape hatch.
 *
 * The screen is deliberately explicit about the Android limit - a custom image becomes a pinned
 * shortcut, not the installed app icon - because a picker that silently did something else would be
 * worse than not offering it.
 */
@Serializable
data object SettingsAppIdentityRoute : NavKey, SearchableRoute {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = KMR.strings.pref_app_identity

    @Composable
    override fun getPreferences(): List<Preference> {
        val manager = remember { globalAppGraph.appIconManager }
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        var iconTick by remember { mutableIntStateOf(0) }
        var customTick by remember { mutableIntStateOf(0) }
        var message by remember { mutableStateOf<String?>(null) }

        val activeIcon = remember(iconTick) { manager.activeIcon() }
        val customIcon = remember(customTick) { manager.customIconBitmap() }
        val customName by manager.customAppName.collectAsState()
        val fallbackName = stringResource(tachiyomi.i18n.MR.strings.app_name)
        val shortcutLabel = customName.ifBlank { fallbackName }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val imported = withContext(Dispatchers.IO) { manager.importCustomIcon(uri) }
                if (imported) {
                    customTick++
                } else {
                    context.toast(KMR.strings.pref_app_custom_icon_pick_failed)
                }
            }
        }

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(KMR.strings.pref_app_icon_section),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.CustomPreference(
                        title = stringResource(KMR.strings.pref_app_icon_section),
                        content = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = PrefsHorizontalPadding, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = stringResource(KMR.strings.pref_app_icon_summary),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                manager.builtInIcons.chunked(3).forEach { row ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        row.forEach { icon ->
                                            IconChoice(
                                                icon = icon,
                                                selected = activeIcon.id == icon.id,
                                                onClick = {
                                                    scope.launch {
                                                        val applied = withContext(Dispatchers.IO) {
                                                            manager.apply(icon)
                                                        }
                                                        if (applied) {
                                                            iconTick++
                                                            context.toast(KMR.strings.pref_app_icon_applied)
                                                        } else {
                                                            context.toast(KMR.strings.pref_app_icon_failed)
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                        repeat(3 - row.size) {
                                            Box(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(KMR.strings.pref_app_custom_icon),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.CustomPreference(
                        title = stringResource(KMR.strings.pref_app_custom_icon),
                        content = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = PrefsHorizontalPadding, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = stringResource(KMR.strings.pref_app_custom_icon_summary),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        tonalElevation = 2.dp,
                                        modifier = Modifier.size(72.dp),
                                    ) {
                                        customIcon?.let { bitmap ->
                                            Image(
                                                bitmap = bitmap.asImageBitmap(),
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                            )
                                        } ?: Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = "+",
                                                style = MaterialTheme.typography.headlineMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = stringResource(KMR.strings.pref_app_shortcut_summary),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Button(
                                            onClick = { picker.launch("image/*") },
                                        ) {
                                            Text(stringResource(KMR.strings.pref_app_custom_icon_pick))
                                        }
                                    }
                                }
                                if (customIcon != null || customName.isNotBlank()) {
                                    Button(
                                        onClick = {
                                            val pinned = manager.pinCustomShortcut(shortcutLabel)
                                            message = context.stringResource(
                                                if (pinned) {
                                                    KMR.strings.pref_app_shortcut_requested
                                                } else {
                                                    KMR.strings.pref_app_shortcut_unavailable
                                                },
                                            )
                                        },
                                    ) {
                                        Text(stringResource(KMR.strings.pref_app_shortcut_button))
                                    }
                                    TextButton(
                                        onClick = {
                                            manager.clearCustomIcon()
                                            customTick++
                                        },
                                    ) {
                                        Text(stringResource(KMR.strings.pref_app_custom_icon_remove))
                                    }
                                }
                                message?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(KMR.strings.pref_app_name_section),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = manager.customAppName,
                        title = stringResource(KMR.strings.pref_app_name),
                        subtitle = "%s",
                        allowEmpty = true,
                        validator = { it.length <= 40 && !it.contains('\n') },
                        onValueChanged = { true },
                    ),
                    Preference.PreferenceItem.InfoPreference(
                        title = stringResource(KMR.strings.pref_app_name_summary),
                    ),
                ),
            ),
        )
    }
}

/** One icon tile: the adaptive-icon preview, its name, and a check mark for the active choice. */
@Composable
private fun IconChoice(
    icon: AppIconManager.BuiltInIcon,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Adaptive icons are not a painterResource-supported drawable type on every platform level;
    // rasterising them here keeps the preview identical to what the launcher draws.
    val preview = remember(icon.preview) {
        runCatching {
            ContextCompat.getDrawable(context, icon.preview)?.toBitmap(width = 96, height = 96)
        }.getOrNull()
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                if (preview != null) {
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = stringResource(icon.labelRes),
                        modifier = Modifier.size(56.dp),
                    )
                } else {
                    Box(modifier = Modifier.size(56.dp))
                }
                if (selected) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Text(
                text = stringResource(icon.labelRes),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

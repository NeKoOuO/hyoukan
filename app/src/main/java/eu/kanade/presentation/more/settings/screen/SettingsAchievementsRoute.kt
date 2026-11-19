package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import eu.kanade.presentation.library.components.tierAnimatedBackground
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import mihon.app.di.globalAppGraph
import tachiyomi.domain.achievement.model.Achievement
import tachiyomi.domain.achievement.model.AchievementProgress
import tachiyomi.domain.achievement.model.AchievementTier
import tachiyomi.domain.achievement.model.Achievements
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Serializable
data object SettingsAchievementsRoute : NavKey, SearchableRoute {
    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = KMR.strings.label_achievements

    @Composable
    override fun getPreferences(): List<Preference> {
        return listOf(
            getHeader(),
            getGeneralGroup(),
            getAchievementsGridGroup(),
        )
    }

    @Composable
    private fun getHeader(): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = "",
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.CustomPreference(
                    title = "",
                    content = {
                        val prefs = remember { globalAppGraph.achievementPreferences }
                        val unlockedIds by prefs.unlockedAchievements().collectAsState()
                        val unlockedSet = rememberUnlockedSet(unlockedIds)
                        val countableTotal = Achievements.countable.size
                        val countableUnlocked = unlockedSet.count { Achievements.forId(it)?.countsTowardsProgress == true }
                        val stats = prefs.computeStats(countableTotal)
                        AchievementsHeaderCompact(
                            stats = stats,
                            unlocked = countableUnlocked,
                            total = countableTotal,
                        )
                    },
                ),
            ),
        )
    }

    @Composable
    private fun getGeneralGroup(): Preference.PreferenceGroup {
        val prefs = remember { globalAppGraph.achievementPreferences }
        val enabled by prefs.achievementsEnabled().collectAsState()
        val toastsEnabled by prefs.achievementToastsEnabled().collectAsState()
        val soundsEnabled by prefs.achievementSoundsEnabled().collectAsState()
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var showWipeFirst by remember { mutableStateOf(false) }
        var showWipeSecond by remember { mutableStateOf(false) }

        if (showWipeFirst) {
            AlertDialog(
                onDismissRequest = { showWipeFirst = false },
                title = { Text(stringResource(KMR.strings.achievements_reset_title)) },
                text = { Text(stringResource(KMR.strings.achievements_reset_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        showWipeFirst = false
                        showWipeSecond = true
                    }) { Text(stringResource(KMR.strings.achievements_continue)) }
                },
                dismissButton = {
                    TextButton(onClick = { showWipeFirst = false }) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }
        if (showWipeSecond) {
            AlertDialog(
                onDismissRequest = { showWipeSecond = false },
                title = { Text(stringResource(KMR.strings.achievements_really_sure_title)) },
                text = {
                    Text(
                        stringResource(
                            KMR.strings.achievements_really_sure_message,
                            Achievements.all.size,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showWipeSecond = false
                        scope.launch {
                            globalAppGraph.achievementManager.wipeWithConfirmation(true, true)
                            context.toast(KMR.strings.achievements_wiped_toast)
                        }
                    }) { Text(stringResource(KMR.strings.achievements_wipe_everything)) }
                },
                dismissButton = {
                    TextButton(onClick = { showWipeSecond = false }) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_general),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.achievementsEnabled(),
                    title = stringResource(KMR.strings.achievements_enable_title),
                    subtitle = stringResource(
                        if (enabled) {
                            KMR.strings.achievements_enabled_summary
                        } else {
                            KMR.strings.achievements_disabled_summary
                        },
                    ),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.achievementToastsEnabled(),
                    title = stringResource(KMR.strings.achievements_popups_title),
                    subtitle = stringResource(
                        if (toastsEnabled) {
                            KMR.strings.achievements_popups_on
                        } else {
                            KMR.strings.achievements_popups_off
                        },
                    ),
                    enabled = enabled,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.achievementSoundsEnabled(),
                    title = stringResource(KMR.strings.achievements_sounds_title),
                    subtitle = stringResource(
                        if (soundsEnabled) {
                            KMR.strings.achievements_sounds_on
                        } else {
                            KMR.strings.achievements_sounds_off
                        },
                    ),
                    enabled = enabled,
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(KMR.strings.achievements_wipe_data),
                    subtitle = stringResource(KMR.strings.achievements_wipe_data_summary),
                    onClick = { showWipeFirst = true },
                    enabled = enabled,
                ),
            ),
        )
    }

    @Composable
    private fun getAchievementsGridGroup(): Preference.PreferenceGroup {
        val prefs = remember { globalAppGraph.achievementPreferences }
        val unlockedIds by prefs.unlockedAchievements().collectAsState()
        val unlockedSet = rememberUnlockedSet(unlockedIds)
        val animationsEnabled by prefs.animationsEnabled().collectAsState()
        val all = remember { Achievements.all }
        var animationsReady by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(300)
            animationsReady = true
        }
        val canAnimate = animationsEnabled && animationsReady

        return Preference.PreferenceGroup(
            title = stringResource(KMR.strings.achievements_all_title),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.CustomPreference(
                    title = "",
                    content = {
                        // A plain FlowRow, not a LazyVerticalGrid: this page already scrolls as a
                        // settings list, and an 800dp inner scroller meant two scrolling surfaces
                        // fighting each other plus a fixed band of empty space on small screens.
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            maxItemsInEachRow = 2,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            all.forEach { ach ->
                                val isUnlocked = ach.id in unlockedSet
                                val display = remember(ach, isUnlocked) {
                                    ach.copy(unlockedAt = if (isUnlocked) 1L else null)
                                }
                                val progress = rememberTierProgress(canAnimate && isUnlocked, ach.tier)
                                Box(modifier = Modifier.weight(1f)) {
                                    AchievementCardSimple(
                                        achievement = display,
                                        isUnlocked = isUnlocked,
                                        animationsEnabled = canAnimate,
                                        progress = progress,
                                    )
                                }
                            }
                        }
                    },
                ),
            ),
        )
    }

    @Composable
    private fun rememberUnlockedSet(raw: String): Set<String> {
        if (raw.isBlank()) return emptySet()
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
    }

    @Composable
    private fun AchievementsHeaderCompact(
        stats: tachiyomi.domain.achievement.model.AchievementStats,
        unlocked: Int,
        total: Int,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(text = stringResource(KMR.strings.label_achievements), style = MaterialTheme.typography.titleLarge)
                    Text(text = "$unlocked/$total", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
                Text(text = stringResource(KMR.strings.achievements_rank_prefix, stats.rank), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                    StatChipCompact(label = stringResource(KMR.strings.achievements_stat_read), value = "${stats.organicChaptersRead}")
                    StatChipCompact(label = stringResource(KMR.strings.achievements_stat_finished), value = "${stats.mangaFinished}")
                    StatChipCompact(label = stringResource(KMR.strings.achievements_stat_library), value = "${stats.libraryCount}")
                    StatChipCompact(label = stringResource(KMR.strings.achievements_stat_hours), value = "${stats.readingTimeMinutes / 60}")
                    if (stats.negativeUnlocked > 0) {
                        StatChipCompact(label = stringResource(KMR.strings.achievements_stat_negative), value = "${stats.negativeUnlocked}")
                    }
                }
                if (stats.backlogCount > 0) {
                    Text(
                        text = stringResource(KMR.strings.achievements_backlog, stats.backlogCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    @Composable
    private fun StatChipCompact(label: String, value: String) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = value, style = MaterialTheme.typography.titleMedium)
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun rememberTierProgress(enabled: Boolean, tier: AchievementTier): Float {
        return eu.kanade.presentation.library.components.rememberTierProgress(enabled, tier)
    }

    @Composable
    private fun AchievementCardSimple(achievement: Achievement, isUnlocked: Boolean, animationsEnabled: Boolean, progress: Float) {
        val prefs = globalAppGraph.achievementPreferences
        val hasProgress = !isUnlocked && AchievementProgress.hasProgress(achievement.id)
        val progressValue = if (hasProgress) AchievementProgress.progressFor(achievement.id, prefs) else 0f
        val progressLabel = if (hasProgress) AchievementProgress.labelFor(achievement.id, prefs) else null
        val alpha = if (isUnlocked) 1f else 0.45f
        val tierColor = when (achievement.tier) {
            AchievementTier.BRONZE -> MaterialTheme.colorScheme.secondary
            AchievementTier.SILVER -> MaterialTheme.colorScheme.tertiary
            AchievementTier.GOLD -> MaterialTheme.colorScheme.primary
            AchievementTier.PLATINUM -> MaterialTheme.colorScheme.primary
            AchievementTier.LEGENDARY -> MaterialTheme.colorScheme.error
            AchievementTier.MYTHIC -> MaterialTheme.colorScheme.error
            AchievementTier.ULTIMATE -> MaterialTheme.colorScheme.error
        }
        val animatedModifier = if (isUnlocked && animationsEnabled && !achievement.isNegative) {
            when (achievement.tier) {
                AchievementTier.GOLD, AchievementTier.PLATINUM, AchievementTier.LEGENDARY, AchievementTier.MYTHIC, AchievementTier.ULTIMATE ->
                    Modifier.tierAnimatedBackground(achievement.tier, isUnlocked, achievement.isNegative, achievement.isSecret, animationsEnabled, progress)
                else -> Modifier
            }
        } else {
            Modifier
        }
        val cardModifier = Modifier
            .fillMaxWidth()
            .alpha(alpha)
            .then(animatedModifier)
        androidx.compose.material3.Card(
            modifier = cardModifier,
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = if (animatedModifier != Modifier) MaterialTheme.colorScheme.surface.copy(alpha = 0.85f) else MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = if (isUnlocked) 2.dp else 0.dp),
        ) {
            androidx.compose.foundation.layout.Box {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = achievement.displayIcon, style = MaterialTheme.typography.titleLarge)
                    Text(text = achievement.displayTitle, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                    Text(text = achievement.displayDescription, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 4.dp))
                    Text(text = achievement.tier.name, style = MaterialTheme.typography.labelSmall, color = tierColor)
                    if (hasProgress && progressLabel != null) {
                        LinearProgressIndicator(progress = { progressValue }, modifier = Modifier.fillMaxWidth().height(6.dp))
                        Text(text = progressLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (achievement.isSecret) {
                        Text(
                            text = stringResource(
                                if (isUnlocked) {
                                    KMR.strings.achievements_secret_unlocked
                                } else {
                                    KMR.strings.achievements_secret
                                },
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (achievement.isNegative) {
                        Text(
                            text = stringResource(KMR.strings.achievements_negative_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (achievement.isRotating) {
                        Text(
                            text = stringResource(KMR.strings.achievements_rotating_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        }
    }
}

package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.Serializable
import mihon.app.di.globalAppGraph
import mihon.core.navigation.util.LocalBackButtonVisibility
import mihon.core.navigation.util.LocalBackStack
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

@Serializable
data object SettingsTranslationSessionsRoute : NavKey

@Composable
fun SettingsTranslationSessionsScreen() {
    val status = remember { globalAppGraph.mtlStatus }
    val manager = remember { globalAppGraph.mtlController }
    val chapters by status.chapters.collectAsState()
    val backStack = LocalBackStack.current
    val backVisibility = LocalBackButtonVisibility.current

    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(KMR.strings.translation_sessions_title),
                navigateUp = {
                    backStack.removeLastOrNull()
                    Unit
                }.takeIf { backVisibility },
                actions = {
                    AppBarActions(
                        persistentListOf<AppBar.AppBarAction>(
                            AppBar.Action(
                                title = stringResource(KMR.strings.translation_sessions_clear_all),
                                icon = Icons.Filled.Close,
                                onClick = { manager.clearAll() },
                            ),
                        ),
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        TranslationSessionsContent(
            chapters = chapters,
            contentPadding = contentPadding,
            onStop = manager::cancelChapter,
            onPause = manager::pauseChapter,
            onResume = manager::resumeChapter,
            onRetry = manager::retryChapter,
        )
    }
}

@Composable
private fun TranslationSessionsContent(
    chapters: Map<Pair<Long, Long>, mtl.status.StatusHub.ChapterStatus>,
    contentPadding: PaddingValues,
    onStop: (Long, Long) -> Unit,
    onPause: (Long, Long) -> Unit,
    onResume: (Long, Long) -> Unit,
    onRetry: (Long, Long) -> Unit,
) {
    if (chapters.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(KMR.strings.translation_sessions_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(KMR.strings.translation_sessions_empty),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text = stringResource(KMR.strings.translation_sessions_summary),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(chapters.values.toList().sortedByDescending { it.lastUpdated }) { chapter ->
            val total = chapter.totalPages
            val done = chapter.translatedCount
            val errors = chapter.failedCount
            val isTranslating = chapter.isTranslating
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(
                            KMR.strings.translation_sessions_entry,
                            chapter.mangaId,
                            chapter.chapterId,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(
                            KMR.strings.translation_sessions_progress,
                            done,
                            total,
                            errors,
                            stringResource(
                                if (isTranslating) {
                                    KMR.strings.translation_sessions_translating
                                } else {
                                    KMR.strings.translation_sessions_idle
                                },
                            ),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (total > 0) {
                        LinearProgressIndicator(
                            progress = { (done.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (!chapter.lastError.isNullOrBlank()) {
                        Text(
                            text = chapter.lastError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { onStop(chapter.mangaId, chapter.chapterId) }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(KMR.strings.translation_sessions_stop),
                            )
                        }
                        IconButton(onClick = { onPause(chapter.mangaId, chapter.chapterId) }) {
                            Icon(
                                Icons.Filled.Pause,
                                contentDescription = stringResource(KMR.strings.translation_sessions_pause),
                            )
                        }
                        IconButton(onClick = { onResume(chapter.mangaId, chapter.chapterId) }) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = stringResource(KMR.strings.translation_sessions_resume),
                            )
                        }
                        IconButton(onClick = { onRetry(chapter.mangaId, chapter.chapterId) }) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(KMR.strings.translation_sessions_retry),
                            )
                        }
                    }
                }
            }
        }
        item {
            // Extra space so the last card clears the navigation bar.
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(bottom = 16.dp))
        }
    }
}

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
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.Serializable
import mihon.app.di.globalAppGraph
import mihon.core.navigation.util.LocalBackButtonVisibility
import mihon.core.navigation.util.LocalBackStack
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import java.io.File

@Serializable
data object SettingsMangaTranslatorCacheRoute : NavKey

// KMK --> Saved translations live in the spine's disk page store under
// filesDir/mtl_pages/saved/<safeKey>, where safeKey starts with "<mangaId>_<chapterId>_…",
// so per-manga grouping is recovered from the key prefix. The .title sidecar rides next
// to the translated page (safeKey + ".title") and names the entry after library removal.
private data class CacheEntry(val mangaId: Long, val title: String?, val chapters: Int, val pages: Int, val sizeKb: Long)
// KMK <--

private fun savedPagesDir(context: android.content.Context): File =
    File(context.filesDir, "mtl_pages/saved")

@Composable
fun SettingsMangaTranslatorCacheScreen() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val backVisibility = LocalBackButtonVisibility.current
    var entries by remember { mutableStateOf<List<CacheEntry>>(emptyList()) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTick) {
        entries = withIOContext { listSavedEntries(context) }
    }

    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(KMR.strings.manga_translator_cache_title),
                navigateUp = {
                    backStack.removeLastOrNull()
                    Unit
                }.takeIf { backVisibility },
                actions = {
                    AppBarActions(
                        persistentListOf<AppBar.AppBarAction>(
                            AppBar.Action(
                                title = stringResource(KMR.strings.manga_translator_cache_clear_all),
                                icon = Icons.Outlined.DeleteSweep,
                                onClick = {
                                    globalAppGraph.mtlPageStore.clear()
                                    context.toast(KMR.strings.manga_translator_cache_cleared)
                                    refreshTick++
                                },
                            ),
                        ),
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        CacheContent(
            entries = entries,
            contentPadding = contentPadding,
            onWipe = { entry ->
                wipeSavedPages(context, entry.mangaId)
                context.toast(
                    context.stringResource(
                        KMR.strings.manga_translator_cache_wiped,
                        entry.title ?: entry.mangaId.toString(),
                    ),
                )
                refreshTick++
            },
        )
    }
}

@Composable
private fun CacheContent(
    entries: List<CacheEntry>,
    contentPadding: PaddingValues,
    onWipe: (CacheEntry) -> Unit,
) {
    if (entries.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(KMR.strings.manga_translator_cache_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(KMR.strings.manga_translator_cache_empty),
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
                text = stringResource(KMR.strings.manga_translator_cache_summary),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Text(
                text = stringResource(KMR.strings.manga_translator_cache_count, entries.size),
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        items(entries, key = { it.mangaId }) { entry ->
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
                        text = entry.title ?: stringResource(
                            KMR.strings.manga_translator_cache_manga,
                            entry.mangaId,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(
                            KMR.strings.manga_translator_cache_entry_summary,
                            entry.chapters,
                            entry.pages,
                            entry.sizeKb,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onWipe(entry) }) {
                            Text(stringResource(KMR.strings.manga_translator_cache_wipe))
                        }
                    }
                }
            }
        }
        item {
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(bottom = 16.dp))
        }
    }
}

private suspend fun listSavedEntries(context: android.content.Context): List<CacheEntry> {
    val dir = savedPagesDir(context)
    if (!dir.isDirectory) return emptyList()
    return dir.listFiles()
        ?.filter { it.isFile && !it.name.endsWith(".title") && !it.name.endsWith(".tmp") }
        ?.mapNotNull { file ->
            file.name.substringBefore('_').toLongOrNull()?.let { mangaId -> mangaId to file }
        }
        ?.groupBy({ it.first }, { it.second })
        ?.map { (mangaId, files) ->
            CacheEntry(
                mangaId = mangaId,
                title = resolveTitle(context, mangaId, files),
                chapters = files.mapNotNull { it.name.substringAfter('_').substringBefore('_').takeIf(String::isNotBlank) }.distinct().size,
                pages = files.size,
                sizeKb = files.sumOf { it.length() } / 1024,
            )
        }
        ?.sortedByDescending { it.pages }
        ?: emptyList()
}

private fun wipeSavedPages(context: android.content.Context, mangaId: Long) {
    savedPagesDir(context).listFiles()
        ?.filter { it.name.startsWith("${mangaId}_") }
        ?.forEach { runCatching { it.delete() } }
}

// KMK --> Sidecar first (survives library removal), then the database (covers caches
// saved before sidecars existed), then null so the caller falls back to the raw id.
private suspend fun resolveTitle(context: android.content.Context, mangaId: Long, files: List<File>): String? {
    files.firstNotNullOfOrNull { page ->
        val sidecar = File(page.absolutePath + ".title")
        sidecar.takeIf { it.isFile }?.readText()?.takeIf(String::isNotBlank)
    }?.let { return it }
    return runCatching { globalAppGraph.getManga.await(mangaId)?.title }.getOrNull()
}
// KMK <--

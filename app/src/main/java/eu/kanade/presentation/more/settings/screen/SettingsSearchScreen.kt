package eu.kanade.presentation.more.settings.screen

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import eu.kanade.presentation.components.UpIcon
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.filterVisible
import mihon.core.navigation.util.LocalBackStack
import mihon.core.navigation.util.popUntil
import mihon.core.navigation.util.replace
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.runOnEnterKeyPressed

@Composable
fun SettingsSearchScreen() {
    val backStack = LocalBackStack.current
    val softKeyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    // Hide keyboard on change screen
    DisposableEffect(Unit) {
        onDispose {
            softKeyboardController?.hide()
        }
    }

    // Hide keyboard on outside text field is touched
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            focusManager.clearFocus()
        }
    }

    // Request text field focus on launch
    LaunchedEffect(focusRequester) {
        focusRequester.requestFocus()
    }

    val textFieldState = rememberTextFieldState()
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        val canPop = remember { backStack.size > 1 }
                        if (canPop) {
                            IconButton(onClick = backStack::removeLastOrNull) {
                                UpIcon()
                            }
                        }
                    },
                    title = {
                        BasicTextField(
                            state = textFieldState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .runOnEnterKeyPressed(action = focusManager::clearFocus),
                            textStyle = MaterialTheme.typography.bodyLarge
                                .copy(color = MaterialTheme.colorScheme.onSurface),
                            lineLimits = TextFieldLineLimits.SingleLine,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            onKeyboardAction = { focusManager.clearFocus() },
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorator = {
                                if (textFieldState.text.isEmpty()) {
                                    Text(
                                        text = stringResource(MR.strings.action_search_settings),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                                it()
                            },
                        )
                    },
                    actions = {
                        if (textFieldState.text.isNotEmpty()) {
                            IconButton(onClick = { textFieldState.clearText() }) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                )
                HorizontalDivider()
            }
        },
    ) { contentPadding ->
        SearchResult(
            searchKey = textFieldState.text.toString(),
            listState = listState,
            contentPadding = contentPadding,
        ) { result ->
            SearchableSettings.highlightKey = result.highlightKey
            val targetRoute = result.route
            val existingIndex = backStack.indexOfFirst { it == targetRoute }
            if (existingIndex >= 0) {
                backStack.popUntil { it == targetRoute }
            } else {
                backStack.replace(targetRoute)
            }
        }
    }
}

@Composable
private fun SearchResult(
    searchKey: String,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(),
    onItemClick: (SearchResultItem) -> Unit,
) {
    if (searchKey.isEmpty()) return

    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr

    val index = getIndex()
    val result by produceState<List<SearchResultItem>?>(initialValue = null, searchKey) {
        val query = searchKey.trim()
        val tokens = query.lowercase().split(WHITESPACE).filter { it.isNotBlank() }
        if (tokens.isEmpty()) {
            value = emptyList()
            return@produceState
        }

        fun matches(text: String): Boolean = tokens.all { text.contains(it, ignoreCase = true) }

        val hits = mutableListOf<ScoredResultItem>()

        index.forEach { settingsData ->
            // The screen itself is a result too. Searching "webhook" should offer the screen even
            // when none of its rows carries the word, and searching a category name should not
            // require guessing which row inside it mentions the thing.
            if (matches(settingsData.title)) {
                hits += ScoredResultItem(
                    item = SearchResultItem(
                        route = settingsData.route,
                        title = settingsData.title,
                        breadcrumbs = "",
                        highlightKey = null,
                    ),
                    score = if (settingsData.title.equals(query, ignoreCase = true)) 0 else 2,
                )
            }

            settingsData.contents.asSequence()
                .flatMap { preference ->
                    when (preference) {
                        is Preference.PreferenceGroup -> preference.preferenceItems.asSequence()
                            .map { item ->
                                Pair<String?, Preference.PreferenceItem<*, *>>(preference.title, item)
                            }
                        is Preference.PreferenceItem<*, *> ->
                            sequenceOf(Pair<String?, Preference.PreferenceItem<*, *>>(null, preference))
                    }
                }
                // Info rows have no action to land on.
                .filterNot { it.second is Preference.PreferenceItem.InfoPreference }
                .forEach { (categoryTitle, preference) ->
                    val title = preference.title
                    if (title.isBlank()) return@forEach
                    val subtitle = preference.subtitle?.toString().orEmpty()
                    val haystack = buildString {
                        append(title)
                        append(' ')
                        append(subtitle)
                        append(' ')
                        append(settingsData.title)
                        if (categoryTitle != null) {
                            append(' ')
                            append(categoryTitle)
                        }
                    }
                    if (!matches(haystack)) return@forEach

                    val score = when {
                        title.equals(query, ignoreCase = true) -> 0
                        title.startsWith(query, ignoreCase = true) -> 1
                        matches(title) -> 2
                        categoryTitle != null && matches(categoryTitle) -> 3
                        else -> 4
                    }
                    hits += ScoredResultItem(
                        item = SearchResultItem(
                            route = settingsData.route,
                            title = title,
                            breadcrumbs = getLocalizedBreadcrumb(
                                path = settingsData.title,
                                node = categoryTitle,
                                isLtr = isLtr,
                            ),
                            highlightKey = title,
                        ),
                        score = score,
                    )
                }
        }

        value = hits
            .sortedWith(compareBy({ it.score }, { it.item.title.length }))
            .distinctBy { it.item.route to it.item.title }
            .take(SEARCH_RESULT_LIMIT)
            .map { it.item }
    }

    Crossfade(targetState = result) {
        when {
            it == null -> {}
            it.isEmpty() -> {
                EmptyScreen(stringResource(MR.strings.no_results_found))
            }
            else -> {
                LazyColumn(
                    modifier = modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = contentPadding,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    items(
                        items = it,
                        key = { i -> "settings-search-${i.route.hashCode()}-${i.title.hashCode()}" },
                    ) { item ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onItemClick(item) }
                                .padding(horizontal = 24.dp, vertical = 14.dp),
                        ) {
                            Text(
                                text = item.title,
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 1,
                                fontWeight = FontWeight.Normal,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (item.breadcrumbs.isNotBlank()) {
                                Text(
                                    text = item.breadcrumbs,
                                    modifier = Modifier.paddingFromBaseline(top = 16.dp),
                                    maxLines = 1,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
@NonRestartableComposable
private fun getIndex() = settingScreens
    // SY -->
    .filter(SearchableSettings::isEnabled)
    // SY <--
    .map { screen ->
        SettingsData(
            title = stringResource(screen.getTitleRes()),
            route = screen,
            // KMK --> resolve visibility here (composable context) so the search index
            // and the rendered screen agree on which rows exist.
            contents = screen.getPreferences().filterVisible(),
            // KMK <--
        )
    }

private fun getLocalizedBreadcrumb(path: String, node: String?, isLtr: Boolean): String {
    return if (node == null) {
        path
    } else {
        if (isLtr) {
            // This locale reads left to right.
            "$path > $node"
        } else {
            // This locale reads right to left.
            "$node < $path"
        }
    }
}

private val settingScreens: List<SearchableRoute> = SettingsCatalog.searchableScreens

private data class SettingsData(
    val title: String,
    val route: NavKey,
    val contents: List<Preference>,
)

private data class SearchResultItem(
    val route: NavKey,
    val title: String,
    val breadcrumbs: String,
    val highlightKey: String?,
)

private data class ScoredResultItem(
    val item: SearchResultItem,
    val score: Int,
)

private val WHITESPACE = Regex("\\s+")

/**
 * A result cap, not a "top 10": a settings search that hides a matching row behind an arbitrary
 * cut-off reads as broken. The list itself is cheap once the index exists.
 */
private const val SEARCH_RESULT_LIMIT = 50

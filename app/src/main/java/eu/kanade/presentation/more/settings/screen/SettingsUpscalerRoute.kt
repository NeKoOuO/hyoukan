package eu.kanade.presentation.more.settings.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.PrefsHorizontalPadding
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.io.ByteArrayOutputStream
import java.util.Locale
import androidx.compose.runtime.collectAsState as flowCollectAsState

/**
 * The unified upscaling screen: modes, accelerator, catalog, downloads, imports, per-category
 * defaults, prefetch, live status. Backed entirely by the spine — every row reads config or the
 * status hub, never the old engine's private surface.
 *
 * Rows are split into groups by the decision they belong to (quality, performance, storage,
 * models, categories) rather than dumped into one list: the previous single group made a
 * settings screen where the master switch, a model download and a category override were all
 * siblings, with raw status text in between. Anything a reader acts on stays a normal row.
 */
@Serializable
data object SettingsUpscalerRoute : NavKey, SearchableRoute {
    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = KMR.strings.pref_upscale_title

    @Composable
    override fun getPreferences(): List<Preference> {
        val prefs = remember { globalAppGraph.upscalePreferences }
        val controller = remember { globalAppGraph.upscaleController }
        val store = remember { globalAppGraph.upscaleMangaStore }
        val models = remember { controller.models }

        val enabled by prefs.enabled().collectAsState()
        val mode by prefs.mode().collectAsState()
        val simpleAlgo by prefs.simpleAlgo().collectAsState()
        val preset by prefs.preset().collectAsState()
        val backend by prefs.backend().collectAsState()
        val model by prefs.model().collectAsState()
        val factor by prefs.upscaleFactor().collectAsState()
        val cacheEnabled by prefs.cacheEnabled().collectAsState()
        val prefetchDepth by prefs.prefetchDepth().collectAsState()
        val tileRefinement by prefs.tileRefinement().collectAsState()

        val modelStatus by models.status.flowCollectAsState()
        val hubStatus by controller.status.totals.flowCollectAsState()
        val backendStatuses = remember(controller) {
            controller.backends.associate { it.id to it.readiness }
        }
        // Recompose when any backend's readiness flips.
        val readinessTick = backendStatuses.values
            .map { it.flowCollectAsState().value }
            .joinToString { it::class.simpleName.orEmpty() }

        var cacheRefreshTick by remember { mutableIntStateOf(0) }
        val cacheBytes = remember(cacheEnabled, enabled, cacheRefreshTick, hubStatus.served) {
            runCatching { controller.cacheSizeBytes() }.getOrDefault(0L)
        }
        val cacheCount = remember(cacheEnabled, enabled, cacheRefreshTick, hubStatus.served) {
            runCatching { controller.cacheFileCount() }.getOrDefault(0)
        }
        val cacheSummary = remember(cacheBytes, cacheCount) {
            formatCacheSummary(cacheBytes, cacheCount)
        }

        val categoriesState = remember { mutableStateOf<List<Category>>(emptyList()) }
        LaunchedEffect(Unit) {
            categoriesState.value = runCatching { globalAppGraph.getCategories.await() }.getOrDefault(emptyList())
        }
        val categories = categoriesState.value
        val categoryVersion by store.categoryOverridesVersion.flowCollectAsState()
        val categoryStates = remember(categories, categoryVersion) {
            categories.map { it.id to store.categoryOverride(it.id) }
        }

        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        var importError by remember { mutableStateOf<String?>(null) }
        val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch(Dispatchers.IO) {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                        val out = ByteArrayOutputStream()
                        stream.copyTo(out, 64 * 1024)
                        out.toByteArray()
                    }
                    val name = uri.lastPathSegment ?: "model.onnx"
                    if (bytes == null) {
                        importError = context.stringResource(KMR.strings.pref_upscale_import_failed)
                    } else {
                        when (val result = models.importOnnx(name, bytes)) {
                            is upscale.model.UpscaleModelManager.ImportResult.Ok -> importError = null
                            is upscale.model.UpscaleModelManager.ImportResult.Rejected -> importError = result.reason
                        }
                    }
                } catch (e: Exception) {
                    importError = e.message ?: context.stringResource(KMR.strings.pref_upscale_import_failed)
                }
            }
        }

        val result = buildList<Preference> {
            // ---- master ----
            add(
                Preference.PreferenceGroup(
                    title = stringResource(MR.strings.pref_category_general),
                    preferenceItems = persistentListOf(
                        Preference.PreferenceItem.SwitchPreference(
                            preference = prefs.enabled(),
                            title = stringResource(KMR.strings.pref_upscale_enabled),
                            subtitle = if (enabled) {
                                stringResource(KMR.strings.pref_upscale_enabled_summary)
                            } else {
                                stringResource(KMR.strings.pref_upscale_disabled_summary)
                            },
                        ),
                        Preference.PreferenceItem.SwitchPreference(
                            preference = prefs.defaultForNewEntries(),
                            title = stringResource(KMR.strings.pref_upscale_default_for_new),
                            subtitle = stringResource(KMR.strings.pref_upscale_default_for_new_summary),
                            enabled = enabled,
                        ),
                    ),
                ),
            )

            // ---- quality ----
            val qualityItems = persistentListOf<Preference.PreferenceItem<out Any, out Any>>(
                Preference.PreferenceItem.ListPreference(
                    preference = prefs.mode(),
                    entries = persistentMapOf(
                        "AUTO" to stringResource(KMR.strings.pref_upscale_mode_auto),
                        "NATIVE" to stringResource(KMR.strings.pref_upscale_mode_native),
                        "SIMPLE" to stringResource(KMR.strings.pref_upscale_mode_simple),
                    ),
                    title = stringResource(KMR.strings.pref_upscale_mode),
                    subtitle = when (mode) {
                        "SIMPLE" -> stringResource(KMR.strings.pref_upscale_mode_simple_summary)
                        "NATIVE" -> stringResource(KMR.strings.pref_upscale_mode_native_summary)
                        else -> stringResource(KMR.strings.pref_upscale_mode_auto_summary)
                    },
                    enabled = enabled,
                ),
            ).toMutableList()

            if (mode == "SIMPLE") {
                qualityItems += Preference.PreferenceItem.ListPreference(
                    preference = prefs.simpleAlgo(),
                    entries = persistentMapOf(
                        "BICUBIC" to stringResource(KMR.strings.pref_upscale_algo_bicubic),
                        "BILINEAR" to stringResource(KMR.strings.pref_upscale_algo_bilinear),
                        "NEAREST" to stringResource(KMR.strings.pref_upscale_algo_nearest),
                    ),
                    title = stringResource(KMR.strings.pref_upscale_simple_algo),
                    subtitle = when (simpleAlgo) {
                        "BICUBIC" -> stringResource(KMR.strings.pref_upscale_algo_bicubic)
                        "BILINEAR" -> stringResource(KMR.strings.pref_upscale_algo_bilinear)
                        else -> stringResource(KMR.strings.pref_upscale_algo_nearest)
                    },
                    enabled = enabled,
                )
            } else {
                qualityItems += Preference.PreferenceItem.ListPreference(
                    preference = prefs.preset(),
                    entries = persistentMapOf(
                        "FAST" to stringResource(KMR.strings.pref_upscale_preset_fast),
                        "BALANCED" to stringResource(KMR.strings.pref_upscale_preset_balanced),
                        "HIGH" to stringResource(KMR.strings.pref_upscale_preset_high),
                    ),
                    title = stringResource(KMR.strings.pref_upscale_preset),
                    subtitle = when (preset) {
                        "FAST" -> stringResource(KMR.strings.pref_upscale_preset_fast) + " — " +
                            stringResource(KMR.strings.pref_upscale_preset_fast_summary)
                        "HIGH" -> stringResource(KMR.strings.pref_upscale_preset_high) + " — " +
                            stringResource(KMR.strings.pref_upscale_preset_high_summary)
                        else -> stringResource(KMR.strings.pref_upscale_preset_balanced) + " — " +
                            stringResource(KMR.strings.pref_upscale_preset_balanced_summary)
                    },
                    enabled = enabled,
                )
                qualityItems += Preference.PreferenceItem.ListPreference(
                    preference = prefs.backend(),
                    entries = persistentMapOf(
                        "AUTO" to stringResource(KMR.strings.pref_upscale_backend_auto),
                        "VULKAN" to stringResource(KMR.strings.pref_upscale_backend_vulkan),
                        "NPU" to stringResource(KMR.strings.pref_upscale_backend_npu),
                        "CPU" to stringResource(KMR.strings.pref_upscale_backend_cpu),
                    ),
                    title = stringResource(KMR.strings.pref_upscale_backend),
                    subtitle = if (backend == "AUTO") {
                        stringResource(KMR.strings.pref_upscale_backend_auto) + " — " +
                            stringResource(KMR.strings.pref_upscale_backend_auto_summary)
                    } else {
                        when (backend) {
                            "VULKAN" -> stringResource(KMR.strings.pref_upscale_backend_vulkan)
                            "NPU" -> stringResource(KMR.strings.pref_upscale_backend_npu)
                            else -> stringResource(KMR.strings.pref_upscale_backend_cpu)
                        } + readinessSuffix(backendStatuses, backend)
                    },
                    enabled = enabled,
                )
                qualityItems += Preference.PreferenceItem.ListPreference(
                    preference = prefs.model(),
                    entries = persistentMapOf(
                        "REAL_CUGAN" to stringResource(KMR.strings.pref_upscale_model_real_cugan),
                        "REAL_ESRGAN" to stringResource(KMR.strings.pref_upscale_model_real_esrgan),
                        "WAIFU2X" to stringResource(KMR.strings.pref_upscale_model_waifu2x),
                        "IMPORTED" to stringResource(KMR.strings.pref_upscale_model_imported),
                    ),
                    title = stringResource(KMR.strings.pref_upscale_model),
                    subtitle = when (model) {
                        "REAL_CUGAN" -> stringResource(KMR.strings.pref_upscale_model_real_cugan)
                        "REAL_ESRGAN" -> stringResource(KMR.strings.pref_upscale_model_real_esrgan)
                        "WAIFU2X" -> stringResource(KMR.strings.pref_upscale_model_waifu2x)
                        else -> stringResource(KMR.strings.pref_upscale_model_imported)
                    },
                    enabled = enabled,
                )
            }

            qualityItems += Preference.PreferenceItem.SliderPreference(
                value = (factor * 10).toInt().coerceIn(10, 40),
                title = stringResource(KMR.strings.pref_upscale_factor),
                subtitle = if (factor < 1.02f) {
                    stringResource(KMR.strings.pref_upscale_factor_identity)
                } else {
                    String.format(Locale.ROOT, "%.1f×", factor)
                },
                valueString = String.format(Locale.ROOT, "%.1f×", factor),
                valueRange = 10..40,
                steps = 29,
                enabled = enabled,
                onValueChanged = { v -> prefs.upscaleFactor().set(v / 10f) },
            )

            add(
                Preference.PreferenceGroup(
                    title = stringResource(KMR.strings.pref_upscale_group_quality),
                    preferenceItems = qualityItems.toImmutableList(),
                ),
            )

            // ---- performance ----
            add(
                Preference.PreferenceGroup(
                    title = stringResource(KMR.strings.pref_upscale_group_performance),
                    preferenceItems = persistentListOf(
                        Preference.PreferenceItem.SliderPreference(
                            value = prefetchDepth,
                            title = stringResource(KMR.strings.pref_upscale_prefetch),
                            subtitle = if (prefetchDepth == 0) {
                                stringResource(KMR.strings.pref_upscale_prefetch_off)
                            } else {
                                stringResource(KMR.strings.pref_upscale_prefetch_summary, prefetchDepth)
                            },
                            valueString = if (prefetchDepth == 0) {
                                stringResource(MR.strings.disabled)
                            } else {
                                prefetchDepth.toString()
                            },
                            valueRange = 0..5,
                            steps = 4,
                            enabled = enabled,
                            onValueChanged = { v -> prefs.prefetchDepth().set(v) },
                        ),
                        Preference.PreferenceItem.SwitchPreference(
                            preference = prefs.tileRefinement(),
                            title = stringResource(KMR.strings.pref_upscale_tile_refinement),
                            subtitle = stringResource(KMR.strings.pref_upscale_tile_refinement_summary),
                            enabled = enabled,
                        ),
                    ),
                ),
            )

            // ---- cache + status ----
            add(
                Preference.PreferenceGroup(
                    title = stringResource(KMR.strings.pref_upscale_group_cache),
                    preferenceItems = persistentListOf(
                        Preference.PreferenceItem.SwitchPreference(
                            preference = prefs.cacheEnabled(),
                            title = stringResource(KMR.strings.pref_upscale_cache),
                            subtitle = if (cacheEnabled) {
                                if (cacheBytes == 0L) {
                                    stringResource(KMR.strings.pref_upscale_cache_summary)
                                } else {
                                    cacheSummary
                                }
                            } else {
                                stringResource(KMR.strings.pref_upscale_cache_summary) +
                                    " (" + stringResource(MR.strings.disabled) + ")"
                            },
                            enabled = enabled,
                        ),
                        Preference.PreferenceItem.TextPreference(
                            title = stringResource(KMR.strings.pref_upscale_clear_cache),
                            subtitle = if (cacheBytes > 0) {
                                cacheSummary
                            } else {
                                stringResource(KMR.strings.pref_upscale_clear_cache_summary)
                            },
                            onClick = {
                                controller.clearCache()
                                cacheRefreshTick++
                            },
                            enabled = enabled && cacheEnabled,
                        ),
                        Preference.PreferenceItem.CustomPreference(
                            title = stringResource(KMR.strings.pref_upscale_live_status),
                            content = {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = PrefsHorizontalPadding, vertical = 8.dp),
                                ) {
                                    Text(
                                        text = stringResource(KMR.strings.pref_upscale_live_status),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        text = stringResource(
                                            KMR.strings.pref_upscale_status_summary,
                                            hubStatus.served,
                                            hubStatus.servedFromCache,
                                            hubStatus.fellBack,
                                            hubStatus.failed,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (hubStatus.served > 0 && hubStatus.totalInferenceMs > 0) {
                                        Text(
                                            text = stringResource(
                                                KMR.strings.pref_upscale_status_inference,
                                                hubStatus.totalInferenceMs / hubStatus.served,
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (readinessTick.isNotEmpty()) {
                                        controller.backends.forEach { backend0 ->
                                            val ready = when (backend0.readiness.value) {
                                                is upscale.core.Readiness.Ready -> true
                                                is upscale.core.Readiness.Unavailable -> false
                                            }
                                            Text(
                                                text = if (ready) {
                                                    stringResource(
                                                        KMR.strings.pref_upscale_status_backend_ready,
                                                        backend0.displayName,
                                                    )
                                                } else {
                                                    stringResource(
                                                        KMR.strings.pref_upscale_status_backend_unavailable,
                                                        backend0.displayName,
                                                    )
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (ready) {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                } else {
                                                    MaterialTheme.colorScheme.error
                                                },
                                            )
                                        }
                                    }
                                }
                            },
                        ),
                    ),
                ),
            )

            // ---- models ----
            if (mode != "SIMPLE") {
                val modelItems = mutableListOf<Preference.PreferenceItem<out Any, out Any>>()
                modelItems += Preference.PreferenceItem.CustomPreference(
                    title = stringResource(KMR.strings.pref_upscale_models_group),
                    content = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = PrefsHorizontalPadding, vertical = 8.dp),
                        ) {
                            when (modelStatus.state) {
                                upscale.model.UpscaleModelManager.State.READY -> {
                                    Text(
                                        text = stringResource(
                                            KMR.strings.pref_upscale_models_ready_size,
                                            modelStatus.downloadedBytes / (1024 * 1024),
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                upscale.model.UpscaleModelManager.State.DOWNLOADING -> {
                                    Text(
                                        text = stringResource(
                                            KMR.strings.pref_upscale_models_downloading,
                                            (modelStatus.progress * 100).toInt(),
                                            modelStatus.downloadedBytes / (1024 * 1024),
                                            modelStatus.totalBytes / (1024 * 1024),
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    if (!modelStatus.currentFile.isNullOrBlank()) {
                                        Text(
                                            text = modelStatus.currentFile.orEmpty(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    LinearProgressIndicator(
                                        progress = { modelStatus.progress },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp),
                                    )
                                }
                                upscale.model.UpscaleModelManager.State.ERROR -> {
                                    Text(
                                        text = modelStatus.error
                                            ?: stringResource(KMR.strings.pref_upscale_models_not_installed),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                else -> {
                                    Text(
                                        text = stringResource(KMR.strings.pref_upscale_models_not_installed),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        text = stringResource(
                                            KMR.strings.pref_upscale_models_manifest_total,
                                            models.totalBytes() / (1024L * 1024L),
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            importError?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            models.modelStates().forEach { readiness0 ->
                                Text(
                                    text = if (readiness0.readyFormats.isEmpty()) {
                                        stringResource(
                                            KMR.strings.pref_upscale_model_not_installed,
                                            readiness0.label,
                                            readiness0.installedBytes / (1024 * 1024),
                                            readiness0.totalBytes / (1024 * 1024),
                                        )
                                    } else {
                                        stringResource(
                                            KMR.strings.pref_upscale_model_installed,
                                            readiness0.label,
                                            readiness0.installedBytes / (1024 * 1024),
                                            readiness0.totalBytes / (1024 * 1024),
                                        )
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                )
                modelItems += Preference.PreferenceItem.TextPreference(
                    title = when (modelStatus.state) {
                        upscale.model.UpscaleModelManager.State.DOWNLOADING ->
                            stringResource(KMR.strings.pref_upscale_cancel_download)
                        upscale.model.UpscaleModelManager.State.READY ->
                            stringResource(KMR.strings.pref_upscale_redownload_models)
                        else -> stringResource(KMR.strings.pref_upscale_download_models)
                    },
                    subtitle = when (modelStatus.state) {
                        upscale.model.UpscaleModelManager.State.DOWNLOADING ->
                            modelStatus.currentFile?.let {
                                stringResource(KMR.strings.pref_upscale_downloading_file, it)
                            }
                        upscale.model.UpscaleModelManager.State.ERROR ->
                            modelStatus.error ?: stringResource(KMR.strings.pref_upscale_retry)
                        else -> null
                    },
                    onClick = {
                        when (modelStatus.state) {
                            upscale.model.UpscaleModelManager.State.DOWNLOADING -> models.cancelDownload()
                            upscale.model.UpscaleModelManager.State.READY -> models.startDownload(force = true)
                            else -> models.startDownload()
                        }
                    },
                    enabled = enabled,
                )
                modelItems += Preference.PreferenceItem.TextPreference(
                    title = stringResource(KMR.strings.pref_upscale_clear_models),
                    subtitle = stringResource(KMR.strings.pref_upscale_clear_models_summary),
                    onClick = { models.clearModels() },
                    enabled = enabled &&
                        modelStatus.state != upscale.model.UpscaleModelManager.State.DOWNLOADING,
                )
                modelItems += Preference.PreferenceItem.TextPreference(
                    title = stringResource(KMR.strings.pref_upscale_import_onnx),
                    subtitle = stringResource(KMR.strings.pref_upscale_import_onnx_summary),
                    onClick = { importLauncher.launch("*/*") },
                    enabled = enabled,
                )
                add(
                    Preference.PreferenceGroup(
                        title = stringResource(KMR.strings.pref_upscale_group_models),
                        preferenceItems = modelItems.toImmutableList(),
                    ),
                )
            }

            // ---- per-category defaults ----
            if (categories.isNotEmpty()) {
                val categoryItems = mutableListOf<Preference.PreferenceItem<out Any, out Any>>()
                categoryItems += Preference.PreferenceItem.InfoPreference(
                    title = stringResource(KMR.strings.pref_upscale_category_defaults_summary),
                )
                categories.forEach { category ->
                    val state = categoryStates.firstOrNull { it.first == category.id }?.second
                    categoryItems += Preference.PreferenceItem.BasicListPreference(
                        value = when (state) {
                            true -> "on"
                            false -> "off"
                            null -> "inherit"
                        },
                        entries = persistentMapOf(
                            "inherit" to stringResource(KMR.strings.pref_upscale_category_inherit),
                            "on" to stringResource(KMR.strings.pref_upscale_category_on),
                            "off" to stringResource(KMR.strings.pref_upscale_category_off),
                        ),
                        title = category.name,
                        subtitle = "%s",
                        enabled = enabled,
                        onValueChanged = { value ->
                            store.setCategoryOverride(
                                category.id,
                                when (value) {
                                    "on" -> true
                                    "off" -> false
                                    else -> null
                                },
                            )
                        },
                    )
                }
                add(
                    Preference.PreferenceGroup(
                        title = stringResource(KMR.strings.pref_upscale_group_categories),
                        preferenceItems = categoryItems.toImmutableList(),
                    ),
                )
            }

            add(
                Preference.PreferenceItem.InfoPreference(
                    title = if (enabled) {
                        stringResource(KMR.strings.pref_upscale_info_per_manga)
                    } else {
                        stringResource(KMR.strings.pref_upscale_info_disabled)
                    },
                ),
            )
        }
        return result
    }

    private fun readinessSuffix(
        statuses: Map<String, kotlinx.coroutines.flow.StateFlow<upscale.core.Readiness>>,
        backend: String,
    ): String = when (backend) {
        "AUTO" -> ""
        else -> statuses[
            when (backend) {
                "VULKAN" -> "ncnn"
                "NPU" -> "ort"
                else -> "simple"
            },
        ]?.value?.let { readiness0 ->
            when (readiness0) {
                is upscale.core.Readiness.Ready -> " (ready)"
                is upscale.core.Readiness.Unavailable -> " (unavailable)"
            }
        } ?: ""
    }

    private fun formatCacheSummary(bytes: Long, count: Int): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / 1024f / 1024f / 1024f)
        bytes >= 1024L * 1024 -> "${bytes / (1024L * 1024)} MB"
        bytes > 0 -> "${bytes / 1024} KB"
        else -> "empty"
    } + " · $count page(s)"
}

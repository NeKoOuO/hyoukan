package eu.kanade.presentation.more.settings.screen.browse

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import mihon.core.viewmodel.StateViewModel
import mihon.domain.extension.interactor.AddExtensionStore
import mihon.domain.extension.interactor.GetExtensionStores
import mihon.domain.extension.interactor.RemoveExtensionStore
import mihon.domain.extension.interactor.UpdateExtensionStores
import mihon.domain.extension.model.ExtensionStore
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ExtensionStoresViewModel(
    private val getExtensionStores: GetExtensionStores,
    private val addExtensionStore: AddExtensionStore,
    private val removeExtensionStore: RemoveExtensionStore,
    private val updateExtensionStores: UpdateExtensionStores,
    private val extensionManager: ExtensionManager,
    // KMK -->
    private val sourcePreferences: SourcePreferences,
    // KMK <--
) : StateViewModel<ExtensionStoreScreenState>(ExtensionStoreScreenState.Loading) {

    private inline fun updateSuccessState(
        func: (ExtensionStoreScreenState.Success) -> ExtensionStoreScreenState.Success,
    ) {
        mutableState.update {
            when (it) {
                ExtensionStoreScreenState.Loading -> it
                is ExtensionStoreScreenState.Success -> func(it)
            }
        }
    }

    /**
     * A store URL arriving through the "add repo" deep link can reach this ViewModel
     * while [state] is still [ExtensionStoreScreenState.Loading]; the screen fires its
     * `LaunchedEffect(url)` on the first composition. [updateSuccessState] used to drop
     * the dialog in that state, so the link did nothing until the app was restarted and
     * the screen reopened. Hold the URL until the store list has loaded, then apply it.
     */
    private val pendingDeeplink = PendingExtensionStoreDeeplink()

    private fun applyPendingDeeplink() {
        val success = mutableState.value as? ExtensionStoreScreenState.Success ?: return
        val dialog = pendingDeeplink.consume(success.stores) ?: return
        updateSuccessState { it.copy(dialog = dialog) }
    }

    init {
        viewModelScope.launchIO {
            getExtensionStores.subscribe()
                .collectLatest { stores ->
                    mutableState.update {
                        when (it) {
                            ExtensionStoreScreenState.Loading -> ExtensionStoreScreenState.Success(
                                stores = stores,
                                // KMK -->
                                disabledRepos = sourcePreferences.disabledRepos().get(),
                                // KMK <--
                            )
                            is ExtensionStoreScreenState.Success -> it.copy(stores = stores)
                        }
                    }
                    applyPendingDeeplink()
                }
        }

        // KMK -->
        sourcePreferences.disabledRepos().changes()
            .onEach { disabledRepos ->
                mutableState.update {
                    when (it) {
                        is ExtensionStoreScreenState.Success -> it.copy(disabledRepos = disabledRepos)
                        else -> it
                    }
                }
            }
            .launchIn(viewModelScope)
        // KMK <--
    }

    /**
     * Creates and adds a new repo to the database.
     *
     * @param indexUrl The baseUrl of the repo to create.
     */
    fun createRepo(indexUrl: String) {
        // KMK -->
        // Keep the write alive if the user leaves the screen (or force-stops the
        // app) right after confirming: a cancelled insert would leave the store
        // absent after the next launch with no error ever shown.
        viewModelScope.launchNonCancellable {
            // KMK <--
            updateSuccessState {
                it.copy(
                    dialog = when (it.dialog) {
                        is ExtensionStoreDialog.Create -> it.dialog.copy(processing = true)
                        is ExtensionStoreDialog.Confirm -> it.dialog.copy(processing = true)
                        else -> it.dialog
                    },
                )
            }
            addExtensionStore(indexUrl)
                .onSuccess {
                    extensionManager.findAvailableExtensions()
                    dismissDialog()
                }
                .onFailure { throwable ->
                    updateSuccessState {
                        it.copy(
                            dialog = when (it.dialog) {
                                is ExtensionStoreDialog.Create -> it.dialog.copy(
                                    processing = false,
                                    errorMessage = throwable.message ?: "unknown error",
                                )
                                is ExtensionStoreDialog.Confirm -> it.dialog.copy(
                                    processing = false,
                                    errorMessage = throwable.message ?: "unknown error",
                                )
                                else -> it.dialog
                            },
                        )
                    }
                }
        }
    }

    /**
     * Refreshes information for each repository.
     */
    fun refreshRepos() {
        val status = state.value

        if (status is ExtensionStoreScreenState.Success) {
            viewModelScope.launchIO {
                updateExtensionStores()
            }
        }
    }

    /**
     * Deletes the given repo from the database
     */
    fun deleteRepo(indexUrl: String) {
        // KMK -->
        // Remove repo from disabled list
        enableStore(indexUrl)
        // KMK <--
        viewModelScope.launchIO {
            removeExtensionStore(indexUrl)
            extensionManager.findAvailableExtensions()
        }
    }

    // KMK -->
    fun enableStore(indexUrl: String) {
        val disabledRepos = sourcePreferences.disabledRepos().get()
        if (indexUrl in disabledRepos) {
            sourcePreferences.disabledRepos().set(
                disabledRepos.filterNot { it == indexUrl }.toSet(),
            )
        }
    }

    fun disableStore(indexUrl: String) {
        val disabledRepos = sourcePreferences.disabledRepos().get()
        if (indexUrl !in disabledRepos) {
            sourcePreferences.disabledRepos().set(
                disabledRepos + indexUrl,
            )
        }
    }

    fun refreshExtensionList() {
        viewModelScope.launchIO {
            extensionManager.findAvailableExtensions()
        }
    }
    // KMK <--

    fun addFromDeeplink(storeIndexUrl: String) {
        pendingDeeplink.queue(storeIndexUrl)
        applyPendingDeeplink()
    }

    fun showDialog(dialog: ExtensionStoreDialog) {
        updateSuccessState { state ->
            state.copy(dialog = dialog)
        }
    }

    fun dismissDialog() {
        updateSuccessState {
            it.copy(dialog = null)
        }
    }
}

sealed class ExtensionStoreDialog {
    data class Create(val processing: Boolean = false, val errorMessage: String? = null) : ExtensionStoreDialog()
    data class Delete(val store: ExtensionStore) : ExtensionStoreDialog()
    data class Confirm(
        val url: String,
        val alreadyExists: Boolean = false,
        val processing: Boolean = false,
        val errorMessage: String? = null,
    ) : ExtensionStoreDialog()
}

sealed class ExtensionStoreScreenState {

    @Immutable
    data object Loading : ExtensionStoreScreenState()

    @Immutable
    data class Success(
        val stores: List<ExtensionStore>,
        val dialog: ExtensionStoreDialog? = null,
        // KMK -->
        val disabledRepos: Set<String> = emptySet(),
        // KMK <--
    ) : ExtensionStoreScreenState() {

        val isEmpty: Boolean
            get() = stores.isEmpty()
    }
}

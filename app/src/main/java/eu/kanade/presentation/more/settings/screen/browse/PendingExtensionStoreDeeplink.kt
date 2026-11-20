package eu.kanade.presentation.more.settings.screen.browse

import mihon.domain.extension.model.ExtensionStore

/**
 * Holds an "add repo" link that arrived before the store list was ready.
 *
 * The extension-stores screen launches its deep-link effect on first composition,
 * which on a cold start is before the database flow has emitted. The dialog used to
 * be set through a `Success`-only state update and silently lost in that window, so
 * the repository never got added. Queueing it here lets the ViewModel apply the
 * dialog as soon as the first list is available, and only once.
 */
internal class PendingExtensionStoreDeeplink {
    private var url: String? = null

    /** Remember [storeIndexUrl]; call [consume] once the store list has loaded. */
    @Synchronized
    fun queue(storeIndexUrl: String) {
        url = storeIndexUrl
    }

    /**
     * Returns the confirm dialog for the queued URL, or null when nothing is queued.
     * Consumes the URL so a later store-list emission cannot re-open the dialog after
     * the user dismissed it.
     */
    @Synchronized
    fun consume(stores: List<ExtensionStore>): ExtensionStoreDialog.Confirm? {
        val queued = url ?: return null
        url = null
        return ExtensionStoreDialog.Confirm(
            url = queued,
            alreadyExists = stores.any { it.indexUrl == queued },
        )
    }
}

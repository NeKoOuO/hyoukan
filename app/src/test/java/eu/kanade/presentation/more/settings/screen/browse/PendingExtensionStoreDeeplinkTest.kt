package eu.kanade.presentation.more.settings.screen.browse

import io.kotest.matchers.shouldBe
import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class PendingExtensionStoreDeeplinkTest {
    private fun store(indexUrl: String) = ExtensionStore(
        indexUrl = indexUrl,
        name = "Test store",
        badgeLabel = "Test",
        signingKey = "key",
        contact = ExtensionStore.Contact(website = "", discord = null),
        isLegacy = false,
        extensionListUrl = null,
    )

    @Test
    fun `a link queued while the list is loading is applied when the list arrives`() {
        val pending = PendingExtensionStoreDeeplink()
        pending.queue("https://example.com/repo.json")

        pending.consume(emptyList()) shouldBe ExtensionStoreDialog.Confirm(
            url = "https://example.com/repo.json",
        )
    }

    @Test
    fun `a queued link is marked as already existing when the store is in the list`() {
        val pending = PendingExtensionStoreDeeplink()
        val url = "https://example.com/repo.json"
        pending.queue(url)

        pending.consume(listOf(store(url))) shouldBe ExtensionStoreDialog.Confirm(
            url = url,
            alreadyExists = true,
        )
    }

    @Test
    fun `a queued link is consumed only once`() {
        val pending = PendingExtensionStoreDeeplink()
        pending.queue("https://example.com/repo.json")

        pending.consume(emptyList()) shouldBe ExtensionStoreDialog.Confirm(
            url = "https://example.com/repo.json",
        )
        pending.consume(emptyList()) shouldBe null
    }

    @Test
    fun `nothing queued produces no dialog`() {
        PendingExtensionStoreDeeplink().consume(listOf(store("https://example.com/repo.json"))) shouldBe null
    }

    @Test
    fun `a later link can be queued after the first one was consumed`() {
        val pending = PendingExtensionStoreDeeplink()
        pending.queue("https://first.example/repo.json")
        pending.consume(emptyList())

        pending.queue("https://second.example/repo.json")
        pending.consume(emptyList())?.url shouldBe "https://second.example/repo.json"
    }

    @Test
    fun `a newer link replaces one that has not been consumed yet`() {
        val pending = PendingExtensionStoreDeeplink()
        pending.queue("https://old.example/repo.json")
        pending.queue("https://new.example/repo.json")

        pending.consume(emptyList())?.url shouldBe "https://new.example/repo.json"
        pending.consume(emptyList()) shouldBe null
    }
}

package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.text.EpubArchiveSource
import eu.kanade.tachiyomi.source.text.TextBaseUrlSource
import eu.kanade.tachiyomi.source.text.TextCapabilities
import eu.kanade.tachiyomi.source.text.TextIncrementalSource
import eu.kanade.tachiyomi.source.text.TextPage
import eu.kanade.tachiyomi.source.text.TextResourceSource
import eu.kanade.tachiyomi.source.text.TextSearchHit
import eu.kanade.tachiyomi.source.text.TextSearchSource
import eu.kanade.tachiyomi.source.text.TextSource
import eu.kanade.tachiyomi.source.text.TextTocSource
import eu.kanade.tachiyomi.source.text.TocEntry
import mihon.core.archive.EpubArchiveUrls
import mihon.core.archive.EpubReader
import mihon.core.archive.EpubResourceServer
import mihon.core.archive.EpubTextSource
import mihon.core.archive.epubReader
import java.io.InputStream

/**
 * A downloaded EPUB opened as a book, through the same [EpubTextSource]/[EpubResourceServer]
 * pipeline the live source uses.
 *
 * Downloads of a text chapter are the book file itself ([DownloadableTextSource]), so the offline
 * reader cannot reuse the source's own `getTextPageList` - that would go back to the network the
 * download exists to avoid. This adapter owns the file instead: the loader stays a
 * [TextPageLoader], which is what makes the novel viewer and the contents/search sheets claim the
 * chapter exactly as they do online.
 *
 * Only the text half of [Source] is ever called (the loader is chosen by its `TextPageLoader`
 * identity); the browse methods throw rather than pretend to return an empty catalogue.
 */
internal class DownloadedEpubTextSource(
    private val context: Context,
    private val file: UniFile,
    private val owner: Source,
) : TextSource,
    TextIncrementalSource,
    TextTocSource,
    TextSearchSource,
    TextResourceSource,
    TextBaseUrlSource,
    EpubArchiveSource {

    override val id: Long get() = owner.id
    override val name: String get() = owner.name
    override val lang: String get() = owner.lang
    override val supportsLatest: Boolean = false

    override val textCapabilities: TextCapabilities =
        TextCapabilities.EPUB.copy(TOC = true, SEARCH = true)

    private fun openEpub(): EpubReader? = runCatching { file.epubReader(context) }.getOrNull()

    private val resources = EpubResourceServer<SChapter>(
        keyOf = { it.url },
        bookProvider = { openEpub() },
    )

    private val text = EpubTextSource(
        readerFor = { chapter ->
            resources.readerFor(chapter) ?: openEpub()?.let { resources.adopt(chapter.url, it) }
        },
        // Same default as the source that produced the file: show a declared cover when the book
        // wants one. The illustration toggle is applied by TextPageLoader where the markup is read.
        wantsCoverPage = { true },
    )

    override suspend fun getTextPageList(chapter: SChapter): List<TextPage> = text.textPages(chapter)

    override suspend fun getTextPageIndex(chapter: SChapter): List<TextPage> = text.getTextPageIndex(chapter)

    override suspend fun getTextPageText(chapter: SChapter, page: TextPage): String? =
        text.getTextPageText(chapter, page)

    override suspend fun getTableOfContents(chapter: SChapter): List<TocEntry> =
        text.getTableOfContents(chapter)

    override suspend fun searchInChapter(chapter: SChapter, query: String): List<TextSearchHit> =
        text.searchInChapter(chapter, query)

    override fun openResource(absoluteUrl: String): InputStream? = resources.openResource(absoluteUrl)

    override fun contentTypeFor(absoluteUrl: String): String? = resources.contentTypeFor(absoluteUrl)

    override fun baseUrlFor(page: TextPage): String = EpubArchiveUrls.documentBaseUrl(page.baseHref)

    override fun releaseResourceReader(chapterUrl: String) {
        resources.release(chapterUrl)
    }

    override fun releaseAllResourceReaders() {
        resources.releaseAll()
    }

    // ------------------------------------------------------------ unreachable Source half

    override suspend fun getPopularManga(page: Int): MangasPage = unavailable()
    override suspend fun getLatestUpdates(page: Int): MangasPage = unavailable()
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = unavailable()
    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = unavailable()
    override suspend fun getPageList(chapter: SChapter): List<Page> = unavailable()

    private fun unavailable(): Nothing =
        throw UnsupportedOperationException("A downloaded book has no catalogue or page list")
}

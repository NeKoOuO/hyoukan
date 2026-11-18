package eu.kanade.tachiyomi.data.download

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.SChapter

/**
 * A source whose text chapters can be saved as a single file for offline reading.
 *
 * Downloading a book is not downloading pages: an EPUB is one archive, and the reader opens the
 * archive itself (`EpubTextSource`) rather than image files. Without this seam the downloader asks
 * such a source for an image page list, gets nothing, and fails the chapter with "No pages found"
 * (issue #85).
 *
 * Implemented by the built-in Komga source only. Kept in the app rather than the extension API on
 * purpose: no Keiyoushi extension can answer it, and a host capability a source may or may not
 * have belongs on a narrow optional interface, not on `TextSource` (adding methods there breaks
 * every extension that predates the change).
 *
 * The saved file is looked up by the reader through [DownloadManager] and opened as an [EpubReader]
 * by `DownloadedEpubTextSource`, so the offline book and the online one go through exactly the same
 * text pipeline.
 */
interface DownloadableTextSource {

    /**
     * Whether [chapter] is a text book this source can hand over as a file.
     *
     * False for a chapter the source serves as images, which must keep the ordinary page
     * download path.
     */
    suspend fun isTextDownload(chapter: SChapter): Boolean

    /**
     * Writes the chapter's book file into [target].
     *
     * [target] is a temporary file inside the chapter's download directory; the downloader
     * renames it into place only after it has bytes and only after the whole download succeeds.
     * Throws on network failure, which surfaces as a failed download.
     */
    suspend fun downloadTextChapter(chapter: SChapter, target: UniFile)

    /** Extension of the saved file, without the dot. An EPUB unless a source says otherwise. */
    val textDownloadExtension: String get() = "epub"
}

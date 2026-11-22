package eu.kanade.tachiyomi.util.system

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.IntentCompat
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.io.Serializable

fun Uri.toShareIntent(context: Context, type: String = "image/*", message: String? = null): Intent {
    val uri = this

    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        when (uri.scheme) {
            "http", "https" -> {
                putExtra(Intent.EXTRA_TEXT, uri.toString())
            }
            "content" -> {
                message?.let { putExtra(Intent.EXTRA_TEXT, it) }
                putExtra(Intent.EXTRA_STREAM, uri)
            }
        }
        clipData = ClipData.newRawUri(null, uri)
        setType(type)
        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
    }

    return Intent.createChooser(shareIntent, context.stringResource(MR.strings.action_share)).apply {
        // KMK --> The chooser is what the system actually starts, and system_server (uid 1000,
        // `ChooserActivity`) reads the stream URI out of it to build the target preview/metadata.
        // The grant lived only on the inner intent, so that read was unauthorised and the provider
        // logged `DatabaseUtils: SecurityException ... requires the provider be exported, or
        // grantUriPermission()` on every share (2026-11-22 field dump). Grant on the chooser too,
        // and mirror the ClipData so the permission travels with the intent rather than only with
        // EXTRA_STREAM.
        // KMK <--
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        clipData = shareIntent.clipData
    }
}

inline fun <reified T> Intent.getParcelableExtraCompat(name: String): T? {
    return IntentCompat.getParcelableExtra(this, name, T::class.java)
}

inline fun <reified T : Serializable> Intent.getSerializableExtraCompat(name: String): T? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getSerializableExtra(name, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getSerializableExtra(name) as? T
    }
}

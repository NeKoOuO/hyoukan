package eu.kanade.tachiyomi.data.database

import android.content.Context
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import exh.log.xLogE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// KMK -->
private const val DEFAULT_DATABASE_NAME = "tachiyomi.db"
private const val BACKUP_DIR_NAME = "database_backup"
private const val BACKUP_FILE_PREFIX = "tachiyomi-"
private const val MAX_BACKUPS = 3
private const val BACKUP_INTERVAL_MS = 24L * 60 * 60 * 1000

private const val PRAGMA_WAL_CHECKPOINT_TRUNCATE = "PRAGMA wal_checkpoint(TRUNCATE)"
private const val PRAGMA_WAL_CHECKPOINT_FULL = "PRAGMA wal_checkpoint(FULL)"
private const val PRAGMA_QUICK_CHECK = "PRAGMA quick_check(1)"

/** `wal_checkpoint`'s `busy` column when no reader blocked the checkpoint. */
internal const val CHECKPOINT_NOT_BUSY = "0"

internal enum class IntegrityResult { OK, CORRUPT, FAILED }

/** Maps `PRAGMA quick_check`'s single value to what the startup pass should do about it. */
internal fun integrityResultOf(verdict: String?): IntegrityResult = when (verdict?.lowercase()) {
    "ok" -> IntegrityResult.OK
    null -> IntegrityResult.FAILED
    else -> IntegrityResult.CORRUPT
}

/** A checkpoint only folded every committed frame when no reader kept it busy. */
internal fun checkpointCleared(busy: String?): Boolean = busy == CHECKPOINT_NOT_BUSY

/**
 * Runs [sql] on the app's own connection and returns column 0 of its first row.
 *
 * Top-level and Android-free so it can be asserted against a fake [SqlDriver]; reading `.value`
 * matters because SQLDelight's query result is lazy and a result nobody consumes never runs.
 */
internal fun SqlDriver.queryScalar(sql: String): String? {
    return executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            val hasRow = cursor.next().value
            QueryResult.Value(if (hasRow) cursor.getString(0) else null)
        },
        parameters = 0,
    ).value
}

/**
 * Protects the SQLite database against corruption and wipes by combining three
 * measures, all executed on the app's own live connection:
 *
 * - a startup integrity probe with automatic recovery from the latest backup,
 * - a rolling local backup refreshed at most once per day,
 * - quarantining an unrecoverable database instead of crash-looping.
 *
 * All SQL runs through the app's own [driver], i.e. the same connection SQLDelight uses. Opening
 * a second connection here was the source of `SQLITE_IOERR_SHMOPEN` (POSIX EBADF) while WAL was
 * live: the framework SQLite and requery's bundled SQLite are different native libraries, and a
 * reader opened through one while the other owns the `-shm` file sees a foreign lock owner
 * (0xaaaaaaaa). The driver also selects plaintext versus SQLCipher internally, so passphrases and
 * `System.loadLibrary("sqlcipher")` have no place here.
 *
 * [databaseName] is only used to locate the file for backups and for log messages.
 */
class DatabaseMaintenanceManager(
    private val context: Context,
    private val driver: SqlDriver,
    private val databaseName: String = DEFAULT_DATABASE_NAME,
) {

    private val backupDir get() = File(context.filesDir, BACKUP_DIR_NAME)
    private val dbFile get() = context.getDatabasePath(databaseName)

    suspend fun performStartupMaintenance() = withContext(Dispatchers.IO) {
        if (!dbFile.exists()) return@withContext

        when (integrityCheckResult()) {
            IntegrityResult.OK -> refreshBackup()
            IntegrityResult.CORRUPT -> {
                xLogE("$databaseName failed the integrity check")
                if (!restoreLatestBackup()) {
                    quarantineCorruptDatabase()
                }
            }
            IntegrityResult.FAILED -> Unit
        }
    }

    /**
     * Returns the first column of the single row produced by [sql], or null when unavailable.
     *
     * Executed on [driver] so a `quick_check` or WAL checkpoint uses the connection that owns the
     * `-shm` file, not a second one with a different native SQLite library.
     */
    private fun rawQueryScalar(sql: String): String? = driver.queryScalar(sql)

    private fun integrityCheckResult(): IntegrityResult =
        integrityResultOf(runCatching { rawQueryScalar(PRAGMA_QUICK_CHECK) }.getOrNull())

    private fun latestBackup(): File? {
        return backupDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(BACKUP_FILE_PREFIX) && it.name.endsWith(".db") }
            ?.maxByOrNull { it.name }
    }

    private fun restoreLatestBackup(): Boolean {
        val backup = latestBackup()
        if (backup == null) {
            xLogE("No database backup available to restore from")
            return false
        }
        return runCatching {
            removeSidecarFiles()
            backup.copyTo(dbFile, overwrite = true)
            xLogE("Restored $databaseName from ${backup.name}")
            true
        }.getOrElse {
            xLogE("Failed to restore $databaseName from backup", it)
            false
        }
    }

    private fun quarantineCorruptDatabase() {
        runCatching {
            val quarantine = File(dbFile.absolutePath + ".corrupt-" + System.currentTimeMillis())
            removeSidecarFiles()
            if (dbFile.renameTo(quarantine)) {
                xLogE("Quarantined corrupt database as ${quarantine.name}")
            } else {
                xLogE("Could not quarantine corrupt $databaseName")
            }
        }
    }

    /**
     * Folds the write-ahead log into the main file and confirms the result is exportable.
     *
     * The live connection runs in WAL mode (`AppBindings.providesSqlDriver`), so recent commits
     * sit in the `-wal` sidecar until a checkpoint folds them back. Copying only the main file
     * therefore yields something that opens fine and is quietly missing the last few chapters
     * read, the newest category rename, the newest tracker rows - which is exactly what makes an
     * exported database look like it lost data when it is imported elsewhere.
     *
     * [wal_checkpoint] returns `(busy, log, checkpointed)`; column 0 is 0 only when no reader was
     * blocking it, i.e. when every committed frame is now in the main file. TRUNCATE is tried
     * first because it also empties the sidecar, and FULL is the fallback for the busy case: it
     * folds in everything committed and leaves an empty `-wal`, which is equally safe to copy.
     *
     * Returns the checkpointed file, or null when neither mode completed or the file does not
     * pass a quick check - the caller must not ship a snapshot in that case.
     */
    suspend fun prepareSnapshot(): File? = withContext(Dispatchers.IO) {
        if (!dbFile.isFile || dbFile.length() == 0L) return@withContext null

        val truncated = runCatching { queryRow(PRAGMA_WAL_CHECKPOINT_TRUNCATE) }
            .onFailure { xLogE("WAL truncate checkpoint failed", it) }
            .getOrNull()
        if (checkpointCleared(truncated)) return@withContext verifiedOrNull()

        val full = runCatching { queryRow(PRAGMA_WAL_CHECKPOINT_FULL) }
            .onFailure { xLogE("WAL full checkpoint failed", it) }
            .getOrNull()
        if (checkpointCleared(full)) return@withContext verifiedOrNull()

        xLogE("$databaseName could not be checkpointed; refusing to export a stale copy")
        null
    }

    /** Column 0 of the pragma's single row, or null when the pragma did not answer. */
    private suspend fun queryRow(sql: String): String? = withContext(Dispatchers.IO) {
        runCatching { rawQueryScalar(sql) }.getOrNull()
    }

    private suspend fun verifiedOrNull(): File? {
        val quickCheck = queryRow(PRAGMA_QUICK_CHECK)
        if (!quickCheck.equals("ok", ignoreCase = true)) {
            xLogE("$databaseName failed the pre-export quick check: $quickCheck")
            return null
        }
        return dbFile.takeIf { it.length() > 0L }
    }

    private fun refreshBackup() {
        val newest = latestBackup()
        if (newest != null && System.currentTimeMillis() - newest.lastModified() < BACKUP_INTERVAL_MS) return

        runCatching {
            rawQueryScalar(PRAGMA_WAL_CHECKPOINT_TRUNCATE)
            backupDir.mkdirs()
            val target = File(backupDir, "$BACKUP_FILE_PREFIX${System.currentTimeMillis()}.db")
            dbFile.copyTo(target, overwrite = true)
            pruneOldBackups()
        }.onFailure {
            xLogE("Failed to back up $databaseName", it)
        }
    }

    private fun pruneOldBackups() {
        backupDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(BACKUP_FILE_PREFIX) && it.name.endsWith(".db") }
            ?.sortedByDescending { it.name }
            ?.drop(MAX_BACKUPS)
            ?.forEach { it.delete() }
    }

    private fun removeSidecarFiles() {
        File(dbFile.absolutePath + "-wal").delete()
        File(dbFile.absolutePath + "-shm").delete()
    }
}
// KMK <--

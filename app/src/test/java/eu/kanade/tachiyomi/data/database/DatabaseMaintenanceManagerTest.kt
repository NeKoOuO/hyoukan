package eu.kanade.tachiyomi.data.database

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * The startup maintenance pass must never open its own SQLite connection: doing so while the
 * app's driver owns the WAL `-shm` file fails with SQLITE_IOERR_SHMOPEN (POSIX EBADF). These
 * assert the two halves of that fix - the pragma answers are read through the supplied driver,
 * and their values are interpreted the way the startup/export flows expect.
 */
@Execution(ExecutionMode.CONCURRENT)
class DatabaseMaintenanceManagerTest {

    @Test
    fun `quick check ok is healthy`() {
        integrityResultOf("ok") shouldBe IntegrityResult.OK
    }

    @Test
    fun `quick check is matched case insensitively`() {
        integrityResultOf("OK") shouldBe IntegrityResult.OK
        integrityResultOf("Ok") shouldBe IntegrityResult.OK
    }

    @Test
    fun `a refused quick check is a failed probe, not corruption`() {
        // null means the pragma never ran (the driver threw), so there is nothing to recover from.
        integrityResultOf(null) shouldBe IntegrityResult.FAILED
    }

    @Test
    fun `any other verdict means the database is corrupt`() {
        integrityResultOf("*** in database main ***\nPage 42 is never used") shouldBe IntegrityResult.CORRUPT
        integrityResultOf("") shouldBe IntegrityResult.CORRUPT
    }

    @Test
    fun `checkpoint counts as done only when no reader kept it busy`() {
        checkpointCleared("0") shouldBe true
        checkpointCleared("1") shouldBe false
        checkpointCleared(null) shouldBe false
    }

    @Test
    fun `scalar query reads the first column of the first row`() {
        val driver = FakeDriver(rows = listOf(listOf("ok")))

        driver.queryScalar("PRAGMA quick_check(1)") shouldBe "ok"
        driver.queries.single() shouldBe "PRAGMA quick_check(1)"
        driver.parameterCounts.single() shouldBe 0
    }

    @Test
    fun `scalar query returns the first row only`() {
        val driver = FakeDriver(rows = listOf(listOf("first"), listOf("second")))

        driver.queryScalar("PRAGMA quick_check(1)") shouldBe "first"
    }

    @Test
    fun `scalar query returns null when the pragma produced no row`() {
        val driver = FakeDriver(rows = emptyList())

        driver.queryScalar("PRAGMA quick_check(1)") shouldBe null
    }

    @Test
    fun `the deferred query result is evaluated, not just built`() {
        // A QueryResult that is never read never executes; the row counter proves it ran.
        val driver = FakeDriver(rows = listOf(listOf("ok")))

        driver.queryScalar("PRAGMA wal_checkpoint(TRUNCATE)")

        driver.executed shouldBe 1
    }

    private class FakeDriver(private val rows: List<List<String?>>) : SqlDriver {
        val queries = mutableListOf<String>()
        val parameterCounts = mutableListOf<Int>()
        var executed = 0

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            queries += sql
            parameterCounts += parameters
            val result = mapper(FakeCursor(rows))
            executed += 1
            return result
        }

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> = QueryResult.Value(0L)

        override fun newTransaction(): QueryResult<Transacter.Transaction> =
            QueryResult.Value(UnsupportedTransaction)

        override fun currentTransaction(): Transacter.Transaction? = null

        override fun addListener(vararg queryKeys: String, listener: Query.Listener) = Unit

        override fun removeListener(vararg queryKeys: String, listener: Query.Listener) = Unit

        override fun notifyListeners(vararg queryKeys: String) = Unit

        override fun close() = Unit
    }

    private class FakeCursor(private val rows: List<List<String?>>) : SqlCursor {
        private var index = -1

        override fun next(): QueryResult<Boolean> = QueryResult.Value(++index < rows.size)

        override fun getString(index: Int): String? = rows[this.index][index]

        override fun getLong(index: Int): Long? = null

        override fun getBytes(index: Int): ByteArray? = null

        override fun getDouble(index: Int): Double? = null

        override fun getBoolean(index: Int): Boolean? = null
    }

    private object UnsupportedTransaction : Transacter.Transaction() {
        override val enclosingTransaction: Transacter.Transaction?
            get() = null

        override fun endTransaction(successful: Boolean): QueryResult<Unit> = QueryResult.Value(Unit)
    }
}

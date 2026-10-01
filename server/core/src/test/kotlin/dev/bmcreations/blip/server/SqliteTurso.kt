package dev.bmcreations.blip.server

import dev.bmcreations.blip.server.db.Migrations
import dev.bmcreations.blip.server.db.TursoClient
import dev.bmcreations.blip.server.db.TursoResult
import dev.bmcreations.blip.server.db.TursoValue
import io.mockk.coEvery
import io.mockk.mockk
import java.sql.Connection
import java.sql.DriverManager

/**
 * In-memory SQLite stand-in for TursoClient, running the real [Migrations] with
 * foreign keys enforced (as in production), so SQL-level behaviour such as cascades
 * and timestamp comparisons is exercised rather than mocked.
 */
class SqliteTurso : AutoCloseable {
    val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:").also {
        it.createStatement().use { s -> s.execute("PRAGMA foreign_keys = ON") }
    }

    val client: TursoClient = mockk<TursoClient>().also { mock ->
        coEvery { mock.execute(any(), any()) } coAnswers {
            run(firstArg(), secondArg())
        }
    }

    init {
        Migrations.migrations.forEach { sql ->
            try {
                connection.createStatement().use { it.execute(sql) }
            } catch (e: java.sql.SQLException) {
                if ("duplicate column" !in (e.message ?: "")) throw e
            }
        }
    }

    fun run(sql: String, args: List<TursoValue> = emptyList()): TursoResult {
        connection.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, v ->
                when (v) {
                    is TursoValue.Text -> ps.setString(i + 1, v.value)
                    is TursoValue.Integer -> ps.setLong(i + 1, v.value)
                    is TursoValue.Blob -> ps.setBytes(i + 1, java.util.Base64.getDecoder().decode(v.base64))
                    TursoValue.Null -> ps.setNull(i + 1, java.sql.Types.NULL)
                }
            }
            return if (ps.execute()) {
                val rs = ps.resultSet
                val md = rs.metaData
                val cols = (1..md.columnCount).map { md.getColumnLabel(it) }
                val rows = mutableListOf<List<String?>>()
                while (rs.next()) rows += (1..md.columnCount).map { rs.getString(it) }
                TursoResult(cols, rows, 0, 0)
            } else {
                TursoResult(emptyList(), emptyList(), ps.updateCount.toLong(), 0)
            }
        }
    }

    fun count(table: String): Int = run("SELECT COUNT(*) AS c FROM $table").firstOrNull()!!["c"]!!.toInt()

    override fun close() = connection.close()
}

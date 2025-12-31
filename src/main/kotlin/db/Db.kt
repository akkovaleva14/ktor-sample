package com.example.db

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import javax.sql.DataSource

class Db(private val ds: DataSource) {

    fun <T> query(block: (Connection) -> T): T =
        ds.connection.use { conn -> block(conn) }

    fun <T> tx(block: (Connection) -> T): T =
        ds.connection.use { conn ->
            val prev = conn.autoCommit
            conn.autoCommit = false
            try {
                val res = block(conn)
                conn.commit()
                res
            } catch (t: Throwable) {
                runCatching { conn.rollback() }
                throw t
            } finally {
                runCatching { conn.autoCommit = prev }
            }
        }
}

fun Connection.prepared(sql: String, vararg params: Any?): PreparedStatement {
    val ps = prepareStatement(sql)
    for ((i, p) in params.withIndex()) ps.setObject(i + 1, p)
    return ps
}

inline fun <T> PreparedStatement.queryList(mapper: (ResultSet) -> T): List<T> =
    use { ps ->
        ps.executeQuery().use { rs ->
            val out = ArrayList<T>()
            while (rs.next()) out.add(mapper(rs))
            out
        }
    }

inline fun <T> PreparedStatement.queryOneOrNull(mapper: (ResultSet) -> T): T? =
    use { ps ->
        ps.executeQuery().use { rs ->
            if (!rs.next()) null else mapper(rs)
        }
    }

fun PreparedStatement.execUpdate(): Int = use { it.executeUpdate() }

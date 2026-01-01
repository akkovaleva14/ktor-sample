package com.example.adapters.db.jdbc

import java.sql.Connection
import javax.sql.DataSource

/**
 * JDBC session wrapper with "ambient connection" support.
 *
 * Зачем это нужно:
 * - core/usecase хочет транзакции (атомарность) через TxPort,
 *   но не должен видеть JDBC Connection.
 * - Решение: Tx адаптер ставит Connection в ThreadLocal, а репозитории
 *   прозрачно используют его, если он есть.
 */
class JdbcSession(private val ds: DataSource) {

    private val tl = ThreadLocal<Connection?>()

    fun <T> query(block: (Connection) -> T): T {
        val existing = tl.get()
        return if (existing != null) {
            block(existing)
        } else {
            ds.connection.use { conn -> block(conn) }
        }
    }

    fun <T> tx(block: () -> T): T {
        val prev = tl.get()
        require(prev == null) { "Nested tx is not supported in this simple implementation" }

        ds.connection.use { conn ->
            val prevAuto = conn.autoCommit
            conn.autoCommit = false
            tl.set(conn)
            try {
                val res = block()
                conn.commit()
                return res
            } catch (t: Throwable) {
                runCatching { conn.rollback() }
                throw t
            } finally {
                runCatching { conn.autoCommit = prevAuto }
                tl.set(null)
            }
        }
    }
}

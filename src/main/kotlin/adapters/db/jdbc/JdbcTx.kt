package com.example.adapters.db.jdbc

import com.example.core.ports.TxPort

/**
 * TxPort implementation for JDBC.
 *
 * core/usecase calls tx.tx { ... }, and within that block repository adapters
 * will reuse the same JDBC Connection via JdbcSession ThreadLocal.
 */
class JdbcTx(private val session: JdbcSession) : TxPort {
    override fun <T> tx(block: () -> T): T = session.tx(block)
}

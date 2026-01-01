package com.example.core.ports

/**
 * Transaction boundary abstraction.
 *
 * Core use cases may need atomic operations ("claim idem + insert message", etc),
 * but must NOT know about JDBC Connection, SQL, etc.
 */
interface TxPort {
    fun <T> tx(block: () -> T): T
}

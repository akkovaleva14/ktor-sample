package com.example

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import javax.sql.DataSource

/**
 * Подключение к Postgres через DATABASE_URL (Render).
 *
 * Почему так:
 * - Render отдаёт готовый DATABASE_URL
 * - Hikari делает нормальный пул
 * - Flyway накатывает миграции автоматически при старте
 */
object Database {

    fun createDataSourceFromEnv(): HikariDataSource {
        val databaseUrl = System.getenv("DATABASE_URL")
            ?.trim()
            ?.ifBlank { null }
            ?: error("DATABASE_URL is not set")

        // В Render DATABASE_URL обычно начинается с "postgresql://"
        // JDBC хочет "jdbc:postgresql://"
        val jdbcUrl = if (databaseUrl.startsWith("jdbc:")) {
            databaseUrl
        } else {
            "jdbc:" + databaseUrl
        }

        val cfg = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl

            // Для маленькой нагрузки (1–2 пользователя) этого достаточно
            maximumPoolSize = 5
            minimumIdle = 1

            connectionTimeout = 10_000
            idleTimeout = 60_000
            maxLifetime = 10 * 60_000

            poolName = "app-db-pool"

            // Небольшие safety-настройки
            isAutoCommit = true
        }

        return HikariDataSource(cfg)
    }

    fun migrate(ds: DataSource) {
        val flyway = Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()

        flyway.migrate()
    }
}

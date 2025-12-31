package com.example

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.net.URI
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
        val raw = System.getenv("DATABASE_URL")
            ?.trim()
            ?.ifBlank { null }
            ?: error("DATABASE_URL is not set")

        val uri = URI(raw)

        val scheme = uri.scheme?.lowercase()
        require(scheme == "postgres" || scheme == "postgresql") {
            "DATABASE_URL must start with postgres:// or postgresql:// (got: ${uri.scheme})"
        }

        val host = uri.host ?: error("DATABASE_URL host is missing")
        val port = if (uri.port == -1) 5432 else uri.port
        val db = uri.path?.removePrefix("/")?.takeIf { it.isNotBlank() }
            ?: error("DATABASE_URL database name is missing in path")

        val (user, pass) = parseUserInfo(uri.userInfo)

        val jdbcUrl = "jdbc:postgresql://$host:$port/$db"

        val cfg = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = user
            this.password = pass

            maximumPoolSize = 5
            minimumIdle = 1

            connectionTimeout = 10_000
            idleTimeout = 60_000
            maxLifetime = 10 * 60_000

            poolName = "app-db-pool"
            isAutoCommit = true
        }

        return HikariDataSource(cfg)
    }

    private fun parseUserInfo(userInfo: String?): Pair<String, String> {
        require(!userInfo.isNullOrBlank()) { "DATABASE_URL userInfo is missing (expected user:password@...)" }

        val idx = userInfo.indexOf(':')
        require(idx > 0 && idx < userInfo.length - 1) {
            "DATABASE_URL userInfo must be in form user:password"
        }

        val user = userInfo.substring(0, idx)
        val pass = userInfo.substring(idx + 1)

        require(user.isNotBlank()) { "DATABASE_URL user is blank" }
        require(pass.isNotBlank()) { "DATABASE_URL password is blank" }

        return user to pass
    }

    fun migrate(ds: DataSource) {
        // 1) Flyway version (from the running artifact)
        val implVersion = Flyway::class.java.`package`?.implementationVersion
        println("Flyway implementationVersion = $implVersion")

        // 2) Environment overrides (Render dashboard env vars)
        val flywayEnv = System.getenv()
            .filterKeys { it.startsWith("FLYWAY_") }
            .toSortedMap()
        println("FLYWAY_* env vars = $flywayEnv")

        // 3) JVM system properties overrides (-Dflyway....), often via JAVA_TOOL_OPTIONS
        val flywayProps = System.getProperties()
            .entries
            .asSequence()
            .map { it.key.toString() to it.value.toString() }
            .filter { (k, _) -> k.startsWith("flyway.") }
            .sortedBy { it.first }
            .toList()
        println("flyway.* system properties = $flywayProps")

        val flyway = Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .sqlMigrationPrefix("V")
            .repeatableSqlMigrationPrefix("R")
            .sqlMigrationSeparator("__")
            .sqlMigrationSuffixes(".sql")
            .validateMigrationNaming(false)
            .baselineOnMigrate(true)
            .load()

        // 4) What Flyway thinks the config is AFTER load()
        val cfg = flyway.configuration
        println(
            "Flyway effective config: " +
                    "locations=${cfg.locations?.toList()}, " +
                    "prefix=${cfg.sqlMigrationPrefix}, " +
                    "repeatablePrefix=${cfg.repeatableSqlMigrationPrefix}, " +
                    "separator=${cfg.sqlMigrationSeparator}, " +
                    "suffixes=${cfg.sqlMigrationSuffixes?.toList()}"
        )

        flyway.migrate()
    }
}

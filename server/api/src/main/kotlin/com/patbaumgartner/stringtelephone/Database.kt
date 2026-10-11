package com.patbaumgartner.stringtelephone

import java.sql.DriverManager

class Database(path: String) : AutoCloseable {
    private val url = "jdbc:sqlite:$path"

    init {
        java.io.File(path).absoluteFile.parentFile.mkdirs()
        read { conn ->
            conn.createStatement().use { st ->
                st.execute("PRAGMA journal_mode=WAL")
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS users (
                      id TEXT PRIMARY KEY,
                      username TEXT UNIQUE NOT NULL,
                      display_name TEXT NOT NULL,
                      active INTEGER NOT NULL DEFAULT 1,
                      api_token_hash TEXT NOT NULL,
                      created_at INTEGER NOT NULL,
                      revoked_at INTEGER
                    )
                    """.trimIndent(),
                )
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS messages (
                      id TEXT PRIMARY KEY,
                      sender TEXT NOT NULL,
                      recipient TEXT NOT NULL,
                      content TEXT NOT NULL,
                      created_at INTEGER NOT NULL,
                      client_key TEXT NOT NULL,
                      UNIQUE(sender, recipient, client_key)
                    )
                    """.trimIndent(),
                )
                st.execute("CREATE INDEX IF NOT EXISTS idx_messages_pair_time ON messages(sender, recipient, created_at)")
                st.execute(
                    """CREATE TABLE IF NOT EXISTS pbx_accounts (
                        username TEXT PRIMARY KEY REFERENCES users(username),
                        digest TEXT NOT NULL,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        kill_required INTEGER NOT NULL DEFAULT 0
                    )""",
                )
                st.execute("CREATE TABLE IF NOT EXISTS pbx_settings (id INTEGER PRIMARY KEY CHECK(id=1), settings TEXT NOT NULL)")
            }
        }
    }

    fun <T> read(block: (java.sql.Connection) -> T): T =
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use {
                it.execute("PRAGMA busy_timeout=10000")
                it.execute("PRAGMA foreign_keys=ON")
            }
            block(connection)
        }

    fun <T> tx(block: (java.sql.Connection) -> T): T {
        return read { conn ->
            conn.autoCommit = false
            try {
                val result = block(conn)
                conn.commit()
                result
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun close() = Unit
}

package com.patbaumgartner.stringtelephone

import java.sql.DriverManager

class Database(path: String) : AutoCloseable {
    private val conn = java.sql.DriverManager.getConnection("jdbc:sqlite:$path")

    init {
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA foreign_keys=ON")
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
        }
    }

    fun <T> tx(block: (java.sql.Connection) -> T): T {
        conn.autoCommit = false
        try {
            val result = block(conn)
            conn.commit()
            return result
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    fun prepare(sql: String) = conn.prepareStatement(sql)

    override fun close() {
        conn.close()
    }
}

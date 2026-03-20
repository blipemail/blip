package dev.bmcreations.blip.server.db

import kotlinx.coroutines.runBlocking

object Migrations {
    val migrations = listOf(
        """
        CREATE TABLE IF NOT EXISTS sessions (
            id TEXT PRIMARY KEY,
            token TEXT NOT NULL UNIQUE,
            tier TEXT NOT NULL DEFAULT 'FREE',
            user_id TEXT,
            created_at TEXT NOT NULL DEFAULT (datetime('now')),
            expires_at TEXT NOT NULL
        )
        """.trimIndent(),

        """
        CREATE TABLE IF NOT EXISTS users (
            id TEXT PRIMARY KEY,
            email TEXT UNIQUE,
            oauth_provider TEXT,
            oauth_id TEXT,
            created_at TEXT NOT NULL DEFAULT (datetime('now'))
        )
        """.trimIndent(),

        """
        CREATE TABLE IF NOT EXISTS inboxes (
            id TEXT PRIMARY KEY,
            address TEXT NOT NULL UNIQUE,
            domain TEXT NOT NULL DEFAULT 'useblip.email',
            session_id TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT (datetime('now')),
            expires_at TEXT NOT NULL,
            sniper_opens_at TEXT,
            sniper_closes_at TEXT,
            sniper_sealed INTEGER NOT NULL DEFAULT 0,
            FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
        )
        """.trimIndent(),

        """
        CREATE TABLE IF NOT EXISTS emails (
            id TEXT PRIMARY KEY,
            inbox_id TEXT NOT NULL,
            from_addr TEXT NOT NULL,
            to_addr TEXT NOT NULL,
            subject TEXT NOT NULL DEFAULT '',
            text_body TEXT,
            html_body TEXT,
            headers TEXT DEFAULT '{}',
            received_at TEXT NOT NULL DEFAULT (datetime('now')),
            FOREIGN KEY (inbox_id) REFERENCES inboxes(id) ON DELETE CASCADE
        )
        """.trimIndent(),

        """
        CREATE TABLE IF NOT EXISTS attachments (
            id TEXT PRIMARY KEY,
            email_id TEXT NOT NULL,
            name TEXT NOT NULL,
            content_type TEXT NOT NULL,
            size INTEGER NOT NULL,
            data BLOB NOT NULL,
            FOREIGN KEY (email_id) REFERENCES emails(id) ON DELETE CASCADE
        )
        """.trimIndent(),

        """
        CREATE TABLE IF NOT EXISTS webhooks (
            id TEXT PRIMARY KEY,
            session_id TEXT NOT NULL,
            inbox_id TEXT,
            url TEXT NOT NULL,
            secret TEXT NOT NULL,
            enabled INTEGER NOT NULL DEFAULT 1,
            created_at TEXT NOT NULL DEFAULT (datetime('now')),
            FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE,
            FOREIGN KEY (inbox_id) REFERENCES inboxes(id) ON DELETE SET NULL
        )
        """.trimIndent(),

        """
        CREATE TABLE IF NOT EXISTS webhook_deliveries (
            id TEXT PRIMARY KEY,
            webhook_id TEXT NOT NULL,
            email_id TEXT NOT NULL,
            status_code INTEGER,
            attempts INTEGER NOT NULL DEFAULT 0,
            next_retry_at TEXT,
            completed_at TEXT,
            status TEXT NOT NULL DEFAULT 'PENDING',
            created_at TEXT NOT NULL DEFAULT (datetime('now')),
            FOREIGN KEY (webhook_id) REFERENCES webhooks(id) ON DELETE CASCADE,
            FOREIGN KEY (email_id) REFERENCES emails(id) ON DELETE CASCADE
        )
        """.trimIndent(),

        "CREATE INDEX IF NOT EXISTS idx_sessions_token ON sessions(token)",
        "CREATE INDEX IF NOT EXISTS idx_inboxes_session ON inboxes(session_id)",
        "CREATE INDEX IF NOT EXISTS idx_inboxes_address ON inboxes(address)",
        "CREATE INDEX IF NOT EXISTS idx_inboxes_domain ON inboxes(domain)",
        "CREATE INDEX IF NOT EXISTS idx_emails_inbox ON emails(inbox_id)",
        "CREATE INDEX IF NOT EXISTS idx_attachments_email ON attachments(email_id)",
        "CREATE INDEX IF NOT EXISTS idx_inboxes_expires ON inboxes(expires_at)",
        "CREATE INDEX IF NOT EXISTS idx_sessions_expires ON sessions(expires_at)",
        "CREATE INDEX IF NOT EXISTS idx_webhooks_session ON webhooks(session_id)",
        "CREATE INDEX IF NOT EXISTS idx_webhooks_inbox ON webhooks(inbox_id)",
        "CREATE INDEX IF NOT EXISTS idx_webhook_deliveries_webhook ON webhook_deliveries(webhook_id)",

        // Replies table
        """
        CREATE TABLE IF NOT EXISTS replies (
            id TEXT PRIMARY KEY,
            email_id TEXT NOT NULL,
            session_id TEXT NOT NULL,
            to_addr TEXT NOT NULL,
            body TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'pending',
            resend_id TEXT,
            created_at TEXT NOT NULL DEFAULT (datetime('now')),
            FOREIGN KEY (email_id) REFERENCES emails(id) ON DELETE CASCADE,
            FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_replies_session ON replies(session_id)",
        "CREATE INDEX IF NOT EXISTS idx_replies_email ON replies(email_id)",

        // Inbox user ownership for cross-session access
        "ALTER TABLE inboxes ADD COLUMN user_id TEXT",
        "CREATE INDEX IF NOT EXISTS idx_inboxes_user ON inboxes(user_id)",

        // Index for retention cleanup queries
        "CREATE INDEX IF NOT EXISTS idx_emails_received ON emails(received_at)",

        // Session fingerprinting for cross-client session sharing
        "ALTER TABLE sessions ADD COLUMN fingerprint TEXT",
        "CREATE INDEX IF NOT EXISTS idx_sessions_fingerprint ON sessions(fingerprint)",
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email ON users(email)",

        // Forwarding rules table
        """
        CREATE TABLE IF NOT EXISTS forwarding_rules (
            id TEXT PRIMARY KEY,
            inbox_id TEXT NOT NULL,
            session_id TEXT NOT NULL,
            forward_to_email TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT (datetime('now')),
            FOREIGN KEY (inbox_id) REFERENCES inboxes(id) ON DELETE CASCADE,
            FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_forwarding_rules_inbox ON forwarding_rules(inbox_id)",
        "CREATE INDEX IF NOT EXISTS idx_forwarding_rules_session ON forwarding_rules(session_id)",

        // Composite indexes for common query patterns
        "CREATE INDEX IF NOT EXISTS idx_emails_inbox_received ON emails(inbox_id, received_at DESC)",
        "CREATE INDEX IF NOT EXISTS idx_sessions_user_expires ON sessions(user_id, expires_at)",
        "CREATE INDEX IF NOT EXISTS idx_webhook_deliveries_status_retry ON webhook_deliveries(status, next_retry_at)",
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_users_oauth ON users(oauth_provider, oauth_id)",

        // Encryption at rest
        "ALTER TABLE inboxes ADD COLUMN encryption_key TEXT",
        "ALTER TABLE emails ADD COLUMN preview TEXT",

        // Domain management
        """
        CREATE TABLE IF NOT EXISTS domains (
            id TEXT PRIMARY KEY,
            domain TEXT NOT NULL UNIQUE,
            status TEXT NOT NULL DEFAULT 'PENDING_DNS',
            cloudflare_zone_id TEXT,
            resend_domain_id TEXT,
            created_at TEXT NOT NULL DEFAULT (datetime('now')),
            verified_at TEXT
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_domains_domain ON domains(domain)",
        "CREATE INDEX IF NOT EXISTS idx_domains_status ON domains(status)",

        // Seed initial domain
        "INSERT OR IGNORE INTO domains (id, domain, status, created_at, verified_at) VALUES ('seed-bl1p-dev', 'bl1p.dev', 'ACTIVE', datetime('now'), datetime('now'))",

        // Subscription flags on users
        "ALTER TABLE users ADD COLUMN has_pro INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE users ADD COLUMN has_agent INTEGER NOT NULL DEFAULT 0",

        // Stripe customer linkage
        "ALTER TABLE users ADD COLUMN stripe_customer_id TEXT",
    )

    fun run(turso: TursoClient) = runBlocking {
        // Run CREATE TABLE/INDEX statements that don't depend on ALTERs as a batch
        val preBatch = migrations.takeWhile { !it.trimStart().startsWith("ALTER", ignoreCase = true) }
        val rest = migrations.drop(preBatch.size)

        if (preBatch.isNotEmpty()) {
            turso.executeBatch(preBatch.map { Statement(it) })
        }

        // Run remaining statements individually — ignore "duplicate column" errors from ALTERs
        for (stmt in rest) {
            try {
                turso.execute(stmt)
            } catch (e: RuntimeException) {
                if ("duplicate column" !in (e.message ?: "")) throw e
            }
        }
    }
}

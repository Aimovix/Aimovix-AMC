package com.agent.mobile.data.storage.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.agent.mobile.data.storage.db.dao.AgentQueueDao
import com.agent.mobile.data.storage.db.dao.AuditEventDao
import com.agent.mobile.data.storage.db.dao.ChatMessageDao
import com.agent.mobile.data.storage.db.dao.ChatSessionDao
import com.agent.mobile.data.storage.db.dao.CommandAuditDao
import com.agent.mobile.data.storage.db.entity.AgentRunEntity
import com.agent.mobile.data.storage.db.entity.AuditEventEntity
import com.agent.mobile.data.storage.db.entity.ChatMessageEntity
import com.agent.mobile.data.storage.db.entity.ChatSession
import com.agent.mobile.data.storage.db.entity.CommandAuditEntity
import com.agent.mobile.data.storage.db.entity.PendingApprovalEntity

@Database(
    entities = [
        ChatSession::class,
        ChatMessageEntity::class,
        CommandAuditEntity::class,
        PendingApprovalEntity::class,
        AgentRunEntity::class,
        AuditEventEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun commandAuditDao(): CommandAuditDao
    abstract fun agentQueueDao(): AgentQueueDao
    abstract fun auditEventDao(): AuditEventDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** v2: parked approvals and run checkpoints of background runs. Existing data is untouched. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_approvals` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, " +
                        "`runTitle` TEXT NOT NULL, `toolName` TEXT NOT NULL, `argumentsJson` TEXT NOT NULL, " +
                        "`description` TEXT NOT NULL, `riskLevel` TEXT NOT NULL, `riskReason` TEXT NOT NULL, " +
                        "`fingerprint` TEXT NOT NULL, `grantKey` TEXT, `chain` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `decidedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_approvals_status` ON `pending_approvals` (`status`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `agent_runs` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, `kind` TEXT NOT NULL, `status` TEXT NOT NULL, `chain` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
            }
        }

        /**
         * v3: the tamper-evident audit log for every tool call. The old command_audits table stays
         * untouched; its rows are copied into audit_events (oldest first, as origin LEGACY) and
         * chained with the same hash the app uses, so the history starts as a valid chain.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `audit_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, `runId` TEXT NOT NULL, `sessionId` TEXT, `origin` TEXT NOT NULL, " +
                        "`mode` TEXT NOT NULL, `preset` TEXT NOT NULL, `tool` TEXT NOT NULL, `app` TEXT, " +
                        "`arguments` TEXT NOT NULL, `decision` TEXT NOT NULL, `rule` TEXT NOT NULL, `reason` TEXT NOT NULL, " +
                        "`riskLevel` TEXT NOT NULL, `resultSummary` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, " +
                        "`exitCode` INTEGER, `prevHash` TEXT NOT NULL, `hash` TEXT NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_events_runId` ON `audit_events` (`runId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_events_timestamp` ON `audit_events` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_events_decision` ON `audit_events` (`decision`)")
                copyLegacyAudits(db)
            }
        }

        private fun copyLegacyAudits(db: SupportSQLiteDatabase) {
            var prev = com.agent.mobile.audit.AuditLogger.GENESIS
            db.query(
                "SELECT sessionId, command, riskLevel, riskReason, executionDurationMs, exitCode, stdout, stderr, " +
                    "timestamp, wasApproved FROM command_audits ORDER BY timestamp ASC, id ASC"
            ).use { c ->
                while (c.moveToNext()) {
                    val blocked = c.getString(2) == "BLOCKED"
                    val approved = c.getInt(9) != 0
                    val out = c.getString(6).orEmpty().ifEmpty { c.getString(7).orEmpty() }
                    val unsigned = AuditEventEntity(
                        timestamp = c.getLong(8),
                        runId = "legacy",
                        sessionId = if (c.isNull(0)) null else c.getString(0),
                        origin = "LEGACY",
                        mode = "-",
                        preset = "-",
                        tool = "legacy",
                        app = null,
                        arguments = org.json.JSONObject().put("command", com.agent.mobile.audit.AuditRedactor.scrub(c.getString(1).orEmpty()).take(300)).toString(),
                        decision = when {
                            blocked -> com.agent.mobile.audit.AuditDecision.BLOCKED.name
                            approved -> com.agent.mobile.audit.AuditDecision.ALLOWED.name
                            else -> com.agent.mobile.audit.AuditDecision.NOT_RUN.name
                        },
                        rule = "legacy",
                        reason = c.getString(3).orEmpty().take(600),
                        riskLevel = c.getString(2).orEmpty(),
                        resultSummary = com.agent.mobile.audit.AuditRedactor.scrub(out).take(500),
                        durationMs = c.getLong(4),
                        exitCode = c.getInt(5),
                        prevHash = prev,
                        hash = ""
                    )
                    val hash = com.agent.mobile.audit.AuditLogger.hashOf(unsigned)
                    val values = android.content.ContentValues().apply {
                        put("timestamp", unsigned.timestamp)
                        put("runId", unsigned.runId)
                        put("sessionId", unsigned.sessionId)
                        put("origin", unsigned.origin)
                        put("mode", unsigned.mode)
                        put("preset", unsigned.preset)
                        put("tool", unsigned.tool)
                        putNull("app")
                        put("arguments", unsigned.arguments)
                        put("decision", unsigned.decision)
                        put("rule", unsigned.rule)
                        put("reason", unsigned.reason)
                        put("riskLevel", unsigned.riskLevel)
                        put("resultSummary", unsigned.resultSummary)
                        put("durationMs", unsigned.durationMs)
                        put("exitCode", unsigned.exitCode)
                        put("prevHash", prev)
                        put("hash", hash)
                    }
                    db.insert("audit_events", android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, values)
                    prev = hash
                }
            }
        }

        val MIGRATIONS = arrayOf<Migration>(MIGRATION_1_2, MIGRATION_2_3)

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aimovix_amc.db"
                )
                .addMigrations(*MIGRATIONS)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
                .also { INSTANCE = it }
            }
        }
    }
}

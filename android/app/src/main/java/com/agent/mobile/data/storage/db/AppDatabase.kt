package com.agent.mobile.data.storage.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.agent.mobile.data.storage.db.dao.AgentQueueDao
import com.agent.mobile.data.storage.db.dao.ChatMessageDao
import com.agent.mobile.data.storage.db.dao.ChatSessionDao
import com.agent.mobile.data.storage.db.dao.CommandAuditDao
import com.agent.mobile.data.storage.db.entity.AgentRunEntity
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
        AgentRunEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun commandAuditDao(): CommandAuditDao
    abstract fun agentQueueDao(): AgentQueueDao

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

        val MIGRATIONS = arrayOf<Migration>(MIGRATION_1_2)

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

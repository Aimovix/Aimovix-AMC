package com.agent.mobile.data.storage.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.agent.mobile.data.storage.db.dao.ChatMessageDao
import com.agent.mobile.data.storage.db.dao.ChatSessionDao
import com.agent.mobile.data.storage.db.dao.CommandAuditDao
import com.agent.mobile.data.storage.db.entity.ChatMessageEntity
import com.agent.mobile.data.storage.db.entity.ChatSession
import com.agent.mobile.data.storage.db.entity.CommandAuditEntity

@Database(
    entities = [
        ChatSession::class,
        ChatMessageEntity::class,
        CommandAuditEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun commandAuditDao(): CommandAuditDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aimovix_amc.db"
                )
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
            }
        }
    }
}

package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ProxySetting::class, GatewayLog::class, ModelDownloadState::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun proxySettingDao(): ProxySettingDao
    abstract fun gatewayLogDao(): GatewayLogDao
    abstract fun modelDownloadStateDao(): ModelDownloadStateDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Ensure column is added to preserve backward compatibility without losing existing database records
                db.execSQL("ALTER TABLE gateway_logs ADD COLUMN tokensCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ai_proxy_gateway_db_v3"
                )
                    .addMigrations(MIGRATION_3_4)
                    .fallbackToDestructiveMigration()
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build().also { INSTANCE = it }
            }
        }
    }
}

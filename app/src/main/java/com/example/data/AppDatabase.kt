package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ProxySetting::class, GatewayLog::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun proxySettingDao(): ProxySettingDao
    abstract fun gatewayLogDao(): GatewayLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v1 -> v2: introduces the `geminiApiKey` column on `proxy_settings`,
         * which lets users override the BuildConfig-bundled key without a
         * rebuild. Default empty so existing rows keep working unchanged.
         *
         * Before this migration existed, the project relied on
         * `fallbackToDestructiveMigration()` and silently wiped every user's
         * saved port, proxy API key, active model, and provider on upgrade.
         * That is now fixed.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE proxy_settings " +
                            "ADD COLUMN geminiApiKey TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * v2 -> v3: adds the `enableNpuBackend` opt-in flag (default OFF).
         * SQLite stores Boolean as INTEGER 0/1.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE proxy_settings " +
                            "ADD COLUMN enableNpuBackend INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ai_proxy_gateway_db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

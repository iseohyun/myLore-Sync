package com.iseohyun.mylore.sync.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.iseohyun.mylore.sync.data.local.dao.BusinessCardDao
import com.iseohyun.mylore.sync.data.local.dao.NotificationDao
import com.iseohyun.mylore.sync.data.local.dao.SyncCacheDao
import com.iseohyun.mylore.sync.data.local.dao.SyncLogDao
import com.iseohyun.mylore.sync.data.local.entity.*

@Database(
    entities = [
        NotificationEntity::class,
        SyncLogEntity::class,
        BusinessCardEntity::class,
        KakaoRoomCacheEntity::class,
        SmsCacheEntity::class,
        CallLogCacheEntity::class,
        RecordingCacheEntity::class,
        HealthSyncCacheEntity::class,
        PhotoMediaCacheEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun notificationDao(): NotificationDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun businessCardDao(): BusinessCardDao
    abstract fun syncCacheDao(): SyncCacheDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_notifications ADD COLUMN syncedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sms_cache ADD COLUMN syncedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE call_log_cache ADD COLUMN syncedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE recording_cache ADD COLUMN syncedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `photo_media_cache` (" +
                        "`filePath` TEXT NOT NULL, " +
                        "`fileName` TEXT NOT NULL, " +
                        "`folderCategory` TEXT NOT NULL, " +
                        "`mediaType` TEXT NOT NULL, " +
                        "`fileSize` INTEGER NOT NULL, " +
                        "`lastModified` INTEGER NOT NULL, " +
                        "`takenAt` INTEGER NOT NULL, " +
                        "`fileHash` TEXT NOT NULL, " +
                        "`isSynced` INTEGER NOT NULL DEFAULT 0, " +
                        "`syncedAt` INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`filePath`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_photo_media_cache_takenAt` ON `photo_media_cache` (`takenAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_photo_media_cache_fileHash` ON `photo_media_cache` (`fileHash`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_photo_media_cache_isSynced` ON `photo_media_cache` (`isSynced`)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mylore_sync.db"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

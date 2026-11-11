package com.iseohyun.mylore.sync.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.iseohyun.mylore.sync.data.local.dao.BusinessCardDao
import com.iseohyun.mylore.sync.data.local.dao.HealthGpsDao
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
        PhotoMediaCacheEntity::class,
        HealthGpsPointEntity::class,
        HealthGpsDaySummaryEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun notificationDao(): NotificationDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun businessCardDao(): BusinessCardDao
    abstract fun syncCacheDao(): SyncCacheDao
    abstract fun healthGpsDao(): HealthGpsDao

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

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `health_gps_points` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`date` TEXT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`latitude` REAL NOT NULL, " +
                        "`longitude` REAL NOT NULL, " +
                        "`altitude` REAL NOT NULL, " +
                        "`isStayPoint` INTEGER NOT NULL, " +
                        "`stayDurationMinutes` INTEGER NOT NULL, " +
                        "`stayStartTime` INTEGER NOT NULL, " +
                        "`stayEndTime` INTEGER NOT NULL, " +
                        "`sourceFileName` TEXT NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_health_gps_points_date` ON `health_gps_points` (`date`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_health_gps_points_timestamp` ON `health_gps_points` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_health_gps_points_isStayPoint` ON `health_gps_points` (`isStayPoint`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `health_gps_day_summaries` (" +
                        "`date` TEXT PRIMARY KEY NOT NULL, " +
                        "`pointCount` INTEGER NOT NULL, " +
                        "`startTime` INTEGER NOT NULL, " +
                        "`endTime` INTEGER NOT NULL, " +
                        "`totalDistanceMeters` REAL NOT NULL, " +
                        "`stayCount` INTEGER NOT NULL, " +
                        "`totalStayDurationMinutes` INTEGER NOT NULL, " +
                        "`centerLat` REAL NOT NULL, " +
                        "`centerLng` REAL NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_health_gps_day_summaries_date` ON `health_gps_day_summaries` (`date`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_health_gps_day_summaries_startTime` ON `health_gps_day_summaries` (`startTime`)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mylore_sync.db"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

package com.iseohyun.mylore.sync.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
        HealthSyncCacheEntity::class
    ],
    version = 3,
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

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mylore_sync.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}

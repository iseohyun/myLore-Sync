package com.iseohyun.mylore.sync.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.iseohyun.mylore.sync.data.local.dao.BusinessCardDao
import com.iseohyun.mylore.sync.data.local.dao.NotificationDao
import com.iseohyun.mylore.sync.data.local.dao.SyncLogDao
import com.iseohyun.mylore.sync.data.local.entity.BusinessCardEntity
import com.iseohyun.mylore.sync.data.local.entity.NotificationEntity
import com.iseohyun.mylore.sync.data.local.entity.SyncLogEntity

@Database(
    entities = [NotificationEntity::class, SyncLogEntity::class, BusinessCardEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun notificationDao(): NotificationDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun businessCardDao(): BusinessCardDao

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

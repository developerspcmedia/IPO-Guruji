package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.data.model.AllotmentWatchEntity
import com.example.data.model.IpoEntity
import com.example.data.model.PrivateUserProfileEntity

@Database(
    entities = [
        IpoEntity::class,
        PrivateUserProfileEntity::class,
        AllotmentWatchEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
@TypeConverters(IpoTypeConverters::class)
abstract class IpoDatabase : RoomDatabase() {
    abstract fun ipoDao(): IpoDao
    abstract fun allotmentDao(): AllotmentDao

    companion object {
        @Volatile
        private var INSTANCE: IpoDatabase? = null

        fun getInstance(context: Context): IpoDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    IpoDatabase::class.java,
                    "ipo_pulse.db",
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

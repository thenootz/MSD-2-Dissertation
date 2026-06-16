package com.pavlova.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.pavlova.data.dao.ContentItemDao
import com.pavlova.data.dao.FeedSessionDao
import com.pavlova.data.dao.SessionMetricsDao
import com.pavlova.data.model.ContentItem
import com.pavlova.data.model.FeedSession
import com.pavlova.data.model.SessionMetrics
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

@Database(
    entities = [FeedSession::class, ContentItem::class, SessionMetrics::class],
    version = 4,
    exportSchema = false
)
abstract class PavlovaDatabase : RoomDatabase() {

    abstract fun feedSessionDao(): FeedSessionDao
    abstract fun contentItemDao(): ContentItemDao
    abstract fun sessionMetricsDao(): SessionMetricsDao

    companion object {
        @Volatile
        private var INSTANCE: PavlovaDatabase? = null

        fun getDatabase(context: Context): PavlovaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = buildDatabase(context)
                INSTANCE = instance
                instance
            }
        }

        private fun buildDatabase(context: Context): PavlovaDatabase {
            val passphrase = SQLiteDatabase.getBytes("pavlova_secure_key_${context.packageName}".toCharArray())
            val factory = SupportFactory(passphrase)

            return Room.databaseBuilder(
                context.applicationContext,
                PavlovaDatabase::class.java,
                "pavlova_database"
            )
                .openHelperFactory(factory)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}

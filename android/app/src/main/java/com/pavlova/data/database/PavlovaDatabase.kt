package com.pavlova.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.pavlova.data.dao.FilterEventDao
import com.pavlova.data.model.FilterEvent
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

@Database(
    entities = [FilterEvent::class],
    version = 1,
    exportSchema = false
)
abstract class PavlovaDatabase : RoomDatabase() {

    abstract fun filterEventDao(): FilterEventDao

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
            // Use SQLCipher for encryption
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

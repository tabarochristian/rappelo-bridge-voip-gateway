package com.gateway.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gateway.data.db.dao.EventOutboxDao
import com.gateway.data.db.dao.GatewayLogDao
import com.gateway.data.db.dao.QueuedCallDao
import com.gateway.data.db.dao.SmsLogDao
import com.gateway.data.db.dao.SmsQueueDao
import com.gateway.data.db.entity.EventOutboxEntity
import com.gateway.data.db.entity.GatewayLogEntity
import com.gateway.data.db.entity.QueuedCallEntity
import com.gateway.data.db.entity.SmsLogEntity
import com.gateway.data.db.entity.SmsQueueEntity

@Database(
    entities = [
        QueuedCallEntity::class,
        SmsQueueEntity::class,
        SmsLogEntity::class,
        EventOutboxEntity::class,
        GatewayLogEntity::class
    ],
    version = AppDatabase.VERSION,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun queuedCallDao(): QueuedCallDao
    abstract fun smsQueueDao(): SmsQueueDao
    abstract fun smsLogDao(): SmsLogDao
    abstract fun eventOutboxDao(): EventOutboxDao
    abstract fun gatewayLogDao(): GatewayLogDao

    companion object {
        const val VERSION = 1
        const val DATABASE_NAME = "gateway_database.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(*getMigrations())
                .addCallback(DatabaseCallback())
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
        }

        private fun getMigrations(): Array<Migration> {
            return arrayOf(
                // Future migrations go here
                // MIGRATION_1_2,
                // MIGRATION_2_3
            )
        }

        // Example migration template for future use
        @Suppress("unused")
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Example: Add new column
                // db.execSQL("ALTER TABLE queued_calls ADD COLUMN new_column TEXT DEFAULT ''")
            }
        }
    }

    private class DatabaseCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            // Database first-time creation logic
            // Could insert default data if needed
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)
            // Called every time database is opened
            // Good place for cleanup or integrity checks
        }
    }
}

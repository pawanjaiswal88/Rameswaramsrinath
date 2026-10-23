package com.mandir.manager.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        UserEntity::class, MemberEntity::class, DonationEntity::class, ExpenseEntity::class,
        DeletionRequestEntity::class, DeletionLogEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun memberDao(): MemberDao
    abstract fun donationDao(): DonationDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun deletionDao(): DeletionDao

    companion object {
        const val NAME = "mandir.db"

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                    // v1 was the prototype without users or history. From v2 on, every schema
                    // change MUST ship an explicit Migration so the deletion history is never wiped.
                    .fallbackToDestructiveMigrationFrom(1)
                    .addCallback(object : Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) = protectHistory(db)
                    })
                    .build()
                    .also { instance = it }
            }

        /** SQLite itself rejects any attempt to edit or delete deletion history. */
        private fun protectHistory(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS deletion_log_no_delete BEFORE DELETE ON deletion_log " +
                    "BEGIN SELECT RAISE(ABORT, 'Deletion history is permanent'); END"
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS deletion_log_no_update BEFORE UPDATE ON deletion_log " +
                    "BEGIN SELECT RAISE(ABORT, 'Deletion history is permanent'); END"
            )
        }
    }
}

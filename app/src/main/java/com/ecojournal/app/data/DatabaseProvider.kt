package com.ecojournal.app.data

import android.content.Context
import androidx.room.Room

/**
 * Single Room instance for the whole app (M2). Before this, the seed,
 * the repository, every SyncWorker run and every FCM message each
 * built their own database object on the same "eco.db" file, with
 * no shared transaction or lock. The one instance here is never
 * closed by callers; Room owns its lifecycle with the process.
 */
object DatabaseProvider {
    @Volatile
    private var instance: AppDatabase? = null

    fun get(context: Context): AppDatabase {
        return instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "eco.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
        }
    }
}

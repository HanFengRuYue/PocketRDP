package com.hanfengruyue.pocketrdp.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.hanfengruyue.pocketrdp.core.data.model.ConnectionEntity

@Database(
    entities = [ConnectionEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class PocketRdpDatabase : RoomDatabase() {
    abstract fun connectionDao(): ConnectionDao

    companion object {
        const val DB_NAME = "pocketrdp.db"

        /**
         * v1 → v2: adds the per-connection target_frame_rate column. 0 means "auto" —
         * render uncapped at the device refresh rate, i.e. the exact pre-existing behaviour —
         * so every connection created before this column existed keeps rendering as before.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE connections ADD COLUMN target_frame_rate INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        fun create(context: Context): PocketRdpDatabase =
            Room.databaseBuilder(context, PocketRdpDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}

package com.hanfengruyue.pocketrdp.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.hanfengruyue.pocketrdp.core.data.model.ConnectionEntity

@Database(
    entities = [ConnectionEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class PocketRdpDatabase : RoomDatabase() {
    abstract fun connectionDao(): ConnectionDao

    companion object {
        const val DB_NAME = "pocketrdp.db"

        fun create(context: Context): PocketRdpDatabase =
            Room.databaseBuilder(context, PocketRdpDatabase::class.java, DB_NAME).build()
    }
}

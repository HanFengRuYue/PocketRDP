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
    version = 7,
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

        /**
         * v2 → v3: adds the per-connection use_multitransport column. Default 1 (ON) so RDP-UDP
         * multitransport is requested for existing connections too — it auto-falls-back to TCP when
         * the server doesn't support it, so this is a safe, transparent upgrade.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE connections ADD COLUMN use_multitransport INTEGER NOT NULL DEFAULT 1",
                )
            }
        }

        /**
         * v3 → v4: adds the custom_width / custom_height columns for a per-connection fixed
         * remote resolution (issue: 自定义分辨率). Default 0/0 = "not set", so existing connections
         * keep their dynamic-resolution / default-size behaviour untouched.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connections ADD COLUMN custom_width INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE connections ADD COLUMN custom_height INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v4 → v5: adds default_input_mode (0 = 模拟鼠标 / TRACKPAD, 1 = 直接触屏 / TOUCH). Default 0
         * preserves the historical open-in-trackpad behaviour for existing connections.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connections ADD COLUMN default_input_mode INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v5 → v6: adds prefer_avc420 (0 = 画质优先 / AVC444, 1 = 流畅优先 / AVC420). Default 0 keeps the
         * existing full-chroma AVC444 behaviour for all current connections; opting into AVC420 lowers
         * H.264 software-decode cost / control latency at the cost of 4:2:0 chroma.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connections ADD COLUMN prefer_avc420 INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v6 → v7: adds dynamic_res_max — the max remote-resolution cap (short-edge px) applied while
         * dynamic-resolution is on (0 = 跟随设备 / no cap; 720 / 1080 / 1440 otherwise). Default 0 keeps
         * every existing connection's uncapped behaviour (it still follows the full local view size).
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connections ADD COLUMN dynamic_res_max INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun create(context: Context): PocketRdpDatabase =
            Room.databaseBuilder(context, PocketRdpDatabase::class.java, DB_NAME)
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                )
                .build()
    }
}

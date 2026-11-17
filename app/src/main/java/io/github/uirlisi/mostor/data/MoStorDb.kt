package io.github.uirlisi.mostor.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        ChildEntity::class,
        EventEntity::class,
        SegmentEntity::class,
        PresetEntity::class,
        MetaEntity::class,
        SettingEntity::class,
        UndoEntity::class,
        PeerEntity::class,
    ],
    version = MoStorDb.LATEST_VERSION,
    exportSchema = true,
)
abstract class MoStorDb : RoomDatabase() {
    abstract fun events(): EventDao
    abstract fun children(): ChildDao
    abstract fun presets(): PresetDao
    abstract fun meta(): MetaDao
    abstract fun settings(): SettingDao
    abstract fun undo(): UndoDao
    abstract fun peers(): PeerDao

    companion object {
        const val FILE_NAME = "mostor.db"
        const val LATEST_VERSION = 3

        /**
         * Every schema change after a release gets a migration here, with a test. Never add
         * fallbackToDestructiveMigration(): it would delete the family's data.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

        fun build(context: Context, fileName: String = FILE_NAME): MoStorDb =
            Room.databaseBuilder(context.applicationContext, MoStorDb::class.java, fileName)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}

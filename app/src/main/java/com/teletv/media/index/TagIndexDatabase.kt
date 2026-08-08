package com.teletv.media.index

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        IndexedMessage::class,
        TagEntity::class,
        MessageTag::class,
        ScanState::class,
        FavoriteSource::class,
        PlaybackProgress::class,
        PartGroup::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class TagIndexDatabase : RoomDatabase() {
    abstract fun dao(): TagIndexDao
    abstract fun sourceDao(): SourceDao
    abstract fun progressDao(): PlaybackProgressDao

    companion object {
        const val NAME = "tag-index.db"

        /**
         * Adds the playback-progress table. Must be a real migration, not a
         * destructive fallback: the index in this database costs a full re-scan
         * of the source to rebuild, and adding a table is no reason to pay it.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playback_progress` (" +
                        "`chatId` INTEGER NOT NULL, " +
                        "`messageId` INTEGER NOT NULL, " +
                        "`positionMs` INTEGER NOT NULL, " +
                        "`durationMs` INTEGER NOT NULL, " +
                        "`completed` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`chatId`, `messageId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_playback_progress_updatedAt` " +
                        "ON `playback_progress` (`updatedAt`)"
                )
            }
        }

        /**
         * Adds split-video grouping. `size` is additive with a default so the
         * existing index survives; those rows read as "size unknown" until a
         * rescan replaces them, and [GroupDetector] skips size corroboration
         * rather than rejecting a group on their account.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `part_group` (" +
                        "`chatId` INTEGER NOT NULL, " +
                        "`messageId` INTEGER NOT NULL, " +
                        "`groupId` INTEGER NOT NULL, " +
                        "`partIndex` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`chatId`, `messageId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_part_group_chatId_groupId` " +
                        "ON `part_group` (`chatId`, `groupId`)"
                )
                db.execSQL(
                    "ALTER TABLE `indexed_message` ADD COLUMN `size` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * Splits the file name out of `rawText`. Detection needs the name alone:
         * with the caption glued on, the part marker survives in the stem (so
         * sibling parts stop matching) and a trailing caption hijacks the
         * end-anchored weak rules.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `indexed_message` ADD COLUMN `fileName` TEXT NOT NULL DEFAULT ''"
                )
                // Existing rows keep an empty name; TagIndexer notices that and
                // reopens the full scan itself. Deliberately not done here: a
                // migration fires once, so a backfill hung off it is lost for
                // anyone who already passed through this version.
            }
        }

        /**
         * Records which extraction rules a source's tags were built with, so a
         * rules change can rebuild them. Additive with a 0 default: every
         * existing source reads as stale and gets re-extracted once, locally.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `scan_state` ADD COLUMN `extractorVersion` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        fun open(context: Context): TagIndexDatabase =
            Room.databaseBuilder(context, TagIndexDatabase::class.java, NAME)
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                // Last-resort net for version paths with no migration (e.g. a
                // downgrade); the 2→3 upgrade above never reaches it.
                .fallbackToDestructiveMigration()
                .build()
    }
}

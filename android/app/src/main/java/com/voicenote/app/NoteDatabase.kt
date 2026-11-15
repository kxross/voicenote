package com.voicenote.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

@Database(
    entities = [
        NoteEntity::class,
        ListEntity::class,
        ListItemEntity::class,
        GoalEntity::class,
        GoalContextEntity::class,
        PlanEntity::class,
        GoalTaskEntity::class,
        AgentMessageEntity::class,
        ThreadEntity::class,
        NoteImageEntity::class,
        ArtifactEntity::class,
        NoteMediaEntity::class,
        AppliedDeliveryEntity::class,
    ],
    version = 19,
    exportSchema = true,
)
abstract class NoteDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun listDao(): ListDao
    abstract fun goalDao(): GoalDao
    abstract fun agentMessageDao(): AgentMessageDao
    abstract fun threadDao(): ThreadDao
    abstract fun noteImageDao(): NoteImageDao
    abstract fun artifactDao(): ArtifactDao
    abstract fun noteMediaDao(): NoteMediaDao
    abstract fun appliedDeliveryDao(): AppliedDeliveryDao

    companion object {
        private const val TAG = "NoteDatabase"
        private const val DB_NAME = "voicenote.db"

        @Volatile private var INSTANCE: NoteDatabase? = null

        /**
         * v8 -> v9: add `listId` to assistant_threads so a conversation can be
         * scoped to a list the way it already can to a goal.
         *
         * SQLite can't ALTER TABLE ADD a column with a foreign key, so this is
         * the full recreate-and-copy recipe. Only `assistant_threads` is
         * touched — every other table (lists, list_items, goals, notes,
         * note_images, …) is left completely alone, so user data survives.
         *
         * Index names must match Room's `index_<table>_<column>` convention
         * exactly; Room validates the schema on open and throws otherwise.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS assistant_threads_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        goalId INTEGER,
                        listId INTEGER,
                        status TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        resolvedAt INTEGER,
                        FOREIGN KEY(goalId) REFERENCES goals(id)
                            ON UPDATE NO ACTION ON DELETE SET NULL,
                        FOREIGN KEY(listId) REFERENCES lists(id)
                            ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO assistant_threads_new
                        (id, title, goalId, listId, status, createdAt, updatedAt, resolvedAt)
                    SELECT id, title, goalId, NULL, status, createdAt, updatedAt, resolvedAt
                      FROM assistant_threads
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE assistant_threads")
                db.execSQL("ALTER TABLE assistant_threads_new RENAME TO assistant_threads")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_assistant_threads_goalId " +
                        "ON assistant_threads (goalId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_assistant_threads_status " +
                        "ON assistant_threads (status)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_assistant_threads_listId " +
                        "ON assistant_threads (listId)"
                )
            }
        }

        /**
         * v9 -> v10: add a nullable `canvas` column to agent_messages, holding
         * an AI-generated React widget as a JSON string ({title, jsx}). A plain
         * ADD COLUMN — no foreign key, no default — so SQLite does it in place
         * and every other table (and all existing rows) is untouched.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE agent_messages ADD COLUMN canvas TEXT")
            }
        }

        /**
         * v10 -> v11: add a nullable `steps` column to agent_messages, holding
         * the assistant's activity trace (skills loaded, tools called,
         * reasoning) as a JSON array. Plain ADD COLUMN; all existing data is
         * untouched.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE agent_messages ADD COLUMN steps TEXT")
            }
        }

        /**
         * v11 -> v12: add the `artifacts` child table — long-form documents the
         * assistant publishes to the reader/library, tied to the delivering
         * agent message (CASCADE on message delete). New table only; every
         * existing table and row is untouched. Index names must match Room's
         * `index_<table>_<column>` convention exactly.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS artifacts (
                        id TEXT NOT NULL PRIMARY KEY,
                        messageId INTEGER NOT NULL,
                        threadId INTEGER,
                        kind TEXT NOT NULL,
                        title TEXT NOT NULL,
                        text TEXT,
                        ref TEXT,
                        mime TEXT,
                        createdAt INTEGER NOT NULL,
                        readAt INTEGER,
                        FOREIGN KEY(messageId) REFERENCES agent_messages(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_artifacts_messageId ON artifacts (messageId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_artifacts_threadId ON artifacts (threadId)")
            }
        }

        /**
         * v12 -> v13: add a nullable `resumeJson` column to agent_messages,
         * holding a one-tap resume handle for a paused research run. Plain ADD
         * COLUMN; all existing data untouched.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE agent_messages ADD COLUMN resumeJson TEXT")
            }
        }

        /**
         * v13 -> v14: add a nullable `scheduleProposalJson` column to
         * agent_messages for drafted schedule create/edit proposals awaiting
         * approval. Plain ADD COLUMN; all existing data untouched.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE agent_messages ADD COLUMN scheduleProposalJson TEXT")
            }
        }

        /**
         * v14 -> v15: add a nullable `wikiProposalJson` column to agent_messages
         * for drafted learned-wiki entry proposals awaiting approval. Plain ADD
         * COLUMN; all existing data untouched.
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE agent_messages ADD COLUMN wikiProposalJson TEXT")
            }
        }

        /**
         * v15 -> v16: add nullable `sourcesJson` to agent_messages and artifacts
         * for structured research citations (the "Sources" card). Plain ADD
         * COLUMN on both tables; all existing data untouched.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE agent_messages ADD COLUMN sourcesJson TEXT")
                db.execSQL("ALTER TABLE artifacts ADD COLUMN sourcesJson TEXT")
            }
        }

        /**
         * v16 -> v17: in-place document revisions. `revision` (NOT NULL,
         * default 1 so existing rows read as first versions), `updatedAt`, and
         * the previous version's text/title/sources/revision for one-step undo.
         * Plain ADD COLUMNs; existing data untouched.
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE artifacts ADD COLUMN revision INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE artifacts ADD COLUMN updatedAt INTEGER")
                db.execSQL("ALTER TABLE artifacts ADD COLUMN previousText TEXT")
                db.execSQL("ALTER TABLE artifacts ADD COLUMN previousTitle TEXT")
                db.execSQL("ALTER TABLE artifacts ADD COLUMN previousSourcesJson TEXT")
                db.execSQL("ALTER TABLE artifacts ADD COLUMN previousRevision INTEGER")
            }
        }

        /**
         * v17 -> v18: video attachments. New `note_media` table (sibling of
         * note_images), FK to notes with CASCADE. Existing data untouched.
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `note_media` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`noteId` INTEGER NOT NULL, `localPath` TEXT NOT NULL, " +
                        "`thumbPath` TEXT, `mime` TEXT NOT NULL, `bytes` INTEGER NOT NULL, " +
                        "`durationMs` INTEGER NOT NULL, `width` INTEGER NOT NULL, " +
                        "`height` INTEGER NOT NULL, `remoteId` TEXT, `summary` TEXT, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_media_noteId` ON `note_media` (`noteId`)")
            }
        }

        /**
         * v18 -> v19: exactly-once delivery. New `applied_deliveries` table of
         * gateway delivery ids already applied. Existing data untouched.
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `applied_deliveries` (" +
                        "`id` INTEGER NOT NULL, `appliedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
            }
        }

        fun get(context: Context): NoteDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    backupBeforeUpgrade(context)
                    Room.databaseBuilder(
                        context.applicationContext,
                        NoteDatabase::class.java,
                        DB_NAME
                    )
                        .addMigrations(
                            MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                            MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
                            MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
                            MIGRATION_17_18, MIGRATION_18_19,
                        )
                        // NO fallbackToDestructiveMigration: a missing or broken
                        // migration must fail loudly (recoverable) rather than
                        // silently wipe every list, goal, and note.
                        .build()
                        .also { INSTANCE = it }
                }
            }

        /**
         * Copy the database aside once, right before it is first opened at a
         * newer version. Cheap insurance: if a migration ever goes wrong the
         * user's data is still sitting next to it rather than gone.
         */
        private fun backupBeforeUpgrade(context: Context) {
            try {
                val dbFile = context.applicationContext.getDatabasePath(DB_NAME)
                if (!dbFile.exists()) return
                val current = readUserVersion(dbFile) ?: return
                if (current <= 0 || current >= 19) return
                val backup = File(dbFile.parentFile, "$DB_NAME.v$current.bak")
                if (backup.exists()) return
                dbFile.copyTo(backup, overwrite = false)
                Log.i(TAG, "Backed up v$current database to ${backup.name} before migrating")
            } catch (e: Exception) {
                // Never block startup on the safety net itself.
                Log.w(TAG, "Pre-migration backup skipped: ${e.message}")
            }
        }

        /** Reads PRAGMA user_version without going through Room. */
        private fun readUserVersion(dbFile: File): Int? = try {
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            ).use { it.version }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read db version: ${e.message}")
            null
        }
    }
}

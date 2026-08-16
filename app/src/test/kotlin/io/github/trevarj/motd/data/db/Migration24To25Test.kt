package io.github.trevarj.motd.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v24 -> v25 adds the durable resume cursor for the paced background TARGETS backfill. It is purely
 * additive, so the upgrade must leave saved networks exactly as they were, and the new table has to
 * carry its declared shape: a per-network primary key, a `complete` flag that starts false, and a
 * cascade so a removed network cannot strand a cursor that would later resume a backfill for a
 * network the user deleted.
 */
@RunWith(RobolectricTestRunner::class)
class Migration24To25Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    private fun openV24(): SupportSQLiteDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(24) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """CREATE TABLE networks (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                name TEXT NOT NULL,
                                role TEXT NOT NULL,
                                host TEXT NOT NULL,
                                port INTEGER NOT NULL,
                                nick TEXT NOT NULL,
                                autoConnect INTEGER NOT NULL,
                                ordering INTEGER NOT NULL
                            )""",
                        )
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                }).build(),
        )
        return helper!!.writableDatabase
    }

    private fun SupportSQLiteDatabase.insertNetwork(id: Long, name: String) =
        execSQL(
            """INSERT INTO networks (id, name, role, host, port, nick, autoConnect, ordering)
               VALUES ($id, '$name', 'DIRECT', '$name.example', 6697, 'me', 1, 0)""",
        )

    private fun SupportSQLiteDatabase.cursorNetworkIds(): List<Long> =
        query("SELECT networkId FROM history_backfill_cursors ORDER BY networkId").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }

    /** `PRAGMA table_info` for one table, keyed by column name. */
    private fun SupportSQLiteDatabase.columns(table: String): Map<String, Column> =
        query("PRAGMA table_info(`$table`)").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            val type = cursor.getColumnIndexOrThrow("type")
            val notNull = cursor.getColumnIndexOrThrow("notnull")
            val default = cursor.getColumnIndexOrThrow("dflt_value")
            val primaryKey = cursor.getColumnIndexOrThrow("pk")
            buildMap {
                while (cursor.moveToNext()) {
                    put(
                        cursor.getString(name),
                        Column(
                            type = cursor.getString(type),
                            notNull = cursor.getInt(notNull) == 1,
                            default = cursor.getString(default),
                            primaryKeyPosition = cursor.getInt(primaryKey),
                        ),
                    )
                }
            }
        }

    @Test
    fun `the backfill cursor table lands with its declared shape`() {
        val db = openV24()

        MIGRATION_24_25.migrate(db)

        assertEquals(
            mapOf(
                "networkId" to Column("INTEGER", notNull = true, default = null, primaryKeyPosition = 1),
                "upperBound" to Column("INTEGER", notNull = true, default = null, primaryKeyPosition = 0),
                "complete" to Column("INTEGER", notNull = true, default = "0", primaryKeyPosition = 0),
            ),
            db.columns("history_backfill_cursors"),
        )
        // The cascade is part of the shape: it is what keeps a deleted network from stranding a
        // cursor that a later backfill pass would try to resume.
        db.query("PRAGMA foreign_key_list(`history_backfill_cursors`)").use { cursor ->
            assertTrue("expected one foreign key", cursor.moveToFirst())
            assertEquals("networks", cursor.getString(cursor.getColumnIndexOrThrow("table")))
            assertEquals("networkId", cursor.getString(cursor.getColumnIndexOrThrow("from")))
            assertEquals("id", cursor.getString(cursor.getColumnIndexOrThrow("to")))
            assertEquals("CASCADE", cursor.getString(cursor.getColumnIndexOrThrow("on_delete")))
            assertEquals(1, cursor.count)
        }
    }

    @Test
    fun `a freshly seeded cursor starts incomplete`() {
        val db = openV24()
        db.insertNetwork(1, "libera")

        MIGRATION_24_25.migrate(db)

        // The scheduler seeds only the bound it has; `complete` has to default to false, or the
        // network's backfill would be considered finished before a single page was fetched.
        db.execSQL("INSERT INTO history_backfill_cursors(networkId, upperBound) VALUES (1, 1700)")
        db.query("SELECT upperBound, complete FROM history_backfill_cursors WHERE networkId = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1700, cursor.getLong(0))
            assertEquals(0, cursor.getInt(1))
        }
    }

    @Test
    fun `deleting a network takes its cursor with it`() {
        val db = openV24()
        db.execSQL("PRAGMA foreign_keys=ON")
        // Guards the assertion below from passing vacuously on a build where the pragma is inert.
        db.query("PRAGMA foreign_keys").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("foreign keys must be enforced for this test to mean anything", 1, cursor.getInt(0))
        }
        db.insertNetwork(1, "libera")
        db.insertNetwork(2, "soju")

        MIGRATION_24_25.migrate(db)

        db.execSQL("INSERT INTO history_backfill_cursors(networkId, upperBound) VALUES (1, 1700)")
        db.execSQL("INSERT INTO history_backfill_cursors(networkId, upperBound) VALUES (2, 1800)")

        db.execSQL("DELETE FROM networks WHERE id = 1")

        assertEquals(listOf(2L), db.cursorNetworkIds())
    }

    @Test
    fun `saved networks come through the upgrade untouched`() {
        val db = openV24()
        db.insertNetwork(4, "libera")
        db.insertNetwork(9, "soju")

        MIGRATION_24_25.migrate(db)

        db.query("SELECT id, name, host, port, nick, autoConnect FROM networks ORDER BY id").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(4, cursor.getInt(0))
            assertEquals("libera", cursor.getString(1))
            assertEquals("libera.example", cursor.getString(2))
            assertEquals(6697, cursor.getInt(3))
            assertEquals("me", cursor.getString(4))
            assertEquals(1, cursor.getInt(5))
            assertTrue(cursor.moveToNext())
            assertEquals(9, cursor.getInt(0))
            assertEquals("soju", cursor.getString(1))
        }
        // Additive only: the new table exists and starts empty rather than inventing a cursor for
        // networks that have never run an initial sync.
        assertEquals(emptyList<Long>(), db.cursorNetworkIds())
    }

    @Test
    fun `running the migration twice keeps the cursors already written`() {
        val db = openV24()
        db.insertNetwork(1, "libera")

        MIGRATION_24_25.migrate(db)
        db.execSQL("INSERT INTO history_backfill_cursors(networkId, upperBound, complete) VALUES (1, 1700, 1)")
        // IF NOT EXISTS: a re-entered upgrade must not drop the progress already recorded.
        MIGRATION_24_25.migrate(db)

        db.query("SELECT upperBound, complete FROM history_backfill_cursors WHERE networkId = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1700, cursor.getLong(0))
            assertEquals(1, cursor.getInt(1))
        }
    }

    private data class Column(
        val type: String,
        val notNull: Boolean,
        val default: String?,
        val primaryKeyPosition: Int,
    )

    private companion object {
        const val DB_NAME = "migration-24-25-test.db"
    }
}

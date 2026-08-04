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
 * The v23 -> v24 backfill must be invisible to an existing user: the drawer keeps showing exactly
 * the order it showed before the upgrade, only now that order is stored instead of implied.
 */
@RunWith(RobolectricTestRunner::class)
class Migration23To24Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    private fun openV23(): SupportSQLiteDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(23) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """CREATE TABLE networks (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                name TEXT NOT NULL,
                                role TEXT NOT NULL,
                                parentId INTEGER,
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

    private fun SupportSQLiteDatabase.insertNetwork(id: Long, name: String, ordering: Int) =
        execSQL(
            """INSERT INTO networks (id, name, role, host, port, nick, autoConnect, ordering)
               VALUES ($id, '$name', 'DIRECT', '$name.example', 6697, 'me', 1, $ordering)""",
        )

    /** Read the table the way NetworkDao.observeAll does. */
    private fun SupportSQLiteDatabase.displayOrder(): List<String> =
        query("SELECT name FROM networks ORDER BY ordering, id").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    @Test
    fun `every released database ranks its rows without moving any of them`() {
        val db = openV23()
        // What a released database actually looks like: nothing ever wrote `ordering`, so every row
        // sits at 0 and the visible order comes from the id tiebreak alone.
        db.insertNetwork(1, "libera", ordering = 0)
        db.insertNetwork(2, "soju", ordering = 0)
        db.insertNetwork(3, "hackint", ordering = 0)
        val before = db.displayOrder()

        MIGRATION_23_24.migrate(db)

        assertEquals(before, db.displayOrder())
        assertEquals(listOf("libera", "soju", "hackint"), db.displayOrder())
        // Ranked into distinct, gap-free positions, so a later reorder has something to move.
        db.query("SELECT id, ordering FROM networks ORDER BY id").use { cursor ->
            val ordering = buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getInt(1)) }
            assertEquals(listOf(1L to 0, 2L to 1, 3L to 2), ordering)
        }
    }

    @Test
    fun `an imported configuration that already carries positions keeps its order`() {
        val db = openV23()
        // A restored backup writes real `ordering` values, and a merge can leave two rows sharing
        // one position. Rank must respect the stored value first and only then the id.
        db.insertNetwork(1, "third", ordering = 7)
        db.insertNetwork(2, "first", ordering = 2)
        db.insertNetwork(3, "second", ordering = 2)
        val before = db.displayOrder()

        MIGRATION_23_24.migrate(db)

        assertEquals(before, db.displayOrder())
        assertEquals(listOf("first", "second", "third"), db.displayOrder())
        db.query("SELECT ordering FROM networks ORDER BY ordering").use { cursor ->
            val ordering = buildList { while (cursor.moveToNext()) add(cursor.getInt(0)) }
            assertEquals(listOf(0, 1, 2), ordering)
        }
    }

    @Test
    fun `an empty networks table migrates cleanly`() {
        val db = openV23()

        MIGRATION_23_24.migrate(db)

        db.query("SELECT COUNT(*) FROM networks").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun `no row is dropped and nothing but ordering is rewritten`() {
        val db = openV23()
        db.insertNetwork(4, "libera", ordering = 0)
        db.insertNetwork(9, "soju", ordering = 0)

        MIGRATION_23_24.migrate(db)

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
    }

    private companion object {
        const val DB_NAME = "migration-23-24-test.db"
    }
}

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

@RunWith(RobolectricTestRunner::class)
class Migration18To19Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test fun `migration adds per-network ignore table`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(18) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """CREATE TABLE networks (
                                id INTEGER PRIMARY KEY NOT NULL,
                                name TEXT NOT NULL
                            )""",
                        )
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        val db = helper!!.writableDatabase
        db.execSQL("PRAGMA foreign_keys=ON")
        db.execSQL("INSERT INTO networks(id, name) VALUES (1, 'libera')")

        MIGRATION_18_19.migrate(db)

        db.execSQL(
            """INSERT INTO network_ignores(networkId, pattern, enabled, createdAt)
               VALUES (1, 'alice!*@*', 1, 10)""",
        )
        db.query("SELECT pattern, enabled FROM network_ignores WHERE networkId = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("alice!*@*", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
        }
    }

    private companion object { const val DB_NAME = "migration-18-19-test.db" }
}

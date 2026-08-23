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
class Migration20To21Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test fun `migration preserves visible order and records temporal provenance`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name(DB_NAME)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(20) {
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                db.execSQL(
                                    """CREATE TABLE messages (
                                id INTEGER PRIMARY KEY NOT NULL,
                                bufferId INTEGER NOT NULL,
                                serverTime INTEGER NOT NULL,
                                serverTimeAuthoritative INTEGER NOT NULL
                            )""",
                                )
                            }

                            override fun onUpgrade(
                                db: SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    ).build(),
            )
        val db = helper!!.writableDatabase
        db.execSQL(
            "INSERT INTO messages(id, bufferId, serverTime, serverTimeAuthoritative) " +
                "VALUES (7, 1, 1000, 1), (9, 1, 1000, 0)",
        )

        MIGRATION_20_21.migrate(db)

        db
            .query(
                "SELECT id, timelineOrder, timelineOrderConfirmed, timeProvenance " +
                    "FROM messages ORDER BY id",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(7L, cursor.getLong(0))
                assertEquals(7L, cursor.getLong(1))
                assertEquals(0, cursor.getInt(2))
                assertEquals("SERVER_TAG", cursor.getString(3))
                assertTrue(cursor.moveToNext())
                assertEquals(9L, cursor.getLong(1))
                assertEquals("LOCAL_CLOCK", cursor.getString(3))
            }
        db.query("PRAGMA index_list(messages)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            var found = false
            while (cursor.moveToNext()) {
                found = found || cursor.getString(nameColumn) ==
                    "index_messages_bufferId_serverTime_timelineOrder"
            }
            assertTrue(found)
        }
    }

    private companion object {
        const val DB_NAME = "migration-20-21-test.db"
    }
}

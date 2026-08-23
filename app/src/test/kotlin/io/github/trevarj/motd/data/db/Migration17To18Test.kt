package io.github.trevarj.motd.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration17To18Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test fun `migration adds pending credential state without changing networks`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name(DB_NAME)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(17) {
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                db.execSQL(
                                    """CREATE TABLE networks (
                                id INTEGER PRIMARY KEY NOT NULL,
                                name TEXT NOT NULL,
                                autoConnect INTEGER NOT NULL
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
        db.execSQL("INSERT INTO networks(id, name, autoConnect) VALUES (1, 'libera', 1)")

        MIGRATION_17_18.migrate(db)

        db
            .query(
                "SELECT name, autoConnect, pendingCredentialRequirements, restoreAutoConnect FROM networks WHERE id = 1",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("libera", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
                assertNull(cursor.getString(2))
                assertEquals(0, cursor.getInt(3))
            }
    }

    private companion object {
        const val DB_NAME = "migration-17-18-test.db"
    }
}

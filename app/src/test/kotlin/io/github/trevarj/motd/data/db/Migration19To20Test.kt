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
class Migration19To20Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test fun `migration adds durable dcc transfer table`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(19) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """CREATE TABLE networks (
                                id INTEGER PRIMARY KEY NOT NULL,
                                name TEXT NOT NULL
                            )""",
                        )
                        db.execSQL(
                            """CREATE TABLE buffers (
                                id INTEGER PRIMARY KEY NOT NULL,
                                networkId INTEGER NOT NULL
                            )""",
                        )
                        db.execSQL(
                            """CREATE TABLE messages (
                                id INTEGER PRIMARY KEY NOT NULL,
                                bufferId INTEGER NOT NULL
                            )""",
                        )
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        val db = helper!!.writableDatabase
        db.execSQL("PRAGMA foreign_keys=ON")
        db.execSQL("INSERT INTO networks(id, name) VALUES (1, 'libera')")
        db.execSQL("INSERT INTO buffers(id, networkId) VALUES (10, 1)")
        db.execSQL("INSERT INTO messages(id, bufferId) VALUES (100, 10)")

        MIGRATION_19_20.migrate(db)

        db.execSQL(
            """INSERT INTO dcc_transfers(
                   networkId, timelineEventId, offerKey, direction, protocol, peerNick,
                   normalizedPeer, filename, displayFilename, address, addressKind, port,
                   sizeBytes, token, state, bytesTransferred, createdAt, expiresAt, updatedAt
               ) VALUES (
                   1, 100, 'offer-1', 'INCOMING', 'SEND', 'Alice', 'alice',
                   'file.bin', 'file.bin', '192.0.2.10', 'IPV4_DOTTED', 49152,
                   1024, NULL, 'OFFERED', 0, 10, 310, 10
               )""",
        )

        db.query("SELECT filename, state FROM dcc_transfers WHERE offerKey = 'offer-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("file.bin", cursor.getString(0))
            assertEquals("OFFERED", cursor.getString(1))
        }
    }

    private companion object { const val DB_NAME = "migration-19-20-test.db" }
}

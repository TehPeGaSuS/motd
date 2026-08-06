package io.github.trevarj.motd.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Migration25To26Test {
    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        helper?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test
    fun migrationAddsInheritedPresenceOverrideAndTheActorIndexWithoutLosingRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(25) {
                    override fun onCreate(db: SupportSQLiteDatabase) = createExportedVersion25(db)
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        val db = helper!!.writableDatabase
        db.execSQL(
            """INSERT INTO networks(id, name, role, host, port, tls, nick, username, realname,
                saslMechanism, autoConnect, ordering, restoreAutoConnect)
               VALUES (1, 'net', 'DIRECT', 'irc.example', 6697, 1, 'me', 'me', 'Me', 'NONE', 1, 0, 1)""",
        )
        db.execSQL(
            """INSERT INTO buffers(id, networkId, name, displayName, type, joined, membershipCycle,
                pinned, muted, archived, ordering, historyComplete, dismissed)
               VALUES (1, 1, '#motd', '#motd', 'CHANNEL', 1, 0, 0, 0, 0, 0, 0, 0)""",
        )
        db.execSQL(
            """INSERT INTO messages(id, bufferId, serverTime, sender, normalizedActor, kind, text,
                isSelf, hasMention, failed, dedupKey, serverTimeAuthoritative, timelineOrder,
                timelineOrderConfirmed, timeProvenance, notificationHandled, notificationClaimed,
                soundHandled)
               VALUES (1, 1, 1000, 'alice', 'alice', 'PRIVMSG', 'kept', 0, 0, 0, 'd1', 1, 1, 1,
                'SERVER_TIME', 0, 0, 0)""",
        )

        MIGRATION_25_26.migrate(db)

        // Existing conversations inherit the global mode rather than being pinned to one.
        db.query("SELECT COUNT(*) FROM buffers WHERE presenceModeOverride IS NULL").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM messages").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        db.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = " +
                "'index_messages_bufferId_normalizedActor_serverTime'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        db.execSQL("UPDATE buffers SET presenceModeOverride = 'HIDDEN' WHERE id = 1")
        db.query("SELECT presenceModeOverride FROM buffers WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("HIDDEN", cursor.getString(0))
        }
    }

    private fun createExportedVersion25(db: SupportSQLiteDatabase) {
        val resource = "${MotdDatabase::class.java.canonicalName}/25.json"
        val schema = checkNotNull(javaClass.classLoader?.getResourceAsStream(resource))
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val database = schema.getValue("database").jsonObject
        database.getValue("entities").jsonArray.forEach { element ->
            val entity = element.jsonObject
            val tableName = entity.getValue("tableName").jsonPrimitive.content
            fun executeTemplate(sql: String) = db.execSQL(sql.replace("\${TABLE_NAME}", tableName))
            executeTemplate(entity.getValue("createSql").jsonPrimitive.content)
            entity["indices"]?.jsonArray.orEmpty().forEach { index ->
                executeTemplate(index.jsonObject.getValue("createSql").jsonPrimitive.content)
            }
            entity["contentSyncTriggers"]?.jsonArray.orEmpty().forEach { trigger ->
                db.execSQL(trigger.jsonPrimitive.content)
            }
        }
        database.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
    }

    private companion object {
        const val DB_NAME = "migration-25-26-test.db"
    }
}

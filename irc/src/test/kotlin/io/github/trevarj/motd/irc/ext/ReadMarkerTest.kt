package io.github.trevarj.motd.irc.ext

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadMarkerTest {
    @Test
    fun `set preserves required millisecond precision for exact seconds`() {
        assertEquals(
            "MARKREAD alice timestamp=1970-01-01T00:00:01.000Z",
            ReadMarkerCommands.set("alice", 1_000).serialize(),
        )
    }

    @Test
    fun `set preserves nonzero milliseconds`() {
        assertEquals(
            "MARKREAD alice timestamp=1970-01-01T00:00:01.234Z",
            ReadMarkerCommands.set("alice", 1_234).serialize(),
        )
    }

    @Test
    fun `soju READ set emits the soju im read command with the same timestamp shape`() {
        assertEquals(
            "READ #motd timestamp=1970-01-01T00:00:01.000Z",
            ReadMarkerCommands.set("READ", "#motd", 1_000).serialize(),
        )
    }

    @Test
    fun `soju READ get emits the bare command`() {
        assertEquals("READ #motd", ReadMarkerCommands.get("READ", "#motd").serialize())
        assertEquals("MARKREAD #motd", ReadMarkerCommands.get("MARKREAD", "#motd").serialize())
    }
}

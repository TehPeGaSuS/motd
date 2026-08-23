package io.github.trevarj.motd.dcc

import io.github.trevarj.motd.data.db.DccAddressKind
import io.github.trevarj.motd.data.db.DccTransferProtocol
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class DccProtocolTest {
    @Test fun `send ctcp quotes filenames with spaces`() {
        val ctcp =
            dccSendCtcp(
                DccOutgoingOffer(
                    protocol = DccTransferProtocol.SEND,
                    filename = "two words.txt",
                    address = "3232235777",
                    port = 5000,
                    sizeBytes = 42,
                    token = "tok",
                ),
            )

        assertEquals("\u0001DCC SEND \"two words.txt\" 3232235777 5000 42 tok\u0001", ctcp)
    }

    @Test fun `resume and accept preserve passive token`() {
        assertEquals(
            "\u0001DCC RESUME file.bin 0 1024 abc\u0001",
            dccResumeCtcp("file.bin", 0, 1024, "abc"),
        )
        assertEquals(
            "\u0001DCC ACCEPT file.bin 0 1024 abc\u0001",
            dccAcceptCtcp("file.bin", 0, 1024, "abc"),
        )
    }

    @Test fun `ipv4 integer addresses round trip`() {
        val address = resolveDccAddress("3232235777", DccAddressKind.IPV4_INTEGER)
        assertEquals("192.168.1.1", address.hostAddress)
        assertEquals("3232235777" to DccAddressKind.IPV4_INTEGER, advertiseDccAddress(address))
    }

    @Test fun `endpoint risk blocks local addresses by default`() {
        assertEquals(DccEndpointRisk.LOOPBACK, dccEndpointRisk(InetAddress.getByName("127.0.0.1")))
        assertEquals(DccEndpointRisk.PRIVATE, dccEndpointRisk(InetAddress.getByName("192.168.1.2")))
        assertEquals(DccEndpointRisk.PRIVATE, dccEndpointRisk(InetAddress.getByName("fd00::1")))
        assertEquals(DccEndpointRisk.PUBLIC, dccEndpointRisk(InetAddress.getByName("8.8.8.8")))
    }

    @Test fun `display filename strips path and controls`() {
        assertEquals("passwd", sanitizeDccDisplayFilename("../etc/passwd"))
        assertEquals("download", sanitizeDccDisplayFilename("\u0000"))
    }
}

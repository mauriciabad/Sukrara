// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.localserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket

/** The home-network rules: which address, which networks, which callers and Host headers. */
class LanPolicyTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    private fun v4(s: String) = ip(s) as Inet4Address

    private fun net(
        vararg addrs: Pair<String, Int>,
        secured: Boolean = true,
        gateway: String? = "192.168.1.1",
    ) = WifiNet(addrs.map { ip(it.first) to it.second }, gateway, "192.168.1.1", "home", secured)

    @Test fun `only an RFC 1918 address on a secured Wi-Fi`() {
        assertEquals(LanState.NoWifi, (LanPolicy.pick(null, null) as LanPolicy.Pick.Blocked).state)
        val ok = LanPolicy.pick(net("fe80::1" to 64, "192.168.1.23" to 24), null) as LanPolicy.Pick.Ok
        assertEquals(v4("192.168.1.23"), ok.target.address)
        assertEquals(24, ok.target.prefix)
        for (a in listOf("100.64.0.5", "169.254.3.4", "8.8.8.8", "172.32.0.1")) {
            assertEquals(a, LanState.NotPrivate, (LanPolicy.pick(net(a to 24), null) as LanPolicy.Pick.Blocked).state)
        }
        assertTrue(LanPolicy.pick(net("10.0.0.2" to 8), null) is LanPolicy.Pick.Ok)
        assertTrue(LanPolicy.pick(net("172.16.5.2" to 16), null) is LanPolicy.Pick.Ok)
        // a /31 or /32 isn't a home network either
        assertEquals(LanState.NotPrivate, (LanPolicy.pick(net("192.168.1.2" to 32), null) as LanPolicy.Pick.Blocked).state)
        assertEquals(LanState.OpenWifi, (LanPolicy.pick(net("192.168.1.23" to 24, secured = false), null) as LanPolicy.Pick.Blocked).state)
    }

    @Test fun `pinned to the network it was turned on in`() {
        val home = (LanPolicy.pick(net("192.168.1.23" to 24), null) as LanPolicy.Pick.Ok).target.fingerprint
        // a new DHCP lease on the same network keeps working
        assertTrue(LanPolicy.pick(net("192.168.1.40" to 24), home) is LanPolicy.Pick.Ok)
        val other = net("192.168.1.40" to 24, gateway = "192.168.1.254")
        assertEquals(LanState.OtherWifi, (LanPolicy.pick(other, home) as LanPolicy.Pick.Blocked).state)
        assertEquals(LanState.OtherWifi, (LanPolicy.pick(net("192.168.1.23" to 24), LanPin.UNKNOWN) as LanPolicy.Pick.Blocked).state)
    }

    @Test fun `callers from outside the subnet are dropped, mapped addresses included`() {
        val bound = v4("192.168.1.23")
        assertTrue(LanPolicy.sameSubnet(ip("192.168.1.50"), bound, 24))
        assertTrue(LanPolicy.sameSubnet(ip("::ffff:192.168.1.50"), bound, 24))
        assertFalse(LanPolicy.sameSubnet(ip("192.168.2.50"), bound, 24))
        assertFalse(LanPolicy.sameSubnet(ip("::ffff:192.168.2.50"), bound, 24))
        // the phone's own hotspot clients (another subnet) and the internet
        assertFalse(LanPolicy.sameSubnet(ip("192.168.43.2"), bound, 24))
        assertFalse(LanPolicy.sameSubnet(ip("10.42.0.5"), bound, 24))
        assertFalse(LanPolicy.sameSubnet(ip("2001:db8::1"), bound, 24))
    }

    @Test fun `the Host header must name the bound address`() {
        val bound = v4("192.168.1.23")
        assertTrue(LanPolicy.hostOk("192.168.1.23:17581", bound, 17581))
        assertTrue(LanPolicy.hostOk("192.168.1.23", bound, 17581))
        assertFalse(LanPolicy.hostOk(null, bound, 17581))
        assertFalse(LanPolicy.hostOk("evil.example:17581", bound, 17581))
        assertFalse(LanPolicy.hostOk("localhost:17581", bound, 17581))
        assertFalse(LanPolicy.hostOk("192.168.1.23:80", bound, 17581))
    }

    @Test fun `a server with a caller filter closes refused callers without an answer, and checks Host`() {
        val loop = v4("127.0.0.1")
        var allow = false
        val s =
            LocalHttpServer(
                0,
                { HttpAnswer("200 OK", "ok") },
                bindAddress = loop,
                allowCaller = { allow },
                hostOk = { it == "127.0.0.1" },
            )
        assertTrue(s.bind())
        try {
            fun get(host: String?): String =
                Socket(loop, s.boundPort).use { c ->
                    c.soTimeout = 3_000
                    val h = host?.let { "Host: $it\r\n" } ?: ""
                    c.getOutputStream().write("GET /x HTTP/1.1\r\n${h}\r\n".toByteArray())
                    // a refused caller is closed at once: nothing to read (or a reset)
                    runCatching { c.getInputStream().readBytes().decodeToString() }.getOrDefault("")
                }
            assertEquals("", get("127.0.0.1"))
            allow = true
            assertTrue(get("127.0.0.1").startsWith("HTTP/1.1 200"))
            assertTrue(get("evil.example").startsWith("HTTP/1.1 403"))
            assertTrue(get(null).startsWith("HTTP/1.1 403"))
        } finally {
            s.stop()
        }
    }
}

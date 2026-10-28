// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.nightscout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app permits cleartext (res/xml/network_security_config.xml) because Android can't list the home network's IP
 * ranges there; [NsSite.parse] is the gate (docs/25, guarded by tools/check `cleartext`). Plain http only to this
 * phone or a home-network host, decided on the literal host; everything else is refused, however it's dressed up.
 */
class NsSiteTest {
    private fun refused(url: String) {
        val s = NsSite.parse(url)
        assertNotNull("$url must be refused", s.problem)
        assertFalse(url, s.insecure)
    }

    @Test fun publicHostsOverHttpAreRefused() {
        for (u in listOf(
            "http://my.ns.example", "http://8.8.8.8", "http://8.8.8.8:1337",
            "http://192.168.1.5.nip.io",            // a public DNS name that merely starts like a private IP
            "http://10.0.0.5.example.com", "http://localhost.evil.com", "http://127.0.0.1.evil.com",
            "http://[::ffff:8.8.8.8]",              // IPv4-mapped public address
            "http://[::ffff:192.168.1.5]",          // mapped forms aren't on the list either
            "http://[2001:db8::1]", "http://[64:ff9b::808:808]",
            "http://10.0.0.1@evil.com",             // userinfo: the host is evil.com
            "http://user:pw@192.168.1.5",           // credentials in the URL, even to a home host
            "http://evil.com#@192.168.1.5", "http://evil.com?@192.168.1.5",
            "HTTP://evil.com", "Http://8.8.8.8", "HTTP://EVIL.COM",
            "http://nightscout.local.",             // trailing dot: not normalised, refused
            "http://local", "http://.local", "http://evil.com.local.evil.com",
            "http://172.32.0.1", "http://172.15.255.255", "http://192.169.0.1", "http://169.255.0.1",
            "http://3232235777",                    // 192.168.1.1 as a decimal integer
            "http://0xc0.0xa8.1.5", "http://0300.0250.1.5", "http://192.168.1",   // non-canonical IPv4 forms
            "ws://192.168.1.5", "ftp://192.168.1.5", "file:///etc/hosts", "javascript:alert(1)",
            "", "   ", "192.168.1.5", "//192.168.1.5",
        )) refused(u)
    }

    @Test fun httpsAnywhereAndHttpOnlyAtHome() {
        assertNull(NsSite.parse("HTTPS://My.NS.example/").problem)
        for (u in listOf("http://localhost:1337", "http://127.0.0.1", "http://[::1]:1337", "HTTP://LOCALHOST:1337")) {
            assertNull(u, NsSite.parse(u).problem); assertFalse(u, NsSite.parse(u).insecure)
        }
        for (u in listOf("http://192.168.1.5:1337", "HTTP://192.168.1.5", "http://10.0.0.1", "http://172.16.0.1",
                "http://172.31.255.254", "http://169.254.1.1", "http://NightScout.Local", "http://[fe80::1]", "http://[fd00::5]")) {
            assertNull(u, NsSite.parse(u).problem); assertTrue(u, NsSite.parse(u).insecure)
        }
        assertEquals("https", NsSite.parse("http://evil.com").problem)
    }
}

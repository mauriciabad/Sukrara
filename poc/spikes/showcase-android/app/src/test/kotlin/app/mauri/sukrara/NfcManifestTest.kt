// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Android 17: an app targeting SDK > BAKLAVA only gets NFC intents (NDEF / TECH / TAG_DISCOVERED) dispatched to activities
 * protected by DISPATCH_NFC_MESSAGE (0.11.46 on the user's Pixel: "PenReadActivity does not have DISPATCH_NFC_MESSAGE
 * permission" → no match). Every activity or alias with an NFC filter carries it, and is a real activity (not an alias of
 * MainActivity, which can't be protected); nothing of ours starts those activities itself (we can't hold the permission).
 */
class NfcManifestTest {
    private val manifest = File("src/main/AndroidManifest.xml")
    private val android = "http://schemas.android.com/apk/res/android"

    private fun nfcEntries(): List<Element> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(manifest)
        return listOf("activity", "activity-alias").flatMap { tag ->
            val l = doc.getElementsByTagName(tag); (0 until l.length).map { l.item(it) as Element }
        }.filter { e ->
            val actions = e.getElementsByTagName("action")
            (0 until actions.length).any { (actions.item(it) as Element).getAttributeNS(android, "name").startsWith("android.nfc.action.") }
        }
    }

    @Test fun everyNfcEntryPointIsProtected() {
        val entries = nfcEntries()
        assertTrue("expected the pen and the sensor tap", entries.size >= 2)
        for (e in entries) {
            val name = e.getAttributeNS(android, "name")
            assertEquals("$name: NFC filters need android:permission=DISPATCH_NFC_MESSAGE (Android 17)",
                "android.permission.DISPATCH_NFC_MESSAGE", e.getAttributeNS(android, "permission"))
            assertEquals("$name: an NFC entry point is an activity, not an alias", "activity", e.tagName)
        }
    }

    @Test fun noCodeStartsAProtectedActivity() {
        val names = nfcEntries().map { it.getAttributeNS(android, "name").substringAfterLast('.') }
        File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            val text = f.readText()
            for (n in names) assertTrue("${f.name} starts $n, which only the NFC service may start",
                !Regex("""\b$n::class""").containsMatchIn(text))
        }
    }
}

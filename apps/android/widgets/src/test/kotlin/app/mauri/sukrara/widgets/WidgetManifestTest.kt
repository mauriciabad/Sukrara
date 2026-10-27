// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The widget receivers and their provider xml are complete and battery-friendly (no periodic update). */
class WidgetManifestTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private val main = File("src/main")

    private fun parse(f: File): Element =
        DocumentBuilderFactory
            .newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(f)
            .documentElement

    private fun Element.children(tag: String) = getElementsByTagName(tag).let { l -> (0 until l.length).map { l.item(it) as Element } }

    private fun Element.a(name: String) = getAttributeNS(ns, name)

    @Test
    fun `every widget kind has a receiver with a valid provider xml`() {
        val receivers = parse(File(main, "AndroidManifest.xml")).children("receiver").associateBy { it.a("name") }
        for (kind in WidgetKind.entries) {
            val name = "app.mauri.sukrara.widgets.${kind.widget().javaClass.simpleName}Receiver"
            val r = receivers[name] ?: error("$name missing from the manifest")
            assertEquals("true", r.a("exported"))
            assertTrue(r.children("action").any { it.a("name") == "android.appwidget.action.APPWIDGET_UPDATE" })
            val meta = r.children("meta-data").single { it.a("name") == "android.appwidget.provider" }
            val xml = File(main, "res/xml/${meta.a("resource").removePrefix("@xml/")}.xml")
            assertTrue("$xml exists", xml.isFile)
            val info = parse(xml)
            assertEquals("no periodic update: the app redraws on change", "0", info.a("updatePeriodMillis"))
            assertEquals(WidgetConfigActivity::class.java.name, info.a("configure"))
            assertTrue(info.a("widgetFeatures").split("|").containsAll(listOf("reconfigurable", "configuration_optional")))
            val preview = File(main, "res/layout/${info.a("previewLayout").removePrefix("@layout/")}.xml")
            assertTrue("$preview exists", preview.isFile)
            assertTrue(info.a("description").startsWith("@string/"))
        }
    }

    @Test
    fun `the settings screen answers the configure action and the pin callback stays private`() {
        val app = parse(File(main, "AndroidManifest.xml"))
        val config = app.children("activity").single { it.a("name") == WidgetConfigActivity::class.java.name }
        assertTrue(config.children("action").any { it.a("name") == "android.appwidget.action.APPWIDGET_CONFIGURE" })
        val pinned = app.children("receiver").single { it.a("name").endsWith("PinnedReceiver") }
        assertEquals("false", pinned.a("exported"))
    }
}

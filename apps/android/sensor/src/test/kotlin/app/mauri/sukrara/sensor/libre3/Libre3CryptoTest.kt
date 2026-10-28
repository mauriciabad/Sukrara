// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.libre3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/** jni.c and [Libre3Crypto] must name the same functions, or the core fails at the first sensor handshake. */
class Libre3CryptoTest {
    private val jni = File("src/main/cpp/jni.c").readText()

    @Test
    fun `every jni symbol belongs to Libre3Crypto and every external fun has a symbol`() {
        val prefix = "Java_" + Libre3Crypto::class.java.name.replace('.', '_') + "_"
        val symbols = Regex("""\bJava_\w+(?=\s*\()""").findAll(jni).map { it.value }.toSet()
        assertTrue("no JNI symbols found in jni.c", symbols.isNotEmpty())
        symbols.forEach { assertTrue("$it isn't a Libre3Crypto function", it.startsWith(prefix)) }
        val natives =
            Libre3Crypto::class.java.declaredMethods
                .filter { Modifier.isNative(it.modifiers) }
                .map { prefix + it.name }
                .toSet()
        assertEquals(natives, symbols)
    }
}

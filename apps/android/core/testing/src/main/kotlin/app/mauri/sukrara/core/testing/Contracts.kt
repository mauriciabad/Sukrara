// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.testing

import java.io.File

/**
 * The cross-platform contracts (`<repo>/contracts`) in tests. The path comes from the `sukrara.contracts` system
 * property, which the `sukrara.contracts` convention plugin sets on the module's test tasks.
 */
object Contracts {
    const val PROPERTY = "sukrara.contracts"

    val root: File
        get() {
            val path =
                System.getProperty(PROPERTY)
                    ?: error("System property '$PROPERTY' is not set: apply id(\"sukrara.contracts\") in this module's build file.")
            return File(path).also { check(it.isDirectory) { "contracts folder $it does not exist" } }
        }

    /** A contract file by its path relative to `contracts/`; fails clearly when it is missing. */
    fun file(path: String): File = File(root, path).also { check(it.isFile) { "contract file contracts/$path does not exist" } }
}

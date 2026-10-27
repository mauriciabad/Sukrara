// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import java.io.File

/** A file of the repo's contracts/ (the `sukrara.contracts` system property, set by the build). */
object TestContracts {
    fun file(path: String): File {
        val root = checkNotNull(System.getProperty("sukrara.contracts")) { "system property sukrara.contracts is not set" }
        return File(root, path).also { check(it.exists()) { "missing contract $it" } }
    }
}

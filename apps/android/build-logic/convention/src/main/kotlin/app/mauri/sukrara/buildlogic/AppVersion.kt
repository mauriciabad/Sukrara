// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.buildlogic

/**
 * The app version from `<repo>/VERSION` (`major.minor.patch`, the one version source of both apps).
 * versionCode = major·1 000 000 + minor·1 000 + patch, so it grows with every release (0.12.0 → 12000); minor and
 * patch stay below 1000 so two versions never share a code.
 */
data class AppVersion(val major: Int, val minor: Int, val patch: Int) {
    val name get() = "$major.$minor.$patch"
    val code get() = major * 1_000_000 + minor * 1_000 + patch

    /** Every 0.x build is a release candidate (never 1.0.0 without the owner). */
    val prerelease get() = major == 0

    companion object {
        private val FORMAT = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")

        fun parse(text: String): AppVersion {
            val match = FORMAT.matchEntire(text.trim())
                ?: throw IllegalArgumentException("VERSION must be major.minor.patch (each 0-999), got '${text.trim()}'")
            val (major, minor, patch) = match.destructured
            return AppVersion(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}

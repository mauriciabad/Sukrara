// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.buildlogic

import org.gradle.api.Project
import java.io.File

/**
 * The Libre 3 security core (`libsukrara_libre3.so`) is compiled by `:sensor` from a local Juggluco checkout (GPLv3,
 * not vendored). Where it is: Gradle property `sukrara.juggluco`, else env `SUKRARA_JUGGLUCO`, else
 * `<git common dir>/../third_party/Juggluco` (the main checkout's, also from a git worktree). The checkout must be
 * at the commit pinned in `apps/android/sensor/juggluco.lock` with a clean tree, so every release has the same core.
 */
object NativeCore {
    const val LIBRARY = "lib/arm64-v8a/libsukrara_libre3.so"
    private const val MARKER = "Common/src/main/cpp/libre3/process/libre3_app_core.c"

    /** The Juggluco checkout, or null when it isn't there (a debug build then has no Libre 3 core). */
    fun locate(project: Project): File? {
        val explicit = project.providers.gradleProperty("sukrara.juggluco")
            .orElse(project.providers.environmentVariable("SUKRARA_JUGGLUCO")).orNull
        val dir = explicit?.let(::File) ?: mainCheckout(project)?.resolve("third_party/Juggluco")
        return dir?.canonicalFile?.takeIf { File(it, MARKER).isFile }
    }

    /** Why a release can't use this checkout, or null when it is the pinned, clean revision. */
    fun problem(project: Project, dir: File?): String? {
        if (dir == null) {
            return "no Juggluco checkout found. Clone https://github.com/j-kaltes/Juggluco into <repo>/third_party/Juggluco " +
                "(or set -Psukrara.juggluco / SUKRARA_JUGGLUCO), then check out ${lock(project)}."
        }
        val head = git(project, dir, "rev-parse", "HEAD")
            ?: return "Juggluco at $dir isn't a readable git checkout (git rev-parse HEAD failed)."
        if (head != lock(project)) return "Juggluco at $dir is at $head, the lock (sensor/juggluco.lock) pins ${lock(project)}."
        val dirty = git(project, dir, "status", "--porcelain", "--untracked-files=no")
            ?: return "Juggluco at $dir: git status failed."
        if (dirty.isNotEmpty()) return "Juggluco at $dir has local changes:\n$dirty"
        return null
    }

    private fun lock(project: Project) =
        project.rootProject.file("sensor/juggluco.lock").readText().trim()

    /** The main checkout (parent of the git common dir), also when building in a worktree. */
    private fun mainCheckout(project: Project): File? =
        git(project, project.rootDir, "rev-parse", "--path-format=absolute", "--git-common-dir")
            ?.let(::File)?.takeIf { it.isDirectory }?.parentFile

    /**
     * git's output, or null when git fails (not a repository, git missing, "dubious ownership" on CI): this runs
     * while Gradle configures every build, and a debug build must still work then. A release turns null into a problem.
     */
    private fun git(project: Project, dir: File, vararg args: String): String? = runCatching {
        val result = project.providers.exec {
            commandLine("git", "-C", dir.path, *args)
            isIgnoreExitValue = true
        }
        result.standardOutput.asText.get().trim().takeIf { result.result.get().exitValue == 0 }
    }.getOrNull()
}

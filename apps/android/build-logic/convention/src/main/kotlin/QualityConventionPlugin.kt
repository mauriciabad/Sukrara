// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType

/**
 * Static checks every module runs in `./gradlew check`. Android Lint aborts on errors and treats a curated set of
 * warnings as errors (the ones that hurt the person using the app: untranslated or hardcoded text, exported
 * components, API levels); everything else stays a warning. Pre-existing findings live in `lint-baseline.xml`.
 */
class QualityConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.withPlugin("com.android.base") {
            extensions.getByType<CommonExtension>().lint.apply {
                abortOnError = true
                checkReleaseBuilds = true
                checkDependencies = false
                baseline = file("lint-baseline.xml")
                error += LINT_ERRORS
            }
        }
    }

    private companion object {
        val LINT_ERRORS = setOf(
            "HardcodedText",
            "MissingTranslation",
            "ExtraTranslation",
            "ExportedReceiver",
            "ExportedService",
            "ExportedContentProvider",
            "UnspecifiedRegisterReceiverFlag",
            "UnspecifiedImmutableFlag",
            "NewApi",
            "InlinedApi",
            "MissingClass",
            "StringFormatInvalid",
            "StringFormatMatches",
        )
    }
}

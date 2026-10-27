// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.setup

import app.mauri.sukrara.core.model.Role
import kotlinx.serialization.Serializable

/**
 * The first-run setup steps, in their binding order (brief 24, CHANGES P2–P5, P10): the sensor runs in V1 before the
 * alerts are confirmed and long before the identity can be restored (a restore cuts Sukrara Showcase off from the
 * followers, so it waits until this app has the sensor).
 */
enum class SetupStep {
    Welcome,
    Role,
    Permissions,

    /** Shown only while Sukrara Showcase is installed. */
    Showcase,
    Sensor,
    Alerts,
    Identity,
    ;

    /** Welcome and Role can't be skipped: the app does nothing useful without a role. Every other step can. */
    val skippable: Boolean get() = this != Welcome && this != Role
}

/**
 * What setup has recorded, persisted (typed store `setup`). The role is NOT here: it lives in its own store (`role`,
 * the hub and the alert runtime read it), so a damaged setup file only shows the skipped steps again and never turns
 * a running wearer's alerts off.
 */
@Serializable
data class SetupRecord(
    val completed: Set<SetupStep> = emptySet(),
    val skipped: Set<SetupStep> = emptySet(),
    /** The person said alerts and the sensor are off in Sukrara Showcase (P3). */
    val showcaseOffConfirmed: Boolean = false,
    /** The person said sharing is off in Sukrara Showcase (A3 restore gate; separate from [showcaseOffConfirmed]). */
    val showcaseSharingOffConfirmed: Boolean = false,
)

/** What setup reads from the phone and the other areas, re-read on every resume. */
data class SetupFacts(
    /** The phone's role (its own store); null = not chosen yet. */
    val role: Role?,
    val showcaseInstalled: Boolean,
    /** Following on Android exists in this build (chunk 25). */
    val followerAvailable: Boolean,
    /** A live reading from this phone's own sensor ([RestoreGate.ownSensorLive]). */
    val ownSensorLive: Boolean,
    /** This phone already has a sharing identity (restored or started fresh). */
    val hasIdentity: Boolean,
    /** The alert thresholds were confirmed (here or in Settings › Alerts: `alerts.thresholdsConfirmedAt`). */
    val alertsConfirmed: Boolean = false,
)

/** One line of the home "Finish setup" card: a skipped step that still matters; tapping reopens [step]. */
enum class FinishItem(val step: SetupStep) {
    AllowAlerts(SetupStep.Permissions),
    TurnOffShowcase(SetupStep.Showcase),
    ConnectSensor(SetupStep.Sensor),
    AlertsNotConfirmed(SetupStep.Alerts),
    RestoreFollowers(SetupStep.Identity),
}

/** Why choosing a role didn't happen. */
enum class RoleRefusal {
    /** P10: the role is chosen only after the person confirmed what it means. */
    NotConfirmed,

    /** Following on Android isn't in this build yet: setup stays on the role step. */
    FollowerNotAvailable,
}

/**
 * The pure setup rules: the ordered steps, what completes or skips each one, and the "Finish setup" items. No Android,
 * no I/O; [SetupViewModel] persists the records it returns.
 */
object SetupMachine {
    fun steps(facts: SetupFacts): List<SetupStep> =
        when (facts.role) {
            // follower join (chunk 25) adds its own step here
            Role.Follower -> listOf(SetupStep.Welcome, SetupStep.Role, SetupStep.Permissions, SetupStep.Showcase, SetupStep.Alerts)
            Role.Wearer, null -> SetupStep.entries
        }.filter { it != SetupStep.Showcase || facts.showcaseInstalled }

    /** The step to show now, or null when setup is over (home). Resumes exactly where the person left. */
    fun next(
        record: SetupRecord,
        facts: SetupFacts,
    ): SetupStep? = steps(facts).firstOrNull { !done(it, record, facts) && it !in record.skipped }

    /** Welcome and Role are done once a role exists (a damaged setup file never asks for the role again). */
    private fun done(
        step: SetupStep,
        record: SetupRecord,
        facts: SetupFacts,
    ): Boolean =
        step in record.completed ||
            (facts.role != null && (step == SetupStep.Welcome || step == SetupStep.Role)) ||
            (step == SetupStep.Alerts && facts.alertsConfirmed)

    fun completeWelcome(record: SetupRecord): SetupRecord = record.done(SetupStep.Welcome)

    /** P10: the role to store, or why not: refused until [confirmed]; follower refused while it isn't in this build. */
    fun chooseRole(
        role: Role,
        confirmed: Boolean,
        facts: SetupFacts,
    ): Result<Role, RoleRefusal> =
        when {
            role == Role.Follower && !facts.followerAvailable -> Result.Refused(RoleRefusal.FollowerNotAvailable)
            !confirmed -> Result.Refused(RoleRefusal.NotConfirmed)
            else -> Result.Done(role)
        }

    /** The checklist step is done (missing items stay on the home reliability row). */
    fun completePermissions(record: SetupRecord): SetupRecord = record.done(SetupStep.Permissions)

    /** "I've turned it off" (confirmed in the UI). */
    fun confirmShowcaseOff(record: SetupRecord): SetupRecord = record.copy(showcaseOffConfirmed = true).done(SetupStep.Showcase)

    /** The sensor step completes only with a real live reading from this phone's sensor. */
    fun completeSensor(
        record: SetupRecord,
        facts: SetupFacts,
    ): SetupRecord = if (facts.ownSensorLive) record.done(SetupStep.Sensor) else record

    /** P4: the only way to complete the alert step is "Confirm these alerts" (the thresholds are saved first). */
    fun confirmAlerts(record: SetupRecord): SetupRecord = record.done(SetupStep.Alerts)

    /** The identity exists (restored, or started fresh after confirming). */
    fun completeIdentity(
        record: SetupRecord,
        facts: SetupFacts,
    ): SetupRecord = if (facts.hasIdentity) record.done(SetupStep.Identity) else record

    fun confirmShowcaseSharingOff(
        record: SetupRecord,
        off: Boolean,
    ): SetupRecord = record.copy(showcaseSharingOffConfirmed = off)

    /** Skip / "Later": the step goes to the Finish-setup card. Welcome and Role can't be skipped. */
    fun skip(
        record: SetupRecord,
        step: SetupStep,
    ): SetupRecord = if (step.skippable) record.copy(skipped = record.skipped + step) else record

    /**
     * The home card's items: skipped steps that still matter. They drop out by themselves once the fact behind them
     * changes (the Showcase was uninstalled, a reading arrived, an identity exists).
     */
    fun finishItems(
        record: SetupRecord,
        facts: SetupFacts,
    ): List<FinishItem> {
        if (facts.role == null) return emptyList()
        val steps = steps(facts)
        return FinishItem.entries.filter { item ->
            val step = item.step
            step in steps &&
                step !in record.completed &&
                step in record.skipped &&
                when (item) {
                    FinishItem.TurnOffShowcase -> facts.showcaseInstalled && !record.showcaseOffConfirmed
                    FinishItem.ConnectSensor -> !facts.ownSensorLive
                    FinishItem.RestoreFollowers -> !facts.hasIdentity
                    FinishItem.AlertsNotConfirmed -> !facts.alertsConfirmed
                    else -> true
                }
        }
    }

    /** Reopened from the Finish-setup card: shown again until it is completed or skipped again. */
    fun reopen(
        record: SetupRecord,
        step: SetupStep,
    ): SetupRecord = record.copy(skipped = record.skipped - step)

    private fun SetupRecord.done(step: SetupStep) = copy(completed = completed + step, skipped = skipped - step)
}

/** A pure decision: the new value, or why not. */
sealed interface Result<out T, out E> {
    data class Done<T>(val value: T) : Result<T, Nothing>

    data class Refused<E>(val reason: E) : Result<Nothing, E>
}

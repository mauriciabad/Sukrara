// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.owner.usecase

import app.mauri.sukrara.protocol.KeyPair
import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.protocol.Wire
import app.mauri.sukrara.protocol.event.BackupCodec
import app.mauri.sukrara.protocol.event.Profile
import app.mauri.sukrara.protocol.owner.RecoveryCode
import app.mauri.sukrara.share.owner.EpochKey
import app.mauri.sukrara.share.owner.OwnerState
import app.mauri.sukrara.share.transport.RelayFilter

/** A brand-new identity I, device key D, stream id and epoch 1; publishes roster and backup. */
class CreateIdentity(private val ctx: OwnerContext) {
    operator fun invoke(profile: Profile?, now: Long): Step {
        val s = OwnerState(
            identityHex = ctx.crypto.generate().secret.revealHex(),
            deviceHex = ctx.crypto.generate().secret.revealHex(),
            sid = ControlEvents.newSid(ctx.random),
            epochs = listOf(EpochKey(1, ctx.crypto.generate().secret.revealHex())),
            profileJson = profile?.toJson()?.toString(),
        )
        return ControlEvents.roster(ctx, s, now) + { ControlEvents.backup(ctx, it, now) }
    }
}

/**
 * A new epoch: key deliveries to every active follower first (so they hold the key before they see the roster), then
 * the roster naming this phone only, then the backup. The engine republishes the last 24 h of readings afterwards.
 */
class Rotate(private val ctx: OwnerContext) {
    operator fun invoke(s: OwnerState, now: Long): Step {
        val next = s.copy(epochs = s.epochs + EpochKey(s.current.n + 1, ctx.crypto.generate().secret.revealHex()))
        var step = Step(next)
        next.activeFollowers().forEach { f -> step += { ControlEvents.deliverKey(ctx, it, f.key, now) } }
        return step + { ControlEvents.roster(ctx, it, now) } + { ControlEvents.backup(ctx, it, now) }
    }
}

/** The recovery code (NIP-49 ncryptsec, log_n 18 ≈ 1–3 s: call it off the main thread). Never logged. */
class ExportRecovery(private val ctx: OwnerContext) {
    operator fun invoke(s: OwnerState, password: String): String = RecoveryCode.export(ctx.crypto, s.identity(ctx.crypto).secret, password)
}

/**
 * Phone change (PROTOCOL-identity "Restore"): the identity from the recovery code →
 * the newest owner backup on the relays → a new device key D′ and a new epoch (deliveries to every follower, roster
 * `[D′]`, backup). Followers keep following; the old phone's events stop being accepted.
 */
class RestoreIdentity(private val ctx: OwnerContext) {
    sealed interface Result {
        data class Restored(val step: Step, val followers: Int) : Result

        data object NeedsPassword : Result

        data object WrongPassword : Result

        data object NotAKey : Result

        /** No relay answered: offline or relays down. Retry later; nothing changed. */
        data object RelaysUnreachable : Result

        /** Relays answered but hold no backup for this identity (wrong code, or never shared). */
        data class NoBackupFound(val npub: String) : Result

        data object BackupUnreadable : Result

        /**
         * The newest roster names an epoch the newest backup doesn't hold: another device is still publishing as this
         * identity (e.g. the old phone rotated after its last backup). Rotating now could reuse its epoch number.
         * Turn the other app's sharing off, wait a minute, retry.
         */
        data object BackupOutdated : Result

        /** This phone already shares with an identity: restoring replaces it only when the person confirmed. */
        data object AlreadySharing : Result
    }

    /** Step 1: the identity in [code] (`nsec1…`, `ncryptsec1…` + [password], or hex). */
    fun parse(code: String, password: String?): Pair<KeyPair?, Result?> = when (val r = RecoveryCode.import(ctx.crypto, code, password)) {
        is RecoveryCode.Import.Restored -> ctx.crypto.keyPair(r.identity)!! to null
        RecoveryCode.Import.NeedsPassword -> null to Result.NeedsPassword
        RecoveryCode.Import.WrongPassword -> null to Result.WrongPassword
        RecoveryCode.Import.NotAKey -> null to Result.NotAKey
    }

    /** The relay query for everything [identity] signs: its owner backup and its roster (both addressable 30078). */
    fun identityEventsFilter(identity: KeyPair) = listOf(RelayFilter(kinds = listOf(Wire.KIND_DATA), authors = listOf(identity.public.hex)))

    /** Step 2: from what the relays returned ([answered] relays sent EOSE) to the restored state and its events. */
    fun restore(identity: KeyPair, found: List<SignedEvent>, answered: Int, now: Long): Result {
        val newest = found
            .filter { it.pubkey == identity.public.hex && it.kind == Wire.KIND_DATA && it.tag("d") == Wire.backupD(identity.public) }
            .filter { ctx.crypto.verify(it) }
            .maxByOrNull { it.createdAt }
        if (newest == null) return if (answered == 0) Result.RelaysUnreachable else Result.NoBackupFound(ctx.crypto.npub(identity.public))
        val plain = ctx.crypto.decrypt(identity.secret, identity.public, newest.content) ?: return Result.BackupUnreadable
        val backup = runCatching { BackupCodec.decode(plain) }.getOrNull() ?: return Result.BackupUnreadable
        val restored = ControlEvents.fromBackup(ctx.crypto, identity.secret.revealHex(), ctx.crypto.generate().secret.revealHex(), backup)
            ?: return Result.BackupUnreadable
        // relays keep the newest (author, d): our roster / backup must be newer than the old device's
        val rosters = found.filter { it.pubkey == identity.public.hex && it.tag("d") == Wire.rosterD(backup.sid) && ctx.crypto.verify(it) }
        val newestRoster = rosters.maxByOrNull { it.createdAt }
        val knownGks = restored.epochs.map { Wire.gk(restored.readKey(ctx.crypto, it).public) }.toSet()
        if (newestRoster != null && newestRoster.tag("gk") !in knownGks) return Result.BackupOutdated
        val lastRoster = newestRoster?.createdAt ?: 0
        val seeded = restored.copy(rosterPublishedAt = lastRoster, backupPublishedAt = newest.createdAt)
        return Result.Restored(Rotate(ctx)(seeded, now), restored.activeFollowers().size)
    }
}

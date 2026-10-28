// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import app.mauri.sukrara.core.data.diag.DiagLog
import app.mauri.sukrara.core.data.settings.ImportResult
import app.mauri.sukrara.core.data.settings.SettingsTransfer
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.feature.settings.advanced.AdvancedPort
import app.mauri.sukrara.feature.settings.advanced.AdvancedState
import app.mauri.sukrara.feature.settings.advanced.RelayRow
import app.mauri.sukrara.feature.settings.advanced.SettingsImport
import app.mauri.sukrara.protocol.NostrCrypto
import app.mauri.sukrara.protocol.PublicKeyHex
import app.mauri.sukrara.share.follower.FollowState
import app.mauri.sukrara.share.follower.FollowerEngine
import app.mauri.sukrara.share.owner.OwnerEngine
import app.mauri.sukrara.share.owner.OwnerShareState
import app.mauri.sukrara.share.transport.RelayStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Settings › Advanced over the role's sharing engine (the wearer's owner engine or the follower engine), the
 * diagnostic log and the settings file. Shows public keys only, shortened.
 */
class AppAdvanced(
    private val role: Flow<Role?>,
    private val owner: OwnerEngine,
    private val follower: FollowerEngine,
    private val crypto: NostrCrypto,
    private val diag: DiagLog,
    private val transfer: SettingsTransfer,
) : AdvancedPort {
    private var isFollower = false

    override val state: Flow<AdvancedState> =
        combine(role, owner.state, follower.state) { r, own, follow ->
            isFollower = r == Role.Follower
            if (r == Role.Follower) {
                AdvancedState(
                    follower = true,
                    relays = rows((follow as? FollowState.Following)?.relays.orEmpty()),
                    identity = follower.followerKey()?.let(::short),
                )
            } else {
                val relays =
                    when (own) {
                        is OwnerShareState.Sharing -> own.relays
                        is OwnerShareState.AnotherAppSharingAsYou -> own.relays
                        else -> emptyMap()
                    }
                AdvancedState(
                    relays = rows(relays),
                    identity =
                        owner
                            .identityKey()
                            ?.public
                            ?.hex
                            ?.let(::short),
                    epoch = (own as? OwnerShareState.Sharing)?.epoch,
                )
            }
        }

    override fun checkNow() = if (isFollower) follower.reconnectNow() else owner.reconnectNow()

    override suspend fun log(): String = diag.snapshot()

    override suspend fun exportSettings(): String = transfer.export()

    override suspend fun importSettings(text: String): SettingsImport =
        when (val r = transfer.import(text)) {
            is ImportResult.Done -> SettingsImport.Done(r.count)
            ImportResult.NotSettings -> SettingsImport.NotSettings
        }

    override suspend fun resetSettings() = transfer.reset()

    private fun rows(relays: Map<String, RelayStatus>): List<RelayRow> =
        relays.entries.sortedBy { it.key }.map { (url, s) -> RelayRow(url, s.connected, s.lastError) }

    /** `npub1abcdefgh…uvwxyz`: enough to compare by eye, short enough for a row. */
    private fun short(hex: String): String {
        val npub = runCatching { crypto.npub(PublicKeyHex(hex)) }.getOrNull() ?: return hex.take(8) + "…"
        return npub.take(NPUB_HEAD) + "…" + npub.takeLast(NPUB_TAIL)
    }

    private companion object {
        const val NPUB_HEAD = 13
        const val NPUB_TAIL = 6
    }
}

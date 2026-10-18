// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.treatments

/**
 * Which key may change which treatment entry (spec/ogs-nostr.md N§9.2, PROPOSALS P4): a writer may create entries and
 * change or delete only the ones it created; the wearer may change anything. Every receiver remembers the *creator*
 * of each `id` (the key that signed the first version it accepted). Mirrored by Mac `WriterScope` (Treatments.swift).
 */
object WriterScope {
    enum class Verdict { ACCEPT, IGNORE, REJECT, OVERRIDE }

    /**
     * The owner, for an entry signed by writer [author]: [creator] = the writer recorded for this `id` (null = none),
     * [inOwnLog] = the id is already in the wearer's log. An id the wearer's log has with no recorded writer is the
     * wearer's own entry.
     */
    fun ownerAllows(author: String, creator: String?, inOwnLog: Boolean): Boolean = when {
        creator != null -> creator == author
        else -> !inOwnLog
    }

    /**
     * A follower, for a version of an entry signed by [author] ([fromDevice] = a roster device key, i.e. the wearer's
     * merged log), with [creator] = who it recorded for this `id` (null = never seen), [storedO] = the `o` of the version
     * it holds, and [devices] the roster's.
     *  - a writer: only for a new id or one it created. When the wearer's copy of a writer's entry (`o` = `follower`)
     *    came first, the creator is the wearer's device and the writer's own copy adds nothing the wearer won't echo:
     *    [Verdict.IGNORE] (not a rejection). Anything else is [Verdict.REJECT].
     *  - the wearer: always merged. A wearer's own entry (`o` isn't `follower`) whose recorded creator is a writer was
     *    forged by that writer before the real one arrived: the wearer's version replaces it whatever its `u`
     *    ([Verdict.OVERRIDE]).
     */
    fun follower(author: String, fromDevice: Boolean, incO: String, creator: String?, storedO: String?,
                 devices: Collection<String>): Verdict = when {
        !fromDevice -> when {
            creator == null || creator == author -> Verdict.ACCEPT
            creator in devices && storedO == "follower" -> Verdict.IGNORE
            else -> Verdict.REJECT
        }
        incO != "follower" && creator != null && creator !in devices -> Verdict.OVERRIDE
        else -> Verdict.ACCEPT
    }

    /** The creator to record after accepting [author]'s version ([verdict] ACCEPT or OVERRIDE). */
    fun creatorAfter(verdict: Verdict, author: String, creator: String?): String =
        if (verdict == Verdict.OVERRIDE) author else creator ?: author
}

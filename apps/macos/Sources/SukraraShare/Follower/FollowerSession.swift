// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// The follower side of protocol v2 as a pure state machine: the acceptance rules of PROTOCOL-v2 ("Acceptance rules",
/// "Treatments", "Settings", "Sensor life", "Alerts"), ported from the reference follower
/// (`poc/spec/reference/ogs/follower.py`) and pinned by the OGS vectors. Every transport (relays, Bluetooth) feeds
/// `receive`; no event is trusted for where it came from. No I/O, no clock: `now` is passed in.
///
/// Unfollowing = discarding the state (and its Keychain item); there is nothing to send.
public struct FollowerSession: Sendable {
    public enum Verdict: Equatable, Sendable, CustomStringConvertible {
        case accepted(String)
        case rejected(String)
        case ignored(String)

        /// `accepted:key`, `rejected:writer-not-creator`, … (the OGS vector strings).
        public var description: String {
            switch self {
            case .accepted(let what): "accepted:\(what)"
            case .rejected(let why): "rejected:\(why)"
            case .ignored(let why): "ignored:\(why)"
            }
        }
    }

    /// What changed for the app.
    public enum Delta: Equatable, Sendable {
        case readings([WireReading])
        /// The wearer's sensor from the newest live event; nil = the wearer knows none.
        case sensor(SensorLife?)
        case alert(AlertPayload)
        /// `fromWearer`: signed by a wearer device (its merged log). `creator`: store with the entry. `override`: the
        /// wearer's own entry replaces a writer's forged version whatever its `u`.
        case treatment(TreatmentPayload, fromWearer: Bool, creator: String, override: Bool)
        case settings(SettingsPayload)
        case status(LinkStatus)
        /// Devices, writers, relays or the wearer's profile changed (persist the state, refresh the UI).
        case roster
    }

    public struct Outcome: Equatable, Sendable {
        public var verdict: Verdict
        public var deltas: [Delta] = []
        /// Events to send to the relays and the wearer's phone over Bluetooth (rekey requests).
        public var publish: [SignedEvent] = []
        /// The filters changed: re-issue `filters(now:newestStored:)`. Events dropped as `unknown-epoch` are not
        /// marked seen, so a re-fetch delivers them again; events rejected for another reason stay rejected.
        public var resubscribe = false

        init(_ verdict: Verdict) { self.verdict = verdict }
    }

    public private(set) var state: FollowerState
    private let crypto: any NostrCrypto
    private let messages: FollowerMessages
    private let followerPublic: String
    /// gk → (epoch, read key).
    private var epochKeys: [String: (n: Int, g: SecretKeyHex)] = [:]
    /// Event ids whose fate is final → when they may be forgotten (their expiry, at most a day).
    private var seen: [String: Int] = [:]
    private var rekeyAskedAt: [String: Int] = [:]
    /// d tag → created_at of our last treatment event (strictly increasing per d).
    private var lastTreatmentCreatedAt: [String: Int] = [:]
    private static let seenLimit = 5000

    public init(state: FollowerState, crypto: any NostrCrypto) throws(CryptoError) {
        self.state = state
        self.crypto = crypto
        messages = FollowerMessages(crypto: crypto, follower: state.follower)
        followerPublic = try crypto.publicKey(of: state.follower)
        for epoch in state.epochs { try addEpoch(epoch) }
    }

    /// A new follower for an opened invite: makes the follower key `F` (never printed or logged). Status `new`.
    public static func join(invite: Invite, me: Profile, nearby: Bool, crypto: any NostrCrypto, now: Int)
        throws(JoinError) -> FollowerSession
    {
        guard invite.expiry > now else { throw .expired }
        let secret = crypto.generateSecret()
        guard let pub = try? crypto.publicKey(of: secret) else { throw .keyFailed }
        let state = FollowerState(
            follower: secret, identity: invite.identity, token: invite.token,
            code: Wire.matchCode(followerPublicKey: pub, token: invite.token), nearby: nearby, me: me,
            wearer: invite.profile, relays: invite.relays.isEmpty ? Wire.defaultRelays : invite.relays)
        guard let session = try? FollowerSession(state: state, crypto: crypto) else { throw .keyFailed }
        return session
    }

    public enum JoinError: Error, Equatable, Sendable {
        case expired
        case keyFailed
    }

    public var followerPublicKey: String { followerPublic }
    /// The newest epoch number, nil before the first key.
    public var epoch: Int? { state.epochs.map(\.n).max() }
    /// May add treatment entries: active and `F` is in the newest roster's writers.
    public var canWrite: Bool { state.status == .active && state.writers.contains(followerPublic) }

    // MARK: - outgoing

    /// The access request (gift wrap to the identity, 7 d). Status → `requested`. Sending it again is allowed (a
    /// retry while still waiting).
    public mutating func requestAccess(now: Int) throws(CryptoError) -> SignedEvent {
        let wrap = try messages.toOwner(
            FollowerMessages.requestBody(token: state.token, profile: state.me), identity: state.identity,
            ttl: Wire.ttlRequest, now: now)
        if state.status == .new {
            state.status = .requested
            state.requestedAt = now
        }
        return wrap
    }

    /// Changes how the wearer sees this follower; returns the profile update to send (the wearer applies it only from
    /// an approved follower).
    public mutating func setProfile(typedName: String, avatar: Avatar?, now: Int) throws(CryptoError) -> SignedEvent {
        state.me = Profile(name: Profile.normalisedName(typed: typedName, previous: state.me.name), avatar: avatar)
        return try messages.toOwner(
            FollowerMessages.profileBody(state.me), identity: state.identity, ttl: Wire.ttlRequest, now: now)
    }

    /// A treatment entry as a writer, encrypted to the newest epoch; nil when not allowed to write.
    public mutating func treatmentEvent(_ entry: TreatmentPayload, now: Int) throws(CryptoError) -> SignedEvent? {
        guard canWrite, let sid = state.sid, let newest = state.epochs.max(by: { $0.n < $1.n }) else { return nil }
        let d = Wire.treatmentD(sid: sid, id: entry.id)
        let createdAt = max(now, (lastTreatmentCreatedAt[d] ?? 0) + 1)
        lastTreatmentCreatedAt[d] = createdAt
        return try messages.treatment(entry, sid: sid, groupSecret: newest.g, createdAt: createdAt)
    }

    /// A history request for the hours from `since` (PROTOCOL-v2 "Missing history"; the caller throttles).
    public func historyRequest(since hourStart: Int, now: Int) throws(CryptoError) -> SignedEvent {
        try messages.toOwner(
            FollowerMessages.historyBody(since: hourStart), identity: state.identity, ttl: Wire.ttlHistory, now: now)
    }

    // MARK: - subscriptions

    /// The REQ filters by subscription id. `newestStored` = newest reading already stored (sizes the backfill).
    public func filters(now: Int, newestStored: Int?) -> [String: RelayFilter] {
        var out = ["inbox": RelayFilter(kinds: [Wire.kindWrap], p: [followerPublic], since: now - 3 * 86_400)]
        guard let sid = state.sid, state.status == .active else { return out }
        out["roster"] = RelayFilter(kinds: [Wire.kindData], authors: [state.identity], d: [Wire.rosterD(sid: sid)])
        guard !state.devices.isEmpty else { return out }
        let devices = state.devices.sorted()
        out["chunks"] = RelayFilter(
            kinds: [Wire.kindData], authors: devices, c: [Wire.glucoseC(sid: sid)],
            since: Self.chunkSince(newestStored: newestStored, now: now))
        out["live"] = RelayFilter(kinds: [Wire.kindLive, Wire.kindAlert], authors: devices, since: now - 120)
        out["settings"] = RelayFilter(kinds: [Wire.kindData], authors: devices, d: [Wire.settingsD(sid: sid)])
        out["treatments"] = RelayFilter(
            kinds: [Wire.kindData], authors: Array(Set(devices + state.writers)).sorted(),
            c: [Wire.treatmentsC(sid: sid)], since: now - Wire.ttlTreatment)
        return out
    }

    /// Chunk backfill window: an hour before the newest stored reading, at least the last 13 h, at most the chunks'
    /// 7-day relay expiry.
    public static func chunkSince(newestStored: Int?, now: Int) -> Int {
        max(now - 7 * 86_400, min(now - 13 * 3600, (newestStored ?? now) - 3600))
    }

    /// Hour starts among the last 12 whole hours without a stored reading; nil while the stream isn't live (newest
    /// reading > 15 min old: a real gap, nothing to ask for).
    public static func missingHours(stored: [Int], now: Int) -> [Int]? {
        guard let newest = stored.max(), now - newest <= 15 * 60 else { return nil }
        let current = now - now % 3600
        let have = Set(stored.map { $0 - $0 % 3600 })
        return (1...12).map { current - $0 * 3600 }.filter { !have.contains($0) }
    }

    // MARK: - incoming

    public mutating func receive(_ event: SignedEvent, now: Int) -> Outcome {
        if seen[event.id] != nil { return Outcome(.ignored("duplicate")) }
        guard crypto.verify(event) else { return Outcome(.rejected("bad-signature")) }
        remember(event, now: now)
        if event.isExpired(now: now) { return Outcome(.ignored("expired")) }
        if event.kind == Wire.kindWrap { return receiveWrap(event) }
        if state.status == .revoked { return Outcome(.ignored("revoked")) }
        guard let gk = event.tag("gk") else { return Outcome(.ignored("no-gk")) }
        guard let (n, g) = epochKeys[gk] else { return unknownEpoch(event, gk: gk, now: now) }
        guard let sid = state.sid else { return Outcome(.ignored("no-sid")) }
        do throws(PayloadError) {
            switch event.kind {
            case Wire.kindData: return try receiveData(event, epoch: n, key: g, sid: sid)
            case Wire.kindLive, Wire.kindAlert:
                guard state.devices.contains(event.pubkey) else { return notInRosterYet(event, "author-not-device") }
                let plain = try decrypt(event, key: g)
                return event.kind == Wire.kindLive
                    ? try receiveLive(event, plain: plain, now: now) : try receiveAlert(plain: plain, now: now)
            default: return Outcome(.ignored("unknown-kind"))
            }
        } catch {
            return Outcome(.rejected("invalid"))
        }
    }

    private mutating func unknownEpoch(_ event: SignedEvent, gk: String, now: Int) -> Outcome {
        seen[event.id] = nil  // readable later, once the key arrives
        var outcome = Outcome(.ignored("unknown-epoch"))
        // Only the identity or a roster device can mean a missed rotation: a stranger can't make us send requests.
        let fromStream = event.pubkey == state.identity || state.devices.contains(event.pubkey)
        guard state.status == .active, fromStream, now - (rekeyAskedAt[gk] ?? Int.min / 2) >= Wire.rekeyInterval,
            let request = try? messages.toOwner(
                FollowerMessages.rekeyBody(unknownEpoch: gk), identity: state.identity, ttl: Wire.ttlRequest, now: now)
        else { return outcome }
        rekeyAskedAt[gk] = now
        outcome.publish = [request]
        return outcome
    }

    /// The author isn't in the roster held now: rejected but not remembered, because the roster that adds it (a new
    /// phone after a restore, a new writer) may still be on its way; the event is judged again when it comes back.
    private mutating func notInRosterYet(_ event: SignedEvent, _ reason: String) -> Outcome {
        seen[event.id] = nil
        return Outcome(.rejected(reason))
    }

    private mutating func receiveData(_ event: SignedEvent, epoch n: Int, key g: SecretKeyHex, sid: String)
        throws(PayloadError) -> Outcome
    {
        let d = event.tag("d"), c = event.tag("c"), author = event.pubkey
        if d == Wire.rosterD(sid: sid) {
            guard author == state.identity else { return Outcome(.rejected("roster-not-by-identity")) }
            return receiveRoster(event, roster: try RosterPayload.decode(try decrypt(event, key: g)), epoch: n)
        }
        if c == Wire.treatmentsC(sid: sid) {
            guard state.devices.contains(author) || state.writers.contains(author) else {
                return notInRosterYet(event, "author-not-in-roster")
            }
            let entry = try TreatmentPayload.decode(try decrypt(event, key: g))
            guard d == Wire.treatmentD(sid: sid, id: entry.id) else { return Outcome(.rejected("d-mismatch")) }
            return receiveTreatment(entry, author: author)
        }
        if c == Wire.glucoseC(sid: sid) {
            guard state.devices.contains(author) else { return notInRosterYet(event, "author-not-device") }
            let chunk = try ChunkPayload.decode(try decrypt(event, key: g))
            guard d == Wire.chunkD(sid: sid, hour: chunk.hour) else { return Outcome(.rejected("d-mismatch")) }
            var outcome = Outcome(.accepted("chunk"))
            if !chunk.readings.isEmpty { outcome.deltas = [.readings(chunk.readings)] }
            return outcome
        }
        if d == Wire.settingsD(sid: sid) {
            guard state.devices.contains(author) else { return notInRosterYet(event, "author-not-device") }
            let settings = try SettingsPayload.decode(try decrypt(event, key: g))
            guard settings.u > (state.settingsU ?? Int.min) else { return Outcome(.ignored("settings-older")) }
            state.settingsU = settings.u
            var outcome = Outcome(.accepted("settings"))
            outcome.deltas = [.settings(settings)]
            return outcome
        }
        return Outcome(.ignored("unknown-30078"))
    }

    private mutating func receiveRoster(_ event: SignedEvent, roster: RosterPayload, epoch n: Int) -> Outcome {
        guard roster.epoch == n else { return Outcome(.rejected("roster-epoch-mismatch")) }
        guard (roster.epoch, event.createdAt) > (state.rosterEpoch, state.rosterCreatedAt) else {
            return Outcome(.ignored("roster-older"))
        }
        let wasWriter = canWrite
        let filtersChange = roster.devices.sorted() != state.devices.sorted() || roster.writers != state.writers
        state.rosterEpoch = roster.epoch
        state.rosterCreatedAt = event.createdAt
        state.devices = roster.devices
        state.writers = roster.writers
        if !roster.relays.isEmpty { state.relays = roster.relays }
        if let profile = roster.profile { state.wearer = profile }
        var outcome = Outcome(.accepted("roster"))
        outcome.deltas = [.roster]
        if wasWriter != canWrite { outcome.deltas.append(.status(state.status)) }
        outcome.resubscribe = filtersChange
        return outcome
    }

    /// Writer scope: a writer changes only entries it created; the wearer's own entry (`o` ≠ follower) beats a copy a
    /// writer created first, whatever its `u`; the newest `(u, o)` wins otherwise.
    private mutating func receiveTreatment(_ entry: TreatmentPayload, author: String) -> Outcome {
        let fromWearer = state.devices.contains(author)
        let held = state.treatments[entry.id]
        var override = false
        if !fromWearer {
            if let held, held.creator != author {
                if state.devices.contains(held.creator), held.origin == "follower" {
                    return Outcome(.ignored("treatment-held-by-wearer"))  // its echo came first
                }
                return Outcome(.rejected("writer-not-creator"))
            }
        } else if entry.origin != "follower", let held, !state.devices.contains(held.creator) {
            override = true
        }
        let creator = (held == nil || override) ? author : held!.creator
        let newer = held.map { (entry.u, entry.origin) > ($0.u, $0.origin) } ?? true
        guard newer || override else { return Outcome(.ignored("treatment-older")) }
        state.treatments[entry.id] = .init(creator: creator, u: entry.u, origin: entry.origin)
        var outcome = Outcome(.accepted("treatment"))
        outcome.deltas = [.treatment(entry, fromWearer: fromWearer, creator: creator, override: override)]
        return outcome
    }

    private mutating func receiveLive(_ event: SignedEvent, plain: String, now: Int) throws(PayloadError) -> Outcome {
        let live = try LivePayload.decode(plain)
        if let reading = live.reading, reading.t > now + Wire.futureTolerance {
            return Outcome(.rejected("reading-in-future"))
        }
        var outcome = Outcome(.accepted(live.reading == nil ? "sensor" : "live"))
        if event.createdAt >= state.sensorCreatedAt {
            state.sensorCreatedAt = event.createdAt
            outcome.deltas.append(.sensor(live.sensor))
        }
        if let reading = live.reading { outcome.deltas.append(.readings([reading])) }
        return outcome
    }

    private mutating func receiveAlert(plain: String, now: Int) throws(PayloadError) -> Outcome {
        let alert = try AlertPayload.decode(plain)
        guard now - alert.at <= Wire.alertMaxAge else { return Outcome(.ignored("alert-stale")) }
        let key = "\(alert.id)|\(alert.fires ? "fire" : "clear")|\(alert.count)"
        guard !state.alertKeys.contains(key) else { return Outcome(.ignored("alert-duplicate")) }
        state.alertKeys.append(key)
        if state.alertKeys.count > FollowerState.alertKeysKept { state.alertKeys.removeFirst() }
        var outcome = Outcome(.accepted("alert"))
        outcome.deltas = [.alert(alert)]
        return outcome
    }

    private mutating func receiveWrap(_ wrap: SignedEvent) -> Outcome {
        guard wrap.tag("p") == followerPublic else { return Outcome(.ignored("wrap-not-for-us")) }
        guard let rumor = try? crypto.unwrap(wrap, with: state.follower) else { return Outcome(.rejected("unwrap")) }
        // Only the stream identity may send keys and notices: a forged delivery sealed by anyone else is dropped.
        guard rumor.kind == Wire.kindToFollower, rumor.sender == state.identity else {
            return Outcome(.rejected("wrap-not-from-identity"))
        }
        let message: ToFollowerMessage
        do { message = try ToFollowerMessage.decode(rumor.content) } catch { return Outcome(.rejected("invalid")) }
        switch message {
        case .key(let delivery): return receiveKey(delivery)
        case .revoked:
            state.status = .revoked
            var outcome = Outcome(.accepted("revoked"))
            outcome.deltas = [.status(.revoked)]
            outcome.resubscribe = true
            return outcome
        case .inviteUsed, .declined:
            let name = message == .inviteUsed ? "invite-used" : "declined"
            guard state.status == .requested else { return Outcome(.ignored(name)) }
            state.status = message == .inviteUsed ? .inviteUsed : .declined
            var outcome = Outcome(.accepted(name))
            outcome.deltas = [.status(state.status)]
            return outcome
        case .unknown: return Outcome(.ignored("unknown-type"))
        }
    }

    private mutating func receiveKey(_ delivery: KeyDelivery) -> Outcome {
        if let sid = state.sid, sid != delivery.sid { return Outcome(.rejected("sid-changed")) }
        guard let g = try? crypto.nsecDecode(delivery.nsec), let gPub = try? crypto.publicKey(of: g) else {
            return Outcome(.rejected("invalid"))
        }
        let gk = Wire.gk(groupPublicKey: gPub)
        guard epochKeys[gk] == nil else { return Outcome(.ignored("key-known")) }
        let newest = epoch ?? 0
        state.sid = delivery.sid
        state.epochs.append(.init(n: delivery.epoch, g: g))
        epochKeys[gk] = (delivery.epoch, g)
        if delivery.epoch >= newest {
            if !delivery.devices.isEmpty { state.devices = delivery.devices }
            if !delivery.relays.isEmpty { state.relays = delivery.relays }
        }
        // A newer epoch's key outranks every roster of older epochs (PoC FollowerEngine; sync/rotation.json): a late
        // or refreshed roster from the replaced device can't bring its devices back before the new roster arrives.
        if delivery.epoch > state.rosterEpoch {
            state.rosterEpoch = delivery.epoch
            state.rosterCreatedAt = -1
        }
        var outcome = Outcome(.accepted("key"))
        if state.status != .revoked && state.status != .active {
            state.status = .active
            outcome.deltas = [.status(.active)]
        }
        outcome.resubscribe = true
        return outcome
    }

    // MARK: - helpers

    private func decrypt(_ event: SignedEvent, key: SecretKeyHex) throws(PayloadError) -> String {
        do { return try crypto.nip44Decrypt(event.content, with: key, from: event.pubkey) } catch {
            throw PayloadError("decrypt failed")
        }
    }

    private mutating func addEpoch(_ epoch: FollowerState.Epoch) throws(CryptoError) {
        epochKeys[Wire.gk(groupPublicKey: try crypto.publicKey(of: epoch.g))] = (epoch.n, epoch.g)
    }

    private mutating func remember(_ event: SignedEvent, now: Int) {
        if seen.count >= Self.seenLimit { seen = seen.filter { $0.value > now } }
        seen[event.id] = min(event.expiration ?? now + 86_400, now + 86_400)
    }
}

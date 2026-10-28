// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraCore
import SukraraGraph
import SukraraTesting
import Testing

@testable import SukraraAlerts
@testable import SukraraApp

/// The card's treatment marks, the Taken pill and the last-treatment slot from the alert engine (`CardAlertsMapping`).
@Suite struct CardAlertsMappingTests {
    let clock = FakeClock()
    var now: Date { clock.now }
    var t: TimeInterval { now.timeIntervalSince1970 }

    func treatment(_ id: String, _ k: Treatment.Kind, _ a: Double, minutesAgo: Double, deleted: Bool = false)
        -> Treatment
    {
        Treatment(id: id, k: k, a: a, t: Int64(t - minutesAgo * 60), u: 1, del: deleted, o: .phone)
    }

    /// The engine after `inputs`, at `now`.
    func ui(_ inputs: [AlertInput], settings: AlertSettings = AlertSettings()) -> AlertUIState {
        var engine = AlertEngine(context: AlertContext())
        _ = engine.handle(.settings(settings), at: now)
        for i in inputs { _ = engine.handle(i, at: now) }
        return engine.uiState(at: now)
    }

    func alertTreatments(_ list: [Treatment]) -> AlertInput {
        .treatments(
            list.filter { !$0.del }.compactMap { tr in
                let k: TreatmentKind? = tr.k == .carbs ? .carbs : tr.k == .rapid ? .rapidInsulin : nil
                return k.map { AlertTreatment(kind: $0, at: Date(timeIntervalSince1970: TimeInterval(tr.t))) }
            })
    }

    @Test func marksAreTheLiveTreatmentsOldestFirst() {
        let list = [
            treatment("b", .carbs, 30, minutesAgo: 5), treatment("a", .rapid, 4, minutesAgo: 20),
            treatment("x", .note, 0, minutesAgo: 1, deleted: true),
        ]
        let marks = CardAlertsMapping.marks(list)
        #expect(marks.map(\.id) == ["a", "b"])
        #expect(marks[0].k == "rapid" && marks[0].a == 4 && marks[0].t == t - 1200)
    }

    @Test func carbsQuietALowAsTakenWithTheirMuteEnd() {
        let carbs = treatment("c1", .carbs, 20, minutesAgo: 5)
        let marks = CardAlertsMapping.marks([carbs])
        let state = ui([
            alertTreatments([carbs]), .reading(GlucoseSample(at: now, mgdl: 62, rate: 0)),
        ])
        let card = CardAlertsMapping.of(state, settings: AlertSettings(), marks: marks, now: now)
        #expect(card.status == .taken(.low, treatment: marks[0], until: marks[0].t + 15 * 60))
        #expect(card.muteEnds == ["c1": marks[0].t + 15 * 60])
        #expect(card.last?.tr == marks[0] && card.last?.until == marks[0].t + 15 * 60)
    }

    @Test func runningInsulinFillsTheSlotAndCarriesTheCarbs() {
        let list = [treatment("i1", .rapid, 4, minutesAgo: 10), treatment("c1", .carbs, 40, minutesAgo: 3)]
        let marks = CardAlertsMapping.marks(list)
        let card = CardAlertsMapping.of(.idle, settings: AlertSettings(), marks: marks, now: now)
        #expect(card.status == AlertStatus.none)
        #expect(card.last?.tr.id == "i1" && card.last?.until == t - 600 + 3600)
        #expect(card.last?.carbs?.tr.id == "c1")
    }

    @Test func aQuietRuleTurnedOffDrawsNoMuteLine() {
        var s = AlertSettings()
        s.muteAfterInsulin.on = false
        let marks = CardAlertsMapping.marks([treatment("i1", .rapid, 4, minutesAgo: 10)])
        let card = CardAlertsMapping.of(.idle, settings: s, marks: marks, now: now)
        #expect(card.muteEnds.isEmpty)
        #expect(card.last?.tr.id == "i1" && card.last?.until == nil)  // still the newest treatment, without a mute
    }

    @Test func noAlertEngineMeansNoMuteLines() {
        let marks = CardAlertsMapping.marks([treatment("i1", .rapid, 4, minutesAgo: 10)])
        let card = CardAlertsMapping.of(.idle, settings: nil, marks: marks, now: now)
        #expect(card.muteEnds.isEmpty && card.status == AlertStatus.none)
    }

    @Test func oldTreatmentsLeaveTheSlot() {
        let marks = CardAlertsMapping.marks([
            treatment("c1", .carbs, 10, minutesAgo: 31), treatment("l1", .long, 20, minutesAgo: 1),
        ])
        #expect(CardAlertsMapping.of(.idle, settings: AlertSettings(), marks: marks, now: now).last == nil)
    }

    @Test func fallingBackIsAPausedPill() {
        var high = AlertUIState.idle
        high.active[.level] = .init(kind: .high, quiet: .fallingBack(inMinutes: 12), firedAt: nil, n: 0)
        #expect(CardAlertsMapping.status(high, marks: [], now: now) == .paused(.high, until: t + 720))
    }

    @Test func mirroringShowsTakenNeededAndPaused() {
        let carbs = treatment("c1", .carbs, 20, minutesAgo: 5)
        let marks = CardAlertsMapping.marks([carbs])
        let low = GlucoseSample(at: now, mgdl: 62)
        func status(_ m: [GraphMark], mutes: [AlertSide: Date] = [:], all: Date? = nil, _ r: GlucoseSample? = nil)
            -> AlertStatus
        {
            CardAlertsMapping.mirroredStatus(
                latest: r ?? low, settings: AlertSettings(), marks: m, sideMutes: mutes, muteAll: all, now: now)
        }
        #expect(status(marks) == .taken(.low, treatment: marks[0], until: marks[0].t + 900))
        #expect(status([]) == .needed(.low))
        #expect(status([], mutes: [.low: now.addingTimeInterval(600)]) == .paused(.low, until: t + 600))
        #expect(
            status([], all: now.addingTimeInterval(300), GlucoseSample(at: now, mgdl: 110)) == .muted(until: t + 300))
    }

    @Test func rulesInUseFollowSettingsFrom() throws {
        var own = AlertSettings()
        own[.low].threshold = 80
        let wearer = try #require(WearerSettings(json: #"{"alerts":{"alerts":{"high":{"mgdl":200}}}}"#, wearer: "Ana"))
        let w = own.inUse(from: .wearer, wearer: wearer)
        #expect(w[.high].threshold == 200 && w[.low].threshold == 70 && w.source == .wearer)  // not sent: default
        #expect(own.inUse(from: .default, wearer: wearer)[.low].threshold == 70)
        let c = own.inUse(from: .custom, wearer: wearer)
        #expect(c[.low].threshold == 80 && c.source == .own)
    }

    @Test func badgeFollowsMutes() {
        var low = AlertUIState.idle
        low.active[.level] = .init(kind: .low, quiet: nil, firedAt: now, n: 1)
        #expect(CardAlertsMapping.status(low, marks: [], now: now) == .needed(.low))
        low.active[.level]?.quiet = .sideMuted(until: now.addingTimeInterval(600))
        #expect(CardAlertsMapping.status(low, marks: [], now: now) == .paused(.low, until: t + 600))
        let all = AlertUIState(active: [:], quiets: [], muteAllUntil: now.addingTimeInterval(900))
        #expect(CardAlertsMapping.status(all, marks: [], now: now) == .muted(until: t + 900))
    }
}

/// `GraphConfig.load`: every card setting round-trips through the store, Settings from decides the mirrored ones, and
/// each place keeps its own time shown and sensor time left.
@Suite struct GraphSettingsTests {
    func custom() -> InMemorySettingsStore {
        let s = InMemorySettingsStore()
        s.set(SettingsFrom.custom.rawValue, for: MacSettings.settingsFrom)
        return s
    }

    @Test func storedDefaultsAreTheRenderersDefaults() {
        let c = GraphConfig.load(from: custom(), wearer: nil)
        #expect(c == GraphConfig())
        #expect(MacSettings.slots.defaultValue == GraphConfig.slotsText(GraphConfig.defaultRingSlots))
        #expect(MacSettings.lastElse.defaultValue == GraphConfig.slotText(GraphConfig.defaultLastElseSlot))
        #expect(MacSettings.ringOrder.defaultValue == GraphConfig.ringOrderText(DEFAULT_RING_ORDER))
        #expect(MacSettings.marks.defaultValue == MarkShow.defaults.settingsText)
        #expect(MacSettings.lines.defaultValue == GraphConfig.linesText([100]))
    }

    @Test func everyKeyRoundTrips() {
        let s = custom()
        s.setNumber(160, for: MacSettings.bandTight)
        s.setNumber(6, for: MacSettings.graphHours)
        s.setNumber(3, for: MacSettings.ringCount)
        s.setNumber(3, for: MacSettings.gridHours)
        s.setNumber(4, for: MacSettings.countdownHours)
        s.set("CV:259200:0,TIR:604800:1,AVERAGE:2592000:1", for: MacSettings.slots)
        s.set("TIGHT", for: MacSettings.ringRange)
        s.set("VERY_LOW,LOW,TIGHT,TARGET,HIGH,VERY_HIGH", for: MacSettings.ringOrder)
        s.set(false, for: MacSettings.ringMarks)
        s.set(false, for: MacSettings.side)
        s.set(false, for: MacSettings.columnCrown)
        s.set("80,250", for: MacSettings.lines)
        s.set("END", for: MacSettings.lastPlace)
        s.set("", for: MacSettings.lastElse)
        s.set("rapid:point,long:point,carbs:point+amount,note:point", for: MacSettings.marks)
        let c = GraphConfig.load(from: s, wearer: nil)
        #expect(c.bands.tight == 160 && c.hours == 6 && c.ringCount == 3 && c.gridHours == 3 && c.countdownHours == 4)
        #expect(c.slots.map(\.kind) == [.cv, .tir, .average] && c.slots[0].window == 259200 && !c.slots[0].faded)
        #expect(c.ringRange == .tight && c.ringOrder.first == .veryLow)
        #expect(!c.ringMarks && !c.side && !c.columnCrown && c.lines == [80, 250])
        #expect(c.lastPlace == .end && c.lastElse == nil)
        #expect(c.marks.shows("carbs", "amount") && !c.marks.shows("rapid", "line"))
    }

    @Test func badValuesLoadAsValidOnes() {
        let s = custom()
        s.set("TIR:86400:1", for: MacSettings.slots)  // not three
        s.set("TIGHT,TIGHT", for: MacSettings.ringOrder)
        s.set("5,100,100,999", for: MacSettings.lines)
        s.set("SOMEWHERE", for: MacSettings.lastPlace)
        let c = GraphConfig.load(from: s, wearer: nil)
        #expect(c.slots == GraphConfig().slots && c.ringOrder == DEFAULT_RING_ORDER)
        #expect(c.lines == [100] && c.lastPlace == .nearValue)
    }

    @Test func placesKeepTheirOwnHoursAndSensorTime() {
        let s = custom()
        s.setNumber(24, for: MacSettings.widgetHours)
        s.set(false, for: MacSettings.widgetSensorTime)
        let menubar = GraphConfig.load(from: s, wearer: nil, place: .menubar)
        let widget = GraphConfig.load(from: s, wearer: nil, place: .widget)
        #expect(menubar.hours == 12 && menubar.sensorTime)
        #expect(widget.hours == 24 && !widget.sensorTime)
    }

    @Test func settingsFromDecidesTheMirroredValues() throws {
        let s = InMemorySettingsStore()
        s.setNumber(160, for: MacSettings.bandTight)
        s.set("END", for: MacSettings.lastPlace)
        s.setNumber(6, for: MacSettings.widgetHours)
        let wearer = try #require(
            WearerSettings(
                json: #"{"graph":{"tight":120,"lastPlace":"OFF","slots":"GMI:604800:1,TIR:86400:1,CV:86400:0"}}"#,
                wearer: "Ana"))
        s.set(SettingsFrom.wearer.rawValue, for: MacSettings.settingsFrom)
        var c = GraphConfig.load(from: s, wearer: wearer, place: .widget)
        #expect(c.bands.tight == 120 && c.lastPlace == .off && c.slots[0].kind == .gmi)
        #expect(c.bands.high == 180 && c.hours == 6)  // not sent: the default; the place's own hours stay
        c = GraphConfig.load(from: s, wearer: nil)  // nothing received yet: the defaults
        #expect(c.bands.tight == 140 && c.lastPlace == .nearValue)
        s.set(SettingsFrom.default.rawValue, for: MacSettings.settingsFrom)
        c = GraphConfig.load(from: s, wearer: wearer)
        #expect(c.bands.tight == 140 && c.lastPlace == .nearValue)  // Default never reads this Mac's own values…
        s.set(SettingsFrom.custom.rawValue, for: MacSettings.settingsFrom)
        c = GraphConfig.load(from: s, wearer: wearer)
        #expect(c.bands.tight == 160 && c.lastPlace == .end)  // …and never overwrote them
    }
}

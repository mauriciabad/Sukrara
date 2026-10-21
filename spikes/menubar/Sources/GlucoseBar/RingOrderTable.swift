// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import SwiftUI

/// `list` with the items at `from` moved to sit before `to` (AppKit / SwiftUI drop semantics: `to` is the gap index
/// 0…count in the list before the move, as `Array.move(fromOffsets:toOffset:)`).
func reorderMoved<T>(_ list: [T], from: IndexSet, to: Int) -> [T] {
    guard !from.isEmpty, from.allSatisfy(list.indices.contains), (0...list.count).contains(to) else { return list }
    var l = list
    l.move(fromOffsets: from, toOffset: to)
    return l
}

/// The ring order as the system's own reorderable list: an NSTableView (inside the grouped Form, where a SwiftUI
/// `List` can't nest) with `draggingDestinationFeedbackStyle = .gap`, the Finder-sidebar / System Settings behaviour:
/// the dragged image is the row itself, the source row leaves a gap, and the other rows slide apart live to open the
/// gap where it will land. HIG Lists and tables: "People appreciate being able to reorder a list, even if they can't
/// add or remove items." HIG Drag and drop: "Offer alternative ways to accomplish drag-and-drop actions" — each row's
/// context menu and its VoiceOver actions Move Up / Move Down. No position numbers: the order is the list.
/// States: idle; dragging (source row hidden, gap follows the pointer); dropped (`onMove`, the rows settle); cancelled
/// (released outside, Escape: the image flies back and the row reappears, nothing saved).
struct RingOrderTable: NSViewRepresentable {
    let order: [Band]
    let onMove: ([Band]) -> Void
    static let rowHeight: CGFloat = 28

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeNSView(context: Context) -> NSTableView {
        let t = NSTableView()
        let col = NSTableColumn(identifier: .init("band"))
        col.resizingMask = .autoresizingMask
        t.addTableColumn(col)
        t.headerView = nil
        t.style = .plain
        t.backgroundColor = .clear
        t.gridStyleMask = []
        t.intercellSpacing = .zero
        t.rowHeight = Self.rowHeight
        t.selectionHighlightStyle = .none
        t.focusRingType = .none
        t.columnAutoresizingStyle = .uniformColumnAutoresizingStyle
        t.draggingDestinationFeedbackStyle = .gap
        t.registerForDraggedTypes([Coordinator.type])
        t.setDraggingSourceOperationMask(.move, forLocal: true)
        t.dataSource = context.coordinator
        t.delegate = context.coordinator
        t.setAccessibilityLabel("Ring order")
        let m = NSMenu()
        m.delegate = context.coordinator
        t.menu = m
        context.coordinator.table = t
        return t
    }

    func updateNSView(_ t: NSTableView, context: Context) {
        let c = context.coordinator
        c.parent = self
        if c.order != order, !c.dragging { c.order = order; t.reloadData() }
    }

    func sizeThatFits(_ proposal: ProposedViewSize, nsView: NSTableView, context: Context) -> CGSize? {
        CGSize(width: proposal.width ?? 300, height: Self.rowHeight * CGFloat(order.count))
    }

    @MainActor final class Coordinator: NSObject, NSTableViewDataSource, NSTableViewDelegate, NSMenuDelegate {
        static let type = NSPasteboard.PasteboardType("app.mauri.sukrara.ringband")
        var parent: RingOrderTable
        var order: [Band]
        var dragging = false
        weak var table: NSTableView?

        init(_ p: RingOrderTable) { parent = p; order = p.order }

        func numberOfRows(in tableView: NSTableView) -> Int { order.count }

        func tableView(_ t: NSTableView, viewFor col: NSTableColumn?, row: Int) -> NSView? {
            let band = order[row]
            let v = NSHostingView(rootView: RingOrderRow(band: band))
            v.setAccessibilityElement(true)
            v.setAccessibilityRole(.row)
            v.setAccessibilityLabel(band.label)
            v.setAccessibilityCustomActions([
                NSAccessibilityCustomAction(name: "Move Up") { [weak self] in self?.move(band, by: -1) ?? false },
                NSAccessibilityCustomAction(name: "Move Down") { [weak self] in self?.move(band, by: 1) ?? false },
            ])
            return v
        }

        // MARK: drag source

        func tableView(_ t: NSTableView, pasteboardWriterForRow row: Int) -> NSPasteboardWriting? {
            let item = NSPasteboardItem()
            item.setString(String(row), forType: Self.type)
            return item
        }

        func tableView(_ t: NSTableView, draggingSession session: NSDraggingSession, willBeginAt p: NSPoint, forRowIndexes rows: IndexSet) {
            dragging = true
            session.animatesToStartingPositionsOnCancelOrFail = true
            t.hideRows(at: rows, withAnimation: .effectFade)     // the source leaves a gap, as in the Finder sidebar
        }

        func tableView(_ t: NSTableView, draggingSession session: NSDraggingSession, endedAt p: NSPoint, operation: NSDragOperation) {
            dragging = false
            t.unhideRows(at: t.hiddenRowIndexes, withAnimation: .effectFade)
        }

        // MARK: drop

        func tableView(_ t: NSTableView, validateDrop info: NSDraggingInfo, proposedRow row: Int,
                       proposedDropOperation op: NSTableView.DropOperation) -> NSDragOperation {
            guard info.draggingSource as? NSTableView === t else { return [] }
            if op == .on { t.setDropRow(row, dropOperation: .above) }
            return .move
        }

        func tableView(_ t: NSTableView, acceptDrop info: NSDraggingInfo, row: Int, dropOperation: NSTableView.DropOperation) -> Bool {
            guard let s = info.draggingPasteboard.pasteboardItems?.first?.string(forType: Self.type), let from = Int(s) else { return false }
            let next = reorderMoved(order, from: [from], to: row)
            guard next != order else { return false }
            let dest = from < row ? row - 1 : row
            t.beginUpdates()
            t.unhideRows(at: t.hiddenRowIndexes, withAnimation: [])
            t.moveRow(at: from, to: dest)
            order = next
            t.endUpdates()
            parent.onMove(next)
            return true
        }

        // MARK: Move Up / Move Down (context menu, VoiceOver)

        @discardableResult func move(_ band: Band, by d: Int) -> Bool {
            guard let i = order.firstIndex(of: band), order.indices.contains(i + d) else { return false }
            let next = reorderMoved(order, from: [i], to: d > 0 ? i + 2 : i - 1)
            table?.moveRow(at: i, to: i + d)
            order = next
            parent.onMove(next)
            return true
        }

        func menuNeedsUpdate(_ menu: NSMenu) {
            menu.removeAllItems()
            guard let t = table, order.indices.contains(t.clickedRow) else { return }
            let band = order[t.clickedRow]
            let up = NSMenuItem(title: "Move Up", action: #selector(menuMove(_:)), keyEquivalent: "")
            up.target = self; up.representedObject = (band, -1); up.isEnabled = t.clickedRow > 0
            let down = NSMenuItem(title: "Move Down", action: #selector(menuMove(_:)), keyEquivalent: "")
            down.target = self; down.representedObject = (band, 1); down.isEnabled = t.clickedRow < order.count - 1
            menu.autoenablesItems = false
            menu.addItem(up); menu.addItem(down)
        }

        @objc func menuMove(_ item: NSMenuItem) {
            if let (band, d) = item.representedObject as? (Band, Int) { move(band, by: d) }
        }
    }
}

/// One row, the same at rest and as the drag image (AppKit snapshots the row view): the handle, the segment's colour, its name.
struct RingOrderRow: View {
    let band: Band
    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "line.3.horizontal").foregroundStyle(.tertiary).accessibilityHidden(true)
            Circle().fill(Color(cgColor: GraphPalette.lightPalette.of(band))).frame(width: 10, height: 10).accessibilityHidden(true)
            Text(band.label)
            Spacer()
        }
        .padding(.horizontal, 10)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }
}

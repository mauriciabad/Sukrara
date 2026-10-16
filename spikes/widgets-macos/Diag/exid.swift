// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Diagnostic (private API, read-only): for each extension bundle id, build the _EXExtensionIdentity that
// ExtensionKit discovery uses from its LaunchServices record and print what it reports.
//   swiftc Diag/exid.swift -o build/exid && build/exid dev.spike.GlucoseWidgets.widget com.steipete.codexbar.widget
import Foundation
import ObjectiveC

dlopen("/System/Library/Frameworks/ExtensionFoundation.framework/ExtensionFoundation", RTLD_NOW)
typealias InitErr = @convention(c) (AnyObject, Selector, NSString, UnsafeMutablePointer<NSError?>?) -> AnyObject?
typealias Init1 = @convention(c) (AnyObject, Selector, AnyObject) -> AnyObject?
typealias Bool0 = @convention(c) (AnyObject, Selector) -> Bool
typealias Ent = @convention(c) (AnyObject, Selector, NSString, AnyClass) -> AnyObject?
func alloc(_ c: String) -> AnyObject { (NSClassFromString(c)! as! NSObject.Type).perform(NSSelectorFromString("alloc"))!.takeUnretainedValue() }

for id in CommandLine.arguments.dropFirst() {
    let a = alloc("LSApplicationExtensionRecord")
    let s1 = NSSelectorFromString("initWithBundleIdentifier:error:")
    guard let rec = unsafeBitCast(a.method(for: s1), to: InitErr.self)(a, s1, id as NSString, nil) else { print(id, "no record"); continue }
    let b = alloc("_EXExtensionIdentity")
    let s2 = NSSelectorFromString("initWithApplicationExtensionRecord:")
    guard let ident = unsafeBitCast(b.method(for: s2), to: Init1.self)(b, s2, rec) as? NSObject else { print(id, "no identity"); continue }
    let s3 = NSSelectorFromString("hasSandboxEntitlement")
    let s4 = NSSelectorFromString("entitlementNamed:ofClass:")
    print(id,
          "hasSandboxEntitlement=\(unsafeBitCast(ident.method(for: s3), to: Bool0.self)(ident, s3))",
          "app-sandbox=\(String(describing: unsafeBitCast(ident.method(for: s4), to: Ent.self)(ident, s4, "com.apple.security.app-sandbox", NSNumber.self)))",
          "teamIdentifier=\(String(describing: ident.value(forKey: "teamIdentifier")))",
          "sandboxProfileName=\(String(describing: ident.value(forKey: "sandboxProfileName")))")
}

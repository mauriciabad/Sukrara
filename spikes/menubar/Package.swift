// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "GlucoseBar",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(name: "GlucoseBar", path: "Sources/GlucoseBar"),
    ],
    swiftLanguageModes: [.v5]
)

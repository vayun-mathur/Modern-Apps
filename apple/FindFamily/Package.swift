// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "FindFamily",
    platforms: [
        .iOS(.v18),
    ],
    products: [
        .library(name: "FindFamily", targets: ["FindFamily"]),
    ],
    targets: [
        .target(
            name: "FindFamily",
            path: "FindFamilyiOS",
            // PQCStubs.c is unreferenced documentation for a future Rust PQC link;
            // SwiftPM cannot mix C into a Swift target.
            exclude: [
                "Info.plist",
                "Assets.xcassets",
                "Preview Content",
                "Crypto/PQCStubs.c",
            ],
            swiftSettings: [
                .swiftLanguageMode(.v5),
            ]
        ),
    ]
)

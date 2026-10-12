// swift-tools-version:5.10
import PackageDescription

let package = Package(
    name: "FXJCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "FXJCore", targets: ["FXJCore"])],
    targets: [
        .target(name: "FXJCore"),
        .testTarget(name: "FXJCoreTests", dependencies: ["FXJCore"]),
    ]
)

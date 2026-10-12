// swift-tools-version:5.10
import PackageDescription
let package = Package(name: "Gen", platforms: [.macOS(.v14)],
  targets: [.target(name: "FXJCore"), .executableTarget(name: "Gen", dependencies: ["FXJCore"])])

import Foundation
import CoreLocation
import Observation
import FXJCore

/// CoreLocation 适配层（#14）。仅在用户点击时按需请求“使用期间”授权；默认请求大致位置（reduced accuracy）。
/// 住宅位置与手机实时位置分离：本服务只返回一次读数，是否写入住宅由用户确认。
@MainActor
@Observable
final class LocationService: NSObject, CLLocationManagerDelegate {
    enum State: Equatable { case idle, requesting, denied, failed(String) }

    private(set) var state: State = .idle
    private(set) var heading: Double?          // 真北优先，否则磁北
    private(set) var headingIsTrueNorth = false
    private(set) var headingSamples: [Double] = []
    private let manager = CLLocationManager()
    private var continuation: CheckedContinuation<Location, Error>?

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyReduced
    }

    var authorizationDenied: Bool {
        [.denied, .restricted].contains(manager.authorizationStatus)
    }

    /// 读取一次大致位置；拒绝授权时抛错，调用方回退到手动输入。
    func requestApproximateLocation() async throws -> Location {
        if authorizationDenied { state = .denied; throw LocationError.unparsable }
        state = .requesting
        return try await withCheckedThrowingContinuation { cont in
            continuation = cont
            if manager.authorizationStatus == .notDetermined { manager.requestWhenInUseAuthorization() }
            else { manager.requestLocation() }
        }
    }

    func startHeading() {
        guard CLLocationManager.headingAvailable() else { return }
        headingSamples = []
        manager.startUpdatingHeading()
    }

    func stopHeading() { manager.stopUpdatingHeading() }

    nonisolated func locationManagerDidChangeAuthorization(_ m: CLLocationManager) {
        let status = m.authorizationStatus
        Task { @MainActor in
            switch status {
            case .authorizedWhenInUse, .authorizedAlways: if continuation != nil { manager.requestLocation() }
            case .denied, .restricted: finish(.failure(LocationError.unparsable)); state = .denied
            default: break
            }
        }
    }

    nonisolated func locationManager(_ m: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let l = locations.last else { return }
        let loc = Location(latitude: l.coordinate.latitude, longitude: l.coordinate.longitude).coarsened()
        Task { @MainActor in finish(.success(loc)); state = .idle }
    }

    nonisolated func locationManager(_ m: CLLocationManager, didFailWithError error: Error) {
        let msg = error.localizedDescription
        Task { @MainActor in finish(.failure(error)); state = .failed(msg) }
    }

    nonisolated func locationManager(_ m: CLLocationManager, didUpdateHeading h: CLHeading) {
        let isTrue = h.trueHeading >= 0
        let value = isTrue ? h.trueHeading : h.magneticHeading
        Task { @MainActor in
            heading = value; headingIsTrueNorth = isTrue
            headingSamples = Array((headingSamples + [value]).suffix(10))
        }
    }

    private func finish(_ r: Result<Location, Error>) {
        guard let c = continuation else { return }
        continuation = nil
        c.resume(with: r)
    }
}

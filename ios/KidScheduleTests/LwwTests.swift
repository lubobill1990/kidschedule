import XCTest
@testable import KidSchedule

final class LwwTests: XCTestCase {
    func testNewerTimestampWins() {
        let a = LwwVersion(clientUpdatedAt: 100, deviceId: "a")
        let b = LwwVersion(clientUpdatedAt: 200, deviceId: "b")
        XCTAssertEqual(Lww.decide(a: a, b: b), .bWins)
    }

    func testOlderTimestampLoses() {
        let a = LwwVersion(clientUpdatedAt: 200, deviceId: "a")
        let b = LwwVersion(clientUpdatedAt: 100, deviceId: "b")
        XCTAssertEqual(Lww.decide(a: a, b: b), .aWins)
    }

    func testTieBreaksByDeviceId() {
        let a = LwwVersion(clientUpdatedAt: 100, deviceId: "a")
        let b = LwwVersion(clientUpdatedAt: 100, deviceId: "b")
        XCTAssertEqual(Lww.decide(a: a, b: b), .bWins)
    }

    func testFullTieKeepsExisting() {
        let a = LwwVersion(clientUpdatedAt: 100, deviceId: "a")
        let b = LwwVersion(clientUpdatedAt: 100, deviceId: "a")
        XCTAssertEqual(Lww.decide(a: a, b: b), .equalKeepA)
    }
}

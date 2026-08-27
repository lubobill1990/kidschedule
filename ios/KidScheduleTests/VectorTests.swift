import XCTest
@testable import KidSchedule

// 共享测试向量(shared-tests/vectors),与 Android 端加载同一组 JSON,保证双端行为一致。

private func loadVector<T: Decodable>(_ name: String, as type: T.Type) throws -> T {
    let bundle = Bundle(for: VectorAnchor.self)
    guard let url = bundle.url(forResource: name, withExtension: "json") else {
        throw NSError(domain: "vectors", code: 1, userInfo: [NSLocalizedDescriptionKey: "missing vector \(name).json"])
    }
    let data = try Data(contentsOf: url)
    return try JSONDecoder().decode(T.self, from: data)
}

private final class VectorAnchor {}

// MARK: - LWW 向量

final class LwwVectorTests: XCTestCase {
    private struct File: Decodable {
        let cases: [Case]
    }
    private struct Case: Decodable {
        let name: String
        let a: Version
        let b: Version
        let expected: String
    }
    private struct Version: Decodable {
        let clientUpdatedAt: String
        let deviceId: String

        enum CodingKeys: String, CodingKey {
            case clientUpdatedAt = "client_updated_at"
            case deviceId = "device_id"
        }
    }

    func testLwwVectors() throws {
        let file = try loadVector("lww-merge", as: File.self)
        XCTAssertFalse(file.cases.isEmpty)
        for c in file.cases {
            let a = LwwVersion(clientUpdatedAt: IsoTime.toMillis(c.a.clientUpdatedAt), deviceId: c.a.deviceId)
            let b = LwwVersion(clientUpdatedAt: IsoTime.toMillis(c.b.clientUpdatedAt), deviceId: c.b.deviceId)
            let verdict: String
            switch Lww.decide(a: a, b: b) {
            case .aWins: verdict = "a_wins"
            case .bWins: verdict = "b_wins"
            case .equalKeepA: verdict = "equal_keep_a"
            }
            XCTAssertEqual(verdict, c.expected, "case: \(c.name)")
        }
    }
}

// MARK: - Outbox 状态机向量

final class OutboxVectorTests: XCTestCase {
    private struct File: Decodable {
        let cases: [Case]
    }
    private struct Case: Decodable {
        let name: String
        let steps: [Step]
        let expected: Expected
    }
    private struct Step: Decodable {
        let action: String
        let entityId: String?
        let t: String
        let expectResult: String?

        enum CodingKeys: String, CodingKey {
            case action
            case entityId = "entity_id"
            case t
            case expectResult = "expect_result"
        }
    }
    private struct Expected: Decodable {
        let outbox: [ExpectedItem]
        let localRows: [String: Bool]

        enum CodingKeys: String, CodingKey {
            case outbox
            case localRows = "local_rows"
        }
    }
    private struct ExpectedItem: Decodable {
        let entityId: String
        let state: String

        enum CodingKeys: String, CodingKey {
            case entityId = "entity_id"
            case state
        }
    }

    func testOutboxVectors() throws {
        let file = try loadVector("outbox-state", as: File.self)
        XCTAssertFalse(file.cases.isEmpty)
        for c in file.cases {
            let outbox = InMemoryOutboxStore()
            let rows = InMemoryLocalRowStore()
            let engine = OutboxEngine(outbox: outbox, rows: rows)
            var lastBatch: [OutboxItem] = []

            for step in c.steps {
                let now = IsoTime.toMillis(step.t)
                switch step.action {
                case "quick_record":
                    try engine.quickRecord(entityId: step.entityId!, nowMillis: now)
                case "normal_write":
                    try engine.normalWrite(entityId: step.entityId!, nowMillis: now)
                case "undo":
                    let result = try engine.undo(entityId: step.entityId!)
                    let got = result == .ok ? "ok" : "rejected"
                    XCTAssertEqual(got, step.expectResult, "case: \(c.name), undo result")
                case "tick":
                    try engine.releaseExpiredHolds(nowMillis: now)
                case "push_begin":
                    lastBatch = try engine.pushBegin()
                case "push_ack":
                    try engine.pushAck(opIds: lastBatch.map { $0.opId })
                case "push_fail":
                    try engine.pushFail(opIds: lastBatch.map { $0.opId })
                case "pull_remote_wins":
                    try engine.pullRemoteWins(entityId: step.entityId!)
                default:
                    XCTFail("case: \(c.name), unknown action \(step.action)")
                }
            }

            let gotOutbox = try outbox.items()
                .map { "\($0.entityId)|\($0.state.rawValue)" }
                .sorted()
            let wantOutbox = c.expected.outbox
                .map { "\($0.entityId)|\($0.state)" }
                .sorted()
            XCTAssertEqual(gotOutbox, wantOutbox, "case: \(c.name), outbox")

            for (entityId, want) in c.expected.localRows {
                let got = try rows.exists(entityId: entityId)
                XCTAssertEqual(got, want, "case: \(c.name), local row \(entityId)")
            }
        }
    }
}

// MARK: - 提醒计算向量

final class ReminderVectorTests: XCTestCase {
    private struct File: Decodable {
        let cases: [Case]
    }
    private struct Case: Decodable {
        let name: String
        let kind: String
        let reminderMode: String
        let reminderFixedIntervalSec: Int64?
        let events: [REvent]
        let expected: RExpected

        enum CodingKeys: String, CodingKey {
            case name, kind, events, expected
            case reminderMode = "reminder_mode"
            case reminderFixedIntervalSec = "reminder_fixed_interval_sec"
        }
    }
    private struct REvent: Decodable {
        let startedAt: String
        let endedAt: String?
        let status: String?
        let deleted: Bool?

        enum CodingKeys: String, CodingKey {
            case startedAt = "started_at"
            case endedAt = "ended_at"
            case status, deleted
        }
    }
    private struct RExpected: Decodable {
        let thresholdSec: Int64?
        let nextFireAt: String?

        enum CodingKeys: String, CodingKey {
            case thresholdSec = "threshold_sec"
            case nextFireAt = "next_fire_at"
        }
    }

    func testReminderVectors() throws {
        let file = try loadVector("reminder-calc", as: File.self)
        XCTAssertFalse(file.cases.isEmpty)
        for c in file.cases {
            let events = c.events.map { e in
                ReminderEvent(
                    startedAt: IsoTime.toMillis(e.startedAt),
                    endedAt: e.endedAt.map { IsoTime.toMillis($0) },
                    ongoing: e.status == "ongoing",
                    deleted: e.deleted ?? false
                )
            }
            let result = ReminderCalculator.compute(
                kind: ActivityKind(rawValue: c.kind)!,
                mode: ReminderMode(rawValue: c.reminderMode)!,
                fixedIntervalSec: c.reminderFixedIntervalSec,
                events: events
            )
            XCTAssertEqual(result.thresholdSec, c.expected.thresholdSec, "case: \(c.name), threshold")
            XCTAssertEqual(
                result.nextFireAtMillis,
                c.expected.nextFireAt.map { IsoTime.toMillis($0) },
                "case: \(c.name), nextFireAt"
            )
        }
    }
}

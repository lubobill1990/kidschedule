import Foundation
import GRDB

// 本地行(GRDB record,列名 camelCase)与服务端 DTO(snake_case JSON)。
// 与 android/.../data/local/Entities.kt、data/remote/Dtos.kt 一一对应。

protocol LwwRecord: Codable, FetchableRecord, PersistableRecord, Identifiable {
    var id: String { get }
    var clientUpdatedAt: Int64 { get }
    var deviceId: String { get }
}

struct BabyRow: LwwRecord, Equatable {
    static let databaseTableName = "babies"
    var id: String
    var familyId: String
    var name: String
    var birthday: String? // ISO date: 2026-01-31
    var avatarPath: String?
    var deletedAt: Int64?
    var clientUpdatedAt: Int64
    var deviceId: String
}

struct ActivityTypeRow: LwwRecord, Equatable {
    static let databaseTableName = "activity_types"
    var id: String
    var familyId: String
    var name: String
    var icon: String?
    var color: String?
    var kind: String // instant | duration
    var defaultMaxDurationSec: Int64?
    var reminderMode: String // auto | fixed | off
    var reminderFixedIntervalSec: Int64?
    var sortOrder: Int
    var deletedAt: Int64?
    var clientUpdatedAt: Int64
    var deviceId: String
}

struct EventRow: LwwRecord, Equatable {
    static let databaseTableName = "events"
    var id: String
    var familyId: String
    var babyId: String
    var activityTypeId: String
    var startedAt: Int64
    var endedAt: Int64?
    var status: String // ongoing | done
    var autoEnded: Bool
    var note: String?
    var createdBy: String?
    var deletedAt: Int64?
    var clientUpdatedAt: Int64
    var deviceId: String
}

struct EventAttachmentRow: LwwRecord, Equatable {
    static let databaseTableName = "event_attachments"
    var id: String
    var eventId: String
    var familyId: String
    var storagePath: String?
    var uploadState: String // pending | uploaded
    var localPath: String? // 仅本地:待上传的原图路径
    var deletedAt: Int64?
    var clientUpdatedAt: Int64
    var deviceId: String
}

// 成员资料只读缓存,每次 sync 全量刷新,不走 outbox
struct FamilyMemberRow: Codable, FetchableRecord, PersistableRecord, Equatable {
    static let databaseTableName = "family_members"
    var familyId: String
    var userId: String
    var role: String // owner | member
    var displayName: String?
    var avatarEmoji: String?
}

struct OutboxRow: Codable, FetchableRecord, MutablePersistableRecord {
    static let databaseTableName = "outbox"
    var opId: Int64?
    var entity: String // babies | activity_types | events | event_attachments
    var entityId: String
    var state: String // held | pending | inflight
    var holdUntil: Int64?
    var createdAt: Int64

    mutating func didInsert(_ inserted: InsertionSuccess) {
        opId = inserted.rowID
    }
}

struct SyncCursorRow: Codable, FetchableRecord, PersistableRecord {
    static let databaseTableName = "sync_cursors"
    var entity: String
    var lastUpdatedAt: String // 服务端 updated_at 原样字符串,避免精度损失
    var lastId: String
}

// ---- DTO(snake_case 由 JSONEncoder/Decoder 的 keyStrategy 转换)----
// push 时 updatedAt/createdBy 为 nil 被省略(服务端管理)。

struct PushResultDto: Codable {
    var id: String
    var outcome: String // applied | stale | rejected
    var reason: String?
}

struct BabyDto: Codable {
    var id: String
    var familyId: String
    var name: String
    var birthday: String?
    var avatarPath: String?
    var deletedAt: String?
    var clientUpdatedAt: String
    var deviceId: String
    var updatedAt: String?
}

struct ActivityTypeDto: Codable {
    var id: String
    var familyId: String
    var name: String
    var icon: String?
    var color: String?
    var kind: String
    var defaultMaxDurationSec: Int64?
    var reminderMode: String
    var reminderFixedIntervalSec: Int64?
    var sortOrder: Int
    var deletedAt: String?
    var clientUpdatedAt: String
    var deviceId: String
    var updatedAt: String?
}

struct EventDto: Codable {
    var id: String
    var familyId: String
    var babyId: String
    var activityTypeId: String
    var startedAt: String
    var endedAt: String?
    var status: String
    var autoEnded: Bool
    var note: String?
    var deletedAt: String?
    var clientUpdatedAt: String
    var deviceId: String
    var createdBy: String?
    var updatedAt: String?
}

struct EventAttachmentDto: Codable {
    var id: String
    var eventId: String
    var familyId: String
    var storagePath: String?
    var uploadState: String
    var deletedAt: String?
    var clientUpdatedAt: String
    var deviceId: String
    var updatedAt: String?
}

struct FamilyMemberDto: Codable {
    var familyId: String
    var userId: String
    var role: String
    var displayName: String?
    var avatarEmoji: String?
}

extension FamilyMemberDto {
    func toRow() -> FamilyMemberRow {
        FamilyMemberRow(
            familyId: familyId, userId: userId, role: role,
            displayName: displayName, avatarEmoji: avatarEmoji
        )
    }
}

// ---- Row -> DTO(push)----

extension BabyRow {
    func toDto() -> BabyDto {
        BabyDto(
            id: id, familyId: familyId, name: name, birthday: birthday, avatarPath: avatarPath,
            deletedAt: deletedAt.map(IsoTime.toIso),
            clientUpdatedAt: IsoTime.toIso(clientUpdatedAt), deviceId: deviceId, updatedAt: nil
        )
    }
}

extension ActivityTypeRow {
    func toDto() -> ActivityTypeDto {
        ActivityTypeDto(
            id: id, familyId: familyId, name: name, icon: icon, color: color, kind: kind,
            defaultMaxDurationSec: defaultMaxDurationSec, reminderMode: reminderMode,
            reminderFixedIntervalSec: reminderFixedIntervalSec, sortOrder: sortOrder,
            deletedAt: deletedAt.map(IsoTime.toIso),
            clientUpdatedAt: IsoTime.toIso(clientUpdatedAt), deviceId: deviceId, updatedAt: nil
        )
    }
}

extension EventRow {
    func toDto() -> EventDto {
        EventDto(
            id: id, familyId: familyId, babyId: babyId, activityTypeId: activityTypeId,
            startedAt: IsoTime.toIso(startedAt), endedAt: endedAt.map(IsoTime.toIso),
            status: status, autoEnded: autoEnded, note: note,
            deletedAt: deletedAt.map(IsoTime.toIso),
            clientUpdatedAt: IsoTime.toIso(clientUpdatedAt), deviceId: deviceId,
            createdBy: nil, updatedAt: nil
        )
    }
}

extension EventAttachmentRow {
    func toDto() -> EventAttachmentDto {
        EventAttachmentDto(
            id: id, eventId: eventId, familyId: familyId, storagePath: storagePath,
            uploadState: uploadState,
            deletedAt: deletedAt.map(IsoTime.toIso),
            clientUpdatedAt: IsoTime.toIso(clientUpdatedAt), deviceId: deviceId, updatedAt: nil
        )
    }
}

// ---- DTO -> Row(pull)----

extension BabyDto {
    func toRow() -> BabyRow {
        BabyRow(
            id: id, familyId: familyId, name: name, birthday: birthday, avatarPath: avatarPath,
            deletedAt: deletedAt.map(IsoTime.toMillis),
            clientUpdatedAt: IsoTime.toMillis(clientUpdatedAt), deviceId: deviceId
        )
    }
}

extension ActivityTypeDto {
    func toRow() -> ActivityTypeRow {
        ActivityTypeRow(
            id: id, familyId: familyId, name: name, icon: icon, color: color, kind: kind,
            defaultMaxDurationSec: defaultMaxDurationSec, reminderMode: reminderMode,
            reminderFixedIntervalSec: reminderFixedIntervalSec, sortOrder: sortOrder,
            deletedAt: deletedAt.map(IsoTime.toMillis),
            clientUpdatedAt: IsoTime.toMillis(clientUpdatedAt), deviceId: deviceId
        )
    }
}

extension EventDto {
    func toRow() -> EventRow {
        EventRow(
            id: id, familyId: familyId, babyId: babyId, activityTypeId: activityTypeId,
            startedAt: IsoTime.toMillis(startedAt), endedAt: endedAt.map(IsoTime.toMillis),
            status: status, autoEnded: autoEnded, note: note, createdBy: createdBy,
            deletedAt: deletedAt.map(IsoTime.toMillis),
            clientUpdatedAt: IsoTime.toMillis(clientUpdatedAt), deviceId: deviceId
        )
    }
}

extension EventAttachmentDto {
    /// localPath 仅本地字段,pull 覆盖时保留
    func toRow(localPath: String?) -> EventAttachmentRow {
        EventAttachmentRow(
            id: id, eventId: eventId, familyId: familyId, storagePath: storagePath,
            uploadState: uploadState, localPath: localPath,
            deletedAt: deletedAt.map(IsoTime.toMillis),
            clientUpdatedAt: IsoTime.toMillis(clientUpdatedAt), deviceId: deviceId
        )
    }
}

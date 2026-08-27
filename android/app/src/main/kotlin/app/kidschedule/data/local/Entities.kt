package app.kidschedule.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// 与 supabase/migrations/0001_init.sql 对应;时间一律 epoch 毫秒(UTC)。
// updated_at 是服务端游标字段,不落本地行,游标进度存 SyncCursorEntity。

@Entity(tableName = "babies")
data class BabyEntity(
    @PrimaryKey val id: String,
    val familyId: String,
    val name: String,
    val birthday: String?, // ISO date: 2026-01-31
    val avatarPath: String?,
    val deletedAt: Long?,
    val clientUpdatedAt: Long,
    val deviceId: String,
)

@Entity(tableName = "activity_types")
data class ActivityTypeEntity(
    @PrimaryKey val id: String,
    val familyId: String,
    val name: String,
    val icon: String?,
    val color: String?,
    val kind: String, // instant | duration
    val defaultMaxDurationSec: Long?,
    val reminderMode: String, // auto | fixed | off
    val reminderFixedIntervalSec: Long?,
    val sortOrder: Int,
    val deletedAt: Long?,
    val clientUpdatedAt: Long,
    val deviceId: String,
)

@Entity(
    tableName = "events",
    indices = [
        Index(value = ["babyId", "activityTypeId", "startedAt"]),
        Index(value = ["babyId", "startedAt"]),
    ],
)
data class EventEntity(
    @PrimaryKey val id: String,
    val familyId: String,
    val babyId: String,
    val activityTypeId: String,
    val startedAt: Long,
    val endedAt: Long?,
    val status: String, // ongoing | done
    val autoEnded: Boolean,
    val note: String?,
    val createdBy: String?,
    val deletedAt: Long?,
    val clientUpdatedAt: Long,
    val deviceId: String,
)

@Entity(
    tableName = "event_attachments",
    indices = [Index(value = ["eventId"])],
)
data class EventAttachmentEntity(
    @PrimaryKey val id: String,
    val eventId: String,
    val familyId: String,
    val storagePath: String?,
    val uploadState: String, // pending | uploaded
    val localPath: String?, // 仅本地:待上传的原图路径
    val deletedAt: Long?,
    val clientUpdatedAt: Long,
    val deviceId: String,
)

// 成员资料只读缓存,每次 sync 全量刷新,不走 outbox
@Entity(tableName = "family_members", primaryKeys = ["familyId", "userId"])
data class FamilyMemberEntity(
    val familyId: String,
    val userId: String,
    val role: String, // owner | member
    val displayName: String?,
    val avatarEmoji: String?,
)

@Entity(
    tableName = "outbox",
    indices = [Index(value = ["entityId"]), Index(value = ["state"])],
)
data class OutboxItemEntity(
    @PrimaryKey(autoGenerate = true) val opId: Long = 0,
    val entity: String, // babies | activity_types | events | event_attachments
    val entityId: String,
    val state: String, // held | pending | inflight
    val holdUntil: Long?,
    val createdAt: Long,
)

@Entity(tableName = "sync_cursors")
data class SyncCursorEntity(
    @PrimaryKey val entity: String,
    val lastUpdatedAt: String, // 服务端 updated_at 原样字符串,避免精度损失
    val lastId: String,
)

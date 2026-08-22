package app.kidschedule.data.remote

import app.kidschedule.data.local.ActivityTypeEntity
import app.kidschedule.data.local.BabyEntity
import app.kidschedule.data.local.EventAttachmentEntity
import app.kidschedule.data.local.EventEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// 服务端行 <-> Room 实体。时间戳网络传输用 ISO8601,本地存 epoch 毫秒(协议 §1)。
// push 时 updated_at/created_at/created_by 为 null 并被省略(服务端管理)。

object IsoTime {
    private val fmt = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSX").withZone(ZoneOffset.UTC)

    fun toIso(millis: Long): String = fmt.format(Instant.ofEpochMilli(millis))

    fun toMillis(iso: String): Long = OffsetDateTime.parse(iso).toInstant().toEpochMilli()
}

@Serializable
data class PushResultDto(
    val id: String,
    val outcome: String, // applied | stale | rejected
    val reason: String? = null,
)

@Serializable
data class BabyDto(
    val id: String,
    @SerialName("family_id") val familyId: String,
    val name: String,
    val birthday: String? = null,
    @SerialName("avatar_path") val avatarPath: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class ActivityTypeDto(
    val id: String,
    @SerialName("family_id") val familyId: String,
    val name: String,
    val icon: String? = null,
    val color: String? = null,
    val kind: String,
    @SerialName("default_max_duration_sec") val defaultMaxDurationSec: Long? = null,
    @SerialName("reminder_mode") val reminderMode: String,
    @SerialName("reminder_fixed_interval_sec") val reminderFixedIntervalSec: Long? = null,
    @SerialName("sort_order") val sortOrder: Int,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class EventDto(
    val id: String,
    @SerialName("family_id") val familyId: String,
    @SerialName("baby_id") val babyId: String,
    @SerialName("activity_type_id") val activityTypeId: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String? = null,
    val status: String,
    @SerialName("auto_ended") val autoEnded: Boolean = false,
    val note: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class EventAttachmentDto(
    val id: String,
    @SerialName("event_id") val eventId: String,
    @SerialName("family_id") val familyId: String,
    @SerialName("storage_path") val storagePath: String? = null,
    @SerialName("upload_state") val uploadState: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("updated_at") val updatedAt: String? = null,
)

// ---- Entity -> DTO(push)----

fun BabyEntity.toDto() = BabyDto(
    id = id, familyId = familyId, name = name, birthday = birthday, avatarPath = avatarPath,
    deletedAt = deletedAt?.let(IsoTime::toIso),
    clientUpdatedAt = IsoTime.toIso(clientUpdatedAt), deviceId = deviceId,
)

fun ActivityTypeEntity.toDto() = ActivityTypeDto(
    id = id, familyId = familyId, name = name, icon = icon, color = color, kind = kind,
    defaultMaxDurationSec = defaultMaxDurationSec, reminderMode = reminderMode,
    reminderFixedIntervalSec = reminderFixedIntervalSec, sortOrder = sortOrder,
    deletedAt = deletedAt?.let(IsoTime::toIso),
    clientUpdatedAt = IsoTime.toIso(clientUpdatedAt), deviceId = deviceId,
)

fun EventEntity.toDto() = EventDto(
    id = id, familyId = familyId, babyId = babyId, activityTypeId = activityTypeId,
    startedAt = IsoTime.toIso(startedAt), endedAt = endedAt?.let(IsoTime::toIso),
    status = status, autoEnded = autoEnded, note = note,
    deletedAt = deletedAt?.let(IsoTime::toIso),
    clientUpdatedAt = IsoTime.toIso(clientUpdatedAt), deviceId = deviceId,
)

fun EventAttachmentEntity.toDto() = EventAttachmentDto(
    id = id, eventId = eventId, familyId = familyId, storagePath = storagePath,
    uploadState = uploadState,
    deletedAt = deletedAt?.let(IsoTime::toIso),
    clientUpdatedAt = IsoTime.toIso(clientUpdatedAt), deviceId = deviceId,
)

// ---- DTO -> Entity(pull)----

fun BabyDto.toEntity() = BabyEntity(
    id = id, familyId = familyId, name = name, birthday = birthday, avatarPath = avatarPath,
    deletedAt = deletedAt?.let(IsoTime::toMillis),
    clientUpdatedAt = IsoTime.toMillis(clientUpdatedAt), deviceId = deviceId,
)

fun ActivityTypeDto.toEntity() = ActivityTypeEntity(
    id = id, familyId = familyId, name = name, icon = icon, color = color, kind = kind,
    defaultMaxDurationSec = defaultMaxDurationSec, reminderMode = reminderMode,
    reminderFixedIntervalSec = reminderFixedIntervalSec, sortOrder = sortOrder,
    deletedAt = deletedAt?.let(IsoTime::toMillis),
    clientUpdatedAt = IsoTime.toMillis(clientUpdatedAt), deviceId = deviceId,
)

fun EventDto.toEntity() = EventEntity(
    id = id, familyId = familyId, babyId = babyId, activityTypeId = activityTypeId,
    startedAt = IsoTime.toMillis(startedAt), endedAt = endedAt?.let(IsoTime::toMillis),
    status = status, autoEnded = autoEnded, note = note, createdBy = createdBy,
    deletedAt = deletedAt?.let(IsoTime::toMillis),
    clientUpdatedAt = IsoTime.toMillis(clientUpdatedAt), deviceId = deviceId,
)

/** localPath 仅本地字段,pull 覆盖时保留 */
fun EventAttachmentDto.toEntity(localPath: String?) = EventAttachmentEntity(
    id = id, eventId = eventId, familyId = familyId, storagePath = storagePath,
    uploadState = uploadState, localPath = localPath,
    deletedAt = deletedAt?.let(IsoTime::toMillis),
    clientUpdatedAt = IsoTime.toMillis(clientUpdatedAt), deviceId = deviceId,
)

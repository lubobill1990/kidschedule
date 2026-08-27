package app.kidschedule.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BabyDao {
    @Upsert
    suspend fun upsert(baby: BabyEntity)

    @Query("SELECT * FROM babies WHERE id = :id")
    suspend fun getById(id: String): BabyEntity?

    @Query("SELECT * FROM babies WHERE deletedAt IS NULL ORDER BY name")
    fun observeAll(): Flow<List<BabyEntity>>

    @Query("SELECT * FROM babies WHERE id = :id")
    fun observeById(id: String): Flow<BabyEntity?>

    // 以下阻塞方法仅供同步层在事务内使用
    @Upsert
    fun upsertBlocking(baby: BabyEntity)

    @Query("SELECT * FROM babies WHERE id = :id")
    fun getByIdBlocking(id: String): BabyEntity?

    @Query("SELECT * FROM babies WHERE id IN (:ids)")
    fun getByIdsBlocking(ids: List<String>): List<BabyEntity>

    @Query("DELETE FROM babies WHERE id = :id")
    fun physicalDeleteBlocking(id: String)
}

@Dao
interface ActivityTypeDao {
    @Upsert
    suspend fun upsert(type: ActivityTypeEntity)

    @Query("SELECT * FROM activity_types WHERE id = :id")
    suspend fun getById(id: String): ActivityTypeEntity?

    @Query("SELECT * FROM activity_types WHERE deletedAt IS NULL ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<ActivityTypeEntity>>

    @Query("SELECT * FROM activity_types WHERE id = :id AND deletedAt IS NULL")
    fun observeById(id: String): Flow<ActivityTypeEntity?>

    @Upsert
    fun upsertBlocking(type: ActivityTypeEntity)

    @Query("SELECT * FROM activity_types WHERE id = :id")
    fun getByIdBlocking(id: String): ActivityTypeEntity?

    @Query("SELECT * FROM activity_types WHERE id IN (:ids)")
    fun getByIdsBlocking(ids: List<String>): List<ActivityTypeEntity>

    @Query("DELETE FROM activity_types WHERE id = :id")
    fun physicalDeleteBlocking(id: String)
}

@Dao
interface EventDao {
    @Upsert
    suspend fun upsert(event: EventEntity)

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun getById(id: String): EventEntity?

    /** 仅撤销窗口内未上行的行可物理删除(协议 §5) */
    @Query("DELETE FROM events WHERE id = :id")
    suspend fun physicalDelete(id: String)

    @Query(
        """SELECT * FROM events
           WHERE babyId = :babyId AND deletedAt IS NULL
           ORDER BY startedAt DESC LIMIT :limit"""
    )
    fun observeTimeline(babyId: String, limit: Int = 100): Flow<List<EventEntity>>

    @Query(
        """SELECT * FROM events
           WHERE babyId = :babyId AND activityTypeId = :typeId AND deletedAt IS NULL
           ORDER BY startedAt DESC LIMIT 1"""
    )
    fun observeLast(babyId: String, typeId: String): Flow<EventEntity?>

    @Query(
        """SELECT * FROM events
           WHERE babyId = :babyId AND activityTypeId = :typeId
             AND status = 'ongoing' AND deletedAt IS NULL
           LIMIT 1"""
    )
    suspend fun ongoingFor(babyId: String, typeId: String): EventEntity?

    @Query(
        """SELECT * FROM events
           WHERE status = 'ongoing' AND deletedAt IS NULL"""
    )
    suspend fun allOngoing(): List<EventEntity>

    @Query(
        """SELECT * FROM events
           WHERE status = 'ongoing' AND deletedAt IS NULL"""
    )
    fun observeOngoing(): Flow<List<EventEntity>>

    @Query(
        """SELECT * FROM events
           WHERE babyId = :babyId AND activityTypeId = :typeId AND deletedAt IS NULL
           ORDER BY startedAt DESC LIMIT :limit"""
    )
    suspend fun recentFor(babyId: String, typeId: String, limit: Int): List<EventEntity>

    @Query(
        """SELECT * FROM events
           WHERE babyId = :babyId AND deletedAt IS NULL AND startedAt BETWEEN :from AND :to
           ORDER BY startedAt DESC"""
    )
    fun observeRange(babyId: String, from: Long, to: Long): Flow<List<EventEntity>>

    @Query(
        """SELECT COUNT(*) FROM events
           WHERE babyId = :babyId AND activityTypeId = :typeId
             AND deletedAt IS NULL AND startedAt >= :from"""
    )
    fun observeCountSince(babyId: String, typeId: String, from: Long): Flow<Int>

    @Upsert
    fun upsertBlocking(event: EventEntity)

    @Query(
        """SELECT * FROM events
           WHERE babyId = :babyId AND activityTypeId = :typeId
             AND status = 'ongoing' AND deletedAt IS NULL
           LIMIT 1"""
    )
    fun ongoingForBlocking(babyId: String, typeId: String): EventEntity?

    @Query("SELECT * FROM events WHERE status = 'ongoing' AND deletedAt IS NULL")
    fun allOngoingBlocking(): List<EventEntity>

    @Query("SELECT * FROM events WHERE id = :id")
    fun getByIdBlocking(id: String): EventEntity?

    @Query("SELECT * FROM events WHERE id IN (:ids)")
    fun getByIdsBlocking(ids: List<String>): List<EventEntity>

    @Query("DELETE FROM events WHERE id = :id")
    fun physicalDeleteBlocking(id: String)

    /** ongoing_conflict 时纯本地标记删除,不入 outbox(协议 §4) */
    @Query("UPDATE events SET deletedAt = :now, clientUpdatedAt = :now WHERE id = :id")
    fun markDeletedLocalBlocking(id: String, now: Long)
}

@Dao
interface EventAttachmentDao {
    @Upsert
    suspend fun upsert(attachment: EventAttachmentEntity)

    @Query("SELECT * FROM event_attachments WHERE id = :id")
    suspend fun getById(id: String): EventAttachmentEntity?

    @Query("SELECT * FROM event_attachments WHERE eventId = :eventId AND deletedAt IS NULL")
    fun observeForEvent(eventId: String): Flow<List<EventAttachmentEntity>>

    @Query("SELECT * FROM event_attachments WHERE uploadState = 'pending' AND deletedAt IS NULL")
    suspend fun pendingUploads(): List<EventAttachmentEntity>

    /** localPath 是本地专属字段,直改不触碰 LWW 版本 */
    @Query("UPDATE event_attachments SET localPath = :path WHERE id = :id")
    suspend fun updateLocalPath(id: String, path: String)

    @Upsert
    fun upsertBlocking(attachment: EventAttachmentEntity)

    @Query("SELECT * FROM event_attachments WHERE id = :id")
    fun getByIdBlocking(id: String): EventAttachmentEntity?

    @Query("SELECT * FROM event_attachments WHERE id IN (:ids)")
    fun getByIdsBlocking(ids: List<String>): List<EventAttachmentEntity>

    @Query("DELETE FROM event_attachments WHERE id = :id")
    fun physicalDeleteBlocking(id: String)
}

@Dao
interface FamilyMemberDao {
    @Query("SELECT * FROM family_members WHERE familyId = :familyId")
    fun observeAll(familyId: String): Flow<List<FamilyMemberEntity>>

    @Query("SELECT * FROM family_members WHERE familyId = :familyId AND userId = :userId")
    suspend fun get(familyId: String, userId: String): FamilyMemberEntity?

    @Upsert
    suspend fun upsert(member: FamilyMemberEntity)

    // 以下阻塞方法仅供同步层在事务内使用
    @Query("DELETE FROM family_members")
    fun deleteAllBlocking()

    @Upsert
    fun upsertBlocking(member: FamilyMemberEntity)
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: OutboxItemEntity): Long

    @Query("SELECT * FROM outbox ORDER BY opId")
    suspend fun all(): List<OutboxItemEntity>

    @Query("SELECT * FROM outbox WHERE entityId = :entityId ORDER BY opId")
    suspend fun forEntity(entityId: String): List<OutboxItemEntity>

    @Query("SELECT * FROM outbox WHERE state = :state ORDER BY opId LIMIT :limit")
    suspend fun byState(state: String, limit: Int): List<OutboxItemEntity>

    @Query("UPDATE outbox SET state = :state, holdUntil = :holdUntil WHERE opId = :opId")
    suspend fun updateState(opId: Long, state: String, holdUntil: Long?)

    @Query("DELETE FROM outbox WHERE opId = :opId")
    suspend fun delete(opId: Long)

    @Query("UPDATE outbox SET state = 'pending', holdUntil = NULL WHERE state = 'held' AND holdUntil <= :nowMillis")
    suspend fun releaseExpiredHolds(nowMillis: Long)

    // 以下阻塞方法仅供同步层 RoomOutboxStore 在事务内使用
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertBlocking(item: OutboxItemEntity): Long

    @Query("SELECT * FROM outbox WHERE entity = :entity ORDER BY opId")
    fun allForEntityBlocking(entity: String): List<OutboxItemEntity>

    @Query("SELECT * FROM outbox WHERE entity = :entity AND entityId = :entityId ORDER BY opId")
    fun forEntityIdBlocking(entity: String, entityId: String): List<OutboxItemEntity>

    @Query("UPDATE outbox SET state = :state, holdUntil = :holdUntil WHERE opId = :opId")
    fun updateStateBlocking(opId: Long, state: String, holdUntil: Long?)

    @Query("DELETE FROM outbox WHERE opId = :opId")
    fun deleteBlocking(opId: Long)
}

@Dao
interface SyncCursorDao {
    @Upsert
    suspend fun upsert(cursor: SyncCursorEntity)

    @Query("SELECT * FROM sync_cursors WHERE entity = :entity")
    suspend fun get(entity: String): SyncCursorEntity?
}

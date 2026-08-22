package app.kidschedule.data.repo

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import app.kidschedule.data.local.AppDatabase
import app.kidschedule.data.local.EventAttachmentEntity
import app.kidschedule.data.sync.SyncEngine
import app.kidschedule.data.sync.SyncEntity
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

// 附件:行随 outbox 同步(占位),图片文件走独立上传队列重试(协议 §11)。
class AttachmentRepo(
    private val context: Context,
    private val db: AppDatabase,
    syncEngine: SyncEngine,
    private val client: SupabaseClient,
    private val deviceId: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val engine = syncEngine.engines.getValue(SyncEntity.EVENT_ATTACHMENTS)
    private val bucket get() = client.storage.from("attachments")
    private val dir: File
        get() = File(context.filesDir, "attachments").apply { mkdirs() }

    suspend fun add(familyId: String, eventId: String, source: Uri) = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val file = File(dir, "$id.jpg")
        context.contentResolver.openInputStream(source)?.use { input ->
            file.outputStream().use { input.copyTo(it) }
        } ?: return@withContext
        val t = now()
        db.withTransaction {
            db.eventAttachmentDao().upsertBlocking(
                EventAttachmentEntity(
                    id = id, eventId = eventId, familyId = familyId,
                    storagePath = "$familyId/$eventId/$id.jpg",
                    uploadState = "pending", localPath = file.absolutePath,
                    deletedAt = null, clientUpdatedAt = t, deviceId = deviceId,
                )
            )
            engine.normalWrite(id, t)
        }
    }

    suspend fun softDelete(id: String) = withContext(Dispatchers.IO) {
        val t = now()
        db.withTransaction {
            val a = db.eventAttachmentDao().getByIdBlocking(id) ?: return@withTransaction
            db.eventAttachmentDao().upsertBlocking(
                a.copy(deletedAt = t, clientUpdatedAt = t, deviceId = deviceId)
            )
            engine.normalWrite(id, t)
        }
    }

    /** 上传队列:逐个上传 pending 附件,单个失败跳过等下一轮 */
    suspend fun uploadPending() = withContext(Dispatchers.IO) {
        for (a in db.eventAttachmentDao().pendingUploads()) {
            val path = a.storagePath ?: continue
            val file = a.localPath?.let(::File)?.takeIf { it.exists() } ?: continue
            runCatching {
                bucket.upload(path, file.readBytes()) { upsert = true }
                val t = now()
                db.withTransaction {
                    val cur = db.eventAttachmentDao().getByIdBlocking(a.id) ?: return@withTransaction
                    db.eventAttachmentDao().upsertBlocking(
                        cur.copy(uploadState = "uploaded", clientUpdatedAt = t, deviceId = deviceId)
                    )
                    engine.normalWrite(a.id, t)
                }
            }.onFailure { Log.w("Attach", "upload failed ${a.id}", it) }
        }
    }

    /** 他端上传的附件按需下载;返回可展示的本地路径,失败返回 null */
    suspend fun ensureLocal(a: EventAttachmentEntity): String? = withContext(Dispatchers.IO) {
        a.localPath?.let { if (File(it).exists()) return@withContext it }
        val path = a.storagePath ?: return@withContext null
        if (a.uploadState != "uploaded") return@withContext null
        runCatching {
            val bytes = bucket.downloadAuthenticated(path)
            val file = File(dir, "${a.id}.jpg")
            file.writeBytes(bytes)
            db.eventAttachmentDao().updateLocalPath(a.id, file.absolutePath)
            file.absolutePath
        }.onFailure { Log.w("Attach", "download failed ${a.id}", it) }.getOrNull()
    }
}

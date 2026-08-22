package app.kidschedule.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        BabyEntity::class,
        ActivityTypeEntity::class,
        EventEntity::class,
        EventAttachmentEntity::class,
        OutboxItemEntity::class,
        SyncCursorEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun babyDao(): BabyDao
    abstract fun activityTypeDao(): ActivityTypeDao
    abstract fun eventDao(): EventDao
    abstract fun eventAttachmentDao(): EventAttachmentDao
    abstract fun outboxDao(): OutboxDao
    abstract fun syncCursorDao(): SyncCursorDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "kidschedule.db")
                .build()
    }
}

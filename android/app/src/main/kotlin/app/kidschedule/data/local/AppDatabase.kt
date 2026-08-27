package app.kidschedule.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BabyEntity::class,
        ActivityTypeEntity::class,
        EventEntity::class,
        EventAttachmentEntity::class,
        FamilyMemberEntity::class,
        OutboxItemEntity::class,
        SyncCursorEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun babyDao(): BabyDao
    abstract fun activityTypeDao(): ActivityTypeDao
    abstract fun eventDao(): EventDao
    abstract fun eventAttachmentDao(): EventAttachmentDao
    abstract fun familyMemberDao(): FamilyMemberDao
    abstract fun outboxDao(): OutboxDao
    abstract fun syncCursorDao(): SyncCursorDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `family_members` (
                        `familyId` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        `displayName` TEXT,
                        `avatarEmoji` TEXT,
                        PRIMARY KEY(`familyId`, `userId`)
                    )"""
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `activity_types` ADD COLUMN `babyId` TEXT")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "kidschedule.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}

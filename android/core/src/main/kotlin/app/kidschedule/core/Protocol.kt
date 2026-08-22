package app.kidschedule.core

// 协议常量,见 docs/sync-protocol.md §14
object Protocol {
    const val UNDO_WINDOW_SEC = 10
    const val PUSH_BATCH_SIZE = 200
    const val PULL_PAGE_SIZE = 500
    const val RETRY_BACKOFF_BASE_SEC = 2
    const val RETRY_BACKOFF_MAX_SEC = 300
    const val REMINDER_SAMPLE_N = 20
    const val REMINDER_MIN_SAMPLES = 5
    const val ATTACHMENT_MAX_EDGE = 2048
    const val ATTACHMENT_JPEG_QUALITY = 80
}

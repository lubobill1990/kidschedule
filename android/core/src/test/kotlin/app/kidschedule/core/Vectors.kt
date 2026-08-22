package app.kidschedule.core

import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

object Vectors {
    val json = Json { ignoreUnknownKeys = true }

    fun read(name: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "shared-tests/vectors/$name")
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        error("shared-tests/vectors/$name not found upwards from ${System.getProperty("user.dir")}")
    }

    fun millis(iso: String): Long = Instant.parse(iso).toEpochMilli()
}

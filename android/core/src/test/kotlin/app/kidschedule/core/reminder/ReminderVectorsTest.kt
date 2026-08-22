package app.kidschedule.core.reminder

import app.kidschedule.core.Vectors
import kotlinx.serialization.Serializable
import org.junit.Test
import kotlin.test.assertEquals

@Serializable
private data class REvent(
    val started_at: String,
    val ended_at: String? = null,
    val status: String? = null,
    val deleted: Boolean = false,
)

@Serializable
private data class RExpected(val threshold_sec: Long?, val next_fire_at: String?)

@Serializable
private data class RCase(
    val name: String,
    val kind: String,
    val reminder_mode: String,
    val reminder_fixed_interval_sec: Long? = null,
    val events: List<REvent>,
    val expected: RExpected,
)

@Serializable
private data class ReminderVectorFile(val cases: List<RCase>)

class ReminderVectorsTest {

    @Test
    fun `reminder calc vectors`() {
        val file = Vectors.json.decodeFromString<ReminderVectorFile>(Vectors.read("reminder-calc.json"))
        for (case in file.cases) {
            val kind = when (case.kind) {
                "instant" -> ActivityKind.INSTANT
                "duration" -> ActivityKind.DURATION
                else -> error("unknown kind: ${case.kind}")
            }
            val mode = when (case.reminder_mode) {
                "auto" -> ReminderMode.AUTO
                "fixed" -> ReminderMode.FIXED
                "off" -> ReminderMode.OFF
                else -> error("unknown mode: ${case.reminder_mode}")
            }
            val events = case.events.map {
                ReminderEvent(
                    startedAt = Vectors.millis(it.started_at),
                    endedAt = it.ended_at?.let(Vectors::millis),
                    ongoing = it.status == "ongoing",
                    deleted = it.deleted,
                )
            }

            val result = ReminderCalculator.compute(kind, mode, case.reminder_fixed_interval_sec, events)

            assertEquals(case.expected.threshold_sec, result.thresholdSec, "case: ${case.name}, threshold")
            val expectedFire = case.expected.next_fire_at?.let(Vectors::millis)
            assertEquals(expectedFire, result.nextFireAtMillis, "case: ${case.name}, next_fire_at")
        }
    }
}

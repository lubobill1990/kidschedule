package app.kidschedule.core.sync

import app.kidschedule.core.Vectors
import kotlinx.serialization.Serializable
import org.junit.Test
import kotlin.test.assertEquals

@Serializable
private data class Step(
    val action: String,
    val entity_id: String? = null,
    val t: String,
    val expect_result: String? = null,
)

@Serializable
private data class ExpectedItem(val entity_id: String, val state: String)

@Serializable
private data class Expected(
    val outbox: List<ExpectedItem>,
    val local_rows: Map<String, Boolean>,
)

@Serializable
private data class OutboxCase(val name: String, val steps: List<Step>, val expected: Expected)

@Serializable
private data class OutboxVectorFile(val cases: List<OutboxCase>)

class OutboxVectorsTest {

    @Test
    fun `outbox state machine vectors`() {
        val file = Vectors.json.decodeFromString<OutboxVectorFile>(Vectors.read("outbox-state.json"))
        for (case in file.cases) {
            runCase(case)
        }
    }

    private fun runCase(case: OutboxCase) {
        val store = InMemoryOutboxStore()
        val rows = InMemoryLocalRowStore()
        val engine = OutboxEngine(store, rows)
        var lastBatch: List<OutboxItem> = emptyList()

        for (step in case.steps) {
            val now = Vectors.millis(step.t)
            when (step.action) {
                "quick_record" -> engine.quickRecord(step.entity_id!!, now)
                "normal_write" -> engine.normalWrite(step.entity_id!!, now)
                "undo" -> {
                    val result = engine.undo(step.entity_id!!)
                    val expected = when (step.expect_result) {
                        "ok" -> UndoResult.OK
                        "rejected" -> UndoResult.REJECTED
                        null -> null
                        else -> error("unknown expect_result: ${step.expect_result}")
                    }
                    if (expected != null) {
                        assertEquals(expected, result, "case: ${case.name}, undo result")
                    }
                }
                "tick" -> engine.releaseExpiredHolds(now)
                "push_begin" -> lastBatch = engine.pushBegin()
                "push_ack" -> engine.pushAck(lastBatch.map { it.opId })
                "push_fail" -> engine.pushFail(lastBatch.map { it.opId })
                "pull_remote_wins" -> engine.pullRemoteWins(step.entity_id!!)
                else -> error("unknown action: ${step.action}")
            }
        }

        val actualOutbox = store.items()
            .map { it.entityId to it.state.name.lowercase() }
            .sortedBy { "${it.first}|${it.second}" }
        val expectedOutbox = case.expected.outbox
            .map { it.entity_id to it.state }
            .sortedBy { "${it.first}|${it.second}" }
        assertEquals(expectedOutbox, actualOutbox, "case: ${case.name}, outbox items")

        for ((entityId, shouldExist) in case.expected.local_rows) {
            assertEquals(shouldExist, rows.exists(entityId), "case: ${case.name}, local row $entityId")
        }
    }
}

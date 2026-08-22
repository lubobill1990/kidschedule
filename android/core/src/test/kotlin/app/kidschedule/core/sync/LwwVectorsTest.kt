package app.kidschedule.core.sync

import app.kidschedule.core.Vectors
import kotlinx.serialization.Serializable
import org.junit.Test
import kotlin.test.assertEquals

@Serializable
private data class LwwSide(val client_updated_at: String, val device_id: String)

@Serializable
private data class LwwCase(
    val name: String,
    val a: LwwSide,
    val b: LwwSide,
    val expected: String,
)

@Serializable
private data class LwwVectorFile(val cases: List<LwwCase>)

class LwwVectorsTest {

    @Test
    fun `lww merge vectors`() {
        val file = Vectors.json.decodeFromString<LwwVectorFile>(Vectors.read("lww-merge.json"))
        for (case in file.cases) {
            val a = LwwVersion(Vectors.millis(case.a.client_updated_at), case.a.device_id)
            val b = LwwVersion(Vectors.millis(case.b.client_updated_at), case.b.device_id)
            val expected = when (case.expected) {
                "a_wins" -> LwwVerdict.A_WINS
                "b_wins" -> LwwVerdict.B_WINS
                "equal_keep_a" -> LwwVerdict.EQUAL_KEEP_A
                else -> error("unknown expected: ${case.expected}")
            }
            assertEquals(expected, Lww.decide(a, b), "case: ${case.name}")
        }
    }
}

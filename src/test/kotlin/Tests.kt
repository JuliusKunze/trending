import org.jetbrains.spek.api.Spek
import kotlin.test.assertEquals

class Tests : Spek() { init {
    given("a list of number pairs") {
        val l = listOf(1.0 to 2.0, 3.0 to 1.0, 0.0 to 4.0)

        on("getting the weighted average") {
            val a = l.weightedAverageBy(weight = { it.first }) { it.second }

            it("should be as expected") {
                assertEquals(1.25, a)
            }
        }
    }
}
}
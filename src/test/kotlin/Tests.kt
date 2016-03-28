import org.jetbrains.spek.api.Spek
import kotlin.test.assertEquals

class Tests : Spek() { init {
    given("a list of number pairs") {
        val l = listOf(1.0 to 2.0, 3.0 to 1.0, 0.0 to 4.0)

        on("getting the weighted average") {
            val a = l.averageWeightedBy(weight = { it.first }) { it.second }

            it("should be as expected") {
                assertEquals(1.25, a)
            }
        }
    }

    given("a list of number pairs") {
        val l = listOf(1.0 to 1.0, 2.0 to 2.0, 3.0 to 4.0)

        on("getting the weighted average") {
            val a = l.averageWeightedBy(weight = { it.first }) { it.second }

            it("should be as expected") {
                assertEquals((1.0 + 2 * 2 + 3 * 4) / (1 + 2 + 3), a)
            }
        }
    }

    given("a list of numbers") {
        val l = listOf(1.0, 2.0, 3.0)

        on("getting the product") {
            val p = l.product()

            it("should be the product") {
                assertEquals(6.0, p)
            }
        }
    }

    given("the dax and a wma2 of dax") {
        val wma2 = dax.weightedMovingAverage(2)

        on("getting the dax value on a specific date") {
            val d = dax.value(instant(2013, 1, 7))

            it("should equal the value calculated on the on vista web page http://www.onvista.de/index/chart/DAX-Index-20735?notation=20735&activeType=line&activeTab=M1&displayVolume=true&scaling=linear&indicatorselect=%7B%22indicator%22%3A%5B%5B%22WeightedMovingAverage%22%2C%7B%22period%22%3A28%7D%5D%5D%7D") {
                assertEquals(7732.660, d)
            }
        }

        on("getting the wma2 value at a specific date") {
            val v = wma2.value(instant(2013, 1, 7))

            it("should equal the value found on the on-vista web page http://www.onvista.de/index/chart/DAX-Index-20735?notation=20735&activeType=line&activeTab=M1&displayVolume=true&scaling=linear&indicatorselect=%7B%22indicator%22%3A%5B%5B%22WeightedMovingAverage%22%2C%7B%22period%22%3A28%7D%5D%5D%7D") {
                assertEquals(7747.230, v)
            }
        }
    }
}
}
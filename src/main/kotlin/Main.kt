import org.jfree.chart.ChartFactory
import org.jfree.chart.ChartUtilities
import org.jfree.chart.JFreeChart
import org.jfree.chart.plot.PlotOrientation
import org.jfree.data.xy.XYDataItem
import org.jfree.data.xy.XYSeries
import org.jfree.data.xy.XYSeriesCollection
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

fun LocalDate.toInstant() = atStartOfDay().toInstant(ZoneOffset.UTC)
fun instantFromDateString(s: String) = LocalDate.parse(s).toInstant()
fun instant(year: Int, month: Int, day: Int) = LocalDate.of(year, month, day).toInstant()
operator fun Instant.minus(other: Instant) = Duration.between(other, this)
val Duration.totalYears: Double get() = toDays() / 365.0

fun Double.toPercentString() = this.toPercent().toInt().toString() + "%"
fun Double.toPercent() = this * 100
val Instant.year: Int get() = atOffset(ZoneOffset.UTC).year

class DatedValue(val value: Double, val instant: Instant)

fun <T> List<T>.weightedAverageBy(weight: (T) -> Double, value: (T) -> Double) = sumByDouble { weight(it) * value(it) } / sumByDouble { weight(it) }


private val days: List<Long> by lazy {
    val min = 8
    val max = 500
    val factor = 1.1
    (0..(Math.log(max.toDouble() / min) / Math.log(factor)).toInt()).map { (min * Math.pow(factor, it.toDouble())).toLong() }
}

fun main(args: Array<String>) {
    val cells = File("dax.csv").readLines().drop(1).map { it.split(";") }.filter { it.any { it.isNotEmpty() } }
    val dax = TimedIndex(cells.withIndex().map { DatedValue(it.value[4].replace(".", "").replace(",", ".").toDouble(), instantFromDateString(it.value[0])) })

    val enclosingInterval = dax.Interval(start = instant(1993, 1, 4), end = dax.totalInterval.end)

    val simpleMovingAverageStrategies = days.map { dax.SimpleMovingAverageStrategy(duration = Duration.ofDays(it), enclosingInterval = enclosingInterval) }
    val exponentialMovingAverageStrategies = days.map { dax.ExponentialMovingAverageStrategy(halfLife = Duration.ofDays(it), enclosingInterval = enclosingInterval) }
    val buyAndHoldStrategy = dax.Strategy(listOf(enclosingInterval), enclosingInterval = enclosingInterval)
    val buyAndHoldStrategiesChartDummy = days.map { buyAndHoldStrategy }

    saveChartPng(
            (listOf("DAX" to dax) + simpleMovingAverageStrategies.withIndex().map { "SMA ${days[it.index]}" to it.value.reference }).toMap(),
            File("simple moving averages of dax.png"))

    saveChartPng(
            (listOf("DAX" to dax) + exponentialMovingAverageStrategies.withIndex().map { "EMA ${days[it.index]} (half life)" to it.value.reference }).toMap(),
            File("exponential moving averages of dax.png"))

    fun List<TimedIndex.Strategy>.gainsByDays() = withIndex().map { XYDataItem(days[it.index], it.value.averageRelativeGainPerYear.toPercent()) }
    fun List<TimedIndex.Strategy>.transactionsByDays() = withIndex().map { XYDataItem(days[it.index], it.value.transactionCount) }

    saveChartPng(mapOf(
            "simple moving average strategy" to simpleMovingAverageStrategies.gainsByDays(),
            "exponential moving average strategy" to exponentialMovingAverageStrategies.gainsByDays(),
            "buy and hold strategy (as comparison)" to buyAndHoldStrategiesChartDummy.gainsByDays()),
            File("gain by moving average duration (test period ${enclosingInterval.start.year} - ${enclosingInterval.end.year}).png"),
            title = "",
            xAxisLabel = "moving average duration or half life in days",
            yAxisLabel = "average relative gain per year in %")

    saveChartPng(mapOf(
            "simple moving average strategy" to simpleMovingAverageStrategies.transactionsByDays(),
            "exponential moving average strategy" to exponentialMovingAverageStrategies.transactionsByDays(),
            "buy and hold strategy (as comparison)" to buyAndHoldStrategiesChartDummy.transactionsByDays()),
            File("transaction count by moving average duration (test period ${enclosingInterval.start.year} - ${enclosingInterval.end.year}).png"),
            xAxisLabel = "moving average duration or half life in days",
            yAxisLabel = "transaction count")
}

fun chartDataSet(values: Map<String, List<XYDataItem>>) = XYSeriesCollection().apply {
    values.forEach { addSeries(XYSeries(it.key).apply { it.value.forEach { add(it) } }) }
}

fun JFreeChart.saveToPng(file: File) = ChartUtilities.writeChartAsPNG(FileOutputStream(file), this, 1920, 1080)

fun saveChartPng(values: Map<String, List<XYDataItem>>, file: File, title: String = file.nameWithoutExtension, xAxisLabel: String, yAxisLabel: String) = ChartFactory.createXYLineChart(
        title,
        xAxisLabel,
        yAxisLabel,
        chartDataSet(values),
        PlotOrientation.VERTICAL,
        true, true, false).saveToPng(file)

fun saveChartPng(values: Map<String, TimedIndex>, file: File, title: String = file.nameWithoutExtension, valueAxisLabel: String = "Value") = ChartFactory.createTimeSeriesChart(
        title,
        "Time",
        valueAxisLabel,
        chartDataSet(values.mapValues { it.value.values.map { XYDataItem(it.instant.toEpochMilli(), it.value) } }),
        true, true, false).saveToPng(file)

class TimedIndex(unsortedValues: List<DatedValue>) {
    val values = unsortedValues.sortedBy { it.instant.epochSecond }

    val totalInterval = Interval(start = values.first().instant, end = values.last().instant)


    private fun aboveIntervals(enclosingInterval: Interval, other: TimedIndex): List<Interval> {
        val aboveIndexes = values.indices.filter { values[it].value > other.values[it].value }
        val beginning = aboveIndexes.filter { it - 1 !in aboveIndexes }
        val ending = aboveIndexes.filter { it + 1 !in aboveIndexes }
        return beginning.zip(ending) { b, e -> Interval(values[b].instant, values[e].instant) }.filter { it.start >= enclosingInterval.start && it.end <= enclosingInterval.end }
    }

    inner open class IntervalsAboveStrategy(val reference: TimedIndex, enclosingInterval: Interval = totalInterval) : Strategy(aboveIntervals(enclosingInterval, reference), enclosingInterval = enclosingInterval)

    fun valuesBefore(instant: Instant) = values.filter { it.instant < instant }

    fun simpleMovingAverageBefore(instant: Instant, duration: Duration) = valuesBefore(instant).filter { instant - duration < it.instant }.map { it.value }.average()
    fun simpleMovingAverage(duration: Duration) = TimedIndex(values.map { DatedValue(simpleMovingAverageBefore(it.instant, duration = duration), it.instant) })
    // https://en.wikipedia.org/wiki/Moving_average
    inner class SimpleMovingAverageStrategy(val duration: Duration, enclosingInterval: Interval) : IntervalsAboveStrategy(simpleMovingAverage(duration), enclosingInterval = enclosingInterval)

    fun exponentialMovingAverageBefore(instant: Instant, halfLife: Duration): Double {
        if (halfLife.isNegative) {
            throw IllegalArgumentException("Half life must be non-negative.")
        }

        val alpha = Math.pow(.5, 1.0 / halfLife.toDays())

        assert(alpha < 1)

        return valuesBefore(instant).weightedAverageBy({ Math.pow(alpha, (instant - it.instant).toDays().toDouble()) }) { it.value }
    }

    fun exponentialMovingAverage(halfLife: Duration) = TimedIndex(values.map { DatedValue(exponentialMovingAverageBefore(it.instant, halfLife = halfLife), it.instant) })
    inner class ExponentialMovingAverageStrategy(val halfLife: Duration, enclosingInterval: Interval) : IntervalsAboveStrategy(exponentialMovingAverage(halfLife), enclosingInterval = enclosingInterval)

    fun value(instant: Instant) = values.single { it.instant == instant }.value

    inner class Interval(val start: Instant, val end: Instant) {
        val duration = end - start
        val startValue = value(start)
        val endValue = value(end)
        val gain = endValue - startValue
        val relativeGain = gain / startValue
        val gainFactor = 1 + relativeGain
        val gainFactorPerYear = Math.pow(gainFactor, 1 / duration.totalYears)

        override fun toString() = "${duration.toDays().toString()} days from $start, ${gainFactorPerYear.toPercentString()}% gain per year"
    }

    inner open class Strategy(val buyIntervals: List<Interval>, val enclosingInterval: Interval) {
        init {
            val outliers = buyIntervals.filter { it.start < enclosingInterval.start || it.end > enclosingInterval.end }
            if(outliers.any()) {
                throw IllegalArgumentException("Buy intervals must be inside enclosing interval: $outliers")
            }
        }

        val gainFactor = buyIntervals.fold(1.0) { gain, new -> gain * new.gainFactor }

        val transactionCount = 2 * buyIntervals.size

        val averageGainFactorPerYear = Math.pow(gainFactor, 1 / enclosingInterval.duration.totalYears)
        val averageRelativeGainPerYear = averageGainFactorPerYear - 1

        override fun toString() = "Total gain: ${gainFactor.toPercentString()}, avg. per year: ${averageGainFactorPerYear.toPercentString()}"
    }
}

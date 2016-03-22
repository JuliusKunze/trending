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
    val factor = 1.2
    (0..(Math.log(max.toDouble() / min) / Math.log(factor)).toInt()).map { (min * Math.pow(factor, it.toDouble())).toLong() }
}

fun main(args: Array<String>) {
    val cells = File("dax.csv").readLines().drop(1).map { it.split(";") }.filter { it.any { it.isNotEmpty() } }
    val dax = TimedIndex(cells.withIndex().map { DatedValue(it.value[4].replace(".", "").replace(",", ".").toDouble(), instantFromDateString(it.value[0])) })

    val enclosingInterval = dax.Interval(start = instant(1993, 1, 4), end = dax.totalInterval.end)

    val movingAverages = days.map { dax.movingAverage(Duration.ofDays(it)) }
    val exponentialMovingAverages = days.map { dax.exponentialMovingAverage(halfLife = Duration.ofDays(it)) }
    val movingAverageStrategies = movingAverages.map { dax.intervalsAboveStrategy(it, enclosingInterval = enclosingInterval) }
    val exponentialMovingAverageStrategies = exponentialMovingAverages.map { dax.intervalsAboveStrategy(it, enclosingInterval = enclosingInterval) }
    val passiveStrategy = dax.Strategy(listOf(enclosingInterval), enclosingInterval = enclosingInterval)

    saveChartPng(
            (listOf("DAX" to dax) +
                    movingAverages.indices.map { "Moving average ${days[it]} days" to movingAverages[it] }).toMap(),
            File("moving averages of dax.png"))

    saveChartPng(
            (listOf("DAX" to dax) +
                    exponentialMovingAverages.indices.map { "Exponential moving average ${days[it]} days half life" to exponentialMovingAverages[it] }).toMap(),
            File("exponential moving averages of dax.png"))

    val gains = movingAverageStrategies.withIndex().map { XYDataItem(days[it.index], it.value.averageRelativeGainPerYear.toPercent()) }
    val exponentialGains = exponentialMovingAverageStrategies.withIndex().map { XYDataItem(days[it.index], it.value.averageRelativeGainPerYear.toPercent()) }
    val passiveValuesDummy = days.map { XYDataItem(it, passiveStrategy.averageRelativeGainPerYear.toPercent()) }

    saveChartPng(mapOf(
            "moving average strategy" to gains,
            "exponential moving average strategy" to exponentialGains,
            "passive strategy (as comparison)" to passiveValuesDummy),
            File("gain.png"),
            title = "gain by moving average duration (test period ${enclosingInterval.start.year} - ${enclosingInterval.end.year})",
            xAxisLabel = "moving average duration in days",
            yAxisLabel = "average relative gain per year in %")

    saveChartPng(mapOf(
            "moving average strategy" to gains,
            "exponential moving average strategy" to exponentialGains,
            "passive strategy (as comparison)" to passiveValuesDummy),
            File("gain.png"),
            title = "gain by moving average duration (test period ${enclosingInterval.start.year} - ${enclosingInterval.end.year})",
            xAxisLabel = "moving average duration in days",
            yAxisLabel = "average relative gain per year in %")
}

fun chartDataSet(values: Map<String, List<XYDataItem>>) = XYSeriesCollection().apply {
    values.forEach { addSeries(XYSeries(it.key).apply { it.value.forEach { add(it) } }) }
}

fun JFreeChart.saveToPng(file: File) = ChartUtilities.writeChartAsPNG(FileOutputStream(file), this, 1920, 1080)

fun saveChartPng(values: Map<String, List<XYDataItem>>, file: File, title: String, xAxisLabel: String, yAxisLabel: String) = ChartFactory.createXYLineChart(
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

    fun valuesBefore(instant: Instant) = values.filter { it.instant < instant }
    fun movingAverageBefore(instant: Instant, duration: Duration) = valuesBefore(instant).filter { instant - duration < it.instant }.map { it.value }.average()
    fun exponentialMovingAverageBefore(instant: Instant, halfLife: Duration): Double {
        if (halfLife.isNegative) {
            throw IllegalArgumentException("Half life must be non-negative.")
        }

        val base = Math.pow(.5, 1.0 / halfLife.toDays())

        assert(base < 1)

        return valuesBefore(instant).weightedAverageBy({ Math.pow(base, (instant - it.instant).toDays().toDouble()) }) { it.value }
    }

    fun movingAverage(duration: Duration) = TimedIndex(values.map { DatedValue(movingAverageBefore(it.instant, duration = duration), it.instant) })
    fun movingAverageStrategy(duration: Duration, enclosingInterval: Interval) = intervalsAboveStrategy(movingAverage(duration), enclosingInterval = enclosingInterval)

    fun exponentialMovingAverage(halfLife: Duration) = TimedIndex(values.map { DatedValue(exponentialMovingAverageBefore(it.instant, halfLife = halfLife), it.instant) })

    fun intervalsAboveStrategy(other: TimedIndex, enclosingInterval: Interval = totalInterval): Strategy {
        val aboveIndexes = values.indices.filter { values[it].value > other.values[it].value }
        val beginning = aboveIndexes.filter { it - 1 !in aboveIndexes }
        val ending = aboveIndexes.filter { it + 1 !in aboveIndexes }
        return Strategy(beginning.zip(ending) { b, e -> Interval(values[b].instant, values[e].instant) }.filter { it.start >= enclosingInterval.start && it.end <= enclosingInterval.end }, enclosingInterval = enclosingInterval)
    }

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

    inner class Strategy(val buyIntervals: List<Interval>, val enclosingInterval: Interval) {
        val gainFactor = buyIntervals.fold(1.0) { gain, new -> gain * new.gainFactor }

        val transactionCount = 2 * buyIntervals.size

        val averageGainFactorPerYear = Math.pow(gainFactor, 1 / enclosingInterval.duration.totalYears)
        val averageRelativeGainPerYear = averageGainFactorPerYear - 1

        override fun toString() = "Total gain: ${gainFactor.toPercentString()}, avg. per year: ${averageGainFactorPerYear.toPercentString()}"
    }
}

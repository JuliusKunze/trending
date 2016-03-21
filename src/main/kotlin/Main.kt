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

fun Double.toPercentString() = (this * 100).toInt().toString() + "%"

class DatedValue(val value: Double, val instant: Instant)

fun main(args: Array<String>) {
    val cells = File("dax.csv").readLines().drop(1).map { it.split(";") }.filter { it.any { it.isNotEmpty() } }
    val dax = TimedIndex(cells.withIndex().map { DatedValue(it.value[4].replace(".", "").replace(",", ".").toDouble(), instantFromDateString(it.value[0])) })

    val enclosingInterval = dax.Interval(start = instant(1993, 1, 4), end = dax.totalInterval.end)

    val days = 7000L
    val movingAverage = dax.movingAverage(Duration.ofDays(days))

    val movingAverageStrategy = dax.intervalsAboveStrategy(movingAverage, enclosingInterval = enclosingInterval)
    val passiveStrategy = dax.Strategy(listOf(enclosingInterval), enclosingInterval = enclosingInterval)

    saveChartPng(mapOf("DAX" to dax, "Moving Average $days days" to movingAverage), File("chart.png"), title = "DAX")
}


fun chartDataSet(values: Map<String, List<XYDataItem>>) = XYSeriesCollection().apply {
    values.forEach { addSeries(XYSeries(it.key).apply { it.value.forEach { add(it) } }) }
}

fun JFreeChart.saveToPng(file: File) = ChartUtilities.writeChartAsPNG(FileOutputStream(file), this, 1500, 1000)

fun saveChartPng(values: Map<String, List<XYDataItem>>, file: File, title: String, xAxixLabel: String, yAxisLabel: String) = ChartFactory.createXYLineChart(
        title,
        xAxixLabel,
        yAxisLabel,
        chartDataSet(values),
        PlotOrientation.VERTICAL,
        true, true, false).saveToPng(file)

fun saveChartPng(values: Map<String, TimedIndex>, file: File, title: String, valueAxisLabel: String = title) = ChartFactory.createTimeSeriesChart(
        title,
        "Time",
        valueAxisLabel,
        chartDataSet(values.mapValues { it.value.values.map { XYDataItem(it.instant.toEpochMilli(), it.value) } }),
        true, true, false).saveToPng(file)

class TimedIndex(unsortedValues: List<DatedValue>) {
    val values = unsortedValues.sortedBy { it.instant.epochSecond }

    val totalInterval = Interval(start = values.first().instant, end = values.last().instant)

    fun movingAverageBefore(date: Instant, duration: Duration) = values.filter { date - duration < it.instant && it.instant < date }.map { it.value }.average()
    fun movingAverage(duration: Duration) = TimedIndex(values.map { DatedValue(movingAverageBefore(it.instant, duration = duration), it.instant) })
    fun movingAverageStrategy(duration: Duration, enclosingInterval: Interval) = intervalsAboveStrategy(movingAverage(duration), enclosingInterval = enclosingInterval)

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
        val averageGainFactorPerYear = Math.pow(gainFactor, 1 / totalInterval.duration.totalYears)

        override fun toString() = "Total gain: ${gainFactor.toPercentString()}, avg. per year: ${averageGainFactorPerYear.toPercentString()}"
    }
}

val Duration.totalYears: Double get() = toDays() / 365.0


import org.jfree.chart.ChartFactory
import org.jfree.chart.ChartUtilities
import org.jfree.chart.JFreeChart
import org.jfree.chart.labels.XYItemLabelGenerator
import org.jfree.chart.plot.PlotOrientation
import org.jfree.chart.plot.XYPlot
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer
import org.jfree.data.xy.XYDataItem
import org.jfree.data.xy.XYSeries
import org.jfree.data.xy.XYSeriesCollection
import org.jsoup.Jsoup
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.nio.channels.Channels
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

fun LocalDate.toInstant() = atStartOfDay().toInstant(ZoneOffset.UTC)
fun instantFromDateString(s: String) = LocalDate.parse(s).toInstant()
fun instant(year: Int, month: Int, day: Int) = LocalDate.of(year, month, day).toInstant()
operator fun Instant.minus(other: Instant) = Duration.between(other, this)
operator fun Duration.div(number: Int) = dividedBy(number.toLong())
operator fun Duration.times(number: Int) = multipliedBy(number.toLong())
val Duration.days: Double get() = seconds.toDouble() / (60 * 60 * 24)
val Duration.years: Double get() = days / 365.0

fun Double.toPercentString() = ((this.toPercent() * 10).toInt() / 10.0).toString() + "%"
fun Double.toPercent() = this * 100
val Instant.year: Int get() = atOffset(ZoneOffset.UTC).year

data class DatedValue(val value: Double, val instant: Instant)

val relativeTransactionFee = 0.0005

fun <T> List<T>.averageWeightedBy(weight: (T) -> Double, value: (T) -> Double) = sumByDouble { weight(it) * value(it) } / sumByDouble { weight(it) }
fun List<Double>.product() = fold(1.0) { product, factor -> product * factor }
fun <T> List<T>.productBy(selector: (T) -> Double) = map(selector).product()

private fun URL.downloadTo(file: File) = file.apply {
    FileOutputStream(this).channel.transferFrom(Channels.newChannel(openStream()), 0, Long.MAX_VALUE)
}

fun main(args: Array<String>) {
    val shortDax = shortDax()
    val sp500TotalReturn = sp500TotalReturn()
    val sp500PriceReturn = sp500PriceReturn()
    saveChartPng(
            mapOf("total return" to sp500TotalReturn.values, "price return" to sp500PriceReturn.values),
            File("total return vs price return.png"))

    //analyze(sp500TotalReturn, "SP500TR")
    analyze(dax(), "DAX")
}

fun sp500TotalReturn(): TimedIndex {
    // data from https://www.google.de/url?sa=t&rct=j&q=&esrc=s&source=web&cd=2&cad=rja&uact=8&ved=0ahUKEwj0k7PF8NbLAhVkj3IKHaxvC_8QFggkMAE&url=http%3A%2F%2Fwww.cboe.com%2Fmicro%2Fbuywrite%2Fdailypricehistory.xls&usg=AFQjCNEO59UEWFT2cM8YYNLcH0NXGtTrng&sig2=XNKdDd4l9Ff24xxo6gEkuA
    val tableFile = File("SP500TR.csv")
    val cells = tableFile.readLines().drop(1).map { it.split(";") }.filter { it.any { it.isNotEmpty() } }
    return TimedIndex(cells.withIndex().map { DatedValue(it.value[1].toDouble(), instantFromDateString(it.value[0])) })
}

fun sp500PriceReturn(): TimedIndex {
    val tableFile = File("SP500PR.csv")
    val today = LocalDate.now()
    URL("http://real-chart.finance.yahoo.com/table.csv?s=%5EGSPC&a=00&b=1&c=1900&d=${today.month}&e=${today.dayOfMonth}&f=${today.year}&g=d&ignore=.csv").downloadTo(tableFile)

    val cells = tableFile.readLines().drop(1).map { it.split(",") }.filter { it.any { it.isNotEmpty() } }
    return TimedIndex(cells.withIndex().map { DatedValue(it.value[4].toDouble(), instantFromDateString(it.value[0])) })
}

fun dax(): TimedIndex {
    val cells = File("dax.csv").readLines().drop(1).map { it.split(";") }.filter { it.any { it.isNotEmpty() } }
    return TimedIndex(cells.withIndex().map { DatedValue(it.value[4].replace(".", "").replace(",", ".").toDouble(), instantFromDateString(it.value[0])) })
}

fun shortDax(): TimedIndex {
    val doc = Jsoup.parse(File("shortDax.html"), "UTF-8", "http://de.investing.com/indices/short-dax-historical-data");

    val dates = doc.select("#curr_table .bold.noWrap").map { instantFromDateString(it.ownText()) }
    val values = doc.select("#curr_table .noWrap+ td").map { it.ownText().toDouble() }

    return TimedIndex(dates.zip(values) { date, value -> DatedValue(value = value, instant = date) })
}

private fun analyze(index: TimedIndex, name: String, enclosingInterval: TimedIndex.Interval = index.Interval(start = instant(2010, 1, 4), end = instant(2014, 1, 10))) {
    val differences = index.values.withIndex().drop(1).map {
        val before = index.values[it.index - 1].value
        val after = it.value.value
        DatedValue((after - before) / before, it.value.instant)
    }
    println("max relative daily losses: ${differences.sortedBy { it.value }.map { "${it.value.toPercentString()} (${it.instant})" }.take(10)}")

    val min = 8
    val max = 500
    val factor = 1.2
    val days = (0..(Math.log(max.toDouble() / min) / Math.log(factor)).toInt()).map { (min * Math.pow(factor, it.toDouble())).toLong() }

    val buyAndHoldStrategy = index.Strategy(listOf(enclosingInterval), enclosingInterval = enclosingInterval)
    val buyAndHoldStrategiesChartDummy = days.map { buyAndHoldStrategy }
    val simpleMovingAverageStrategies = days.map { index.SimpleMovingAverageStrategy(duration = Duration.ofDays(it), enclosingInterval = enclosingInterval) }
    val weightedMovingAverageStrategies = days.map { index.WeightedMovingAverageStrategy(duration = Duration.ofDays(it), enclosingInterval = enclosingInterval) }
    val exponentialMovingAverageStrategies = days.map { index.ExponentialMovingAverageStrategy(halfLife = Duration.ofDays(it), enclosingInterval = enclosingInterval) }

    val testPeriodDescription = "(assuming ${relativeTransactionFee.toPercentString()} fee per transaction) ($name ${enclosingInterval.start.year} - ${enclosingInterval.end.year}).png"

    fun TimedIndex.valuesInEnclosingInterval() = values.filter { it.instant in enclosingInterval }

    saveChartPng(
            (listOf(name to index.valuesInEnclosingInterval()) + simpleMovingAverageStrategies.withIndex().map { "SMA ${days[it.index]}" to it.value.reference.valuesInEnclosingInterval() }).toMap(),
            File("simple moving averages ($name).png"))

    saveChartPng(
            (listOf(name to index.valuesInEnclosingInterval()) + weightedMovingAverageStrategies.withIndex().map { "WMA ${days[it.index]}" to it.value.reference.valuesInEnclosingInterval() }).toMap(),
            File("weighted moving averages ($name).png"))

    saveChartPng(
            (listOf(name to index.valuesInEnclosingInterval()) + exponentialMovingAverageStrategies.withIndex().map { "EMA ${days[it.index]} (half life)" to it.value.reference.valuesInEnclosingInterval() }).toMap(),
            File("exponential moving averages ($name).png"))

    fun TimedIndex.Strategy.gainByTime() = index.valuesInEnclosingInterval().map { DatedValue(Math.log(gainFactorBy(it.instant)) / Math.log(2.0), it.instant) }

    saveChartPng(
            (simpleMovingAverageStrategies.withIndex().map { "WMA ${days[it.index]}" to it.value.gainByTime() } +
                    listOf("buy and hold strategy (as comparison)" to buyAndHoldStrategy.gainByTime())).toMap(),
            File("SMA gains factor (logarithmic: +1 means x2) by time" + testPeriodDescription))

    fun List<TimedIndex.Strategy>.averageYearlyRelativeGainsByDays() = withIndex().map { XYDataItem(days[it.index], it.value.averageRelativeGainPerYear.toPercent()) }
    fun List<TimedIndex.Strategy>.averageTransactionIntervalInDaysByDays() = withIndex().map { XYDataItem(days[it.index], it.value.averageTransactionInterval.days) }

    fun List<TimedIndex.Strategy>.averageYearlyRelativeGainsByTransactionInterval() = map { XYDataItem(it.averageTransactionInterval.days, it.averageRelativeGainPerYear.toPercent()) }

    saveChartPng(mapOf(
            "simple moving average strategy" to simpleMovingAverageStrategies.averageYearlyRelativeGainsByTransactionInterval(),
            "weighted moving average strategy" to weightedMovingAverageStrategies.averageYearlyRelativeGainsByTransactionInterval(),
            "exponential moving average strategy" to exponentialMovingAverageStrategies.averageYearlyRelativeGainsByTransactionInterval()),
            File("averageYearlyRelativeGainsByTransactionInterval" + testPeriodDescription),
            xAxisLabel = "averageTransactionInterval",
            yAxisLabel = "relative gain per year in %") {
        val renderer = (plot as XYPlot).renderer as XYLineAndShapeRenderer
        renderer.baseItemLabelGenerator = XYItemLabelGenerator { xyDataset, seriesIndex, itemIndex -> days[itemIndex].toString() }
        renderer.baseItemLabelsVisible = true
    }

    saveChartPng(mapOf(
            "simple moving average strategy" to simpleMovingAverageStrategies.averageYearlyRelativeGainsByDays(),
            "weighted moving average strategy" to weightedMovingAverageStrategies.averageYearlyRelativeGainsByDays(),
            "exponential moving average strategy" to exponentialMovingAverageStrategies.averageYearlyRelativeGainsByDays(),
            "buy and hold strategy (as comparison)" to buyAndHoldStrategiesChartDummy.averageYearlyRelativeGainsByDays()),
            File("relative gain per year" + testPeriodDescription),
            xAxisLabel = "moving average duration or half life in days",
            yAxisLabel = "relative gain per year in %")

    saveChartPng(mapOf(
            "simple moving average strategy" to simpleMovingAverageStrategies.averageTransactionIntervalInDaysByDays(),
            "weighted moving average strategy" to weightedMovingAverageStrategies.averageTransactionIntervalInDaysByDays(),
            "exponential moving average strategy" to exponentialMovingAverageStrategies.averageTransactionIntervalInDaysByDays()),
            File("average transaction interval" + testPeriodDescription),
            xAxisLabel = "moving average duration or half life in days",
            yAxisLabel = "average transaction interval in days")
}

fun chartDataSet(values: Map<String, List<XYDataItem>>) = XYSeriesCollection().apply {
    values.forEach { addSeries(XYSeries(it.key).apply { it.value.forEach { add(it) } }) }
}

fun JFreeChart.saveToPng(file: File) = ChartUtilities.writeChartAsPNG(FileOutputStream(file), this, 1920, 1080)

fun saveChartPng(values: Map<String, List<XYDataItem>>, file: File, title: String = file.nameWithoutExtension, xAxisLabel: String, yAxisLabel: String, applyToChart: JFreeChart.() -> Unit = {}) = ChartFactory.createXYLineChart(
        title,
        xAxisLabel,
        yAxisLabel,
        chartDataSet(values),
        PlotOrientation.VERTICAL,
        true, true, false).apply { applyToChart() }.saveToPng(file)

fun saveChartPng(values: Map<String, List<DatedValue>>, file: File, title: String = file.nameWithoutExtension, valueAxisLabel: String = "Value") = ChartFactory.createTimeSeriesChart(
        title,
        "Time",
        valueAxisLabel,
        chartDataSet(values.mapValues { it.value.map { XYDataItem(it.instant.toEpochMilli(), it.value) } }),
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

    fun valuesBefore(instant: Instant) = values.filter { it.instant <= instant }
    fun valuesInInterval(instant: Instant, duration: Duration) = valuesBefore(instant).filter { instant - duration <= it.instant }

    fun simpleMovingAverageBefore(instant: Instant, duration: Duration) = valuesInInterval(instant, duration).map { it.value }.average()
    fun simpleMovingAverage(duration: Duration) = TimedIndex(values.map { DatedValue(simpleMovingAverageBefore(it.instant, duration = duration), it.instant) })
    // https://en.wikipedia.org/wiki/Moving_average
    inner class SimpleMovingAverageStrategy(val duration: Duration, enclosingInterval: Interval) : IntervalsAboveStrategy(simpleMovingAverage(duration), enclosingInterval = enclosingInterval)

    fun weightedMovingAverageBefore(instant: Instant, duration: Duration) = valuesInInterval(instant, duration).averageWeightedBy({ (duration - (instant - it.instant)).days }) { it.value }
    fun weightedMovingAverage(duration: Duration) = TimedIndex(values.map { DatedValue(weightedMovingAverageBefore(it.instant, duration = duration), it.instant) })
    inner class WeightedMovingAverageStrategy(val duration: Duration, enclosingInterval: Interval) : IntervalsAboveStrategy(weightedMovingAverage(duration), enclosingInterval = enclosingInterval)


    fun exponentialMovingAverageBefore(instant: Instant, halfLife: Duration, n: Duration = halfLife * 4): Double {
        if (halfLife.isNegative) {
            throw IllegalArgumentException("Half life must be non-negative.")
        }

        val alpha = Math.pow(.5, 1.0 / halfLife.days)

        assert(alpha < 1)

        return valuesInInterval(instant, duration = n).averageWeightedBy({ Math.pow(alpha, (instant - it.instant).days) }) { it.value }
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
        val totalGainFactor = 1 + relativeGain
        val gainFactorPerYear = Math.pow(totalGainFactor, 1 / duration.years)

        val transactionFeeFactor = 1 - relativeTransactionFee

        fun gainFactorBy(instant: Instant) = if (instant <= start) 1.0 else transactionFeeFactor * (if (end <= instant) transactionFeeFactor * totalGainFactor else Interval(start, instant).totalGainFactor)

        override fun toString() = "${duration.days.toString()} days from $start, ${gainFactorPerYear.toPercentString()}% gain per year"

        operator fun contains(it: Instant) = start <= it && it <= end
    }

    inner open class Strategy(val buyIntervals: List<Interval>, val enclosingInterval: Interval) {
        init {
            val outliers = buyIntervals.filter { it.start < enclosingInterval.start || it.end > enclosingInterval.end }
            if (outliers.any()) {
                throw IllegalArgumentException("Buy intervals must be inside enclosing interval: $outliers")
            }
        }

        fun gainFactorBy(instant: Instant) = buyIntervals.productBy { it.gainFactorBy(instant) }
        val totalGainFactor = gainFactorBy(enclosingInterval.end)

        val transactionCount = 2 * buyIntervals.size
        val averageTransactionInterval = enclosingInterval.duration / transactionCount

        val averageGainFactorPerYear = Math.pow(totalGainFactor, 1 / enclosingInterval.duration.years)
        val averageRelativeGainPerYear = averageGainFactorPerYear - 1

        override fun toString() = "Total gain: ${totalGainFactor.toPercentString()}, avg. per year: ${averageGainFactorPerYear.toPercentString()}"
    }
}

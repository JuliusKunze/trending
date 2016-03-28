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
import java.time.format.DateTimeFormatter

fun LocalDate.toInstant() = atStartOfDay().toInstant(ZoneOffset.UTC)
fun instantFromDateString(s: String) = LocalDate.parse(s).toInstant()
fun instantFromGermanDateString(s: String) = LocalDate.parse(s, DateTimeFormatter.ofPattern("dd.MM.yyyy")).toInstant()
fun instant(year: Int, month: Int, day: Int) = LocalDate.of(year, month, day).toInstant()
operator fun Instant.minus(other: Instant) = Duration.between(other, this)
operator fun Duration.div(number: Int) = dividedBy(number.toLong())
operator fun Duration.times(number: Int) = multipliedBy(number.toLong())
val Duration.days: Double get() = seconds.toDouble() / (60 * 60 * 24)
val Duration.years: Double get() = days / 365.0

fun Double.toPercentString() = ((this.toPercent() * 100).toInt() / 100.0).toString() + "%"
fun Double.toPercent() = this * 100
val Instant.year: Int get() = atOffset(ZoneOffset.UTC).year

data class DatedValue(val value: Double, val instant: Instant)

val relativeTransactionFee = 0.0000

fun <T> Iterable<T>.averageWeightedBy(weight: (T) -> Double, value: (T) -> Double) = sumByDouble { weight(it) * value(it) } / sumByDouble { weight(it) }
fun List<Double>.product() = fold(1.0) { product, factor -> product * factor }
fun <T> List<T>.productBy(selector: (T) -> Double) = map(selector).product()

private fun URL.downloadTo(file: File) = file.apply {
    FileOutputStream(this).channel.transferFrom(Channels.newChannel(openStream()), 0, Long.MAX_VALUE)
}

fun totalReturnVsPriceReturn() = saveChartPng(
        mapOf("total return" to sp500TotalReturn.values, "price return" to sp500PriceReturn.values),
        File("total return vs price return.png"))

fun daxVsShortDax() = saveChartPng(
        mapOf("DAX" to dax.values, "Short DAX" to shortDax.values, "DAX*Short DAX" to shortDax.values.mapNotNull { s ->
            val matching = dax.values.singleOrNull() { it.instant == s.instant }
            if (matching == null) null else DatedValue(matching.value * s.value, s.instant)
        }),
        File("DAX vs Short DAX.png"))

fun main(args: Array<String>) {
    //daxVsShortDax()
    val wma28Dax = dax.WeightedMovingAverageStrategy(count = 28, enclosingInterval = shortDax.totalInterval)
    //val wma28DaxStrategyReversedOnShortDax = wma28Dax.reversedOn(shortDax)
    //val wma28ShortDax = shortDax.WeightedMovingAverageStrategy(duration = Duration.ofDays(28), enclosingInterval = shortDax.totalInterval)

    val i = Interval(instant(2013, 1, 1), instant(2013, 3, 1))

    saveChartPng(
            mapOf("DAX" to dax.values.filter { it.instant in i }, "WMA28" to wma28Dax.reference.values.filter { it.instant in i }),
            File("WMA28, DAX over time.png"))

    analyze(dax, "DAX")
}

val sp500TotalReturn by lazy {
    // data from https://www.google.de/url?sa=t&rct=j&q=&esrc=s&source=web&cd=2&cad=rja&uact=8&ved=0ahUKEwj0k7PF8NbLAhVkj3IKHaxvC_8QFggkMAE&url=http%3A%2F%2Fwww.cboe.com%2Fmicro%2Fbuywrite%2Fdailypricehistory.xls&usg=AFQjCNEO59UEWFT2cM8YYNLcH0NXGtTrng&sig2=XNKdDd4l9Ff24xxo6gEkuA
    val tableFile = File("SP500TR.csv")
    val cells = tableFile.readLines().drop(1).map { it.split(";") }.filter { it.any { it.isNotEmpty() } }
    TimedIndex(cells.withIndex().map { DatedValue(it.value[1].toDouble(), instantFromDateString(it.value[0])) })
}

val sp500PriceReturn by lazy {
    val tableFile = File("SP500PR.csv")
    val today = LocalDate.now()
    URL("http://real-chart.finance.yahoo.com/table.csv?s=%5EGSPC&a=00&b=1&c=1900&d=${today.month}&e=${today.dayOfMonth}&f=${today.year}&g=d&ignore=.csv").downloadTo(tableFile)

    val cells = tableFile.readLines().drop(1).map { it.split(",") }.filter { it.any { it.isNotEmpty() } }
    TimedIndex(cells.withIndex().map { DatedValue(it.value[4].toDouble(), instantFromDateString(it.value[0])) })
}

val dax by lazy {
    val cells = File("dax.csv").readLines().drop(1).map { it.split(";") }.filter { it.any { it.isNotEmpty() } }
    TimedIndex(cells.withIndex().map { DatedValue(it.value[4].replace(".", "").replace(",", ".").toDouble(), instantFromDateString(it.value[0])) })
}

val shortDax by lazy {
    val doc = Jsoup.parse(File("shortDax.html"), "UTF-8", "http://de.investing.com/indices/short-dax-historical-data");

    val dates = doc.select("#curr_table .bold.noWrap").map { instantFromGermanDateString(it.ownText()) }
    val values = doc.select("#curr_table .noWrap+ td").map { it.ownText().toDouble() }

    TimedIndex(dates.zip(values) { date, value -> DatedValue(value = value, instant = date) })
}

private fun analyze(index: TimedIndex, name: String, enclosingInterval: Interval = Interval(instant(2012, 1, 4), index.totalInterval.end)) {
    val differences = index.values.withIndex().drop(1).map {
        val before = index.values[it.index - 1].value
        val after = it.value.value
        DatedValue((after - before) / before, it.value.instant)
    }
    println("max relative daily losses: ${differences.sortedBy { it.value }.map { "${it.value.toPercentString()} (${it.instant})" }.take(10)}")

    val min = 8
    val max = 2000
    val factor = 1.05
    val days = (0..(Math.log(max.toDouble() / min) / Math.log(factor)).toInt()).map { (min * Math.pow(factor, it.toDouble())).toInt() }

    val buyAndHoldStrategy = index.Strategy(listOf(enclosingInterval), enclosingInterval = enclosingInterval)
    val buyAndHoldStrategiesChartDummy = days.map { buyAndHoldStrategy }
    val simpleMovingAverageStrategies = days.map { index.SimpleMovingAverageStrategy(count = it, enclosingInterval = enclosingInterval) }
    val weightedMovingAverageStrategies = days.map { index.WeightedMovingAverageStrategy(count = it, enclosingInterval = enclosingInterval) }
    val exponentialMovingAverageStrategies = days.map { index.ExponentialMovingAverageStrategy(halfLifeInDays = it, enclosingInterval = enclosingInterval) }

    val testPeriodDescription = " (assuming ${relativeTransactionFee.toPercentString()} fee per transaction, $name ${enclosingInterval.start.year} - ${enclosingInterval.end.year}).png"

    fun TimedIndex.valuesInEnclosingInterval() = values.filter { it.instant in enclosingInterval }

    fun averagesByTime() {
        saveChartPng(
                (listOf(name to index.valuesInEnclosingInterval()) + simpleMovingAverageStrategies.withIndex().map { "SMA ${days[it.index]}" to it.value.reference.valuesInEnclosingInterval() }).toMap(),
                File("simple moving averages ($name).png"))

        saveChartPng(
                (listOf(name to index.valuesInEnclosingInterval()) + weightedMovingAverageStrategies.withIndex().map { "WMA ${days[it.index]}" to it.value.reference.valuesInEnclosingInterval() }).toMap(),
                File("weighted moving averages ($name).png"))

        saveChartPng(
                (listOf(name to index.valuesInEnclosingInterval()) + exponentialMovingAverageStrategies.withIndex().map { "EMA ${days[it.index]} (half life)" to it.value.reference.valuesInEnclosingInterval() }).toMap(),
                File("exponential moving averages ($name).png"))
    }

    fun TimedIndex.Strategy.gainByTime() = index.valuesInEnclosingInterval().map { DatedValue(Math.log(gainFactorBy(it.instant)) / Math.log(2.0), it.instant) }

    saveChartPng(
            (weightedMovingAverageStrategies.withIndex().map { "WMA ${days[it.index]}" to it.value.gainByTime() } +
                    listOf("buy and hold strategy (as comparison)" to buyAndHoldStrategy.gainByTime())).toMap(),
            File("WMA gains factor (logarithmic: +1 means x2) by time" + testPeriodDescription))


    fun List<TimedIndex.Strategy>.relativeGainsByTransactionCountPerYear() = map { XYDataItem(it.averageTransactionCountPerYear, it.averageRelativeGainPerYear.toPercent()) }
    val series = listOf(
            "simple moving average strategy" to simpleMovingAverageStrategies.relativeGainsByTransactionCountPerYear(),
            "weighted moving average strategy" to weightedMovingAverageStrategies.relativeGainsByTransactionCountPerYear(),
            "exponential moving average strategy" to exponentialMovingAverageStrategies.relativeGainsByTransactionCountPerYear())
    saveChartPng(series.toMap(),
            File("relative gain per year by transaction count per year" + testPeriodDescription),
            xAxisLabel = "average transaction count per year",
            yAxisLabel = "average relative gain per year in %") {
        val renderer = (plot as XYPlot).renderer as XYLineAndShapeRenderer
        renderer.baseItemLabelGenerator = XYItemLabelGenerator { xyDataset, seriesIndex, itemIndex ->
            val x = xyDataset.getXValue(seriesIndex, itemIndex)
            val y = xyDataset.getYValue(seriesIndex, itemIndex)
            days[series[seriesIndex].second.withIndex().first { it.value.xValue == x && it.value.yValue == y }.index].toString()
        }
        renderer.baseItemLabelsVisible = true
    }

    fun List<TimedIndex.Strategy>.averageRelativeGainsPerYearByDays() = withIndex().map { XYDataItem(days[it.index], it.value.averageRelativeGainPerYear.toPercent()) }
    fun gains() = saveChartPng(mapOf(
            "simple moving average strategy" to simpleMovingAverageStrategies.averageRelativeGainsPerYearByDays(),
            "weighted moving average strategy" to weightedMovingAverageStrategies.averageRelativeGainsPerYearByDays(),
            "exponential moving average strategy" to exponentialMovingAverageStrategies.averageRelativeGainsPerYearByDays(),
            "buy and hold strategy (as comparison)" to buyAndHoldStrategiesChartDummy.averageRelativeGainsPerYearByDays()),
            File("relative gain per year" + testPeriodDescription),
            xAxisLabel = "moving average duration or half life in days",
            yAxisLabel = "relative gain per year in %")

    fun List<TimedIndex.Strategy>.averageTransactionsPerYearByDays() = withIndex().map { XYDataItem(days[it.index], it.value.averageTransactionCountPerYear) }
    fun interval() = saveChartPng(mapOf(
            "simple moving average strategy" to simpleMovingAverageStrategies.averageTransactionsPerYearByDays(),
            "weighted moving average strategy" to weightedMovingAverageStrategies.averageTransactionsPerYearByDays(),
            "exponential moving average strategy" to exponentialMovingAverageStrategies.averageTransactionsPerYearByDays()),
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

class Interval(val start: Instant, val end: Instant) {
    val duration = end - start

    operator fun contains(it: Instant) = start <= it && it <= end
    operator fun contains(it: Interval) = start <= it.start && it.end <= end
    override fun toString() = "$start to $end, duration $duration"
}

class TimedIndex(unsortedValues: List<DatedValue>) {
    val values = unsortedValues.sortedBy { it.instant.epochSecond }
    val valuesByDate = values.associateBy { it.instant }
    fun value(instant: Instant) = valuesByDate[instant]!!.value

    val totalInterval = Interval(start = values.first().instant, end = values.last().instant)

    val Interval.startValue: Double get() = value(start)
    val Interval.endValue: Double get() = value(end)
    val Interval.gain: Double get() = endValue - startValue
    val Interval.relativeGain: Double get() = gain / startValue
    val Interval.totalGainFactor: Double get() = 1 + relativeGain
    val Interval.gainFactorPerYear: Double get() = Math.pow(totalGainFactor, 1 / duration.years)
    val Interval.transactionFeeFactor: Double get() = 1 - relativeTransactionFee
    fun Interval.gainFactorBy(instant: Instant) = if (instant <= start) 1.0 else transactionFeeFactor * (if (end <= instant) transactionFeeFactor * totalGainFactor else Interval(start, instant).totalGainFactor)

    private fun aboveBuyIntervals(enclosingInterval: Interval, other: TimedIndex): List<Interval> {
        val above = values.indices.filter { values[it].value > other.values[it].value }
        val notAbove = (values.indices.toSet() - above).toList().sorted()
        val buyIndexes = above.filter { it - 1 in notAbove }
        val sellIndexes = notAbove.filter { it - 1 in above }

        val startsWithSell = sellIndexes.any() && (!buyIndexes.any() || sellIndexes.first() < buyIndexes.first() )
        assert(startsWithSell == values.indices.first in above)
        val correctedBuyIndexes = (if (startsWithSell) listOf(values.indices.first) else emptyList()) + buyIndexes

        val endsWithBuy = buyIndexes.any() && (!sellIndexes.any() || buyIndexes.last() < sellIndexes.last())
        assert(endsWithBuy == values.indices.last in above)
        val correctedSellIndexes = sellIndexes + if (endsWithBuy) listOf(values.indices.last) else emptyList()

        return correctedBuyIndexes.zip(correctedSellIndexes) { buy, sell -> Interval(values[buy].instant, values[sell].instant) }.filter { it in enclosingInterval }
    }

    inner open class IntervalsAboveStrategy(val reference: TimedIndex, enclosingInterval: Interval = totalInterval) : Strategy(aboveBuyIntervals(enclosingInterval, reference), enclosingInterval = enclosingInterval)

    fun valuesBefore(instant: Instant) = values.filter { it.instant <= instant }
    fun valuesBefore(instant: Instant, count: Int) = valuesBefore(instant).takeLast(count)

    fun simpleMovingAverageBefore(instant: Instant, count: Int) = valuesBefore(instant, count).map { it.value }.average()
    fun simpleMovingAverage(count: Int) = TimedIndex(values.map { DatedValue(simpleMovingAverageBefore(it.instant, count = count), it.instant) })
    // https://en.wikipedia.org/wiki/Moving_average
    inner class SimpleMovingAverageStrategy(val count: Int, enclosingInterval: Interval) : IntervalsAboveStrategy(simpleMovingAverage(count), enclosingInterval = enclosingInterval)

    fun weightedMovingAverageBefore(instant: Instant, count: Int) = valuesBefore(instant, count).withIndex().averageWeightedBy({ (it.index + 1).toDouble() }) { it.value.value }
    fun weightedMovingAverage(count: Int) = TimedIndex(values.map { DatedValue(weightedMovingAverageBefore(it.instant, count = count), it.instant) })
    inner class WeightedMovingAverageStrategy(val count: Int, enclosingInterval: Interval) : IntervalsAboveStrategy(weightedMovingAverage(count), enclosingInterval = enclosingInterval)


    fun exponentialMovingAverageBefore(instant: Instant, halfLife: Int, n: Int = halfLife * 4): Double {
        if (halfLife < 0) {
            throw IllegalArgumentException("Half life must be non-negative.")
        }

        val alpha = Math.pow(.5, 1.0 / halfLife)

        assert(alpha < 1)

        return valuesBefore(instant, count = n).withIndex().averageWeightedBy({ Math.pow(alpha, (it.index + 1).toDouble()) }) { it.value.value }
    }

    fun exponentialMovingAverage(halfLifeInDays: Int) = TimedIndex(values.map { DatedValue(exponentialMovingAverageBefore(it.instant, halfLife = halfLifeInDays), it.instant) })
    inner class ExponentialMovingAverageStrategy(val halfLifeInDays: Int, enclosingInterval: Interval) : IntervalsAboveStrategy(exponentialMovingAverage(halfLifeInDays), enclosingInterval = enclosingInterval)

    inner open class Strategy(val buyIntervals: List<Interval>, val enclosingInterval: Interval) {
        init {
            val outliers = buyIntervals.filter { it !in enclosingInterval }
            if (outliers.any()) {
                throw IllegalArgumentException("Buy intervals must be inside enclosing interval: $outliers")
            }
        }

        fun gainFactorBy(instant: Instant) = buyIntervals.productBy { it.gainFactorBy(instant) }
        val totalGainFactor = gainFactorBy(enclosingInterval.end)

        val transactionCount = 2 * buyIntervals.size
        val averageTransactionCountPerYear = transactionCount.toDouble() / enclosingInterval.duration.years

        val averageGainFactorPerYear = Math.pow(totalGainFactor, 1 / enclosingInterval.duration.years)
        val averageRelativeGainPerYear = averageGainFactorPerYear - 1

        override fun toString() = "Total gain: ${totalGainFactor.toPercentString()}, avg. per year: ${averageGainFactorPerYear.toPercentString()}"

        fun onlyDuring(interval: Interval) = Strategy(buyIntervals.filter { it in interval }, interval)

        fun reversedOn(index: TimedIndex) = index.Strategy(buyIntervals.withIndex().drop(1).map { Interval(start = buyIntervals[it.index - 1].end, end = it.value.start) }, enclosingInterval).onlyDuring(index.totalInterval)
    }
}
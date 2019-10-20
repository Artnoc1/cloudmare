package dev.jtsalva.cloudmare

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.AxisBase
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import dev.jtsalva.cloudmare.api.analytics.AnalyticsDashboard
import dev.jtsalva.cloudmare.api.analytics.AnalyticsDashboardRequest
import dev.jtsalva.cloudmare.api.toDate
import dev.jtsalva.cloudmare.api.toDateHourAsString
import dev.jtsalva.cloudmare.api.toDateMonthAsString
import dev.jtsalva.cloudmare.api.toDateWeekDayAsString
import dev.jtsalva.cloudmare.api.zone.Zone
import dev.jtsalva.cloudmare.databinding.ActivityAnalyticsBinding
import dev.jtsalva.cloudmare.viewmodel.AnalyticsViewModel
import kotlinx.android.synthetic.main.activity_analytics.*

class AnalyticsActivity : CloudMareActivity(), SwipeRefreshable {

    class DayAxisValueFormatter : ValueFormatter() {
        override fun getAxisLabel(value: Float, axis: AxisBase?): String =
            value.toDateWeekDayAsString()
    }

    class HourAxisValueFormatter : ValueFormatter() {
        override fun getAxisLabel(value: Float, axis: AxisBase?): String =
            value.toDateHourAsString()
    }

    class MonthAxisValueFormatter : ValueFormatter() {
        override fun getAxisLabel(value: Float, axis: AxisBase?): String =
            value.toDateMonthAsString()
    }

    companion object {
        private const val AXIS_LABEL_TEXT_SIZE = 15f
        private const val LINE_WIDTH = 4f
        private const val X_AXIS_LABEL_ROTATION = -45f
        private const val MAX_NUM_OF_X_AXIS_LABELS = 7

        private fun customXAxis(count: Int,
                                formatter: ValueFormatter,
                                forceLabelCount: Boolean = true): XAxis.() -> Unit =
            {
                position = XAxis.XAxisPosition.BOTTOM
                textSize = AXIS_LABEL_TEXT_SIZE
                valueFormatter = formatter
                labelRotationAngle = X_AXIS_LABEL_ROTATION
                setDrawGridLines(false)
                setDrawAxisLine(false)
                setLabelCount(
                    if (count <= MAX_NUM_OF_X_AXIS_LABELS) count else MAX_NUM_OF_X_AXIS_LABELS,
                    forceLabelCount
                )
            }

        private val customYAxis: YAxis.() -> Unit = {
            textSize = AXIS_LABEL_TEXT_SIZE
            setDrawGridLines(true)
            setDrawAxisLine(false)
            setDrawGridLinesBehindData(false)
        }

        private fun customLineDataSet(context: Context, lineColor: Int = R.color.colorPrimary): LineDataSet.() -> Unit = {
            setColors(intArrayOf(lineColor), context)
            lineWidth = LINE_WIDTH
            setDrawValues(false)
            setDrawCircles(false)
        }

        private fun customChart(count: Int,
                                formatter: ValueFormatter): LineChart.() -> Unit = {
            isAutoScaleMinMaxEnabled = true

            setTouchEnabled(false)
            description.isEnabled = false
            axisRight.isEnabled = false

            legend.textSize = 18f

            setExtraOffsets(0f, 0f, 0f, 10f)

            xAxis.apply(customXAxis(count, formatter))
            axisLeft.apply(customYAxis)
        }
    }

    private fun totalsTemplate(label: String, value: String): View =
        layoutInflater.inflate(R.layout.analytics_dashboard_totals_item, null).apply {
            val labelTextView = findViewById<TextView>(R.id.label)
            val valueTextView = findViewById<TextView>(R.id.value)

            labelTextView.text = label
            valueTextView.text = value
        }

    private fun drawTotal(label: String, value: Int, units: String = "") {
        totals_table.addView(totalsTemplate(label, "$value $units"))
    }

    private fun autoFormatter() = when (viewModel.timePeriod) {
        in -900 downTo -4319 -> HourAxisValueFormatter()
        in -4320 downTo -10080 -> DayAxisValueFormatter()
        in -10081 downTo -525600 -> MonthAxisValueFormatter()

        else -> throw Exception("Can't auto assign formatter given viewModel.timePeriod")
    }

    private lateinit var domain: Zone

    private lateinit var binding: ActivityAnalyticsBinding

    private lateinit var viewModel: AnalyticsViewModel

    private val categoryAdapter by lazy {
        ArrayAdapter.createFromResource(
            this,
            R.array.entries_analytics_dashboard_categories,
            R.layout.spinner_item
        )
    }

    private val timePeriodAdapter by lazy {
        ArrayAdapter.createFromResource(
            this,
            R.array.entries_analytics_dashboard_time_periods,
            R.layout.spinner_item
        )
    }

    var analyticsDashboard: AnalyticsDashboard? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        domain = intent.getParcelableExtra("domain")!!

        viewModel = AnalyticsViewModel(this, domain)
        binding = setLayoutBinding(R.layout.activity_analytics)
        binding.viewModel = viewModel

        setToolbarTitle("${domain.name} | Analytics")

        launch {
            categoryAdapter.let { adapter ->
                adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)

                category_spinner.apply {
                    setAdapter(adapter)
                    onItemSelectedListener = viewModel
                }
            }

            timePeriodAdapter.let { adapter ->
                adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)

                time_period_spinner.apply {
                    setAdapter(adapter)
                    onItemSelectedListener = viewModel
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()

        render()
    }

    override fun onSwipeRefresh() {
        super.onSwipeRefresh()

        analyticsDashboard = null
    }

    override fun render() = launch {
        if (analyticsDashboard == null) {
            val response = AnalyticsDashboardRequest(this).get(domain.id, since = viewModel.timePeriod)
            if (response.failure || response.result == null)
                dialog.error(message = response.firstErrorMessage, onAcknowledge = ::onStart)

            else analyticsDashboard = response.result
        }

        totals_table.removeAllViews()

        when (viewModel.category) {
            AnalyticsViewModel.CATEGORY_REQUESTS -> drawRequests()
            AnalyticsViewModel.CATEGORY_BANDWIDTH -> drawBandwidth()
            AnalyticsViewModel.CATEGORY_THREATS -> drawThreats()
            AnalyticsViewModel.CATEGORY_PAGEVIEWS -> drawPageviews()

            else -> throw Exception("Selected category doesn't exist")
        }

        analytics_chart.invalidate()

        showProgressBar = false
        analytics_view_group.visibility = View.VISIBLE
    }

    private fun drawRequests() = with (analyticsDashboard!!) {
        drawTotal("All", totals.requests.all)
        drawTotal("Cached", totals.requests.cached)
        drawTotal("Uncached", totals.requests.uncached)

        val all = ArrayList<Entry>()
        val cached = ArrayList<Entry>()
        val uncached = ArrayList<Entry>()
        val dataSets = ArrayList<ILineDataSet>()

        timeSeries.forEach { data ->
            val time = data.since.toDate().time.toFloat()
            all.add(Entry(time, data.requests.all.toFloat()))
            cached.add(Entry(time, data.requests.cached.toFloat()))
            uncached.add(Entry(time, data.requests.uncached.toFloat()))
        }

        y_axis_title.text = "Number of Requests"

        dataSets.add(
            LineDataSet(all, "All").apply(customLineDataSet(this@AnalyticsActivity))
        )

        dataSets.add(
            LineDataSet(cached, "Cached").apply(customLineDataSet(this@AnalyticsActivity, R.color.colorBlue))
        )

        dataSets.add(
            LineDataSet(uncached, "Uncached").apply(customLineDataSet(this@AnalyticsActivity, R.color.colorRed))
        )

        analytics_chart.apply(customChart(all.size, autoFormatter())).apply {
            data = LineData(dataSets)
        }
    }

    private fun drawBandwidth() = with (analyticsDashboard!!) {
        drawTotal("All", totals.bandwidth.all, "B")
        drawTotal("Cached", totals.bandwidth.cached, "B")
        drawTotal("Uncached", totals.bandwidth.uncached, "B")

        val all = ArrayList<Entry>()
        val cached = ArrayList<Entry>()
        val uncached = ArrayList<Entry>()
        val dataSets = ArrayList<ILineDataSet>()

        timeSeries.forEach { data ->
            val time = data.since.toDate().time.toFloat()
            all.add(Entry(time, data.bandwidth.all.toFloat()))
            cached.add(Entry(time, data.bandwidth.cached.toFloat()))
            uncached.add(Entry(time, data.bandwidth.uncached.toFloat()))
        }

        y_axis_title.text = "Bandwidth Usage (Bytes)"

        dataSets.add(
            LineDataSet(all, "All").apply(customLineDataSet(this@AnalyticsActivity))
        )

        dataSets.add(
            LineDataSet(cached, "Cached").apply(customLineDataSet(this@AnalyticsActivity, R.color.colorBlue))
        )

        dataSets.add(
            LineDataSet(uncached, "Uncached").apply(customLineDataSet(this@AnalyticsActivity, R.color.colorRed))
        )

        analytics_chart.apply(customChart(all.size, autoFormatter())).apply {
            data = LineData(dataSets)
        }
    }

    private fun drawThreats() = with (analyticsDashboard!!) {
        drawTotal("Threats", totals.threats.all)

        val all = ArrayList<Entry>()
        val dataSets = ArrayList<ILineDataSet>()

        timeSeries.forEach { data ->
            val time = data.since.toDate().time.toFloat()
            all.add(Entry(time, data.threats.all.toFloat()))
        }

        y_axis_title.text = "Number of Threats"

        dataSets.add(
            LineDataSet(all, "All").apply(customLineDataSet(this@AnalyticsActivity))
        )

        analytics_chart.apply(customChart(all.size, autoFormatter())).apply {
            data = LineData(dataSets)
        }
    }

    private fun drawPageviews() = with (analyticsDashboard!!) {
        drawTotal("Pageviews", totals.pageviews.all)

        val all = ArrayList<Entry>()
        val dataSets = ArrayList<ILineDataSet>()

        timeSeries.forEach { data ->
            val time = data.since.toDate().time.toFloat()
            all.add(Entry(time, data.pageviews.all.toFloat()))
        }

        y_axis_title.text = "Number of Pageviews"

        dataSets.add(
            LineDataSet(all, "All").apply(customLineDataSet(this@AnalyticsActivity))
        )

        analytics_chart.apply(customChart(all.size, autoFormatter())).apply {
            data = LineData(dataSets)
        }
    }
}
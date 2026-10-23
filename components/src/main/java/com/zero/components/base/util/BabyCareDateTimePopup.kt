package com.zero.components.base.util

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.doOnLayout
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseQuickAdapter
import com.zero.components.R
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * BabyCare 统一日期/时间 Sheet。
 *
 * 组件只负责产出时间值，跨天、未来时间等业务约束仍由调用方或 ViewModel 处理。
 */
internal class BabyCareDateTimePopup(
    context: Context,
    private val title: String,
    private val mode: DialogHelper.DateTimeMode,
    initialTime: Long,
    private val minTime: Long? = null,
    private val maxTime: Long? = null,
    private val yearRange: IntRange? = null,
    private val onConfirm: (Long) -> Unit,
    private val onCancel: (() -> Unit)? = null
) : BabyCareBottomSheetPopup(context) {

    private val initialCalendar = calendarOf(clamp(initialTime))
    private var selectedYear = initialCalendar.get(Calendar.YEAR)
    private var selectedMonth = initialCalendar.get(Calendar.MONTH) + 1
    private var selectedDay = initialCalendar.get(Calendar.DAY_OF_MONTH)
    private var selectedHour = initialCalendar.get(Calendar.HOUR_OF_DAY)
    private var selectedMinute = initialCalendar.get(Calendar.MINUTE)
    private var selectedSecond = initialCalendar.get(Calendar.SECOND)
    private var isBindingColumns = false

    private lateinit var dateGroup: View
    private lateinit var timeGroup: View
    private lateinit var yearColumn: PickerColumn
    private lateinit var monthColumn: PickerColumn
    private lateinit var dayColumn: PickerColumn
    private lateinit var hourColumn: PickerColumn
    private lateinit var minuteColumn: PickerColumn
    private lateinit var secondColumn: PickerColumn
    private lateinit var groupGap: View
    private lateinit var contentTouchArea: View

    override fun getImplLayoutId(): Int = R.layout.popup_babycare_date_time_sheet

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::contentTouchArea.isInitialized) {
            protectSheetDragForContentTouch(event, contentTouchArea)
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onCreate() {
        super.onCreate()
        findViewById<TextView>(R.id.tv_cancel).setOnClickListener {
            onCancel?.invoke()
            dismiss()
        }
        findViewById<TextView>(R.id.tv_confirm).setOnClickListener {
            onConfirm(clamp(buildSelectedCalendar().timeInMillis))
            dismiss()
        }

        dateGroup = findViewById(R.id.picker_date_group)
        timeGroup = findViewById(R.id.picker_time_group)
        groupGap = findViewById(R.id.picker_date_time_group_gap)
        contentTouchArea = findViewById(R.id.picker_frame)
        ViewCompat.setAccessibilityPaneTitle(contentTouchArea, title)

        yearColumn = createColumn(findViewById(R.id.list_year))
        monthColumn = createColumn(findViewById(R.id.list_month))
        dayColumn = createColumn(findViewById(R.id.list_day))
        hourColumn = createColumn(findViewById(R.id.list_hour))
        minuteColumn = createColumn(findViewById(R.id.list_minute))
        secondColumn = createColumn(findViewById(R.id.list_second))
        applyModeVisibility()
        bindAllColumns()
        applyColumnGroupLayout()
        // 以实际弹层内容宽度决定分组方向，分屏窗口不能使用整个显示屏宽度。
        contentTouchArea.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) applyColumnGroupLayout()
        }
    }

    private fun createColumn(recyclerView: RecyclerView): PickerColumn {
        val layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
        val adapter = PickerColumnAdapter(context)
        val snapHelper = LinearSnapHelper()
        val column = PickerColumn(
            recyclerView = recyclerView,
            layoutManager = layoutManager,
            adapter = adapter,
            snapHelper = snapHelper
        )

        adapter.setOnItemClickListener { _, _, position ->
            // 点击与读屏 ACTION_CLICK 走同一选择路径，不要求用户用拖动猜测中心值。
            column.isSelecting = true
            try {
                recyclerView.stopScroll()
                selectColumnValue(column, position)
                scrollColumnToSelected(column)
            } finally {
                column.isSelecting = false
            }
        }
        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = adapter
        recyclerView.itemAnimator = null
        // 时间列只在自身内部滚动，禁用嵌套滚动避免 XPopup 父 Sheet 消费快速 fling。
        recyclerView.isNestedScrollingEnabled = false
        recyclerView.setHasFixedSize(true)
        snapHelper.attachToRecyclerView(recyclerView)

        // 通过上下 padding 把首尾 item 也放到中心选择线内，避免第一项/最后一项无法被选中。
        recyclerView.doOnLayout {
            syncColumnPadding(column)
            scrollColumnToSelected(column)
        }
        recyclerView.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            syncColumnPadding(column)
            if (bottom - top != oldBottom - oldTop) scrollColumnToSelected(column)
        }
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE && !column.isSelecting) {
                    selectSnappedValue(column)
                }
            }
        })
        return column
    }

    private fun applyModeVisibility() {
        val showYear = showYearColumn()
        val showMonthDay = showMonthDayColumns()
        val showTime = showTimeColumns()
        val showSecond = showSecondColumn()
        val showDateGroup = showYear || showMonthDay
        val showTimeGroup = showTime

        dateGroup.visibility = if (showDateGroup) View.VISIBLE else View.GONE
        timeGroup.visibility = if (showTimeGroup) View.VISIBLE else View.GONE
        yearColumn.recyclerView.visibility = if (showYear) View.VISIBLE else View.GONE
        monthColumn.recyclerView.visibility = if (showMonthDay) View.VISIBLE else View.GONE
        dayColumn.recyclerView.visibility = if (showMonthDay) View.VISIBLE else View.GONE
        groupGap.visibility = if (showDateGroup && showTimeGroup) View.VISIBLE else View.GONE
        hourColumn.recyclerView.visibility = if (showTime) View.VISIBLE else View.GONE
        minuteColumn.recyclerView.visibility = if (showTime) View.VISIBLE else View.GONE
        secondColumn.recyclerView.visibility = if (showSecond) View.VISIBLE else View.GONE
        applyColumnGroupLayout()
    }

    private fun showYearColumn(): Boolean {
        return mode == DialogHelper.DateTimeMode.DATE ||
            mode == DialogHelper.DateTimeMode.DATE_TIME ||
            mode == DialogHelper.DateTimeMode.DATE_TIME_SECOND
    }

    private fun showMonthDayColumns(): Boolean {
        return mode == DialogHelper.DateTimeMode.DATE ||
            mode == DialogHelper.DateTimeMode.DATE_TIME ||
            mode == DialogHelper.DateTimeMode.DATE_TIME_SECOND ||
            mode == DialogHelper.DateTimeMode.MONTH_DAY_TIME ||
            mode == DialogHelper.DateTimeMode.MONTH_DAY_TIME_SECOND
    }

    private fun showTimeColumns(): Boolean {
        return mode == DialogHelper.DateTimeMode.TIME ||
            mode == DialogHelper.DateTimeMode.TIME_SECOND ||
            mode == DialogHelper.DateTimeMode.DATE_TIME ||
            mode == DialogHelper.DateTimeMode.DATE_TIME_SECOND ||
            mode == DialogHelper.DateTimeMode.MONTH_DAY_TIME ||
            mode == DialogHelper.DateTimeMode.MONTH_DAY_TIME_SECOND
    }

    private fun showSecondColumn(): Boolean {
        return mode == DialogHelper.DateTimeMode.TIME_SECOND ||
            mode == DialogHelper.DateTimeMode.DATE_TIME_SECOND ||
            mode == DialogHelper.DateTimeMode.MONTH_DAY_TIME_SECOND
    }

    /** 完整文字和至少 48dp 热区优先；放不下整行时将日期与时钟上下分组。 */
    private fun applyColumnGroupLayout() {
        val dateColumns = listOf(yearColumn, monthColumn, dayColumn)
            .filter { it.recyclerView.visibility == View.VISIBLE }
        val timeColumns = listOf(hourColumn, minuteColumn, secondColumn)
            .filter { it.recyclerView.visibility == View.VISIBLE }
        val gap = resources.getDimensionPixelSize(com.zero.common.R.dimen.content_gap_medium)
        val availableWidth = contentTouchArea.width - contentTouchArea.paddingLeft - contentTouchArea.paddingRight
        val visibleColumns = dateColumns + timeColumns
        // 单列也按可用宽度测量多行文字；极大字号不能再把年份裁成不可识别的残片。
        val widths = visibleColumns.associateWith { column ->
            column.adapter.preferredWidthPx().let { if (availableWidth > 0) minOf(it, availableWidth) else it }
        }
        val itemHeight = visibleColumns.maxOfOrNull { it.adapter.requiredHeightPx(widths.getValue(it)) }
            ?: resources.getDimensionPixelSize(com.zero.common.R.dimen.min_touch_target)
        visibleColumns.forEach { column ->
            column.adapter.updateItemHeight(itemHeight)
            setViewSize(column.recyclerView, widths.getValue(column), itemHeight * VISIBLE_ITEM_COUNT)
        }
        fun configureGroup(group: View, columns: List<PickerColumn>, selectionId: Int, fadeTopId: Int, fadeBottomId: Int): Int {
            val totalWidth = columns.sumOf { widths.getValue(it) }
            val wrapColumns = availableWidth > 0 && totalWidth > availableWidth
            val row = (group as ViewGroup).getChildAt(0) as LinearLayout
            row.orientation = if (wrapColumns) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER
            columns.forEachIndexed { index, column ->
                val params = column.recyclerView.layoutParams as LinearLayout.LayoutParams
                val margin = if (wrapColumns && index < columns.lastIndex) gap else 0
                if (params.bottomMargin != margin) {
                    params.bottomMargin = margin
                    column.recyclerView.layoutParams = params
                }
            }
            val width = if (wrapColumns) columns.maxOfOrNull { widths.getValue(it) } ?: 0 else totalWidth
            val height = itemHeight * VISIBLE_ITEM_COUNT * (if (wrapColumns) columns.size else 1) +
                if (wrapColumns) gap * (columns.size - 1).coerceAtLeast(0) else 0
            setViewSize(group, width, height)
            // 逐列纵排时中心位置各自独立，不能用一条跨组选择线误指中间那列。
            listOf(selectionId, fadeTopId, fadeBottomId).forEach { id ->
                findViewById<View>(id).apply {
                    visibility = if (wrapColumns) View.GONE else View.VISIBLE
                    setViewSize(this, ViewGroup.LayoutParams.MATCH_PARENT, itemHeight)
                }
            }
            return width
        }
        val dateWidth = configureGroup(dateGroup, dateColumns, R.id.picker_date_selection,
            R.id.picker_date_fade_top, R.id.picker_date_fade_bottom)
        val timeWidth = configureGroup(timeGroup, timeColumns, R.id.picker_time_selection,
            R.id.picker_time_fade_top, R.id.picker_time_fade_bottom)
        val hasBothGroups = dateColumns.isNotEmpty() && timeColumns.isNotEmpty()
        val stacked = hasBothGroups && (availableWidth <= 0 || dateWidth + timeWidth + gap > availableWidth)
        findViewById<LinearLayout>(R.id.picker_column_container).orientation =
            if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        setViewSize(groupGap, if (stacked) 0 else gap, if (stacked) gap else 0)
    }

    private fun setViewSize(view: View, width: Int, height: Int) {
        val params = view.layoutParams
        if (params.width != width || params.height != height) {
            params.width = width
            params.height = height
            view.layoutParams = params
        }
    }

    private fun bindAllColumns() {
        isBindingColumns = true
        bindYearColumn()
        bindMonthColumn()
        bindDayColumn()
        bindHourColumn()
        bindMinuteColumn()
        bindSecondColumn()
        isBindingColumns = false
    }

    private fun bindYearColumn() {
        val values = yearValues()
        selectedYear = selectedYear.coerceIn(values.first(), values.last())
        bindColumn(
            column = yearColumn,
            values = values,
            selectedValue = selectedYear,
            label = { context.getString(com.zero.common.R.string.picker_year_format, it) },
            onSelected = {
                selectedYear = it
                normalizeDateTime()
                bindMonthColumn()
                bindDayColumn()
                bindHourColumn()
                bindMinuteColumn()
                bindSecondColumn()
            }
        )
    }

    private fun bindMonthColumn() {
        val values = monthValues()
        selectedMonth = selectedMonth.coerceIn(values.first(), values.last())
        bindColumn(
            column = monthColumn,
            values = values,
            selectedValue = selectedMonth,
            label = { context.getString(com.zero.common.R.string.picker_month_format, it) },
            onSelected = {
                selectedMonth = it
                normalizeDateTime()
                bindDayColumn()
                bindHourColumn()
                bindMinuteColumn()
                bindSecondColumn()
            }
        )
    }

    private fun bindDayColumn() {
        val values = dayValues()
        selectedDay = selectedDay.coerceIn(values.first(), values.last())
        bindColumn(
            column = dayColumn,
            values = values,
            selectedValue = selectedDay,
            label = { context.getString(com.zero.common.R.string.picker_day_format, it) },
            onSelected = {
                selectedDay = it
                normalizeDateTime()
                bindHourColumn()
                bindMinuteColumn()
                bindSecondColumn()
            }
        )
    }

    private fun bindHourColumn() {
        val values = hourValues()
        selectedHour = selectedHour.coerceIn(values.first(), values.last())
        bindColumn(
            column = hourColumn,
            values = values,
            selectedValue = selectedHour,
            label = { context.getString(com.zero.common.R.string.picker_hour_format, it) },
            onSelected = {
                selectedHour = it
                normalizeDateTime()
                bindMinuteColumn()
                bindSecondColumn()
            }
        )
    }

    private fun bindMinuteColumn() {
        val values = minuteValues()
        selectedMinute = selectedMinute.coerceIn(values.first(), values.last())
        bindColumn(
            column = minuteColumn,
            values = values,
            selectedValue = selectedMinute,
            label = { context.getString(com.zero.common.R.string.picker_minute_format, it) },
            onSelected = {
                selectedMinute = it
                normalizeDateTime()
                bindSecondColumn()
            }
        )
    }

    private fun bindSecondColumn() {
        val values = secondValues()
        selectedSecond = selectedSecond.coerceIn(values.first(), values.last())
        bindColumn(
            column = secondColumn,
            values = values,
            selectedValue = selectedSecond,
            label = { context.getString(com.zero.common.R.string.picker_second_format, it) },
            onSelected = {
                selectedSecond = it
                normalizeDateTime()
            }
        )
    }

    private fun bindColumn(
        column: PickerColumn,
        values: List<Int>,
        selectedValue: Int,
        label: (Int) -> String,
        onSelected: (Int) -> Unit
    ) {
        val selectedIndex = values.indexOf(selectedValue).coerceAtLeast(0)
        column.values = values
        column.selectedIndex = selectedIndex
        column.onSelected = onSelected
        val accessibilityFormat = when (column.recyclerView.id) {
            R.id.list_year -> com.zero.common.R.string.picker_year_accessibility_format
            R.id.list_month -> com.zero.common.R.string.picker_month_accessibility_format
            R.id.list_day -> com.zero.common.R.string.picker_day_accessibility_format
            R.id.list_hour -> com.zero.common.R.string.picker_hour_accessibility_format
            R.id.list_minute -> com.zero.common.R.string.picker_minute_accessibility_format
            else -> com.zero.common.R.string.picker_second_accessibility_format
        }
        column.adapter.submitValues(values.map { value ->
            PickerColumnItem(label(value), context.getString(accessibilityFormat, value))
        }, selectedIndex)
        scrollColumnToSelected(column)
    }

    private fun syncColumnPadding(column: PickerColumn) {
        val verticalPadding = ((column.recyclerView.height - column.adapter.itemHeightPx) / 2).coerceAtLeast(0)
        if (column.recyclerView.paddingTop != verticalPadding) {
            column.recyclerView.setPadding(0, verticalPadding, 0, verticalPadding)
        }
    }

    private fun scrollColumnToSelected(column: PickerColumn) {
        column.scrollRequest?.let(column.recyclerView::removeCallbacks)
        val request = Runnable {
            if (!column.recyclerView.isAttachedToWindow) return@Runnable
            syncColumnPadding(column)
            column.layoutManager.scrollToPositionWithOffset(column.selectedIndex, 0)
        }
        column.scrollRequest = request
        column.recyclerView.post(request)
    }

    private fun selectSnappedValue(column: PickerColumn) {
        val snapView = column.snapHelper.findSnapView(column.layoutManager) ?: return
        val position = column.layoutManager.getPosition(snapView)
        selectColumnValue(column, position)
    }

    private fun selectColumnValue(column: PickerColumn, position: Int) {
        if (position !in column.values.indices || position == column.selectedIndex) return
        column.selectedIndex = position
        column.adapter.setSelectedIndex(position)
        if (isBindingColumns) return

        // 只有用户滚动产生的新值才回写状态；联动刷新列时只更新展示，避免递归触发。
        val callback = column.onSelected ?: return
        column.values.getOrNull(position)?.let(callback)
        applyColumnGroupLayout()
    }

    private fun normalizeDateTime() {
        selectedMonth = selectedMonth.coerceIn(monthValues().first(), monthValues().last())
        selectedDay = selectedDay.coerceIn(dayValues().first(), dayValues().last())
        selectedHour = selectedHour.coerceIn(hourValues().first(), hourValues().last())
        selectedMinute = selectedMinute.coerceIn(minuteValues().first(), minuteValues().last())
        selectedSecond = selectedSecond.coerceIn(secondValues().first(), secondValues().last())
    }

    private fun yearValues(): List<Int> {
        val minYear = minCalendar()?.get(Calendar.YEAR)
        val maxYear = maxCalendar()?.get(Calendar.YEAR)
        val start = yearRange?.first ?: minYear ?: (selectedYear - DEFAULT_YEAR_LOOKBACK)
        val end = yearRange?.last ?: maxYear ?: selectedYear
        val normalizedStart = minOf(start, selectedYear)
        val normalizedEnd = maxOf(end, selectedYear)
        return (normalizedStart..normalizedEnd).toList()
    }

    private fun monthValues(): List<Int> {
        if (!showMonthDayColumns()) return (1..12).toList()
        val start = if (selectedYear == minCalendar()?.get(Calendar.YEAR)) {
            minCalendar()?.get(Calendar.MONTH)?.plus(1) ?: 1
        } else {
            1
        }
        val end = if (selectedYear == maxCalendar()?.get(Calendar.YEAR)) {
            maxCalendar()?.get(Calendar.MONTH)?.plus(1) ?: 12
        } else {
            12
        }
        return (start..end).toList()
    }

    private fun dayValues(): List<Int> {
        if (!showMonthDayColumns()) return (1..31).toList()
        val maxDay = Calendar.getInstance().apply {
            set(Calendar.YEAR, selectedYear)
            set(Calendar.MONTH, selectedMonth - 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }.getActualMaximum(Calendar.DAY_OF_MONTH)

        val start = if (sameYearMonth(minCalendar())) {
            minCalendar()?.get(Calendar.DAY_OF_MONTH) ?: 1
        } else {
            1
        }
        val end = if (sameYearMonth(maxCalendar())) {
            maxCalendar()?.get(Calendar.DAY_OF_MONTH) ?: maxDay
        } else {
            maxDay
        }
        return (start..end).toList()
    }

    private fun hourValues(): List<Int> {
        if (!showTimeColumns()) return listOf(selectedHour)
        val start = if (sameYearMonthDay(minCalendar())) minCalendar()?.get(Calendar.HOUR_OF_DAY) ?: 0 else 0
        val end = if (sameYearMonthDay(maxCalendar())) maxCalendar()?.get(Calendar.HOUR_OF_DAY) ?: 23 else 23
        return (start..end).toList()
    }

    private fun minuteValues(): List<Int> {
        if (!showTimeColumns()) return listOf(selectedMinute)
        val start = if (sameYearMonthDayHour(minCalendar())) minCalendar()?.get(Calendar.MINUTE) ?: 0 else 0
        val end = if (sameYearMonthDayHour(maxCalendar())) maxCalendar()?.get(Calendar.MINUTE) ?: 59 else 59
        return (start..end).toList()
    }

    private fun secondValues(): List<Int> {
        if (!showSecondColumn()) return listOf(selectedSecond)
        val start = if (sameYearMonthDayHourMinute(minCalendar())) minCalendar()?.get(Calendar.SECOND) ?: 0 else 0
        val end = if (sameYearMonthDayHourMinute(maxCalendar())) maxCalendar()?.get(Calendar.SECOND) ?: 59 else 59
        return (start..end).toList()
    }

    private fun sameYearMonth(calendar: Calendar?): Boolean {
        return calendar != null &&
            selectedYear == calendar.get(Calendar.YEAR) &&
            selectedMonth == calendar.get(Calendar.MONTH) + 1
    }

    private fun sameYearMonthDay(calendar: Calendar?): Boolean {
        return calendar != null && sameYearMonth(calendar) && selectedDay == calendar.get(Calendar.DAY_OF_MONTH)
    }

    private fun sameYearMonthDayHour(calendar: Calendar?): Boolean {
        return calendar != null && sameYearMonthDay(calendar) && selectedHour == calendar.get(Calendar.HOUR_OF_DAY)
    }

    private fun sameYearMonthDayHourMinute(calendar: Calendar?): Boolean {
        return calendar != null && sameYearMonthDayHour(calendar) && selectedMinute == calendar.get(Calendar.MINUTE)
    }

    private fun buildSelectedCalendar(): Calendar {
        return Calendar.getInstance().apply {
            timeInMillis = initialCalendar.timeInMillis
            if (showMonthDayColumns()) {
                set(Calendar.YEAR, selectedYear)
                set(Calendar.MONTH, selectedMonth - 1)
                set(Calendar.DAY_OF_MONTH, selectedDay)
            }
            if (showTimeColumns()) {
                set(Calendar.HOUR_OF_DAY, selectedHour)
                set(Calendar.MINUTE, selectedMinute)
                set(Calendar.SECOND, if (showSecondColumn()) selectedSecond else 0)
            } else {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
            }
            set(Calendar.MILLISECOND, 0)
        }
    }

    private fun minCalendar(): Calendar? = minTime?.let(::calendarOf)

    private fun maxCalendar(): Calendar? = maxTime?.let(::calendarOf)

    private fun calendarOf(time: Long): Calendar {
        return Calendar.getInstance().apply {
            timeInMillis = time
            set(Calendar.MILLISECOND, 0)
        }
    }

    private fun clamp(time: Long): Long {
        val min = minTime
        val max = maxTime
        return when {
            min != null && time < min -> min
            max != null && time > max -> max
            else -> time
        }
    }

    override fun onDetachedFromWindow() {
        if (::yearColumn.isInitialized) {
            listOf(yearColumn, monthColumn, dayColumn, hourColumn, minuteColumn, secondColumn).forEach { column ->
                column.scrollRequest?.let(column.recyclerView::removeCallbacks)
                column.scrollRequest = null
            }
        }
        super.onDetachedFromWindow()
    }

    private data class PickerColumn(
        val recyclerView: RecyclerView,
        val layoutManager: LinearLayoutManager,
        val adapter: PickerColumnAdapter,
        val snapHelper: LinearSnapHelper,
        var values: List<Int> = emptyList(),
        var selectedIndex: Int = 0,
        var isSelecting: Boolean = false,
        var scrollRequest: Runnable? = null,
        var onSelected: ((Int) -> Unit)? = null
    )

    private data class PickerColumnItem(val label: String, val accessibilityLabel: String)

    private class PickerColumnAdapter(
        viewContext: Context
    ) : BaseQuickAdapter<PickerColumnItem, PickerColumnAdapter.ViewHolder>() {

        private var selectedIndex: Int = 0
        private val measurementLabel = TextView(viewContext).apply {
            // 离屏测量也要提供布局参数；首次 measure 后再次 setText 会由 TextView 读取宽高策略。
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            TextViewCompat.setTextAppearance(this, com.zero.common.R.style.TextAppearance_BabyCare_Body1)
            includeFontPadding = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val minimumTouch = viewContext.resources.getDimensionPixelSize(com.zero.common.R.dimen.min_touch_target)
        private val textPadding = viewContext.resources.getDimensionPixelSize(com.zero.common.R.dimen.content_gap_small)
        var itemHeightPx = max(minimumTouch, measurementLabel.lineHeight + textPadding * 2)
            private set
        private var labels: List<String> = emptyList()

        fun preferredWidthPx(): Int = max(minimumTouch,
            ceil(labels.maxOfOrNull { measurementLabel.paint.measureText(it) } ?: 0f).toInt() + textPadding * 2)

        fun requiredHeightPx(width: Int): Int {
            measurementLabel.text = labels.maxByOrNull { measurementLabel.paint.measureText(it) }.orEmpty()
            measurementLabel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            return max(minimumTouch, measurementLabel.measuredHeight + textPadding * 2)
        }

        fun updateItemHeight(height: Int) {
            if (itemHeightPx == height) return
            itemHeightPx = height
            if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
        }

        private val primaryColor = viewContext.resolveThemeColor(com.zero.common.R.attr.colorTextPrimary)
        private val secondaryColor = viewContext.resolveThemeColor(com.zero.common.R.attr.colorTextSecondary)
        private val hintColor = viewContext.resolveThemeColor(com.zero.common.R.attr.colorTextHint)

        fun submitValues(values: List<PickerColumnItem>, selectedIndex: Int) {
            this.selectedIndex = selectedIndex
            this.labels = values.map { it.label }
            submitList(values)
        }

        fun setSelectedIndex(index: Int) {
            val oldIndex = selectedIndex
            selectedIndex = index
            if (oldIndex in items.indices) notifyItemChanged(oldIndex)
            if (index in items.indices) notifyItemChanged(index)
        }

        override fun onCreateViewHolder(context: Context, parent: ViewGroup, viewType: Int): ViewHolder {
            val textView = object : AppCompatTextView(context) {
                override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(info)
                    // RecyclerView 有待处理更新时会跳过原 delegate；数值自身必须始终保留单选语义。
                    info.className = android.widget.RadioButton::class.java.name
                    info.isCheckable = true
                    info.isChecked = isSelected
                }
            }.apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                TextViewCompat.setTextAppearance(this, com.zero.common.R.style.TextAppearance_BabyCare_Body1)
                isFocusable = true
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    itemHeightPx
                )
            }
            return ViewHolder(textView)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int, item: PickerColumnItem?) {
            val distance = abs(position - selectedIndex)
            val isSelected = distance == 0
            if (holder.textView.layoutParams.height != itemHeightPx) {
                holder.textView.layoutParams = holder.textView.layoutParams.apply { height = itemHeightPx }
            }
            holder.textView.isSelected = isSelected
            holder.textView.text = item?.label.orEmpty()
            holder.textView.contentDescription = item?.accessibilityLabel
            holder.textView.setTextColor(
                when {
                    isSelected -> primaryColor
                    distance == 1 -> secondaryColor
                    else -> hintColor
                }
            )
            holder.textView.typeface = Typeface.create(
                if (isSelected) "sans-serif-medium" else "sans-serif",
                Typeface.NORMAL
            )
        }

        class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)
    }

    private companion object {
        const val DEFAULT_YEAR_LOOKBACK = 20
        const val VISIBLE_ITEM_COUNT = 3
    }
}

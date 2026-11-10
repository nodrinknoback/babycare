package com.zero.babycare.statistics

import android.app.Application
import com.zero.common.ext.getAppString
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.zero.babycare.home.BabyRecordChangeBus
import com.zero.babycare.statistics.mapper.StatisticsDateRange
import com.zero.babycare.statistics.model.DayRecordSectionUiModel
import com.zero.babycare.statistics.model.CalendarBrowseState
import com.zero.babycare.statistics.model.InsightSectionUiModel
import com.zero.babycare.statistics.model.StatisticsUiEvent
import com.zero.babycare.statistics.model.StatisticsUiState
import com.zero.babycare.statistics.model.StatisticsClockContext
import com.zero.babydata.room.BabyRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth

/**
 * 统计页面 ViewModel
 */
class StatisticsViewModel @JvmOverloads constructor(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
    injectedRepository: BabyRepository? = null
) : AndroidViewModel(application) {

    // 默认仍使用应用数据库；隔离回归可通过同一查询实现验证提交后的派生状态。
    private val repository = injectedRepository ?: BabyRepository(application)
    private val contentLoader = StatisticsContentLoader(application, repository)

    // ==================== 状态 ====================

    /** 当前宝宝ID */
    private val _currentBabyId = MutableStateFlow(0)
    val currentBabyId: StateFlow<Int> = _currentBabyId.asStateFlow()

    /** 选中的日期只作为 ViewModel 内部驱动源，对外统一通过 uiState 暴露。 */
    private val _selectedDate = MutableStateFlow(readSavedDate("statistics_selected_date") ?: LocalDate.now())

    /** 统计页统一状态，后续 Fragment 只按它渲染整页。 */
    private val _uiState = MutableStateFlow(StatisticsUiState(
        selectedDate = _selectedDate.value,
        calendarBrowse = CalendarBrowseState.forDate(
            readSavedDate("statistics_calendar_anchor") ?: _selectedDate.value,
            savedStateHandle["statistics_calendar_month"] ?: false
        ),
        dayRecord = DayRecordSectionUiModel.empty(_selectedDate.value),
        insights = InsightSectionUiModel.empty(_selectedDate.value)
    ))
    val uiState: StateFlow<StatisticsUiState> = _uiState.asStateFlow()

    private var contentLoadJob: Job? = null
    private var calendarLoadJob: Job? = null
    private var requestedClockContext = _uiState.value.clockContext

    init {
        // 保存允许离页；返回统计后的首次查询可能早于提交完成，必须继续接收提交后的失效信号。
        viewModelScope.launch {
            BabyRecordChangeBus.changes.collect { changedBabyId ->
                val currentBabyId = _currentBabyId.value
                if (currentBabyId > 0 && (changedBabyId == currentBabyId ||
                        changedBabyId == BabyRecordChangeBus.ALL_BABIES_CHANGED)) {
                    loadData()
                }
            }
        }
    }

    // ==================== 公开方法 ====================

    /**
     * 设置当前宝宝ID并加载数据
     */
    fun setBabyId(babyId: Int) {
        if (_currentBabyId.value != babyId) {
            _currentBabyId.value = babyId
            _uiState.value = _uiState.value.copy(
                babyId = babyId,
                datesWithRecords = emptySet()
            )
            loadData()
        }
    }

    fun onEvent(event: StatisticsUiEvent) {
        when (event) {
            is StatisticsUiEvent.SelectDate -> selectDate(event.date)
            is StatisticsUiEvent.ChangeMonth -> onMonthChanged(event.yearMonth)
            StatisticsUiEvent.GoToday -> goToToday()
            StatisticsUiEvent.Refresh -> refreshData()
            is StatisticsUiEvent.OpenTimelineItem -> Unit
        }
    }

    /**
     * 选择日期
     */
    fun selectDate(date: LocalDate) {
        val browse = CalendarBrowseState.forDate(date, _uiState.value.calendarBrowse.monthMode)
        if (_selectedDate.value != date || _uiState.value.calendarBrowse != browse) {
            _selectedDate.value = date
            savedStateHandle["statistics_selected_date"] = date.toString()
            saveCalendarBrowse(browse)
            _uiState.value = _uiState.value.copy(
                selectedDate = date,
                calendarBrowse = browse,
                dayRecord = _uiState.value.dayRecord.copy(date = date),
                insights = _uiState.value.insights.copy(selectedDate = date)
            )
            loadData()
        }
    }

    /**
     * 跳转到今天
     */
    fun goToToday() {
        selectDate(LocalDate.now())
    }

    /**
     * 刷新数据
     */
    fun refreshData() {
        loadData()
    }

    /** 返回前台核对时区/日期；系统校时广播可强制刷新，但保留选中日和独立浏览位置。 */
    fun onClockChanged(force: Boolean = false) {
        if (force || requestedClockContext != StatisticsClockContext.now()) loadData()
    }

    /** 查询期间切换时区时丢弃旧结果，并由最新上下文接管整页加载。 */
    private fun acceptsClock(clock: StatisticsClockContext): Boolean {
        if (clock == StatisticsClockContext.now()) return true
        onClockChanged()
        return false
    }

    // ==================== 私有方法 ====================

    /**
     * 以宝宝和选中日期为一个输入快照加载整页。新输入会取消旧请求，避免快速切换后
     * 旧协程覆盖新页面，也让 Loading/Error 明确代表整组核心内容。
     */
    private fun loadData() {
        val babyId = _currentBabyId.value
        val date = _selectedDate.value
        val clock = StatisticsClockContext.now()
        val zone = clock.zone
        requestedClockContext = clock
        contentLoadJob?.cancel()

        if (babyId <= 0) {
            _uiState.value = _uiState.value.copy(
                babyId = babyId,
                selectedDate = date,
                dayRecord = DayRecordSectionUiModel.empty(date),
                insights = InsightSectionUiModel.empty(date),
                datesWithRecords = emptySet(),
                isLoading = false,
                errorMessage = null,
                clockContext = clock
            )
            calendarLoadJob?.cancel()
            return
        }

        _uiState.value = _uiState.value.copy(
            isLoading = true,
            errorMessage = null,
            datesWithRecords = if (_uiState.value.clockContext == clock) _uiState.value.datesWithRecords else emptySet()
        )
        contentLoadJob = viewModelScope.launch {
            try {
                val content = contentLoader.load(babyId, date, zone)

                currentCoroutineContext().ensureActive()
                if (_selectedDate.value == date && _currentBabyId.value == babyId && acceptsClock(clock)) {
                    _uiState.value = _uiState.value.copy(
                        babyId = babyId,
                        selectedDate = date,
                        dayRecord = content.dayRecord,
                        insights = content.insights,
                        isLoading = false,
                        errorMessage = null,
                        clockContext = clock
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (_selectedDate.value == date && _currentBabyId.value == babyId && acceptsClock(clock)) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = getLoadFailedText()
                    )
                }
            }
        }
        loadDatesWithRecords(_uiState.value.calendarBrowse, clock)
    }

    /**
     * 月份标记独立于选中日内容；同样只允许最新月份请求写回。
     */
    private fun loadDatesWithRecords(browse: CalendarBrowseState, clock: StatisticsClockContext = requestedClockContext) {
        val babyId = _currentBabyId.value
        val zone = clock.zone
        calendarLoadJob?.cancel()

        if (babyId <= 0) {
            _uiState.value = _uiState.value.copy(datesWithRecords = emptySet())
            return
        }

        calendarLoadJob = viewModelScope.launch {
            try {
                // 月视图也补齐首尾周；灰色日期仍可点击，记录点必须覆盖整个可见日期范围。
                val month = YearMonth.from(browse.anchorDate)
                val firstDate = if (browse.monthMode) StatisticsDateRange.week(month.atDay(1), zone).startDate
                    else browse.anchorDate
                val lastDate = if (browse.monthMode) StatisticsDateRange.week(month.atEndOfMonth(), zone).endDate
                    else browse.anchorDate.plusDays(6)
                val dates = withContext(Dispatchers.IO) {
                    buildSet {
                        var queryMonth = YearMonth.from(firstDate)
                        val lastMonth = YearMonth.from(lastDate)
                        while (queryMonth <= lastMonth) {
                            addAll(repository.getDatesWithRecords(babyId, queryMonth, zone).filter { it in firstDate..lastDate })
                            queryMonth = queryMonth.plusMonths(1)
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                if (_currentBabyId.value == babyId && _uiState.value.calendarBrowse == browse && acceptsClock(clock)) {
                    _uiState.value = _uiState.value.copy(datesWithRecords = dates)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (_currentBabyId.value == babyId && _uiState.value.calendarBrowse == browse && acceptsClock(clock)) {
                    _uiState.value = _uiState.value.copy(datesWithRecords = emptySet())
                }
            }
        }
    }

    /**
     * 当月份变化时加载新月份的记录日期
     */
    fun onMonthChanged(yearMonth: YearMonth) {
        onCalendarBrowsed(CalendarBrowseState(monthMode = true, anchorDate = yearMonth.atDay(1)))
    }

    /** 浏览状态只改变日历，统计内容继续归属 selectedDate；进程恢复也保留两者的区别。 */
    fun onCalendarBrowsed(browse: CalendarBrowseState) {
        if (_uiState.value.calendarBrowse == browse) return
        saveCalendarBrowse(browse)
        _uiState.value = _uiState.value.copy(calendarBrowse = browse, datesWithRecords = emptySet())
        loadDatesWithRecords(browse)
    }

    private fun saveCalendarBrowse(browse: CalendarBrowseState) {
        savedStateHandle["statistics_calendar_anchor"] = browse.anchorDate.toString()
        savedStateHandle["statistics_calendar_month"] = browse.monthMode
    }

    private fun readSavedDate(key: String): LocalDate? = savedStateHandle.get<String>(key)?.let {
        runCatching { LocalDate.parse(it) }.getOrNull()
    }

    private fun getLoadFailedText(): String =
        getApplication<Application>().getAppString(com.zero.common.R.string.data_load_failed)
}

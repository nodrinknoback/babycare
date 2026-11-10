package com.zero.babycare.statistics

import android.app.Application
import android.content.Context
import com.zero.common.util.LanguageManager
import com.zero.common.ext.getAppString
import com.zero.babycare.statistics.mapper.StatisticsDateRange
import com.zero.babycare.statistics.mapper.StatisticsDurationCalculator
import com.zero.babycare.statistics.mapper.StatisticsGrowthHistory
import com.zero.babycare.statistics.mapper.StatisticsTimelineMapper
import com.zero.babycare.statistics.mapper.WhoSexParser
import com.zero.babycare.statistics.model.DayRecordSectionUiModel
import com.zero.babycare.statistics.model.DaySummary
import com.zero.babycare.statistics.model.GrowthPercentileOverview
import com.zero.babycare.statistics.model.GrowthPercentileSeries
import com.zero.babycare.statistics.model.GrowthTrend
import com.zero.babycare.statistics.model.GrowthTrendItem
import com.zero.babycare.statistics.model.HealthStats
import com.zero.babycare.statistics.model.InsightSectionUiModel
import com.zero.babycare.statistics.model.StructureItem
import com.zero.babycare.statistics.model.StructureOverview
import com.zero.babycare.statistics.model.StructureSection
import com.zero.babycare.statistics.model.SummaryMetricType
import com.zero.babycare.statistics.model.SummaryMetricUiModel
import com.zero.babycare.statistics.model.TimelineUiItem
import com.zero.babycare.statistics.model.TrendOverview
import com.zero.babycare.statistics.model.TrendPeriod
import com.zero.babycare.statistics.model.TrendPeriodSummary
import com.zero.babycare.statistics.standard.WhoGrowthStandardProvider
import com.zero.babycare.statistics.standard.WhoIndicator
import com.zero.babycare.statistics.standard.WhoSex
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.FeedingType
import com.zero.babydata.entity.EventType
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.room.BabyRepository
import com.zero.common.util.UnitConfig
import com.zero.common.util.UnitConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 封装一次统计任务的快照读取与区块计算；调用者只选择宝宝、日期和时区。 */
internal class StatisticsContentLoader(
    private val application: Application,
    private val repository: BabyRepository
) {
    private val whoStandardProvider by lazy { WhoGrowthStandardProvider(application) }

    suspend fun load(babyId: Int, date: LocalDate, zone: ZoneId): Content = withContext(Dispatchers.IO) {
        val ranges = listOf(StatisticsDateRange.week(date, zone), StatisticsDateRange.month(date, zone),
            StatisticsDateRange.year(date, zone))
        val snapshot = repository.getStatisticsSnapshot(babyId, date, zone, ranges.map { it.startMillis..it.endMillis })
        val monthRange = StatisticsDateRange.month(date, zone)
        val month = snapshot.periods.getValue(monthRange.startMillis..monthRange.endMillis)
        val input = StatisticsLoadInputs(snapshot.baby,
            snapshot.growth.mapValues { StatisticsGrowthHistory.create(it.value, date, zone) },
            month.feedings, month.events, snapshot.periods)
        currentCoroutineContext().ensureActive()
        Content(buildDayRecordSection(snapshot, date, snapshot.baby, zone),
            InsightSectionUiModel(date,
                trend = buildTrendOverview(date, input, zone),
                structure = buildStructureOverview(input.monthFeedings, input.monthEvents),
                growth = buildGrowthTrend(input), percentile = buildGrowthPercentileOverview(input),
                health = buildHealthStats(date, input.monthEvents, zone)))
    }

    private suspend fun buildDayRecordSection(
        snapshot: BabyRepository.StatisticsSnapshot,
        date: LocalDate,
        babyInfo: BabyInfo?,
        zone: ZoneId
    ): DayRecordSectionUiModel {
        val summaryData = snapshot.summary
        val timelineRecords = snapshot.timeline
        val summary = DaySummary(
            date = date,
            feedingCount = summaryData.feedingCount,
            feedingTotalMinutes = summaryData.feedingTotalMinutes,
            feedingTotalMl = summaryData.feedingTotalMl,
            sleepCount = summaryData.sleepCount,
            sleepTotalMinutes = summaryData.sleepTotalMinutes,
            diaperWetCount = summaryData.diaperWetCount,
            diaperDirtyCount = summaryData.diaperDirtyCount,
            diaperMixedCount = summaryData.diaperMixedCount,
            diaperDryCount = summaryData.diaperDryCount,
            otherEventCount = summaryData.otherEventCount
        )
        val mapper = buildTimelineMapper(zone)
        val context = currentCoroutineContext()
        val timelineItems = timelineRecords.map { record ->
            context.ensureActive()
            when (record) {
                is BabyRepository.TimelineRecord.Feeding -> mapper.mapFeeding(record.record)
                is BabyRepository.TimelineRecord.Sleep -> mapper.mapSleep(record.record)
                is BabyRepository.TimelineRecord.Event -> mapper.mapEvent(record.record)
            }
        }
        return buildDayRecordSection(
            date = date,
            babyInfo = babyInfo,
            summary = summary,
            timelineItems = timelineItems,
            zone = zone
        )
    }

    data class Content(
        val dayRecord: DayRecordSectionUiModel,
        val insights: InsightSectionUiModel
    )

    private data class StatisticsLoadInputs(
        val babyInfo: BabyInfo?,
        val growth: Map<Int, StatisticsGrowthHistory>,
        val monthFeedings: List<FeedingRecord>,
        val monthEvents: List<EventRecord>,
        val periods: Map<LongRange, BabyRepository.StatisticsPeriodRecords>
    )

    /**
     * 查询并计算生长趋势
     */
    private suspend fun buildGrowthTrend(input: StatisticsLoadInputs): GrowthTrend {
        val weightUnit = UnitConfig.getWeightUnit()
        val heightUnit = UnitConfig.getHeightUnit()

        val weightItem = buildGrowthItem(
            history = input.growth.getValue(EventType.GROWTH_WEIGHT),
            unitLabelResId = UnitConfig.getWeightUnitLabelResId(),
            targetUnit = weightUnit,
            converter = UnitConverter::weightToDisplay
        )

        val heightItem = buildGrowthItem(
            history = input.growth.getValue(EventType.GROWTH_HEIGHT),
            unitLabelResId = UnitConfig.getHeightUnitLabelResId(),
            targetUnit = heightUnit,
            converter = UnitConverter::heightToDisplay
        )

        val headItem = buildGrowthItem(
            history = input.growth.getValue(EventType.GROWTH_HEAD),
            unitLabelResId = UnitConfig.getHeightUnitLabelResId(),
            targetUnit = heightUnit,
            converter = UnitConverter::heightToDisplay
        )

        return GrowthTrend(
            weight = weightItem,
            height = heightItem,
            head = headItem
        )
    }

    /**
     * 生成周/月/年趋势数据
     */
    private suspend fun buildTrendOverview(date: LocalDate, input: StatisticsLoadInputs, zone: ZoneId): TrendOverview {
        val weekRange = StatisticsDateRange.week(date, zone)
        val monthRange = StatisticsDateRange.month(date, zone)
        val yearRange = StatisticsDateRange.year(date, zone)

        val summaries = listOf(
            buildPeriodSummary(TrendPeriod.WEEK, weekRange, input),
            buildPeriodSummary(TrendPeriod.MONTH, monthRange, input),
            buildPeriodSummary(TrendPeriod.YEAR, yearRange, input)
        )
        return TrendOverview(summaries)
    }

    /**
     * 统计指定时间范围内的趋势数据
     */
    private suspend fun buildPeriodSummary(
        period: TrendPeriod,
        range: StatisticsDateRange.Range,
        input: StatisticsLoadInputs
    ): TrendPeriodSummary {
        val records = input.periods.getValue(range.startMillis..range.endMillis)
        val feedings = records.feedings
        val sleeps = records.sleeps
        val feedingOverlaps = records.feedingOverlaps
        val sleepOverlaps = records.sleepOverlaps
        val events = records.events
        val context = currentCoroutineContext()

        // 次数仍按记录开始时间归属周期；时长按重叠部分归属周期，避免跨边界记录重复放大。
        val feedingTotalMinutes = StatisticsDurationCalculator.toWholeMinutes(
            StatisticsDurationCalculator.feedingDurationMillis(
                records = feedingOverlaps,
                rangeStart = range.startMillis,
                rangeEnd = range.endMillis,
                checkCancellation = { context.ensureActive() }
            )
        )
        val sleepTotalMinutes = StatisticsDurationCalculator.toWholeMinutes(
            StatisticsDurationCalculator.sleepDurationMillis(
                records = sleepOverlaps,
                rangeStart = range.startMillis,
                rangeEnd = range.endMillis,
                checkCancellation = { context.ensureActive() }
            )
        )

        var diaperCount = 0
        var otherEventCount = 0
        events.forEach { event ->
            context.ensureActive()
            if (EventType.isDiaper(event.type)) {
                diaperCount++
            } else {
                otherEventCount++
            }
        }

        return TrendPeriodSummary(
            period = period,
            startDate = range.startDate,
            endDate = range.endDate,
            feedingCount = feedings.size,
            feedingTotalMinutes = feedingTotalMinutes,
            sleepCount = sleeps.size,
            sleepTotalMinutes = sleepTotalMinutes,
            diaperCount = diaperCount,
            otherEventCount = otherEventCount
        )
    }

    /**
     * 构建结构图数据（默认按月）
     */
    private suspend fun buildStructureOverview(feedings: List<FeedingRecord>, events: List<EventRecord>): StructureOverview {
        val feedingSection = StructureSection(
            titleResId = com.zero.common.R.string.statistics_structure_feeding_title,
            items = buildFeedingStructureItems(feedings)
        )
        val diaperSection = StructureSection(
            titleResId = com.zero.common.R.string.statistics_structure_diaper_title,
            items = buildDiaperStructureItems(events)
        )
        val healthSection = StructureSection(
            titleResId = com.zero.common.R.string.statistics_structure_health_title,
            items = buildHealthStructureItems(events)
        )

        val sections = listOf(feedingSection, diaperSection, healthSection)
            .filter { it.items.isNotEmpty() }
        return StructureOverview(sections)
    }

    /**
     * 喂养结构拆解
     */
    private suspend fun buildFeedingStructureItems(records: List<FeedingRecord>): List<StructureItem> {
        val counts = mutableMapOf<FeedingType, Int>()
        val context = currentCoroutineContext()
        records.forEach { record ->
            context.ensureActive()
            val type = FeedingType.fromType(record.feedingType)
            counts[type] = (counts[type] ?: 0) + 1
        }
        // 结构图使用业务语义色，避免装饰色导致不同统计模块的含义漂移。
        return listOf(
            StructureItem(
                labelResId = com.zero.common.R.string.feeding_type_breast,
                count = counts[FeedingType.BREAST] ?: 0,
                colorResId = com.zero.common.R.color.feeding_primary
            ),
            StructureItem(
                labelResId = com.zero.common.R.string.feeding_type_formula,
                count = counts[FeedingType.FORMULA] ?: 0,
                colorResId = com.zero.common.R.color.event_care
            ),
            StructureItem(
                labelResId = com.zero.common.R.string.feeding_type_mixed,
                count = counts[FeedingType.MIXED] ?: 0,
                colorResId = com.zero.common.R.color.sleep_primary
            ),
            StructureItem(
                labelResId = com.zero.common.R.string.feeding_type_solid,
                count = counts[FeedingType.SOLID_FOOD] ?: 0,
                colorResId = com.zero.common.R.color.event_growth
            ),
            StructureItem(
                labelResId = com.zero.common.R.string.feeding_type_other,
                count = counts[FeedingType.OTHER] ?: 0,
                colorResId = com.zero.common.R.color.event_other
            )
        ).filter { it.count > 0 }
    }

    /**
     * 排泄结构拆解
     */
    private suspend fun buildDiaperStructureItems(records: List<EventRecord>): List<StructureItem> {
        val context = currentCoroutineContext()
        val wet = records.count { context.ensureActive(); it.type == EventType.DIAPER_WET }
        val dirty = records.count { context.ensureActive(); it.type == EventType.DIAPER_DIRTY }
        val mixed = records.count { context.ensureActive(); it.type == EventType.DIAPER_MIXED }
        val dry = records.count { context.ensureActive(); it.type == EventType.DIAPER_DRY }

        // 排泄结构保留可区分颜色，但仍从业务语义色中选取，保证图例含义稳定。
        return listOf(
            StructureItem(
                labelResId = com.zero.common.R.string.event_diaper_wet,
                count = wet,
                colorResId = com.zero.common.R.color.event_care
            ),
            StructureItem(
                labelResId = com.zero.common.R.string.event_diaper_dirty,
                count = dirty,
                colorResId = com.zero.common.R.color.event_diaper
            ),
            StructureItem(
                labelResId = com.zero.common.R.string.event_diaper_mixed,
                count = mixed,
                colorResId = com.zero.common.R.color.event_milestone
            ),
            StructureItem(
                labelResId = com.zero.common.R.string.event_diaper_dry,
                count = dry,
                colorResId = com.zero.common.R.color.event_other
            )
        ).filter { it.count > 0 }
    }

    /**
     * 健康结构拆解
     */
    private suspend fun buildHealthStructureItems(records: List<EventRecord>): List<StructureItem> {
        val context = currentCoroutineContext()
        val temperature = records.count { context.ensureActive(); it.type == EventType.HEALTH_TEMPERATURE }
        val medicine = records.count { context.ensureActive(); it.type == EventType.HEALTH_MEDICINE }
        val doctor = records.count { context.ensureActive(); it.type == EventType.HEALTH_DOCTOR }
        val symptom = records.count { context.ensureActive(); it.type == EventType.HEALTH_SYMPTOM }
        val vaccine = records.count { context.ensureActive(); it.type == EventType.HEALTH_VACCINE }

        // 健康结构使用健康、照护、成长等语义色，便于与其他统计卡片形成一致认知。
        return listOf(
            StructureItem(
                labelResId = com.zero.common.R.string.event_health_temperature,
                count = temperature,
                colorResId = com.zero.common.R.color.event_health
            ),
            StructureItem(
                labelResId = com.zero.common.R.string.event_health_medicine,
                count = medicine,
                colorResId = com.zero.common.R.color.sleep_primary
            ),
            StructureItem(
                labelResId = com.zero.common.R.string.event_health_doctor,
                count = doctor,
                colorResId = com.zero.common.R.color.event_care
            ),
            StructureItem(
                labelResId = com.zero.common.R.string.event_health_symptom,
                count = symptom,
                colorResId = com.zero.common.R.color.event_milestone
            ),
            StructureItem(
                labelResId = com.zero.common.R.string.event_health_vaccine,
                count = vaccine,
                colorResId = com.zero.common.R.color.event_growth
            )
        ).filter { it.count > 0 }
    }

    /**
     * 构建生长百分位数据（基于 WHO 标准）
     */
    private suspend fun buildGrowthPercentileOverview(input: StatisticsLoadInputs): GrowthPercentileOverview {
        val baby = input.babyInfo
        val birthDate = baby?.birthDate ?: 0L
        val sex = WhoSexParser.parse(baby?.gender)

        if (birthDate <= 0L || sex == WhoSex.UNKNOWN) {
            return buildEmptyGrowthPercentile(
                com.zero.common.R.string.statistics_growth_percentile_note_missing_info
            )
        }

        val weightUnit = UnitConfig.getWeightUnit()
        val heightUnit = UnitConfig.getHeightUnit()
        val weight = buildPercentileSeries(
            history = input.growth.getValue(EventType.GROWTH_WEIGHT),
            labelResId = com.zero.common.R.string.statistics_growth_weight,
            unitLabelResId = UnitConfig.getWeightUnitLabelResId(),
            targetUnit = weightUnit,
            indicator = WhoIndicator.WEIGHT,
            sex = sex,
            birthDate = birthDate
        )
        val height = buildPercentileSeries(
            history = input.growth.getValue(EventType.GROWTH_HEIGHT),
            labelResId = com.zero.common.R.string.statistics_growth_height,
            unitLabelResId = UnitConfig.getHeightUnitLabelResId(),
            targetUnit = heightUnit,
            indicator = WhoIndicator.LENGTH_HEIGHT,
            sex = sex,
            birthDate = birthDate
        )
        val head = buildPercentileSeries(
            history = input.growth.getValue(EventType.GROWTH_HEAD),
            labelResId = com.zero.common.R.string.statistics_growth_head,
            unitLabelResId = UnitConfig.getHeightUnitLabelResId(),
            targetUnit = heightUnit,
            indicator = WhoIndicator.HEAD,
            sex = sex,
            birthDate = birthDate
        )

        return GrowthPercentileOverview(
            weight = weight,
            height = height,
            head = head,
            noteResId = com.zero.common.R.string.statistics_growth_percentile_note_who
        )
    }

    /**
     * 生成单项百分位序列
     */
    private suspend fun buildPercentileSeries(
        history: StatisticsGrowthHistory,
        labelResId: Int,
        unitLabelResId: Int,
        targetUnit: String,
        indicator: WhoIndicator,
        sex: WhoSex,
        birthDate: Long
    ): GrowthPercentileSeries {
        val recent = history.recentPercentiles(
            birthDateMillis = birthDate,
            standardValue = { data ->
                when (indicator) {
                    WhoIndicator.WEIGHT -> UnitConverter.weightToDisplay(
                        data.value, data.unit, UnitConfig.WEIGHT_UNIT_KG
                    )
                    WhoIndicator.LENGTH_HEIGHT, WhoIndicator.HEAD -> UnitConverter.heightToDisplay(
                        data.value, data.unit, UnitConfig.HEIGHT_UNIT_CM
                    )
                }
            },
            percentile = { ageDays, value ->
                whoStandardProvider.computePercentile(
                    indicator = indicator,
                    sex = sex,
                    ageDays = ageDays,
                    ageMonths = ageDays / 30.4375,
                    value = value
                )
            },
            displayValue = { toDisplayValue(it, indicator, targetUnit) }
        )

        return GrowthPercentileSeries(
            labelResId = labelResId,
            unitLabelResId = unitLabelResId,
            latestValue = recent.latestValue,
            latestPercentile = recent.latestPercentile,
            points = recent.points
        )
    }

    /**
     * 构建健康/疫苗统计（按选中月份）
     */
    private suspend fun buildHealthStats(date: LocalDate, events: List<EventRecord>, zone: ZoneId): HealthStats {
        val range = StatisticsDateRange.month(date, zone)

        var temperatureCount = 0
        var medicineCount = 0
        var doctorCount = 0
        var symptomCount = 0
        var vaccineCount = 0
        var lastVaccineTime: Long? = null
        val context = currentCoroutineContext()

        events.forEach { event ->
            context.ensureActive()
            when (event.type) {
                EventType.HEALTH_TEMPERATURE -> temperatureCount++
                EventType.HEALTH_MEDICINE -> medicineCount++
                EventType.HEALTH_DOCTOR -> doctorCount++
                EventType.HEALTH_SYMPTOM -> symptomCount++
                EventType.HEALTH_VACCINE -> {
                    vaccineCount++
                    val current = lastVaccineTime ?: 0L
                    if (event.time > current) {
                        lastVaccineTime = event.time
                    }
                }
            }
        }

        return HealthStats(
            startDate = range.startDate,
            endDate = range.endDate,
            temperatureCount = temperatureCount,
            medicineCount = medicineCount,
            doctorCount = doctorCount,
            symptomCount = symptomCount,
            vaccineCount = vaccineCount,
            lastVaccineTime = lastVaccineTime
        )
    }

    /**
     * 构建空的百分位数据
     */
    private fun buildEmptyGrowthPercentile(noteResId: Int): GrowthPercentileOverview {
        return GrowthPercentileOverview(
            weight = GrowthPercentileSeries(
                labelResId = com.zero.common.R.string.statistics_growth_weight,
                unitLabelResId = UnitConfig.getWeightUnitLabelResId(),
                latestValue = null,
                latestPercentile = null,
                points = emptyList()
            ),
            height = GrowthPercentileSeries(
                labelResId = com.zero.common.R.string.statistics_growth_height,
                unitLabelResId = UnitConfig.getHeightUnitLabelResId(),
                latestValue = null,
                latestPercentile = null,
                points = emptyList()
            ),
            head = GrowthPercentileSeries(
                labelResId = com.zero.common.R.string.statistics_growth_head,
                unitLabelResId = UnitConfig.getHeightUnitLabelResId(),
                latestValue = null,
                latestPercentile = null,
                points = emptyList()
            ),
            noteResId = noteResId
        )
    }

    /**
     * 将标准单位转为当前展示单位
     */
    private fun toDisplayValue(value: Double, indicator: WhoIndicator, targetUnit: String): Double {
        return when (indicator) {
            WhoIndicator.WEIGHT -> UnitConverter.weightToDisplay(
                value,
                UnitConfig.WEIGHT_UNIT_KG,
                targetUnit
            )
            WhoIndicator.LENGTH_HEIGHT, WhoIndicator.HEAD -> UnitConverter.heightToDisplay(
                value,
                UnitConfig.HEIGHT_UNIT_CM,
                targetUnit
            )
        }
    }

    private fun buildDayRecordSection(
        date: LocalDate,
        babyInfo: BabyInfo?,
        summary: DaySummary,
        timelineItems: List<TimelineUiItem>,
        zone: ZoneId
    ): DayRecordSectionUiModel {
        val babyAgeText = babyInfo?.let { buildBabyAgeText(it, date, zone) }
        return DayRecordSectionUiModel(
            date = date,
            babyAgeText = babyAgeText,
            showBirthHint = babyAgeText.isNullOrBlank(),
            recordCountText = application.getAppString(
                com.zero.common.R.string.statistics_day_record_count_format,
                timelineItems.size
            ),
            summaryMetrics = buildSummaryMetrics(summary),
            timelineItems = timelineItems,
            isEmpty = timelineItems.isEmpty()
        )
    }

    private fun buildBabyAgeText(babyInfo: BabyInfo, selectedDate: LocalDate, zone: ZoneId): String? {
        if (babyInfo.birthDate <= 0) {
            return null
        }
        val birthDate = Instant.ofEpochMilli(babyInfo.birthDate)
            .atZone(zone)
            .toLocalDate()
        val days = ChronoUnit.DAYS.between(birthDate, selectedDate)
        return if (days < 0) {
            application.getAppString(com.zero.common.R.string.baby_not_born_yet_playful)
        } else {
            application.getAppString(com.zero.common.R.string.days_born, days.toInt())
        }
    }

    private fun buildSummaryMetrics(summary: DaySummary): List<SummaryMetricUiModel> {
        val app = LanguageManager.localizedContext(application)
        return listOf(
            SummaryMetricUiModel(
                type = SummaryMetricType.FEEDING,
                titleResId = com.zero.common.R.string.feeding,
                primaryText = app.getString(com.zero.common.R.string.times_count_format, summary.feedingCount),
                secondaryText = formatSummaryDuration(app, summary.feedingTotalMinutes),
                iconResId = com.zero.common.R.drawable.ic_feeding,
                colorResId = com.zero.common.R.color.feeding_primary,
                surfaceColorResId = com.zero.common.R.color.feeding_bg
            ),
            SummaryMetricUiModel(
                type = SummaryMetricType.SLEEP,
                titleResId = com.zero.common.R.string.sleeping,
                primaryText = app.getString(com.zero.common.R.string.times_count_format, summary.sleepCount),
                secondaryText = formatSummaryDuration(app, summary.sleepTotalMinutes),
                iconResId = com.zero.common.R.drawable.ic_sleep,
                colorResId = com.zero.common.R.color.sleep_primary,
                surfaceColorResId = com.zero.common.R.color.sleep_bg
            ),
            SummaryMetricUiModel(
                type = SummaryMetricType.DIAPER,
                titleResId = com.zero.common.R.string.diaper,
                primaryText = app.getString(com.zero.common.R.string.times_count_format, summary.totalDiaperCount),
                secondaryText = formatDiaperDetail(app, summary).ifBlank {
                    app.getString(com.zero.common.R.string.statistics_growth_no_record)
                },
                iconResId = com.zero.common.R.drawable.ic_event_diaper,
                colorResId = com.zero.common.R.color.event_diaper,
                surfaceColorResId = com.zero.common.R.color.event_diaper_light
            ),
            SummaryMetricUiModel(
                type = SummaryMetricType.OTHER,
                titleResId = com.zero.common.R.string.other,
                primaryText = app.getString(com.zero.common.R.string.times_count_format, summary.otherEventCount),
                secondaryText = app.getString(com.zero.common.R.string.statistics_summary_other_scope),
                iconResId = com.zero.common.R.drawable.ic_event_other,
                colorResId = com.zero.common.R.color.event_other,
                surfaceColorResId = com.zero.common.R.color.event_other_light
            )
        )
    }

    /**
     * 统计摘要属于 UI 文案，放在 ViewModel 统一组装，避免数据模型直接拼接中文。
     */
    private fun formatSummaryDuration(app: Context, totalMinutes: Int): String {
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> app.getString(
                com.zero.common.R.string.summary_duration_hours_minutes_compact,
                hours,
                minutes
            )
            hours > 0 -> app.getString(com.zero.common.R.string.summary_duration_hours_compact, hours)
            minutes > 0 -> app.getString(com.zero.common.R.string.summary_duration_minutes_compact, minutes)
            else -> app.getString(com.zero.common.R.string.summary_duration_zero_compact)
        }
    }

    /**
     * 尿布详情按存在的分类组合，分类名称来自资源，连接符只承担结构表达。
     */
    private fun formatDiaperDetail(app: Context, summary: DaySummary): String {
        val parts = mutableListOf<String>()
        if (summary.diaperWetCount > 0) {
            parts.add(app.getString(com.zero.common.R.string.summary_diaper_wet_count_short, summary.diaperWetCount))
        }
        if (summary.diaperDirtyCount > 0) {
            parts.add(app.getString(com.zero.common.R.string.summary_diaper_dirty_count_short, summary.diaperDirtyCount))
        }
        if (summary.diaperMixedCount > 0) {
            parts.add(app.getString(com.zero.common.R.string.summary_diaper_mixed_count_short, summary.diaperMixedCount))
        }
        return if (parts.isEmpty()) "" else "(${parts.joinToString("/")})"
    }

    private fun buildTimelineMapper(zone: ZoneId): StatisticsTimelineMapper {
        val app = LanguageManager.localizedContext(application)
        return StatisticsTimelineMapper(
            strings = object : StatisticsTimelineMapper.Strings {
                override fun get(resId: Int): String {
                    return app.getString(resId)
                }

                override fun format(resId: Int, vararg args: Any): String {
                    return app.getString(resId, *args)
                }
            },
            zone = zone
        )
    }

    /**
     * 读取最近两条记录，换算成当前单位后计算差值
     */
    private suspend fun buildGrowthItem(
        history: StatisticsGrowthHistory,
        unitLabelResId: Int,
        targetUnit: String,
        converter: (Double, String, String) -> Double
    ): GrowthTrendItem {
        val dataList = history.latestValid().map { it.data }
        val latest = dataList.getOrNull(0)
        if (latest == null) {
            return GrowthTrendItem(latestValue = null, diffValue = null, unitLabelResId = unitLabelResId)
        }

        val latestValue = converter(latest.value, latest.unit, targetUnit)
        val previousValue = dataList.getOrNull(1)?.let {
            converter(it.value, it.unit, targetUnit)
        }
        val diffValue = previousValue?.let { latestValue - it }

        return GrowthTrendItem(
            latestValue = latestValue,
            diffValue = diffValue,
            unitLabelResId = unitLabelResId
        )
    }
}

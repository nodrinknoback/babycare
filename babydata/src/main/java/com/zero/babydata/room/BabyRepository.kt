package com.zero.babydata.room

import android.content.Context
import androidx.room.withTransaction
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.ChildDailyRecord
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.EventType
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.FeedingType
import com.zero.babydata.entity.SleepRecord
import com.zero.babydata.domain.RecordDurationCalculator
import com.zero.babydata.domain.RecordWriteException
import com.zero.babydata.domain.RecordWriteFailure
import com.zero.babydata.domain.RecordWritePayload
import com.zero.babydata.domain.TimerRecordNormalizer
import com.zero.babydata.domain.TimerMetadataCodec
import com.zero.babydata.domain.DurationBasis
import com.zero.common.util.TimerClock
import com.zero.babydata.entity.RecordWriteReceipt
import com.zero.babydata.entity.BabyCreationReceipt
import com.zero.babydata.domain.BabyWriteException
import com.zero.babydata.domain.BabyWriteFailure
import java.util.UUID
import java.time.LocalDate
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * BabyCare 数据访问边界。
 *
 * 所有一次性 Room 操作都通过挂起函数暴露，让调用方获得真实的成功或失败结果；
 * 持续观察的数据使用 Flow，避免回调线程与生命周期脱节。
 */
class BabyRepository(private val database: BabyDatabase) {
    constructor(context: Context) : this(BabyDatabase.getInstance(context))

    /** 所有计时命令和未完成查询集中在同一 Room 边界，调用方不再直接写 MMKV 会话。 */
    val timers: RecordTimerRepository by lazy { RecordTimerRepository(database) }

    private val babyInfoDao = database.babyInfoDao()
    private val feedingRecordDao = database.feedingRecordDao()
    private val sleepRecordDao = database.sleepRecordDao()
    private val childDailyRecordDao = database.childDailyRecordDao()
    private val eventRecordDao = database.eventRecordDao()
    private val recordWriteReceiptDao = database.recordWriteReceiptDao()

    // ==================== BabyInfo ====================

    suspend fun insertBabyInfo(babyInfo: BabyInfo): Int {
        // 回填 Room 生成的主键，保证随后写入的记录不会继续引用 babyId=0。
        return babyInfoDao.insertBabyInfo(babyInfo).toInt().also { babyInfo.babyId = it }
    }

    /** stableId 随新建草稿保存；回执与宝宝同事务提交，重试保留后来编辑，删除后不复活。 */
    suspend fun insertBabyInfoOnce(babyInfo: BabyInfo): Int {
        val snapshot = babyInfo.copy(babyId = 0)
        val normalizedId = runCatching { UUID.fromString(snapshot.stableId).toString() }.getOrNull()
        if (normalizedId == null || normalizedId != snapshot.stableId ||
            !snapshot.birthWeight.isFinite() || !snapshot.birthHeight.isFinite()) {
            throw BabyWriteException(BabyWriteFailure.INVALID_REQUEST)
        }
        // 业务摘要刻意不含身份；历史 UUID 的大小写兼容不能改变既有回执摘要协议。
        val payloadHash = RecordWritePayload.baby(snapshot)
        return database.withTransaction {
            val receipts = database.babyCreationReceiptDao()
            val receipt = receipts.getReceipt(normalizedId)
            if (receipt != null) {
                if (receipt.payloadHash != payloadHash) throw BabyWriteException(BabyWriteFailure.CONTENT_CONFLICT)
                val saved = babyInfoDao.getBabyInfo(receipt.babyId)
                // 只承认回执原本地 ID 下的完整 UUID；删除或覆盖后的同 UUID 新行不能重绑。
                if (saved == null || !saved.stableId.equals(normalizedId, ignoreCase = true)) {
                    throw BabyWriteException(BabyWriteFailure.BABY_DELETED)
                }
                return@withTransaction receipt.babyId
            }
            // 已通过备份恢复的相同身份也不能重复创建；首次绑定时只接受完全相同的业务内容。
            val existing = babyInfoDao.findBabyByStableId(normalizedId) ?: run {
                // 历史备份允许大写 UUID；没有精确目标时只绑定唯一候选，不改写其原始身份。
                val candidates = babyInfoDao.findBabiesByStableIdIgnoringCase(normalizedId)
                if (candidates.size > 1) throw BabyWriteException(BabyWriteFailure.CONTENT_CONFLICT)
                candidates.singleOrNull()
            }
            if (existing != null && RecordWritePayload.baby(existing) != payloadHash) {
                throw BabyWriteException(BabyWriteFailure.CONTENT_CONFLICT)
            }
            val id = existing?.babyId ?: babyInfoDao.insertBabyInfo(snapshot).toInt()
            receipts.insertReceipt(BabyCreationReceipt(normalizedId, id, payloadHash))
            id
        }
    }

    /** 身份核验与更新同事务，删除/覆盖期间的旧编辑不能将零行更新当作成功。 */
    suspend fun updateBabyInfoIfPresent(babyInfo: BabyInfo): Boolean {
        val snapshot = babyInfo.copy()
        return database.withTransaction {
            if (babyInfoDao.getBabyInfo(snapshot.babyId)?.stableId != snapshot.stableId) return@withTransaction false
            babyInfoDao.updateBabyInfo(snapshot)
            true
        }
    }

    suspend fun updateBabyInfo(babyInfo: BabyInfo) = babyInfoDao.updateBabyInfo(babyInfo)

    suspend fun deleteBabyInfo(babyInfo: BabyInfo) = babyInfoDao.deleteBabyInfo(babyInfo)

    suspend fun getBabyInfo(babyId: Int): BabyInfo? = babyInfoDao.getBabyInfo(babyId)

    suspend fun getAllBabyInfo(): List<BabyInfo> = babyInfoDao.getAllBabyInfo()

    fun observeAllBabyInfo(): Flow<List<BabyInfo>> = babyInfoDao.observeAllBabyInfo()

    // ==================== FeedingRecord ====================

    suspend fun insertFeedingRecord(feedingRecord: FeedingRecord) {
        feedingRecordDao.insertFeedingRecord(feedingRecord)
    }

    /** 同一草稿的跨进程重试只提交一次；自增 ID 和创建时间不参与业务内容比较。 */
    suspend fun insertFeedingRecordOnce(record: FeedingRecord, requestId: String): Int {
        val snapshot = TimerRecordNormalizer.feeding(record.copy(feedingId = 0), null, TimerClock.snapshot())
        return insertRecordOnce(
            requestId, snapshot.babyId, RecordWritePayload.FEEDING, RecordWritePayload.feeding(snapshot),
            insert = { feedingRecordDao.insertFeedingRecord(snapshot) },
            recordOwner = { feedingRecordDao.getFeedingRecordById(it)?.babyId }
        )
    }

    /** 会话清理恢复只确认已有提交，不把未提交会话变成新记录。 */
    suspend fun findCommittedFeedingRecord(record: FeedingRecord, requestId: String): Int? {
        val snapshot = TimerRecordNormalizer.feeding(record.copy(), null, TimerClock.snapshot())
        return findCommittedRecord(requestId, snapshot.babyId, RecordWritePayload.FEEDING,
            RecordWritePayload.feeding(snapshot)) { feedingRecordDao.getFeedingRecordById(it)?.babyId }
    }

    suspend fun updateFeedingRecord(feedingRecord: FeedingRecord) {
        val snapshot = feedingRecord.copy()
        database.withTransaction {
            // 覆盖/删除可能先于排队的编辑提交；零行更新不能伪报保存成功，也不能迁移宝宝归属。
            val existing = feedingRecordDao.getFeedingRecordById(snapshot.feedingId)
            if (existing?.babyId != snapshot.babyId) {
                throw RecordWriteException(RecordWriteFailure.RECORD_DELETED)
            }
            feedingRecordDao.updateFeedingRecord(TimerRecordNormalizer.feeding(snapshot, existing, TimerClock.snapshot()))
        }
    }

    suspend fun deleteFeedingRecord(feedingRecord: FeedingRecord) =
        feedingRecordDao.deleteFeedingRecord(feedingRecord)

    suspend fun deleteFeedingRecordById(feedingId: Int) =
        feedingRecordDao.deleteFeedingRecordById(feedingId)

    suspend fun getAllFeedingRecords(babyId: Int): List<FeedingRecord> =
        feedingRecordDao.getAllFeedingRecords(babyId)

    suspend fun getFeedingRecordsForDay(
        babyId: Int,
        startOfDay: Long,
        endOfDay: Long
    ): List<FeedingRecord> = feedingRecordDao.getFeedingRecordsForDay(babyId, startOfDay, endOfDay)

    suspend fun getFeedingRecordsIntersectingDay(
        babyId: Int,
        startOfDay: Long,
        endOfDay: Long
    ): List<FeedingRecord> = feedingRecordDao.getFeedingRecordsIntersectingDay(babyId, startOfDay, endOfDay)

    suspend fun getFeedingRecordsIntersectingRange(
        babyId: Int,
        startTime: Long,
        endTime: Long
    ): List<FeedingRecord> = feedingRecordDao.getFeedingRecordsIntersectingRange(babyId, startTime, endTime)

    suspend fun getFeedingRecordsBetween(
        babyId: Int,
        startTime: Long,
        endTime: Long
    ): List<FeedingRecord> = feedingRecordDao.getFeedingRecordsBetween(babyId, startTime, endTime)

    suspend fun getLastFeedingRecord(babyId: Int): FeedingRecord? =
        (listOfNotNull(feedingRecordDao.getLastFeedingRecord(babyId)) +
            feedingRecordDao.getRecentUnmeasuredFeedings(babyId, 1, FeedingType.entries.map { it.type }))
            .maxWithOrNull(compareBy<FeedingRecord> { com.zero.babydata.domain.RecordReferenceTime.feeding(it) }
                .thenBy { it.feedingStart }.thenBy { it.feedingId })

    suspend fun getRecentFeedings(babyId: Int, limit: Int): List<FeedingRecord> =
        feedingRecordDao.getRecentFeedings(babyId, limit)

    /** 预测只使用最近已完成的奶类记录，限制在数据库筛选之后生效。 */
    suspend fun getRecentCompletedMilkFeedings(babyId: Int, limit: Int): List<FeedingRecord> {
        if (limit <= 0) return emptyList()
        val milkTypes = listOf(FeedingType.BREAST.type, FeedingType.FORMULA.type, FeedingType.MIXED.type)
        return (feedingRecordDao.getRecentCompletedMilkFeedings(babyId, limit, milkTypes) +
            feedingRecordDao.getRecentUnmeasuredFeedings(babyId, limit, milkTypes))
            .sortedWith(compareByDescending<FeedingRecord> { com.zero.babydata.domain.RecordReferenceTime.feeding(it) }
                .thenByDescending { it.feedingStart }.thenByDescending { it.feedingId }).take(limit)
    }

    suspend fun getFeedingRecordById(feedingId: Int): FeedingRecord? =
        feedingRecordDao.getFeedingRecordById(feedingId)

    // ==================== SleepRecord ====================

    suspend fun insertSleepRecord(sleepRecord: SleepRecord) {
        sleepRecordDao.insertSleepRecord(sleepRecord)
    }

    suspend fun insertSleepRecordOnce(record: SleepRecord, requestId: String): Int {
        val snapshot = TimerRecordNormalizer.sleep(record.copy(sleepId = 0), null, TimerClock.snapshot())
        return insertRecordOnce(
            requestId, snapshot.babyId, RecordWritePayload.SLEEP, RecordWritePayload.sleep(snapshot),
            insert = { sleepRecordDao.insertSleepRecord(snapshot) },
            recordOwner = { sleepRecordDao.getSleepRecordById(it)?.babyId }
        )
    }

    suspend fun findCommittedSleepRecord(record: SleepRecord, requestId: String): Int? {
        val snapshot = TimerRecordNormalizer.sleep(record.copy(), null, TimerClock.snapshot())
        return findCommittedRecord(requestId, snapshot.babyId, RecordWritePayload.SLEEP,
            RecordWritePayload.sleep(snapshot)) { sleepRecordDao.getSleepRecordById(it)?.babyId }
    }

    suspend fun updateSleepRecord(sleepRecord: SleepRecord) {
        val snapshot = sleepRecord.copy()
        database.withTransaction {
            val existing = sleepRecordDao.getSleepRecordById(snapshot.sleepId)
            if (existing?.babyId != snapshot.babyId) {
                throw RecordWriteException(RecordWriteFailure.RECORD_DELETED)
            }
            sleepRecordDao.updateSleepRecord(TimerRecordNormalizer.sleep(snapshot, existing, TimerClock.snapshot()))
        }
    }

    suspend fun deleteSleepRecord(sleepRecord: SleepRecord) =
        sleepRecordDao.deleteSleepRecord(sleepRecord)

    suspend fun deleteSleepRecordById(sleepId: Int) =
        sleepRecordDao.deleteSleepRecordById(sleepId)

    suspend fun getAllSleepRecords(babyId: Int): List<SleepRecord> =
        sleepRecordDao.getAllSleepRecords(babyId)

    suspend fun getSleepRecordsForDay(
        babyId: Int,
        startOfDay: Long,
        endOfDay: Long
    ): List<SleepRecord> = sleepRecordDao.getSleepRecordsForDay(babyId, startOfDay, endOfDay)

    suspend fun getSleepRecordsIntersectingDay(
        babyId: Int,
        startOfDay: Long,
        endOfDay: Long
    ): List<SleepRecord> = sleepRecordDao.getSleepRecordsIntersectingDay(babyId, startOfDay, endOfDay)

    suspend fun getSleepRecordsIntersectingRange(
        babyId: Int,
        startTime: Long,
        endTime: Long
    ): List<SleepRecord> = sleepRecordDao.getSleepRecordsIntersectingRange(babyId, startTime, endTime)

    suspend fun getSleepRecordsBetween(
        babyId: Int,
        startTime: Long,
        endTime: Long
    ): List<SleepRecord> = sleepRecordDao.getSleepRecordsBetween(babyId, startTime, endTime)

    suspend fun getLastSleepRecord(babyId: Int): SleepRecord? =
        sleepRecordDao.getLastSleepRecord(babyId)

    suspend fun getRecentSleeps(babyId: Int, limit: Int): List<SleepRecord> =
        sleepRecordDao.getRecentSleeps(babyId, limit)

    suspend fun getRecentCompletedSleeps(babyId: Int, limit: Int): List<SleepRecord> =
        sleepRecordDao.getRecentCompletedSleeps(babyId, limit)

    suspend fun getSleepRecordById(sleepId: Int): SleepRecord? =
        sleepRecordDao.getSleepRecordById(sleepId)

    // ==================== ChildDailyRecord ====================

    suspend fun insertChildDailyRecord(record: ChildDailyRecord) =
        childDailyRecordDao.insertChildDailyRecord(record)

    suspend fun updateChildDailyRecord(record: ChildDailyRecord) =
        childDailyRecordDao.updateChildDailyRecord(record)

    suspend fun deleteChildDailyRecord(record: ChildDailyRecord) =
        childDailyRecordDao.deleteChildDailyRecord(record)

    suspend fun getChildDailyRecordByDate(babyId: Int, recordDate: Long): ChildDailyRecord? =
        childDailyRecordDao.getRecordByDate(babyId, recordDate)

    suspend fun getAllChildDailyRecords(babyId: Int): List<ChildDailyRecord> =
        childDailyRecordDao.getAllRecordsByBabyId(babyId)

    fun observeAllChildDailyRecords(babyId: Int): Flow<List<ChildDailyRecord>> =
        childDailyRecordDao.observeAllRecordsByBabyId(babyId)

    suspend fun updateOrInsertChildDailyRecord(
        babyId: Int,
        recordDate: Long,
        weight: Float? = null,
        height: Float? = null,
        headCircumference: Float? = null,
        pic: String? = null,
        extra: String? = null
    ) = childDailyRecordDao.updateOrInsert(
        babyId = babyId,
        recordDate = recordDate,
        weight = weight,
        height = height,
        headCircumference = headCircumference,
        pic = pic,
        extra = extra
    )

    // ==================== EventRecord ====================

    suspend fun insertEventRecord(record: EventRecord) {
        eventRecordDao.insertEventRecord(record)
    }

    suspend fun insertEventRecordOnce(record: EventRecord, requestId: String): Int {
        val snapshot = TimerRecordNormalizer.event(record.copy(eventId = 0), null, TimerClock.snapshot())
        return insertRecordOnce(
            requestId, snapshot.babyId, RecordWritePayload.EVENT, RecordWritePayload.event(snapshot),
            insert = { eventRecordDao.insertEventRecord(snapshot) },
            recordOwner = { eventRecordDao.getEventRecordById(it)?.babyId }
        )
    }

    /** 记录和回执一起提交或回滚；只保存摘要，避免重复保存敏感正文。 */
    private suspend fun insertRecordOnce(
        requestId: String,
        babyId: Int,
        recordType: Int,
        payloadHash: String,
        insert: suspend () -> Long,
        recordOwner: suspend (Int) -> Int?
    ): Int {
        val normalizedRequestId = RecordWritePayload.requestId(requestId, babyId)
        return database.withTransaction {
            // 覆盖导入/删除宝宝会使旧草稿的本机 ID 失效，不按姓名或备份身份转移写入目标。
            if (babyInfoDao.getBabyInfo(babyId) == null) {
                throw RecordWriteException(RecordWriteFailure.BABY_NOT_FOUND)
            }
            val receipt = recordWriteReceiptDao.getReceipt(normalizedRequestId)
            if (receipt != null) {
                return@withTransaction validateCommittedRecord(receipt, babyId, recordType, payloadHash, recordOwner)
            }
            val recordId = insert().toInt()
            recordWriteReceiptDao.insertReceipt(RecordWriteReceipt(
                requestId = normalizedRequestId,
                babyId = babyId,
                recordType = recordType,
                recordId = recordId,
                payloadHash = payloadHash
            ))
            recordId
        }
    }

    /** 损坏会话缺少冻结正文时只查询请求是否已提交，不向界面暴露回执摘要或业务内容。 */
    suspend fun hasRecordWriteReceipt(requestId: String): Boolean = database.withTransaction {
        // 此查询尚无宝宝信息；使用既有 UUID 校验，正数仅满足该工具的独立参数前置条件。
        val normalized = RecordWritePayload.requestId(requestId, babyId = 1)
        recordWriteReceiptDao.getReceipt(normalized) != null
    }

    private suspend fun findCommittedRecord(
        requestId: String,
        babyId: Int,
        recordType: Int,
        payloadHash: String,
        recordOwner: suspend (Int) -> Int?
    ): Int? = database.withTransaction {
        val normalized = RecordWritePayload.requestId(requestId, babyId)
        val receipt = recordWriteReceiptDao.getReceipt(normalized) ?: return@withTransaction null
        if (babyInfoDao.getBabyInfo(babyId) == null) {
            throw RecordWriteException(RecordWriteFailure.BABY_NOT_FOUND)
        }
        validateCommittedRecord(receipt, babyId, recordType, payloadHash, recordOwner)
    }

    /** 查询与重复插入采用同一核验次序；确认原请求成功时保留后来主动编辑的内容。 */
    private suspend fun validateCommittedRecord(
        receipt: RecordWriteReceipt,
        babyId: Int,
        recordType: Int,
        payloadHash: String,
        recordOwner: suspend (Int) -> Int?
    ): Int {
        if (receipt.babyId != babyId || receipt.recordType != recordType) {
            throw RecordWriteException(RecordWriteFailure.CONTENT_CONFLICT)
        }
        val owner = recordOwner(receipt.recordId)
            ?: throw RecordWriteException(RecordWriteFailure.RECORD_DELETED)
        if (owner != babyId || receipt.payloadHash != payloadHash) {
            throw RecordWriteException(RecordWriteFailure.CONTENT_CONFLICT)
        }
        return receipt.recordId
    }

    suspend fun updateEventRecord(record: EventRecord) {
        val snapshot = record.copy()
        database.withTransaction {
            val existing = eventRecordDao.getEventRecordById(snapshot.eventId)
            if (existing?.babyId != snapshot.babyId) {
                throw RecordWriteException(RecordWriteFailure.RECORD_DELETED)
            }
            eventRecordDao.updateEventRecord(TimerRecordNormalizer.event(snapshot, existing, TimerClock.snapshot()))
        }
    }

    suspend fun deleteEventRecord(record: EventRecord) = eventRecordDao.deleteEventRecord(record)

    suspend fun getAllEventRecords(babyId: Int): List<EventRecord> =
        eventRecordDao.getAllEventRecord(babyId)

    suspend fun getEventRecordsByType(babyId: Int, type: Int): List<EventRecord> =
        eventRecordDao.getAllEventRecordByType(babyId, type)

    suspend fun getLatestEventRecordsByType(
        babyId: Int,
        type: Int,
        limit: Int
    ): List<EventRecord> = eventRecordDao.getLatestEventRecordsByType(babyId, type, limit)

    suspend fun getEventRecordsForDay(
        babyId: Int,
        startOfDay: Long,
        endOfDay: Long
    ): List<EventRecord> = eventRecordDao.getEventRecordsForDay(babyId, startOfDay, endOfDay)

    suspend fun getEventRecordsBetween(
        babyId: Int,
        startTime: Long,
        endTime: Long
    ): List<EventRecord> = eventRecordDao.getEventRecordsBetween(babyId, startTime, endTime)

    suspend fun deleteEventRecordById(eventId: Int) =
        eventRecordDao.deleteEventRecordById(eventId)

    suspend fun getEventRecordById(eventId: Int): EventRecord? =
        eventRecordDao.getEventRecordById(eventId)

    // ==================== 统计页面相关查询 ====================

    /** 获取指定月份存在任意生活记录的日期集合。 */
    suspend fun getDatesWithRecords(babyId: Int, yearMonth: YearMonth, zone: ZoneId = ZoneId.systemDefault()): Set<LocalDate> {
        val startOfMonth = yearMonth.atDay(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        val endOfMonth = yearMonth.atEndOfMonth()
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli() - 1

        val feedingSpans = feedingRecordDao.getFeedingTimeSpans(babyId, startOfMonth, endOfMonth)
        val sleepSpans = sleepRecordDao.getSleepTimeSpans(babyId, startOfMonth, endOfMonth)
        val eventSpans = eventRecordDao.getEventTimeSpans(babyId, startOfMonth, endOfMonth)
        val context = currentCoroutineContext()

        return buildSet {
            fun addSpan(span: RecordTimeSpan) {
                context.ensureActive()
                val metadata = TimerMetadataCodec.decode(span.timerData)
                if (metadata?.basis == DurationBasis.SEGMENTED) {
                    // 次数与编辑入口仍归原开始日，即使首段为零；其他日期只沿有效段展开。
                    if (span.startTime in startOfMonth..endOfMonth)
                        add(Instant.ofEpochMilli(span.startTime).atZone(zone).toLocalDate())
                    // 整天处于暂停/夜醒时不点亮日历；只沿实际有效片段标记覆盖日期。
                    metadata.segments.filter { it.durationMs > 0L }.forEach { segment ->
                        addSpan(RecordTimeSpan(segment.startAt, requireNotNull(segment.endAt)))
                    }
                    return
                }
                // 旧未完成或倒置记录只标记开始日，仍能进入编辑，不向后扩散成持续事件。
                if (span.startTime in startOfMonth..endOfMonth) {
                    add(Instant.ofEpochMilli(span.startTime).atZone(zone).toLocalDate())
                }
                if (span.startTime <= 0L || span.endTime <= span.startTime) return
                val first = maxOf(span.startTime, startOfMonth)
                val last = minOf(span.endTime - 1L, endOfMonth)
                if (first > last) return
                var date = Instant.ofEpochMilli(first).atZone(zone).toLocalDate()
                val lastDate = Instant.ofEpochMilli(last).atZone(zone).toLocalDate()
                // 按本地日期递进并截在本月内，夏令时日不是固定 24 小时。
                while (date <= lastDate) {
                    add(date)
                    date = date.plusDays(1)
                }
            }
            feedingSpans.forEach(::addSpan)
            sleepSpans.forEach(::addSpan)
            eventSpans.forEach(::addSpan)
        }
    }

    data class DaySummaryData(
        val feedingCount: Int = 0,
        val feedingTotalMinutes: Int = 0,
        val feedingTotalMl: Int = 0,
        val sleepCount: Int = 0,
        val sleepTotalMinutes: Int = 0,
        val diaperWetCount: Int = 0,
        val diaperDirtyCount: Int = 0,
        val diaperMixedCount: Int = 0,
        val diaperDryCount: Int = 0,
        val otherEventCount: Int = 0
    )

    data class StatisticsPeriodRecords(
        val feedings: List<FeedingRecord>, val sleeps: List<SleepRecord>,
        val feedingOverlaps: List<FeedingRecord>, val sleepOverlaps: List<SleepRecord>,
        val events: List<EventRecord>
    )

    data class StatisticsSnapshot(
        val baby: BabyInfo?, val summary: DaySummaryData, val timeline: List<TimelineRecord>,
        val growth: Map<Int, List<EventRecord>>,
        val periods: Map<LongRange, StatisticsPeriodRecords>
    )

    /** 一次读取整页的数据库事实，避免保存发生在分区查询之间造成摘要与时间轴不一致。
     * 事务只包含查询和既有当日聚合；WHO 文件解析、图表计算与文案映射在事务外执行。
     */
    suspend fun getStatisticsSnapshot(
        babyId: Int, date: LocalDate, zone: ZoneId, ranges: List<LongRange>
    ): StatisticsSnapshot = database.withTransaction {
        StatisticsSnapshot(
            baby = getBabyInfo(babyId), summary = getDaySummary(babyId, date, zone),
            timeline = getTimelineRecords(babyId, date, zone),
            growth = listOf(EventType.GROWTH_WEIGHT, EventType.GROWTH_HEIGHT, EventType.GROWTH_HEAD)
                .associateWith { getEventRecordsByType(babyId, it) },
            periods = ranges.distinct().associateWith { range ->
                StatisticsPeriodRecords(
                    getFeedingRecordsBetween(babyId, range.first, range.last),
                    getSleepRecordsBetween(babyId, range.first, range.last),
                    getFeedingRecordsIntersectingRange(babyId, range.first, range.last),
                    getSleepRecordsIntersectingRange(babyId, range.first, range.last),
                    getEventRecordsBetween(babyId, range.first, range.last)
                )
            }
        )
    }

    /** 获取当日喂养、睡眠、排泄与其他事件摘要。 */
    suspend fun getDaySummary(babyId: Int, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): DaySummaryData {
        val startOfDay = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val endOfDay = date.plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli() - 1

        val feedings = feedingRecordDao.getFeedingRecordsForDay(babyId, startOfDay, endOfDay)
        val sleeps = sleepRecordDao.getSleepRecordsForDay(babyId, startOfDay, endOfDay)
        // 次数按开始日计入，时长按当天重叠区间分摊，避免跨夜睡眠全部归到前一天。
        val overlappingFeedings = feedingRecordDao.getFeedingRecordsIntersectingDay(babyId, startOfDay, endOfDay)
        val overlappingSleeps = sleepRecordDao.getSleepRecordsIntersectingDay(babyId, startOfDay, endOfDay)
        val events = eventRecordDao.getEventRecordsForDay(babyId, startOfDay, endOfDay)

        var diaperWetCount = 0
        var diaperDirtyCount = 0
        var diaperMixedCount = 0
        var diaperDryCount = 0
        var otherEventCount = 0
        events.forEach { event ->
            when (event.type) {
                EventType.DIAPER_WET -> diaperWetCount++
                EventType.DIAPER_DIRTY -> diaperDirtyCount++
                EventType.DIAPER_MIXED -> diaperMixedCount++
                EventType.DIAPER_DRY -> diaperDryCount++
                else -> otherEventCount++
            }
        }

        return DaySummaryData(
            feedingCount = feedings.size,
            feedingTotalMinutes = TimeUnit.MILLISECONDS.toMinutes(overlappingFeedings.sumOf {
                RecordDurationCalculator.feedingDuration(it, startOfDay, endOfDay) ?: 0L
            }).toInt(),
            feedingTotalMl = feedings
                .filter {
                    it.feedingType == FeedingType.FORMULA.type ||
                        it.feedingType == FeedingType.MIXED.type
                }
                .sumOf { it.feedingAmount ?: 0 },
            sleepCount = sleeps.size,
            sleepTotalMinutes = TimeUnit.MILLISECONDS.toMinutes(overlappingSleeps.sumOf {
                RecordDurationCalculator.sleepDuration(it, startOfDay, endOfDay) ?: 0L
            }).toInt(),
            diaperWetCount = diaperWetCount,
            diaperDirtyCount = diaperDirtyCount,
            diaperMixedCount = diaperMixedCount,
            diaperDryCount = diaperDryCount,
            otherEventCount = otherEventCount
        )
    }

    sealed class TimelineRecord(open val time: Long) {
        data class Feeding(val record: FeedingRecord) : TimelineRecord(record.feedingStart)
        data class Sleep(val record: SleepRecord) : TimelineRecord(record.sleepStart)
        data class Event(val record: EventRecord) : TimelineRecord(record.time)
    }

    /** 返回当天发生或持续至当天的原记录，保留编辑身份和原始起止时间。 */
    suspend fun getTimelineRecords(babyId: Int, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<TimelineRecord> {
        val startOfDay = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val endOfDay = date.plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli() - 1

        val feedings = feedingRecordDao.getFeedingRecordsCoveringDay(babyId, startOfDay, endOfDay)
            .filter { it.feedingStart in startOfDay..endOfDay || (RecordDurationCalculator.feedingDuration(it, startOfDay, endOfDay) ?: 0L) > 0L }
            .map { TimelineRecord.Feeding(it) }
        val sleeps = sleepRecordDao.getSleepRecordsCoveringDay(babyId, startOfDay, endOfDay)
            .filter { it.sleepStart in startOfDay..endOfDay || (RecordDurationCalculator.sleepDuration(it, startOfDay, endOfDay) ?: 0L) > 0L }
            .map { TimelineRecord.Sleep(it) }
        val events = eventRecordDao.getEventRecordsCoveringDay(babyId, startOfDay, endOfDay)
            .filter { it.time in startOfDay..endOfDay || (RecordDurationCalculator.eventDuration(it, startOfDay, endOfDay) ?: 0L) > 0L }
            .map { TimelineRecord.Event(it) }
        return (feedings + sleeps + events).sortedByDescending { it.time }
    }
}

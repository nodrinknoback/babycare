package com.zero.babycare.home.prediction

import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.FeedingType
import com.zero.babydata.entity.SleepRecord
import com.zero.babydata.domain.TimerStatus

/** 预测与提醒共用有效样本，避免去重依据与实际预测的最近一次记录不同。 */
internal object PredictionRecordSelector {

    fun completedMilkFeedings(records: List<FeedingRecord>): List<FeedingRecord> {
        return records.filter {
            it.timerStatus == TimerStatus.COMPLETED.code && it.feedingStart > 0L &&
                feedingEnd(it) >= it.feedingStart &&
                when (it.getFeedingTypeEnum()) {
                    FeedingType.BREAST, FeedingType.FORMULA, FeedingType.MIXED -> true
                    FeedingType.SOLID_FOOD, FeedingType.OTHER -> false
                }
        }.sortedBy { it.feedingStart }
    }

    fun completedSleeps(records: List<SleepRecord>): List<SleepRecord> {
        return records.filter {
            it.timerStatus == TimerStatus.COMPLETED.code && it.sleepStart > 0L && sleepEnd(it) > it.sleepStart
        }
            .sortedBy { it.sleepStart }
    }

    fun feedingEnd(record: FeedingRecord): Long = com.zero.babydata.domain.RecordReferenceTime.feeding(record)
    fun sleepEnd(record: SleepRecord): Long = com.zero.babydata.domain.RecordReferenceTime.sleep(record)
}

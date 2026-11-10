package com.zero.babydata.domain

import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord

/** 展示、排序、预测与提醒共享同一时间语义；未计时喂养以发生时刻为准。 */
object RecordReferenceTime {
    fun feeding(record: FeedingRecord): Long = effectiveEnd(
        record.timerData, record.feedingStart, record.feedingEnd, allowUnmeasured = true
    )

    fun sleep(record: SleepRecord): Long = effectiveEnd(
        record.timerData, record.sleepStart, record.sleepEnd, allowUnmeasured = false
    )

    private fun effectiveEnd(json: String?, start: Long, end: Long, allowUnmeasured: Boolean): Long {
        if (start <= 0L) return 0L
        if (json == null) return end.takeIf { it > start } ?: 0L
        return runCatching {
            val timing = requireNotNull(TimerMetadataCodec.decode(json))
            TimerMetadataCodec.validate(timing, completed = true)
            when (timing.basis) {
                DurationBasis.UNMEASURED -> if (allowUnmeasured) start else 0L
                DurationBasis.SEGMENTED -> TimerCalculations.lastEffectiveEnd(timing) ?: 0L
                else -> end.takeIf { it > start } ?: 0L
            }
        }.getOrDefault(0L)
    }
}

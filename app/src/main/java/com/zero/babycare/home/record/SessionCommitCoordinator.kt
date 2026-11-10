package com.zero.babycare.home.record

import com.zero.babycare.home.OngoingRecordManager
import com.zero.babycare.home.OngoingRecordManager.OngoingSession
import com.zero.babycare.home.OngoingRecordManager.RecordType
import com.zero.babydata.domain.*
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord
import com.zero.babydata.room.BabyRepository
import com.zero.common.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 完整计时记录编辑任务：冻结输入、停止、校验、确认与完成均由同一生命周期协调。
 * 停止是独立持久化事实；取消确认或写入失败均保留草稿，正式写入仍保持幂等。 */
internal class SessionCommitCoordinator(
    private val scope: CoroutineScope,
    private val repository: () -> BabyRepository,
    private val type: RecordType,
    private val timer: PersistentTimerEditor = PersistentTimerEditor(scope, repository,
        if (type == RecordType.FEEDING) TimerRecordKind.FEEDING else TimerRecordKind.SLEEP)
) {
    val mutation = RecordMutationController(scope)

    /** View 提交一次输入快照，此后停止与确认不会再次读取 View 或修改草稿归属。 */
    fun submitFeeding(record: FeedingRecord, target: RecordMutationController.Target, sessionId: String?) {
        val frozen = record.copy()
        mutation.submit(target, RecordMutationController.Kind.SAVE, R.string.save_failed) {
            require(frozen.babyId == target.babyId && (target.recordId == null || frozen.feedingId == target.recordId))
            val stopped = if (sessionId != null) requireNotNull(timer.stopForSave(sessionId)) else null
            if (stopped?.metadata?.requiresReview == true) throw TimerOperationException(TimerFailure.NEEDS_REVIEW)
            val prepared = stopped?.let {
                frozen.copy(feedingStart = it.startAt, feedingEnd = it.endAt ?: 0L, feedingDuration = it.durationMs,
                    feedingDurationBreastLeft = TimerCalculations.partDuration(it.metadata, TimerPart.LEFT),
                    feedingDurationBreastRight = TimerCalculations.partDuration(it.metadata, TimerPart.RIGHT),
                    timerData = TimerMetadataCodec.encode(it.metadata))
            } ?: frozen
            val unmeasured = sessionId == null && prepared.feedingType != com.zero.babydata.entity.FeedingType.BREAST.type &&
                prepared.feedingEnd == 0L && (target.recordId == null ||
                    TimerMetadataCodec.decode(prepared.timerData)?.basis == DurationBasis.UNMEASURED)
            validateTime(prepared.feedingStart, prepared.feedingEnd, unmeasured)
            if (!unmeasured && stopped == null) {
                val preview = CompletedTimerDurationPreview.calculate(prepared.timerData,
                    prepared.feedingStart, prepared.feedingEnd)?.getOrThrow()
                prepared.feedingDuration = preview?.durationMs
                    ?: TimerSessionPolicy.calculateSavedDuration(TimerMode.FEEDING, prepared.feedingStart,
                        prepared.feedingEnd, prepared.feedingDuration).valueMs
                // 分段记录的左右时长来自调整后的片段，与数据层最终投影保持一致。
                if (preview != null) {
                    prepared.feedingDurationBreastLeft = preview.leftMs
                    prepared.feedingDurationBreastRight = preview.rightMs
                }
            }
            if (prepared.feedingType == com.zero.babydata.entity.FeedingType.BREAST.type &&
                prepared.feedingDurationBreastLeft + prepared.feedingDurationBreastRight > prepared.feedingDuration) {
                throw RecordMutationController.ValidationFailure(R.string.breast_duration_exceed,
                    if (prepared.feedingDurationBreastRight > 0L) RecordMutationController.Field.RIGHT_DURATION else RecordMutationController.Field.LEFT_DURATION)
            }
            if (!unmeasured) {
                if (prepared.feedingDuration > MAX_FEEDING_DURATION_MS)
                    mutation.confirm(target, prepared.feedingStart, prepared.feedingEnd, prepared.feedingDuration, true)
                mutation.confirm(target, prepared.feedingStart, prepared.feedingEnd, prepared.feedingDuration, false)
            }
            if (target.recordId != null) repository().updateFeedingRecord(prepared)
            else saveFeeding(prepared, target.draftId, sessionId)
            com.zero.babycare.home.BabyRecordChangeBus.notifyChanged(target.babyId)
        }
    }

    fun submitSleep(record: SleepRecord, target: RecordMutationController.Target, sessionId: String?) {
        val frozen = record.copy()
        mutation.submit(target, RecordMutationController.Kind.SAVE, R.string.save_failed) {
            require(frozen.babyId == target.babyId && (target.recordId == null || frozen.sleepId == target.recordId))
            val stopped = if (sessionId != null) requireNotNull(timer.stopForSave(sessionId)) else null
            if (stopped?.metadata?.requiresReview == true) throw TimerOperationException(TimerFailure.NEEDS_REVIEW)
            val prepared = stopped?.let {
                frozen.copy(sleepStart = it.startAt, sleepEnd = it.endAt ?: 0L, sleepDuration = it.durationMs,
                    timerData = TimerMetadataCodec.encode(it.metadata))
            } ?: frozen
            validateTime(prepared.sleepStart, prepared.sleepEnd, false)
            if (stopped == null) prepared.sleepDuration = CompletedTimerDurationPreview.calculate(prepared.timerData,
                prepared.sleepStart, prepared.sleepEnd)?.getOrThrow()?.durationMs
                ?: TimerSessionPolicy.calculateSavedDuration(TimerMode.SLEEP, prepared.sleepStart,
                    prepared.sleepEnd, prepared.sleepDuration).valueMs
            if (target.recordId != null) repository().updateSleepRecord(prepared)
            else saveSleep(prepared, target.draftId, sessionId)
            com.zero.babycare.home.BabyRecordChangeBus.notifyChanged(target.babyId)
        }
    }

    /** 表单语法由输入控件提示；跨字段与时间约束在任务边界统一校验。 */
    private fun validateTime(start: Long, end: Long, unmeasured: Boolean) {
        val now = System.currentTimeMillis()
        fun invalid(message: Int, field: RecordMutationController.Field): Nothing =
            throw RecordMutationController.ValidationFailure(message, field)
        if (start <= 0L) invalid(R.string.start_time_required, RecordMutationController.Field.START)
        if (start > now) invalid(R.string.time_cannot_be_future, RecordMutationController.Field.START)
        if (unmeasured) return
        if (end <= 0L) invalid(R.string.end_time_required, RecordMutationController.Field.END)
        if (end > now) invalid(R.string.time_cannot_be_future, RecordMutationController.Field.END)
        if (end <= start) invalid(R.string.end_time_must_after_start, RecordMutationController.Field.END)
    }

    private companion object { const val MAX_FEEDING_DURATION_MS = 120L * 60L * 1000L }

    data class Recovery(
        val target: RecordMutationController.Target,
        val loading: Boolean,
        val session: OngoingSession? = null,
        val committed: Boolean = false,
        val errorRes: Int? = null
    )
    private val mutableRecovery = MutableStateFlow<Recovery?>(null)
    val recovery = mutableRecovery.asStateFlow()
    private val mutableCommitting = MutableStateFlow<Set<String>>(emptySet())
    val committingSessions = mutableCommitting.asStateFlow()
    private var recoveryJob: Job? = null

    fun recover(target: RecordMutationController.Target, expectedSessionId: String? = null) {
        recoveryJob?.cancel()
        mutableRecovery.value = Recovery(target, true)
        recoveryJob = scope.launch {
            try {
                val snapshot = timer.recoverSession(target.babyId, expectedSessionId)
                val completed = snapshot?.status == com.zero.babydata.domain.TimerStatus.COMPLETED
                mutableRecovery.value = Recovery(target, false,
                    snapshot?.takeUnless { completed }?.let(OngoingRecordManager::fromSnapshot), completed)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableRecovery.value = Recovery(target, false, errorRes = R.string.data_load_failed) }
        }
    }

    suspend fun saveFeeding(record: FeedingRecord, draftId: String, sessionId: String?) {
        if (sessionId == null) { repository().insertFeedingRecordOnce(record, draftId); return }
        committing(sessionId) {
            timer.stopForSave(sessionId)
            timer.finishFeeding(record, draftId, sessionId)
        }
    }
    suspend fun saveSleep(record: SleepRecord, draftId: String, sessionId: String?) {
        if (sessionId == null) { repository().insertSleepRecordOnce(record, draftId); return }
        committing(sessionId) {
            timer.stopForSave(sessionId)
            timer.finishSleep(record, draftId, sessionId)
        }
    }
    private suspend fun committing(sessionId: String, action: suspend () -> Unit) {
        mutableCommitting.value = mutableCommitting.value + sessionId
        try { action() } finally { mutableCommitting.value = mutableCommitting.value - sessionId }
    }
}

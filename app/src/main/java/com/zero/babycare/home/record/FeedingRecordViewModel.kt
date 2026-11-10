package com.zero.babycare.home.record

import androidx.lifecycle.viewModelScope
import com.zero.babycare.home.BabyRecordChangeBus
import com.zero.babydata.entity.FeedingRecord
import com.zero.components.base.vm.BaseViewModel
import com.zero.babydata.domain.BabyDataHelper
import com.zero.babydata.room.BabyRepository
import com.zero.babycare.home.OngoingRecordManager
import com.zero.common.R

class FeedingRecordViewModel(private val injectedRepository: BabyRepository? = null) : BaseViewModel() {
    // 延迟获取默认数据源，让草稿状态恢复独立于数据库初始化，并支持隔离的数据层验证。
    private val repository by lazy { injectedRepository ?: BabyDataHelper.repository }
    internal val timer = PersistentTimerEditor(viewModelScope, { repository }, com.zero.babydata.domain.TimerRecordKind.FEEDING)
    internal val sessions = SessionCommitCoordinator(viewModelScope, { repository }, OngoingRecordManager.RecordType.FEEDING, timer)
    internal val mutation get() = sessions.mutation

    internal val lastRecord = RecordReadController<Int, FeedingRecord>(viewModelScope) { repository.getLastFeedingRecord(it) }
    internal val editedRecord = RecordReadController<EditRecordKey, FeedingRecord>(viewModelScope) { repository.getFeedingRecordById(it.recordId) }

    internal fun save(
        feedingRecord: FeedingRecord,
        target: RecordMutationController.Target,
        sessionId: String? = null,
        originalRecord: FeedingRecord? = null
    ) {
        // 未展示的旧字段不属于本次表单编辑；仅从同宝宝、同记录的原快照保留，不能跨目标复制。
        val original = originalRecord?.takeIf {
            target.recordId != null && it.feedingId == target.recordId &&
                it.feedingId == feedingRecord.feedingId && it.babyId == target.babyId &&
                it.babyId == feedingRecord.babyId
        }
        val frozenRecord = if (original == null) feedingRecord.copy() else feedingRecord.copy(
            babyMood = original.babyMood,
            feedingLocation = original.feedingLocation,
            createdAt = original.createdAt,
            timerData = original.timerData, timerSessionId = original.timerSessionId, timerRevision = original.timerRevision
        )
        sessions.submitFeeding(frozenRecord, target, sessionId)
    }

    /** 删除后统一发出记录变更，界面仅消费有明确草稿归属的结果。 */
    internal fun delete(feedingId: Int, target: RecordMutationController.Target) {
        mutation.submit(target, RecordMutationController.Kind.DELETE, R.string.record_delete_failed) {
            repository.deleteFeedingRecordById(feedingId)
            BabyRecordChangeBus.notifyChanged(target.babyId)
        }
    }

    fun loadLastFeedingRecord(babyId: Int) = lastRecord.load(babyId)

    internal fun loadFeedingRecordById(key: EditRecordKey) = editedRecord.load(key)
}

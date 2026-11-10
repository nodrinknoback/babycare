package com.zero.babycare.home.record

import androidx.lifecycle.viewModelScope
import com.zero.babycare.home.BabyRecordChangeBus
import com.zero.babydata.entity.SleepRecord
import com.zero.components.base.vm.BaseViewModel
import com.zero.babydata.domain.BabyDataHelper
import com.zero.babydata.room.BabyRepository
import com.zero.babycare.home.OngoingRecordManager
import com.zero.common.R

class SleepRecordViewModel(private val injectedRepository: BabyRepository? = null) : BaseViewModel() {
    // 延迟获取默认数据源，让草稿状态恢复独立于数据库初始化，并支持隔离的数据层验证。
    private val repository by lazy { injectedRepository ?: BabyDataHelper.repository }
    internal val timer = PersistentTimerEditor(viewModelScope, { repository }, com.zero.babydata.domain.TimerRecordKind.SLEEP)
    internal val sessions = SessionCommitCoordinator(viewModelScope, { repository }, OngoingRecordManager.RecordType.SLEEP, timer)
    internal val mutation get() = sessions.mutation

    internal val lastRecord = RecordReadController<Int, SleepRecord>(viewModelScope) { repository.getLastSleepRecord(it) }
    internal val editedRecord = RecordReadController<EditRecordKey, SleepRecord>(viewModelScope) { repository.getSleepRecordById(it.recordId) }

    internal fun save(sleepRecord: SleepRecord, target: RecordMutationController.Target, sessionId: String? = null) {
        val frozenRecord = sleepRecord.copy()
        sessions.submitSleep(frozenRecord, target, sessionId)
    }

    /** 删除结果由重建后的页面消费，后台写入不再反向持有旧 View。 */
    internal fun delete(sleepId: Int, target: RecordMutationController.Target) {
        mutation.submit(target, RecordMutationController.Kind.DELETE, R.string.record_delete_failed) {
            repository.deleteSleepRecordById(sleepId)
            BabyRecordChangeBus.notifyChanged(target.babyId)
        }
    }

    fun loadLastSleepRecord(babyId: Int) = lastRecord.load(babyId)

    internal fun loadSleepRecordById(key: EditRecordKey) = editedRecord.load(key)
}

package com.zero.babycare.home

import com.blankj.utilcode.util.Utils
import com.zero.babycare.reminder.ReminderScheduler
import com.zero.babycare.reminder.ReminderNotifier
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 宝宝记录变更事件总线。
 *
 * 保存、修改或删除记录提交后，由该事件同时刷新首页派生状态并重排持久化提醒任务。
 */
object BabyRecordChangeBus {
    const val ALL_BABIES_CHANGED = 0
    private val _changes = MutableSharedFlow<Int>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val changes: SharedFlow<Int> = _changes.asSharedFlow()

    /** 前台恢复只重排提醒，不清除已有通知或伪造记录变更。 */
    fun refreshReminders() = refresh { ReminderScheduler.ensureScheduled(Utils.getApp()) }

    /** 导入后发一次全域失效信号，避免逐宝宝事件被容量为 1 的缓冲区丢弃。 */
    fun notifyAllChanged() {
        refresh { ReminderNotifier.clearAll(Utils.getApp()) }
        _changes.tryEmit(ALL_BABIES_CHANGED)
        refresh { ReminderScheduler.ensureScheduled(Utils.getApp()) }
    }

    fun notifyChanged(babyId: Int) {
        refresh { ReminderNotifier.clearForBaby(Utils.getApp(), babyId) }
        _changes.tryEmit(babyId)
        refresh { ReminderScheduler.ensureScheduled(Utils.getApp()) }
    }

    /** 派生能力各自失败互不阻断，也不能把已提交的业务写入误报为失败。 */
    private inline fun refresh(action: () -> Unit) {
        try { action() } catch (failure: Exception) {
            com.zero.common.util.FailureLog.warning(com.zero.common.util.FailureLog.RECORD_REFRESH) {
                failure.javaClass.simpleName
            }
        }
    }
}

package com.zero.babycare.home.record

import com.zero.babycare.navigation.NavTarget
import com.zero.babydata.domain.RecordWriteException
import com.zero.babydata.domain.RecordWriteFailure
import com.zero.common.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** 写操作独立于 Fragment 存活；完成结果保留到页面恢复后消费，防止旋转重入和旧回调抢导航。 */
internal class RecordMutationController(private val scope: CoroutineScope) {
    data class Target(
        val draftId: String,
        val babyId: Int,
        val recordId: Int?,
        val source: NavTarget,
        val destination: NavTarget
    ) {
        /** 结果只能驱动发起操作的那一份表单，防止离开/换宝宝后完成事件抢走导航。 */
        fun belongsTo(draftId: String, babyId: Int?, source: NavTarget): Boolean =
            this.draftId == draftId && this.babyId == babyId && this.source == source
    }

    enum class Kind { SAVE, DELETE }

    enum class Field { START, END, LEFT_DURATION, RIGHT_DURATION }
    class ValidationFailure(val messageRes: Int, val field: Field) : IllegalArgumentException()
    data class Confirmation(val id: Long, val target: Target, val start: Long, val end: Long,
        val duration: Long, val longDuration: Boolean)
    private val mutableConfirmation = MutableStateFlow<Confirmation?>(null)
    val confirmation = mutableConfirmation.asStateFlow()
    private var confirmationAnswer: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    /** 确认期间保留冻结输入和写入占用；重建后的 View 只回答同一次确认，不重新读控件。 */
    suspend fun confirm(target: Target, start: Long, end: Long, duration: Long, longDuration: Boolean) {
        val answer = kotlinx.coroutines.CompletableDeferred<Boolean>()
        confirmationAnswer = answer
        mutableConfirmation.value = Confirmation(sequence.incrementAndGet(), target, start, end, duration, longDuration)
        try {
            if (!answer.await()) throw CancellationException("用户取消保存")
        } finally {
            mutableConfirmation.value = null
            confirmationAnswer = null
        }
    }

    fun answerConfirmation(id: Long, accepted: Boolean) {
        if (mutableConfirmation.value?.id == id) confirmationAnswer?.complete(accepted)
    }

    sealed interface State {
        data object Idle : State
        data class Running(val target: Target, val kind: Kind, val operationId: Long) : State
        data class Finished(val target: Target, val kind: Kind, val operationId: Long, val errorRes: Int? = null,
            val field: Field? = null) : State
    }

    private val mutableState = MutableStateFlow<State>(State.Idle)
    private val sequence = AtomicLong()
    val state: StateFlow<State> = mutableState.asStateFlow()
    val isPending: Boolean get() = mutableState.value != State.Idle

    /** 原子占用一直保持到结果被消费，同一 ViewModel 的重复点击/重建无法插入第二次写入。 */
    fun submit(target: Target, kind: Kind, errorRes: Int, operation: suspend () -> Unit): Boolean {
        val operationId = sequence.incrementAndGet()
        val running = State.Running(target, kind, operationId)
        if (!mutableState.compareAndSet(State.Idle, running)) return false
        scope.launch {
            try {
                operation()
                mutableState.value = State.Finished(target, kind, operationId)
            } catch (cancelled: CancellationException) {
                mutableState.compareAndSet(running, State.Idle)
                throw cancelled
            } catch (failure: ValidationFailure) {
                mutableState.value = State.Finished(target, kind, operationId, failure.messageRes, failure.field)
            } catch (failure: CompletedTimerDurationPreview.InvalidRange) {
                mutableState.value = State.Finished(target, kind, operationId, R.string.timer_invalid_change,
                    if (failure.startField) Field.START else Field.END)
            } catch (failure: com.zero.babydata.domain.TimerOperationException) {
                val message = when (failure.reason) {
                    com.zero.babydata.domain.TimerFailure.NEEDS_REVIEW -> R.string.timer_review_required
                    com.zero.babydata.domain.TimerFailure.STALE_REVISION,
                    com.zero.babydata.domain.TimerFailure.NOT_FOUND -> R.string.record_draft_already_saved
                    else -> R.string.timer_invalid_change
                }
                mutableState.value = State.Finished(target, kind, operationId, message)
            } catch (failure: RecordWriteException) {
                // 冷进程恢复后的旧草稿不能覆盖已修改或已删除的记录，页面保留输入并解释原因。
                val message = when (failure.reason) {
                    RecordWriteFailure.CONTENT_CONFLICT -> R.string.record_draft_already_saved
                    RecordWriteFailure.RECORD_DELETED -> R.string.record_draft_record_deleted
                    else -> errorRes
                }
                mutableState.value = State.Finished(target, kind, operationId, message)
            } catch (_: Exception) {
                mutableState.value = State.Finished(target, kind, operationId, errorRes)
            }
        }
        return true
    }

    /** 验证失败也沿用同一个一次性结果通道，不保存 Fragment 回调。 */
    fun reject(target: Target, kind: Kind, errorRes: Int) {
        mutableState.compareAndSet(State.Idle, State.Finished(target, kind, sequence.incrementAndGet(), errorRes))
    }

    fun consume(result: State.Finished): Boolean = mutableState.compareAndSet(result, State.Idle)
}

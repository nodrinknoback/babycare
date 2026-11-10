package com.zero.babycare.home.record

import com.zero.common.util.FailureLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class EditRecordKey(val recordId: Int, val babyId: Int?, val draftId: String)

/** 查询持有业务键而非 View 回调；失败与“记录不存在”明确区分，旧查询不能覆盖新草稿。 */
internal class RecordReadController<K, T>(
    private val scope: CoroutineScope,
    private val read: suspend (K) -> T?
) {
    data class State<K, T>(val key: K, val value: T? = null, val loading: Boolean = false, val failed: Boolean = false)
    private val mutableState = MutableStateFlow<State<K, T>?>(null)
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    fun load(key: K) {
        job?.cancel()
        mutableState.value = State(key, loading = true)
        job = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { read(key) }
                ensureActive()
                mutableState.value = State(key, result)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                ensureActive()
                FailureLog.warning(FailureLog.RECORD_LOAD) { failure.javaClass.simpleName }
                mutableState.value = State(key, failed = true)
            }
        }
    }
}

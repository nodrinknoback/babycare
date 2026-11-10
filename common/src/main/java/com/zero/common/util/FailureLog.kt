package com.zero.common.util

import android.util.Log

/** 只记录故障类别，不记录宝宝身份、表单内容、异常消息或备份路径。 */
object FailureLog {
    private const val TAG = "BabyCareFailure"
    const val LIFECYCLE = "lifecycle"
    const val RECORD_LOAD = "record_load"
    const val BABY_LOAD = "baby_load"
    const val RECORD_REFRESH = "record_refresh"

    @Volatile var enabled: Boolean = true

    // 在求值前统一拦截，关闭诊断时调用方不会组装任何日志内容。
    fun warning(stage: String, message: () -> String) {
        if (enabled) Log.w(TAG, "$stage: ${message()}")
    }
}

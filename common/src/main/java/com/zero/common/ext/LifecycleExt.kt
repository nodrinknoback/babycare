package com.zero.common.ext

import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

fun LifecycleOwner.launchInLifecycle(
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    // 页面负责把可恢复错误映射到 UiState；这里不输出可能含身份或记录内容的异常消息。
    handler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, failure ->
        com.zero.common.util.FailureLog.warning(com.zero.common.util.FailureLog.LIFECYCLE) {
            failure.javaClass.simpleName
        }
    },
    block: suspend () -> Unit,
): Job {
    return lifecycleScope.launch(dispatcher + handler) {
        repeatOnLifecycle(minActiveState) {
            block.invoke()
        }
    }
}

fun Activity.getRealWindowSize(): Pair<Int, Int> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds = windowManager.currentWindowMetrics.bounds
        val width = bounds.width()
        val height = bounds.height()
        width to height
    } else {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        metrics.widthPixels to metrics.heightPixels
    }
}

fun Context.getDeviceScreenSize(): Pair<Int, Int> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val wm = getSystemService(WindowManager::class.java)
        val bounds = wm.maximumWindowMetrics.bounds
        bounds.width() to bounds.height()
    } else {
        val metrics = Resources.getSystem().displayMetrics
        metrics.widthPixels to metrics.heightPixels
    }
}

fun View.observeSizeChangeFlow(): Flow<Pair<Int, Int>> = callbackFlow {
    val listener = View.OnLayoutChangeListener { v, lft, top, rgt, bot, ol, ot, or_, ob ->
        val w = rgt - lft;
        val h = bot - top
        val ow = or_ - ol;
        val oh = ob - ot
        if (w != ow || h != oh) trySend(w to h)
    }
    addOnLayoutChangeListener(listener)
    awaitClose { removeOnLayoutChangeListener(listener) }
}


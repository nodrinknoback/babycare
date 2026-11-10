package com.zero.babycare.home.record

import androidx.fragment.app.Fragment
import com.lxj.xpopup.core.BasePopupView
import com.zero.common.R
import com.zero.common.ext.launchInLifecycle
import com.zero.components.base.util.DialogHelper

/** 只处理读取反馈的展示；重试仍交给原查询控制器，离开 View 生命周期即释放弹窗。 */
internal fun <K, T> Fragment.observeRecordRead(
    controller: RecordReadController<K, T>,
    accepts: (K) -> Boolean,
    clearWhileLoading: Boolean = false,
    onLoaded: (K, T?) -> Unit
) {
    viewLifecycleOwner.launchInLifecycle {
        var popup: BasePopupView? = null
        try {
            controller.state.collect { state ->
                popup?.dismiss()
                popup = null
                if (state == null || !accepts(state.key)) return@collect
                if (state.loading) {
                    if (clearWhileLoading) onLoaded(state.key, null)
                    return@collect
                }
                if (state.failed) {
                    popup = DialogHelper.showConfirmDialog(
                        requireContext(), getString(R.string.data_load_failed),
                        getString(R.string.record_load_retry_message),
                        confirmText = getString(R.string.retry),
                        onConfirm = { if (accepts(state.key)) controller.load(state.key) }
                    )
                } else onLoaded(state.key, state.value)
            }
        } finally { popup?.dismiss() }
    }
}

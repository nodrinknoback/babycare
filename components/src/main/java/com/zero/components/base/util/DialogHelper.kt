package com.zero.components.base.util

import android.content.Context
import android.view.Gravity
import com.lxj.xpopup.XPopup
import com.lxj.xpopup.core.BasePopupView
import com.lxj.xpopup.impl.LoadingPopupView
import kotlin.math.min

/**
 * 弹窗辅助工具类
 * 提供统一样式的弹窗创建方法，适配手机和平板设备
 */
object DialogHelper {

    /**
     * 统一选择器选项。
     *
     * value 只给业务层使用，label 是已经本地化后的展示文案。
     */
    data class PickerOption<T>(
        val value: T,
        val label: String
    )

    enum class DateTimeMode {
        DATE,
        TIME,
        TIME_SECOND,
        MONTH_DAY_TIME,
        MONTH_DAY_TIME_SECOND,
        DATE_TIME,
        DATE_TIME_SECOND
    }

    /** 弹窗最大宽度（dp） */
    private const val MAX_POPUP_WIDTH_DP = 400

    /** 弹窗占屏幕宽度的最大比例 */
    private const val MAX_POPUP_WIDTH_RATIO = 0.85f

    /**
     * 创建统一样式的 XPopup Builder
     * 自动限制最大宽度，适配手机和平板设备
     * 
     * @param context 上下文
     * @return 配置好宽度的 XPopup.Builder
     */
    fun createBuilder(context: Context): XPopup.Builder {
        val popupWidth = calculatePopupWidth(context)
        return XPopup.Builder(context)
            .maxWidth(popupWidth)
    }

    /**
     * 创建统一底部 Sheet Builder。
     * 选择器属于临时覆盖层，统一保留遮罩和底部进入动效。
     */
    private fun createBottomSheetBuilder(context: Context): XPopup.Builder {
        return XPopup.Builder(context)
            .isViewMode(false)
            .hasShadowBg(true)
            // 选择器在平板上居中限宽，避免确认按钮和选项横跨整个窗口。
            .maxWidth(min(context.resources.displayMetrics.widthPixels,
                context.resources.getDimensionPixelSize(com.zero.common.R.dimen.picker_sheet_max_width)))
    }

    /**
     * 计算弹窗宽度
     * 取 MAX_POPUP_WIDTH_DP 和 屏幕宽度 * MAX_POPUP_WIDTH_RATIO 的较小值
     */
    fun calculatePopupWidth(context: Context): Int {
        val displayMetrics = context.resources.displayMetrics
        val maxWidthPx = (MAX_POPUP_WIDTH_DP * displayMetrics.density).toInt()
        val screenWidth = displayMetrics.widthPixels
        return min(maxWidthPx, (screenWidth * MAX_POPUP_WIDTH_RATIO).toInt())
    }

    /**
     * 创建加载对话框
     */
    fun createLoadingDialog(context: Context, title: String? = null): LoadingPopupView {
        val displayTitle = title?.takeIf { it.isNotBlank() }
            ?: context.getString(com.zero.common.R.string.loading)
        val popup = BabyCareLoadingPopup(context).apply {
            setTitle(displayTitle)
            setStyle(LoadingPopupView.Style.Spinner)
        }
        XPopup.Builder(context)
            .isViewMode(false)
            .hasShadowBg(true)
            .dismissOnTouchOutside(false)
            .dismissOnBackPressed(false)
            .asCustom(popup)
            .bindSystemBack()
        return popup
    }

    /**
     * 显示确认对话框（自定义样式）
     * 
     * @param context 上下文
     * @param title 标题
     * @param content 内容
     * @param confirmText 确认按钮文字（默认"确定"）
     * @param cancelText 取消按钮文字（默认"取消"）
     * @param onConfirm 确认回调
     * @param onCancel 取消回调（可选）
     * @param isHideCancel 是否隐藏取消按钮（默认 false）
     * @param dismissOnTouchOutside 等待显式确认的任务可关闭外部点击取消，避免任务悬空
     * @param dismissOnBackPressed 关闭时由可见取消按钮提交明确的取消结果
     */
    fun showConfirmDialog(
        context: Context,
        title: String,
        content: String,
        confirmText: String = context.getString(com.zero.common.R.string.confirm),
        cancelText: String = context.getString(com.zero.common.R.string.cancel),
        onConfirm: () -> Unit,
        onCancel: (() -> Unit)? = null,
        isHideCancel: Boolean = false,
        dismissOnTouchOutside: Boolean = true,
        dismissOnBackPressed: Boolean = true
    ): BasePopupView {
        val popup = ConfirmPopup(context)
            .setTitle(title)
            .setContent(content)
            .setConfirmText(confirmText)
            .setCancelText(cancelText)
            .setOnConfirmListener(onConfirm)
            .setHideCancel(isHideCancel)

        onCancel?.let { popup.setOnCancelListener(it) }

        return XPopup.Builder(context)
            .dismissOnTouchOutside(dismissOnTouchOutside)
            .dismissOnBackPressed(dismissOnBackPressed)
            .asCustom(popup)
            .bindSystemBack()
            .show()
    }

    /**
     * 显示简单提示对话框（只有确认按钮）
     */
    fun showAlertDialog(
        context: Context,
        title: String,
        content: String,
        confirmText: String = context.getString(com.zero.common.R.string.confirm),
        contentGravity: Int = Gravity.CENTER,
        onConfirm: (() -> Unit)? = null
    ): BasePopupView {
        val popup = ConfirmPopup(context)
            .setTitle(title)
            .setContent(content)
            .setContentGravity(contentGravity)
            .setConfirmText(confirmText)
            .setHideCancel(true)

        onConfirm?.let { popup.setOnConfirmListener(it) }

        return XPopup.Builder(context)
            .asCustom(popup)
            .bindSystemBack()
            .show()
    }

    /**
     * 显示统一单选 Sheet。
     *
     * 少量固定选项使用列表而不是滚轮，减少截图中“大片空白 + 弱选中态”的问题。
     */
    fun <T> showChoiceSheet(
        context: Context,
        title: String,
        options: List<PickerOption<T>>,
        selectedValue: T? = null,
        onSelected: (T) -> Unit,
        onCancel: (() -> Unit)? = null
    ): BasePopupView {
        val selectedIndex = options.indexOfFirst { it.value == selectedValue }
            .takeIf { it >= 0 }
            ?: 0
        val popup = BabyCareChoicePopup(
            context = context,
            title = title,
            items = options,
            initialIndex = selectedIndex,
            onSelected = onSelected,
            onCancel = onCancel
        )
        return createBottomSheetBuilder(context)
            .asCustom(popup)
            .bindSystemBack()
            .show()
    }

    /**
     * 显示统一日期/时间 Sheet。
     *
     * maxTime/minTime 用于组件层限制可见范围；跨天解释等业务规则由调用方继续负责。
     */
    fun showDateTimeSheet(
        context: Context,
        title: String,
        initialTime: Long = System.currentTimeMillis(),
        mode: DateTimeMode = DateTimeMode.DATE_TIME,
        minTime: Long? = null,
        maxTime: Long? = null,
        yearRange: IntRange? = null,
        onConfirm: (Long) -> Unit,
        onCancel: (() -> Unit)? = null
    ): BasePopupView {
        val popup = BabyCareDateTimePopup(
            context = context,
            title = title,
            mode = mode,
            initialTime = initialTime,
            minTime = minTime,
            maxTime = maxTime,
            yearRange = yearRange,
            onConfirm = onConfirm,
            onCancel = onCancel
        )
        return createBottomSheetBuilder(context)
            .asCustom(popup)
            .bindSystemBack()
            .show()
    }

    /**
     * 显示年月日 + 时分秒 Sheet。
     *
     * 只在业务确实需要秒级编辑时使用，避免普通日期时间入口增加额外列数。
     */
    fun showDateTimeSecondSheet(
        context: Context,
        title: String,
        initialTime: Long = System.currentTimeMillis(),
        minTime: Long? = null,
        maxTime: Long? = null,
        yearRange: IntRange? = null,
        onConfirm: (Long) -> Unit,
        onCancel: (() -> Unit)? = null
    ): BasePopupView {
        return showDateTimeSheet(
            context = context,
            title = title,
            initialTime = initialTime,
            mode = DateTimeMode.DATE_TIME_SECOND,
            minTime = minTime,
            maxTime = maxTime,
            yearRange = yearRange,
            onConfirm = onConfirm,
            onCancel = onCancel
        )
    }

    /**
     * 显示只选择时分的 Sheet。
     */
    fun showTimeSheet(
        context: Context,
        title: String,
        initialTime: Long = System.currentTimeMillis(),
        onConfirm: (Long) -> Unit,
        onCancel: (() -> Unit)? = null
    ): BasePopupView {
        return showDateTimeSheet(
            context = context,
            title = title,
            initialTime = initialTime,
            mode = DateTimeMode.TIME,
            onConfirm = onConfirm,
            onCancel = onCancel
        )
    }

    /**
     * 显示月日 + 时分 Sheet。
     *
     * 计时器场景需要用户明确选择日期和时间，避免只选时分后再由代码猜测跨天关系。
     */
    fun showMonthDayTimeSheet(
        context: Context,
        title: String,
        initialTime: Long = System.currentTimeMillis(),
        minTime: Long? = null,
        maxTime: Long? = null,
        onConfirm: (Long) -> Unit,
        onCancel: (() -> Unit)? = null
    ): BasePopupView {
        return showDateTimeSheet(
            context = context,
            title = title,
            initialTime = initialTime,
            mode = DateTimeMode.MONTH_DAY_TIME,
            minTime = minTime,
            maxTime = maxTime,
            onConfirm = onConfirm,
            onCancel = onCancel
        )
    }

    /**
     * 显示月日 + 时分秒 Sheet。
     *
     * 计时器输入框本身展示秒，弹框也保留秒列，避免确认后秒被组件层静默归零。
     */
    fun showMonthDayTimeSecondSheet(
        context: Context,
        title: String,
        initialTime: Long = System.currentTimeMillis(),
        minTime: Long? = null,
        maxTime: Long? = null,
        onConfirm: (Long) -> Unit,
        onCancel: (() -> Unit)? = null
    ): BasePopupView {
        return showDateTimeSheet(
            context = context,
            title = title,
            initialTime = initialTime,
            mode = DateTimeMode.MONTH_DAY_TIME_SECOND,
            minTime = minTime,
            maxTime = maxTime,
            onConfirm = onConfirm,
            onCancel = onCancel
        )
    }

    // ============ 兼容旧方法 ============

    @Deprecated(
        message = "Use createLoadingDialog instead",
        replaceWith = ReplaceWith("createLoadingDialog(context, title)")
    )
    fun generateLoadingDialog(context: Context, title: String? = null): LoadingPopupView {
        return createLoadingDialog(context, title)
    }
}

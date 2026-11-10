package com.zero.babycare.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import com.blankj.utilcode.util.AppUtils
import com.blankj.utilcode.util.ToastUtils
import com.zero.babycare.MainViewModel
import com.zero.babycare.R
import com.zero.babycare.databinding.FragmentSettingsBinding
import com.zero.babycare.databinding.ItemSettingRowBinding
import com.zero.babycare.navigation.BackPressHandler
import com.zero.babycare.navigation.NavTarget
import com.zero.babycare.reminder.ReminderNotifier
import com.zero.common.ext.getThemeColor
import com.zero.common.ext.launchInLifecycle
import com.zero.components.base.BaseFragment
import com.zero.components.base.util.DialogHelper

/**
 * 设置页面
 * 展示基础偏好项，并通过 ViewModel 做持久化同步。
 */
class SettingsFragment : BaseFragment<FragmentSettingsBinding>(), BackPressHandler {

    companion object {
        fun create(): SettingsFragment = SettingsFragment()
    }

    private val vm by viewModels<SettingsViewModel>()
    private val mainVm by activityViewModels<MainViewModel>()

    // Fragment 的 View 可以独立重建，不能用 lazy 长期持有第一次创建的行绑定。
    private val reminderRow get() = binding.rowReminder
    private val feedingUnitRow get() = binding.rowFeedingUnit
    private val weightUnitRow get() = binding.rowWeightUnit
    private val heightUnitRow get() = binding.rowHeightUnit
    private val versionRow get() = binding.rowVersion
    private val privacyRow get() = binding.rowPrivacy
    private var privacyPopup: com.lxj.xpopup.core.BasePopupView? = null
    private val languageRow get() = binding.rowLanguage
    private val backupRow get() = binding.rowBackup
    private val familyRow get() = binding.rowFamily
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // 通知权限结果决定是否真正开启提醒，避免无权限时“假开启”
            if (granted && ReminderNotifier.canPostNotifications(requireContext())) {
                vm.setReminderEnabled(true)
            } else {
                bindReminderSwitch(false)
                showNotificationSettingsDialog()
            }
        }

    override fun initView(view: View, savedInstanceState: Bundle?) {
        super.initView(view, savedInstanceState)
        setupToolbar()
        setupRows()
        observeState()
    }

    override fun onResume() {
        super.onResume()
        // 从系统通知设置返回后重读权限，保留用户的提醒偏好并如实说明被系统阻止的状态。
        if (view != null) bindRowTexts()
    }

    /**
     * 初始化工具栏
     */
    private fun setupToolbar() {
        binding.toolbar.title = localizedString(com.zero.common.R.string.settings)
        binding.toolbar.showBackButton { handleBack() }
        binding.toolbar.hideAction()
    }

    /**
     * 初始化列表项与点击逻辑
     */
    private fun setupRows() {
        bindGroupedRowBackgrounds()

        reminderRow.switchToggle.isChecked = vm.settingsState.value.reminderEnabled

        feedingUnitRow.tvSummary.visibility = View.GONE
        feedingUnitRow.root.setOnClickListener {
            showFeedingUnitPicker(vm.settingsState.value.feedingUnit)
        }

        weightUnitRow.tvSummary.visibility = View.GONE
        weightUnitRow.root.setOnClickListener {
            showWeightUnitPicker(vm.settingsState.value.weightUnit)
        }

        heightUnitRow.tvSummary.visibility = View.GONE
        heightUnitRow.root.setOnClickListener {
            showHeightUnitPicker(vm.settingsState.value.heightUnit)
        }

        versionRow.tvSummary.visibility = View.GONE
        versionRow.ivArrow.visibility = View.GONE
        versionRow.root.isClickable = false
        // 政策全文随安装包提供，离线也能查看；与公开网页使用同一份多语言源数据。
        privacyRow.root.setOnClickListener {
            privacyPopup?.dismiss()
            privacyPopup = DialogHelper.showAlertDialog(requireContext(),
                localizedString(com.zero.common.R.string.privacy_policy_title),
                localizedString(com.zero.common.R.string.privacy_policy_body),
                contentGravity = android.view.Gravity.START)
        }
        versionRow.root.isFocusable = false
        versionRow.tvValue.text = AppUtils.getAppVersionName()

        languageRow.tvSummary.visibility = View.GONE
        languageRow.root.setOnClickListener {
            showLanguagePicker(vm.settingsState.value.languageOption)
        }

        setupBackupRow()
        setupComingSoonRow(familyRow, com.zero.common.R.string.settings_family_share)

        bindRowTexts()
    }

    /**
     * 分组首尾行需要和外层 16dp 圆角一致，否则按压态会露出矩形边界。
     */
    private fun bindGroupedRowBackgrounds() {
        feedingUnitRow.root.setBackgroundResource(R.drawable.bg_setting_item_top_ripple)
        weightUnitRow.root.setBackgroundResource(R.drawable.bg_setting_item_middle_ripple)
        heightUnitRow.root.setBackgroundResource(R.drawable.bg_setting_item_bottom_ripple)

        reminderRow.root.setBackgroundResource(R.drawable.bg_setting_item_single_ripple)
        versionRow.root.setBackgroundResource(R.drawable.bg_setting_item_top_ripple)
        privacyRow.root.setBackgroundResource(R.drawable.bg_setting_item_bottom_ripple)

        languageRow.root.setBackgroundResource(R.drawable.bg_setting_item_top_ripple)
        backupRow.root.setBackgroundResource(R.drawable.bg_setting_item_middle_ripple)
        familyRow.root.setBackgroundResource(R.drawable.bg_setting_item_bottom_ripple)
    }

    /**
     * 观察并渲染状态
     */
    private fun observeState() {
        launchInLifecycle {
            vm.settingsState.collect { state ->
                bindReminderSwitch(state.reminderEnabled)
                feedingUnitRow.tvValue.text = localizedString(state.feedingUnit.labelResId)
                weightUnitRow.tvValue.text = localizedString(state.weightUnit.labelResId)
                heightUnitRow.tvValue.text = localizedString(state.heightUnit.labelResId)
                languageRow.tvValue.text = localizedString(state.languageOption.labelResId)
                bindRowTexts()
            }
        }
    }

    /**
     * 同步 Switch 状态并避免重复触发
     */
    private fun bindReminderSwitch(checked: Boolean) {
        reminderRow.switchToggle.setOnCheckedChangeListener(null)
        reminderRow.switchToggle.isChecked = checked
        reminderRow.switchToggle.setOnCheckedChangeListener { _, isChecked ->
            handleReminderToggle(isChecked)
        }
    }

    /**
     * 处理提醒开关逻辑，必要时先申请通知权限
     */
    private fun handleReminderToggle(enabled: Boolean) {
        if (!enabled) {
            vm.setReminderEnabled(false)
            return
        }
        if (hasNotificationPermission()) {
            if (!ReminderNotifier.canPostNotifications(requireContext())) {
                bindReminderSwitch(false)
                showNotificationSettingsDialog()
                return
            }
            vm.setReminderEnabled(true)
            return
        }
        // 无权限时先恢复为关闭态，再引导系统权限弹窗
        bindReminderSwitch(false)
        requestNotificationPermission()
    }

    /** 系统总开关、渠道关闭或永久拒绝均提供可恢复入口，避免出现无法生效的“已开启”。 */
    private fun showNotificationSettingsDialog() {
        DialogHelper.showConfirmDialog(
            context = requireContext(),
            title = getString(com.zero.common.R.string.settings_reminder),
            content = getString(com.zero.common.R.string.settings_notifications_blocked),
            confirmText = getString(com.zero.common.R.string.settings_open_notification_settings),
            onConfirm = {
                val settingsIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:${requireContext().packageName}"))
                }
                startActivity(settingsIntent)
            }
        )
    }

    /**
     * Android 13+ 通知权限判断
     */
    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 触发通知权限申请
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            ToastUtils.showShort(localizedString(com.zero.common.R.string.settings_notification_permission_rationale))
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * 喂养单位选择
     */
    private fun showFeedingUnitPicker(current: FeedingUnit) {
        showUnitPicker(
            title = localizedString(com.zero.common.R.string.settings_unit_feeding_title),
            options = FeedingUnit.values().toList(),
            current = current,
            onSelected = { vm.setFeedingUnit(it) }
        )
    }

    /**
     * 体重单位选择
     */
    private fun showWeightUnitPicker(current: WeightUnit) {
        showUnitPicker(
            title = localizedString(com.zero.common.R.string.settings_unit_weight_title),
            options = WeightUnit.values().toList(),
            current = current,
            onSelected = { vm.setWeightUnit(it) }
        )
    }

    /**
     * 身高单位选择
     */
    private fun showHeightUnitPicker(current: HeightUnit) {
        showUnitPicker(
            title = localizedString(com.zero.common.R.string.settings_unit_height_title),
            options = HeightUnit.values().toList(),
            current = current,
            onSelected = { vm.setHeightUnit(it) }
        )
    }

    /**
     * 语言选择
     */
    private fun showLanguagePicker(current: LanguageOption) {
        val options = LanguageOption.values().toList()
        DialogHelper.showChoiceSheet(
            context = requireContext(),
            title = localizedString(com.zero.common.R.string.settings_language),
            options = options.map {
                DialogHelper.PickerOption(
                    value = it,
                    label = localizedString(it.labelResId)
                )
            },
            selectedValue = current,
            onSelected = { selected ->
                if (selected != current) {
                    vm.setLanguage(selected)
                }
            }
        )
    }

    /**
     * 通用单位选择弹窗
     */
    private fun <T> showUnitPicker(
        title: String,
        options: List<T>,
        current: T,
        onSelected: (T) -> Unit
    ) where T : Enum<T> {
        DialogHelper.showChoiceSheet(
            context = requireContext(),
            title = title,
            options = options.map { option ->
                val labelResId = when (option) {
                    is FeedingUnit -> option.labelResId
                    is WeightUnit -> option.labelResId
                    is HeightUnit -> option.labelResId
                    else -> null
                }
                DialogHelper.PickerOption(
                    value = option,
                    label = labelResId?.let { localizedString(it) } ?: ""
                )
            },
            selectedValue = current,
            onSelected = onSelected
        )
    }

    override fun onSystemBackPressed(): Boolean {
        handleBack()
        return true
    }

    private fun handleBack() {
        mainVm.navigateTo(getReturnTarget())
    }

    private fun getReturnTarget(): NavTarget {
        return (mainVm.navTarget.value as? NavTarget.Settings)?.returnTarget ?: NavTarget.Dashboard
    }

    /**
     * 通用占位项
     */
    private fun setupComingSoonRow(row: ItemSettingRowBinding, titleResId: Int) {
        row.tvTitle.text = localizedString(titleResId)
        row.tvSummary.visibility = View.GONE
        row.tvValue.text = localizedString(com.zero.common.R.string.settings_feature_coming_soon)
        row.tvValue.setTextColor(requireContext().getThemeColor(com.zero.common.R.attr.colorTextHint))
        row.ivArrow.visibility = View.GONE
        row.root.setOnClickListener(null)
        row.root.isClickable = false
        row.root.isFocusable = false
    }

    /**
     * 数据备份入口
     */
    private fun setupBackupRow() {
        backupRow.tvTitle.text = localizedString(com.zero.common.R.string.settings_backup)
        backupRow.tvSummary.text = localizedString(com.zero.common.R.string.settings_backup_summary)
        backupRow.tvSummary.visibility = View.VISIBLE
        backupRow.tvValue.text = ""
        backupRow.root.setOnClickListener {
            mainVm.navigateTo(NavTarget.Backup(returnTarget = NavTarget.Settings(returnTarget = getReturnTarget())))
        }
    }

    /**
     * 统一刷新行文案，保证语言切换后即时更新
     */
    private fun bindRowTexts() {
        binding.tvGeneralTitle.text = localizedString(com.zero.common.R.string.settings_general)
        binding.tvReminderTitle.text = localizedString(com.zero.common.R.string.settings_reminder)
        binding.tvAboutTitle.text = localizedString(com.zero.common.R.string.settings_about)
        binding.tvMoreTitle.text = localizedString(com.zero.common.R.string.settings_more)

        reminderRow.tvTitle.text = localizedString(com.zero.common.R.string.settings_reminder_switch_title)
        reminderRow.tvSummary.text = localizedString(
            if (vm.settingsState.value.reminderEnabled &&
                !ReminderNotifier.canPostNotifications(requireContext()))
                com.zero.common.R.string.settings_notifications_blocked
            else com.zero.common.R.string.settings_reminder_switch_summary
        )
        reminderRow.tvSummary.visibility = View.VISIBLE
        feedingUnitRow.tvTitle.text = localizedString(com.zero.common.R.string.settings_unit_feeding_title)
        weightUnitRow.tvTitle.text = localizedString(com.zero.common.R.string.settings_unit_weight_title)
        heightUnitRow.tvTitle.text = localizedString(com.zero.common.R.string.settings_unit_height_title)
        versionRow.tvTitle.text = localizedString(com.zero.common.R.string.settings_about_version)
        privacyRow.tvTitle.text = localizedString(com.zero.common.R.string.privacy_policy_title)
        languageRow.tvTitle.text = localizedString(com.zero.common.R.string.settings_language)
        backupRow.tvTitle.text = localizedString(com.zero.common.R.string.settings_backup)
        backupRow.tvSummary.text = localizedString(com.zero.common.R.string.settings_backup_summary)
        backupRow.tvSummary.visibility = View.VISIBLE
        setupComingSoonRow(familyRow, com.zero.common.R.string.settings_family_share)
    }

    /**
     * 使用当前 Fragment context 获取资源，避免全局 App context 绕过 AppCompat 的应用内语言。
     */
    private fun localizedString(@StringRes resId: Int, vararg args: Any): String {
        return if (args.isEmpty()) getString(resId) else getString(resId, *args)
    }

    override fun onDestroyView() {
        privacyPopup?.dismiss()
        privacyPopup = null
        super.onDestroyView()
    }
}

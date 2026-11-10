package com.zero.babycare.home.record.event

import com.zero.babycare.home.record.observeRecordRead
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.StaticLayout
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.widget.TextViewCompat
import androidx.core.widget.doAfterTextChanged
import com.google.gson.Gson
import com.blankj.utilcode.util.SizeUtils
import com.zero.components.widget.GridSpacingItemDecoration
import com.blankj.utilcode.util.ToastUtils
import com.zero.babycare.MainViewModel
import com.zero.babycare.databinding.FragmentEventRecordBinding
import com.zero.babycare.databinding.LayoutEventDetailDiaperBinding
import com.zero.babycare.databinding.LayoutEventDetailGrowthBinding
import com.zero.babycare.databinding.LayoutEventDetailTemperatureBinding
import com.zero.babycare.databinding.LayoutEventDetailMedicineBinding
import com.zero.babycare.databinding.LayoutEventDetailMilestoneBinding
import com.zero.babycare.databinding.LayoutEventDetailActivityBinding
import com.zero.babycare.databinding.LayoutEventDetailCustomBinding
import com.zero.babycare.databinding.LayoutEventDetailSymptomBinding
import com.zero.babycare.databinding.LayoutEventDetailVaccineBinding
import com.zero.babycare.home.record.RecordTimerController
import com.zero.babycare.home.record.CompletedTimerDurationPreview
import com.zero.babycare.home.record.PersistentTimerEditor
import com.zero.babycare.home.record.RecordDraftState
import com.zero.babycare.home.record.RecordNumberInput
import com.zero.babycare.home.record.TimerMode
import com.zero.babycare.navigation.NavTarget
import com.zero.babycare.navigation.BackPressHandler
import com.zero.babydata.entity.CustomEventData
import com.zero.babydata.domain.MedicineUnit
import com.zero.babydata.domain.TimerRef
import com.zero.babydata.domain.TimerStatus
import com.zero.babydata.domain.TimerCalculations
import com.zero.babydata.domain.TimerMetadataCodec
import com.zero.babydata.entity.DiaperData
import com.zero.babydata.entity.EventExtraData
import com.zero.babydata.entity.EventType
import com.zero.babydata.entity.GrowthData
import com.zero.babydata.entity.MedicineData
import com.zero.babydata.entity.MilestoneData
import com.zero.babydata.entity.SymptomData
import com.zero.babydata.entity.TemperatureData
import com.zero.babydata.entity.VaccineData
import com.zero.common.R
import com.zero.common.ext.launchInLifecycle
import com.zero.common.util.UnitConfig
import com.zero.common.util.UnitConverter
import com.zero.components.base.BaseFragment
import com.zero.components.base.util.DialogHelper
import com.zero.components.touch.HorizontalNestedScrollTouchDelegate
import com.zero.components.widget.ToolbarAction
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.flow.combine
import java.util.UUID
import com.zero.babycare.home.record.RecordMutationController

/**
 * 事件记录页面
 */
class EventRecordFragment : BaseFragment<FragmentEventRecordBinding>(), BackPressHandler {

    private val vm by viewModels<EventRecordViewModel>()
    private val mainVm by activityViewModels<MainViewModel>()

    private lateinit var categoryAdapter: EventCategoryAdapter
    private lateinit var subtypeAdapter: EventSubtypeAdapter

    private var editRecordId: Int? = null
    private var editingRecord: com.zero.babydata.entity.EventRecord? = null
    private var isEditMode = false
    private var isCategoryLocked = false
    private var pendingExtraData: com.zero.babydata.entity.EventExtraData? = null
    private var activityTimerController: RecordTimerController? = null
    private var renderedTimerRef: TimerRef? = null
    private var pendingTimerRawDraft: Bundle? = null
    private var openedTimerKey: Pair<Int, Int>? = null
    private var pendingDiscardRef: TimerRef? = null
    private var expectedTimerSessionId: String? = null
    private var reportedTimerError: Int? = null
    private var reviewTimerRef: TimerRef? = null
    private var saveInProgress = false
    private var noteExpanded = false
    private var hasUnsavedChanges = false
    private var isProgrammaticChange = false
    private var isViewStateRestored = false
    private var cleanInputs = arrayListOf<String>()
    private var originalExtraInputs: EventRecordViewModel.ExtraDataInputs? = null
    private var retainedDraft: Bundle? = null
    private var draftForView: Bundle? = null
    private var draftBabyId: Int? = null
    private var detailRendered = false
    private var renderedDetailType: Int? = null
    private var draftId = UUID.randomUUID().toString()
    private var pendingCategoryScroll: Runnable? = null
    private var weightInputUnit = UnitConfig.WEIGHT_UNIT_KG
    private var heightInputUnit = UnitConfig.HEIGHT_UNIT_CM
    private var numericInput: RecordNumberInput? = null
    private var numericInputType: Int? = null
    private var hasMedicineDraftUnit = false
    private var medicineDraftUnit: String? = null

    private data class MedicineUnitOption(val value: String?, val label: String) {
        override fun toString() = label
    }

    private val dateFormat by lazy {
        // 日期标题跟随应用内语言，避免英文环境下仍显示中文“月/日”格式。
        SimpleDateFormat(getString(com.zero.common.R.string.event_record_date_title_pattern), Locale.getDefault())
    }
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun initView(view: View, savedInstanceState: Bundle?) {
        super.initView(view, savedInstanceState)
        isViewStateRestored = false

        resolveEditMode()
        draftForView = (savedInstanceState?.getBundle(RecordDraftState.KEY) ?: retainedDraft)
            ?.takeIf { mainVm.navTarget.value is NavTarget.EventRecord &&
                RecordDraftState.matches(it, mainVm.getCurrentBabyInfo()?.babyId, editRecordId) }
        draftBabyId = draftForView?.getInt("baby_id")?.takeIf { it > 0 }
            ?: mainVm.getCurrentBabyInfo()?.babyId
        draftId = draftForView?.getString("draft_id") ?: UUID.randomUUID().toString()
        weightInputUnit = draftForView?.getString("weight_input_unit") ?: UnitConfig.getWeightUnit()
        heightInputUnit = draftForView?.getString("height_input_unit") ?: UnitConfig.getHeightUnit()
        numericInput = draftForView?.getString("numeric_input")?.let { Gson().fromJson(it, RecordNumberInput::class.java) }
        numericInputType = draftForView?.getInt("numeric_input_type")?.takeIf { it != 0 }
        hasMedicineDraftUnit = draftForView?.containsKey("medicine_input_unit") == true
        medicineDraftUnit = draftForView?.getString("medicine_input_unit")
        originalExtraInputs = draftForView?.getBundle("original_extra_inputs")?.let { baseline ->
            baseline.getStringArrayList("values")?.let {
                EventRecordViewModel.ExtraDataInputs(baseline.getInt("type"), it)
            }
        }

        setupToolbar()
        setupTimeCard()
        setupCategoryRecyclerView()
        setupSubtypeRecyclerView()
        setupNoteSection()
        setupSaveButton()

        val draft = draftForView
        if (draft != null) {
            editingRecord = draft.getString("editing_record")?.let {
                Gson().fromJson(it, com.zero.babydata.entity.EventRecord::class.java)
            }
            draft.getString("event_state")?.let { vm.restoreDraft(Gson().fromJson(it, EventRecordViewModel.Draft::class.java)) }
            // 动态详情必须早于 Android 的 View 层级恢复创建，首次 Flow 重放不再重建它。
            updateDetailView(vm.selectedSubtype.value)
            updateUIVisibility(vm.selectedSubtype.value != null)
            if (isEditMode && editingRecord == null) loadEditRecord()
        } else if (isEditMode) {
            loadEditRecord()
        } else {
            // 常驻 Fragment 再次进入创建模式时，必须清掉上一次未保存的临时表单。
            prepareCreateModeState()
        }

        observeViewModel()
        observeMutation()
        observeEditRecord()
        launchInLifecycle { vm.timer.state.collect(::renderPersistentTimer) }
        launchInLifecycle {
            vm.fieldValidation.collect(::renderFieldValidation)
        }
        launchInLifecycle {
            mainVm.currentBaby.combine(mainVm.navTarget) { baby, target -> baby?.babyId to target }
                .collect { (babyId, target) ->
                    if (!isHidden && target is NavTarget.EventRecord && babyId != null) {
                        val changed = (draftBabyId != null && draftBabyId != babyId) || editRecordId != target.editRecordId
                        if (draftBabyId == null) draftBabyId = babyId
                        if (changed) openCurrentTarget()
                    }
                }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (view != null && !isHidden) outState.putBundle(RecordDraftState.KEY, captureDraft())
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        draftForView?.let { draft ->
            isProgrammaticChange = true
            RecordDraftState.restoreHierarchy(binding.root, draft)
            draft.getBundle("timer")?.let { timer ->
                // 业务状态从 Room 恢复；页面草稿只覆盖原始输入，不能复活旧计时器。
                pendingTimerRawDraft = timer
                expectedTimerSessionId = timer.getString("ongoing_session_id")
                activityTimerPanel()?.let { panel ->
                    panel.startInput.restoreInputState(timer.getLong("start"), timer.getString("start_raw").orEmpty())
                    panel.endInput.restoreInputState(timer.getLong("end"), timer.getString("end_raw").orEmpty())
                }
                if (isEditMode) activityTimerController?.updateDurationFromInputs(previewOnly = true)
            }
            setNoteExpanded(draft.getBoolean("note_expanded"))
            cleanInputs = draft.getStringArrayList("clean_inputs") ?: RecordDraftState.inputValues(binding.root)
            hasUnsavedChanges = draft.getBoolean("dirty")
            isProgrammaticChange = false
            updateToolbarAction()
        }
        draftForView = null
        retainedDraft = null
        isViewStateRestored = true
        renderFieldValidation(vm.fieldValidation.value)
        openCurrentTimer()
        renderPersistentTimer(vm.timer.state.value)
    }

    private fun captureDraft(): Bundle = Bundle().apply {
        putInt("edit_id", editRecordId ?: 0)
        putString("draft_id", draftId)
        putInt("baby_id", draftBabyId ?: mainVm.getCurrentBabyInfo()?.babyId ?: 0)
        putBoolean("dirty", hasUnsavedChanges || RecordDraftState.inputValues(binding.root) != cleanInputs)
        putBoolean("note_expanded", noteExpanded)
        putStringArrayList("clean_inputs", cleanInputs)
        originalExtraInputs?.let { baseline ->
            putBundle("original_extra_inputs", Bundle().apply {
                putInt("type", baseline.type)
                putStringArrayList("values", ArrayList(baseline.values))
            })
        }
        putString("weight_input_unit", weightInputUnit)
        putString("height_input_unit", heightInputUnit)
        putString("numeric_input", numericInput?.let { Gson().toJson(it) })
        putInt("numeric_input_type", numericInputType ?: 0)
        if (vm.selectedSubtype.value?.type == EventType.HEALTH_MEDICINE) {
            val unit = binding.containerDetail.findViewById<Spinner>(com.zero.babycare.R.id.spinnerUnit)
            putString("medicine_input_unit", (unit?.selectedItem as? MedicineUnitOption)?.value)
        }
        putString("event_state", Gson().toJson(vm.captureDraft()))
        editingRecord?.let { putString("editing_record", Gson().toJson(it)) }
        activityTimerPanel()?.let {
            putBundle("timer", RecordDraftState.saveTimer(it, activityTimerController).apply {
                // 完成已落库但成功结果尚未消费时，仍保留原身份供冷恢复核验。
                expectedTimerSessionId?.let { sessionId -> putString("ongoing_session_id", sessionId) }
            })
        }
        RecordDraftState.saveHierarchy(binding.root, this)
    }

    private fun activityTimerPanel(): com.zero.components.widget.RecordTimerPanelView? {
        if (activityTimerController == null || binding.containerDetail.childCount == 0) return null
        return LayoutEventDetailActivityBinding.bind(binding.containerDetail.getChildAt(0)).timerPanel
    }

    /** 分类和宝宝共同限定槽位，旧页面异步结果不能渲染到新活动表单。 */
    private fun currentPersistentTimer(): PersistentTimerEditor.State? {
        if (isEditMode) return null
        val type = vm.selectedSubtype.value?.type ?: return null
        if (!EventType.hasDuration(type)) return null
        return vm.timer.state.value.takeIf { it.babyId == draftBabyId && it.eventType == type }
    }

    private fun openCurrentTimer() {
        if (!isViewStateRestored || isHidden || isEditMode) return
        val babyId = draftBabyId ?: return
        val type = vm.selectedSubtype.value?.type ?: return
        if (!EventType.hasDuration(type)) return
        val key = babyId to type
        if (openedTimerKey == key) return
        openedTimerKey = key
        vm.openTimer(babyId, type, expectedTimerSessionId)
    }

    /** Room 的版本变化才重绘计时；busy/error 重发不覆盖用户尚未提交的原始输入。 */
    private fun renderPersistentTimer(state: PersistentTimerEditor.State) {
        if (view == null || isHidden || !isViewStateRestored || currentPersistentTimer() !== state) return
        val panel = activityTimerPanel() ?: return
        val snapshot = state.snapshot
        if (!state.busy && snapshot?.status == TimerStatus.COMPLETED) {
            // 提交成功后进程退出也只能消费原会话，不能将恢复草稿再插入成手工记录。
            prepareCreateModeState()
            ToastUtils.showShort(getString(R.string.save_success))
            mainVm.navigateTo(getReturnTarget())
            return
        }
        if (!state.busy && snapshot != null && (snapshot.ref != renderedTimerRef || state.errorRes != null)) {
            val firstRender = renderedTimerRef == null
            val draft = pendingTimerRawDraft?.takeIf {
                it.getString("ongoing_session_id") == snapshot.ref.sessionId
            }
            val rawStart = if (draft?.getLong("start") == 0L) draft.getString("start_raw")
                else panel.startInput.text.toString().takeIf { !panel.startInput.hasValidTime() && it.isNotBlank() }
            val rawEnd = if (draft?.getLong("end") == 0L &&
                (draft.getString("end_raw").orEmpty().isNotBlank() || draft.getLong("pause_end") > 0L)) draft.getString("end_raw")
                else panel.endInput.text.toString().takeIf { !panel.endInput.hasValidTime() && it.isNotBlank() }
            isProgrammaticChange = true
            try {
                activityTimerController?.renderPersistent(snapshot)
                renderedTimerRef = snapshot.ref
                expectedTimerSessionId = snapshot.ref.sessionId
                vm.setDurationStartTime(snapshot.startAt)
                vm.setEndTime(snapshot.endAt)
                // 配置重建保留半截输入；业务行仍是最后一次成功提交的状态。
                rawStart?.let { panel.startInput.restoreInputState(0L, it) }
                rawEnd?.let { panel.endInput.restoreInputState(0L, it) }
                if (firstRender && pendingTimerRawDraft == null && binding.etNote.text.isNullOrBlank()) {
                    binding.etNote.setText(snapshot.event?.note.orEmpty())
                    setNoteExpanded(!binding.etNote.text.isNullOrBlank())
                    cleanInputs = RecordDraftState.inputValues(binding.root)
                }
            } finally {
                pendingTimerRawDraft = null
                isProgrammaticChange = false
            }
        } else if (!state.busy && snapshot == null) {
            activityTimerController?.renderPersistent(null)
            renderedTimerRef = null
        }
        setSaveInProgress(vm.mutation.state.value is RecordMutationController.State.Running)
        if (!state.busy && pendingDiscardRef != null) {
            pendingDiscardRef = null
            if (state.errorRes == null && snapshot == null) prepareCreateModeState()
        }
        if (state.errorRes == null) reportedTimerError = null
        state.errorRes?.let { message ->
            if (reportedTimerError != message) ToastUtils.showShort(getString(message))
            reportedTimerError = message
            // 打开失败时不能清错后退回“无会话”，否则旧草稿会被保存为重复手工记录。
            if (snapshot != null) vm.timer.clearError()
        }
        if (!state.busy && snapshot?.metadata?.requiresReview == true && reviewTimerRef != snapshot.ref) {
            reviewTimerRef = snapshot.ref
            activityTimerController?.showReviewConfirmation {
                if (currentPersistentTimer()?.snapshot?.ref == snapshot.ref) vm.timer.acknowledgeReview()
            }
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (view == null) return
        if (hidden) {
            retainedDraft = null
            releaseActivityTimer()
            setSaveInProgress(false)
            return
        }
        if (!hidden) {
            openCurrentTarget()
        }
    }

    private fun openCurrentTarget() {
        draftId = UUID.randomUUID().toString()
        draftBabyId = mainVm.getCurrentBabyInfo()?.babyId
        hasUnsavedChanges = false
        resolveEditMode()
        prepareCreateModeState()
        if (isEditMode) loadEditRecord()
        else openCurrentTimer()
    }

    private fun prepareCreateModeState() {
        isProgrammaticChange = true
        weightInputUnit = UnitConfig.getWeightUnit()
        heightInputUnit = UnitConfig.getHeightUnit()
        numericInput = null
        numericInputType = null
        originalExtraInputs = null
        pendingTimerRawDraft = null
        expectedTimerSessionId = null
        reportedTimerError = null
        hasMedicineDraftUnit = false
        medicineDraftUnit = null
        releaseActivityTimer()
        detailRendered = false
        vm.reset()
        binding.etNote.setText("")
        setNoteExpanded(false)
        setSaveInProgress(false)
        handlePreSelectedCategory()
        updateDetailView(vm.selectedSubtype.value)
        updateUIVisibility(vm.selectedSubtype.value != null)
        hasUnsavedChanges = false
        cleanInputs = RecordDraftState.inputValues(binding.root)
        isProgrammaticChange = false
    }

    /**
     * 处理从 Dashboard 传递过来的预选分类
     */
    private fun handlePreSelectedCategory() {
        val navTarget = mainVm.navTarget.value
        if (navTarget is NavTarget.EventRecord && navTarget.preSelectedCategory != null) {
            val category = EventCategory.fromId(navTarget.preSelectedCategory)
            if (category != null) {
                vm.selectCategory(category)
            }
        }
    }

    private fun resolveEditMode() {
        val navTarget = mainVm.navTarget.value as? NavTarget.EventRecord
        editRecordId = navTarget?.editRecordId
        isEditMode = editRecordId != null
        isCategoryLocked = isEditMode && navTarget?.returnTarget is NavTarget.Statistics
        if (!isEditMode || editingRecord?.eventId != editRecordId) {
            editingRecord = null
            pendingExtraData = null
        }
        applyCategoryLockState()
        if (view != null) {
            updateToolbarAction()
        }
    }

    private fun loadEditRecord() {
        val recordId = editRecordId ?: return
        vm.loadEventRecordById(com.zero.babycare.home.record.EditRecordKey(recordId, draftBabyId, draftId))
    }

    /** 读取结果随 View 生命周期渲染，失败保留输入并允许重试，不由 ViewModel 持有页面回调。 */
    private fun observeEditRecord() {
        observeRecordRead(vm.editedRecord,
            accepts = { it.recordId == editRecordId && it.draftId == draftId && !isHidden }) { key, record ->
            if (record == null || (key.babyId != null && record.babyId != key.babyId) ||
                (draftBabyId != null && record.babyId != draftBabyId)) {
                mainVm.navigateTo(getReturnTarget())
                return@observeRecordRead
            }
            draftBabyId = record.babyId
            editingRecord = record
            updateToolbarAction()
            if (hasUnsavedChanges) return@observeRecordRead
            isProgrammaticChange = true
            numericInput = null
            numericInputType = null
            pendingExtraData = vm.editableExtraData(record)

            val category = EventCategory.fromId(com.zero.babydata.entity.EventType.getCategory(record.type))
            val subtype = EventSubtype.fromType(record.type)
            if (category != null) {
                vm.selectCategory(category)
            }
            if (subtype != null) {
                vm.selectSubtype(subtype)
            }

            vm.setEventTime(record.time)
            if (EventType.hasDuration(record.type)) {
                vm.setDurationStartTime(record.time)
            } else {
                vm.setDurationStartTime(null)
            }
            vm.setEndTime(if (record.endTime > 0L) record.endTime else null)
            vm.setExtraData(pendingExtraData)
            vm.setNote(record.note)
            binding.etNote.setText(record.note)
            setNoteExpanded(record.note.isNotBlank())
            detailRendered = false
            updateDetailView(subtype)
            updateUIVisibility(subtype != null)
            // 仅首次真实记录回填后建立基线；View/进程重建恢复旧基线，不能把用户改动洗成原值。
            originalExtraInputs = captureExtraInputs()
            cleanInputs = RecordDraftState.inputValues(binding.root)
            hasUnsavedChanges = false
            isProgrammaticChange = false
        }
    }

    private fun setupToolbar() {
        binding.toolbar.title = getString(R.string.event_record)
        binding.toolbar.showBackButton { handleBack() }
        updateToolbarAction()
    }

    private fun updateToolbarAction() {
        val timerState = currentPersistentTimer()
        if (timerState?.snapshot == null && timerState?.errorRes != null) {
            val label = getString(R.string.retry)
            binding.toolbar.setActions(listOf(ToolbarAction(text = label, contentDescription = label,
                enabled = !timerState.busy))) {
                openedTimerKey = null
                openCurrentTimer()
            }
            return
        }
        if (currentPersistentTimer()?.snapshot != null) {
            val label = getString(R.string.timer_discard)
            binding.toolbar.setActions(listOf(ToolbarAction(text = label, contentDescription = label,
                enabled = !saveInProgress))) { showDiscardTimerDialog() }
            return
        }
        if (!isEditMode) {
            binding.toolbar.hideAction()
            return
        }
        val label = getString(R.string.delete_record)
        binding.toolbar.setActions(
            listOf(
                ToolbarAction(
                    text = label,
                    contentDescription = label,
                    enabled = !saveInProgress && editingRecord != null
                )
            )
        ) { showDeleteConfirmDialog() }
    }

    private fun setupTimeCard() {
        updateTimeDisplay(System.currentTimeMillis())

        binding.cardTime.setOnClickListener {
            showDateTimePicker()
        }
    }

    private fun setupCategoryRecyclerView() {
        categoryAdapter = EventCategoryAdapter { category ->
            if (!isCategoryLocked && !saveInProgress) {
                hasUnsavedChanges = true
                vm.selectCategory(category)
            }
        }
        binding.rvCategory.adapter = categoryAdapter
        binding.rvCategory.itemAnimator = null
        HorizontalNestedScrollTouchDelegate.attachTo(binding.rvCategory)
        applyCategoryLockState()
    }

    private fun setupSubtypeRecyclerView() {
        subtypeAdapter = EventSubtypeAdapter { subtype ->
            if (!isCategoryLocked && !saveInProgress) {
                hasUnsavedChanges = true
                vm.selectSubtype(subtype)
            }
        }
        binding.rvSubtype.layoutManager = GridLayoutManager(requireContext(), SUBTYPE_SPAN_COUNT)
        binding.rvSubtype.addItemDecoration(
            GridSpacingItemDecoration(
                spanCount = SUBTYPE_SPAN_COUNT,
                spacing = SizeUtils.dp2px(8f),
                includeEdge = false
            )
        )
        binding.rvSubtype.adapter = subtypeAdapter
        binding.rvSubtype.addOnLayoutChangeListener(subtypeLayoutListener)
        applyCategoryLockState()
    }

    private fun showDiscardTimerDialog() {
        val snapshot = currentPersistentTimer()?.snapshot ?: return
        val expectedDraft = draftId
        DialogHelper.showConfirmDialog(
            context = requireContext(), title = getString(R.string.timer_discard),
            content = getString(R.string.timer_discard_confirm),
            confirmText = getString(R.string.timer_discard), cancelText = getString(R.string.cancel),
            onConfirm = {
                if (view != null && !isHidden && draftId == expectedDraft &&
                    currentPersistentTimer()?.snapshot?.ref == snapshot.ref) {
                    pendingDiscardRef = snapshot.ref
                    vm.timer.discard()
                }
            }
        )
    }

    private val subtypeLayoutListener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
        // 页面预加载时可能已测过空列表；首批子类型只改变高度，也必须重新计算列数。
        // 必须等本轮布局结束再改 spanCount，否则 RecyclerView 已测好的四列子项可能留到下一次交互。
        view.removeCallbacks(subtypeGridUpdate)
        view.post(subtypeGridUpdate)
    }

    private val subtypeGridUpdate = Runnable {
        view?.findViewById<RecyclerView>(com.zero.babycare.R.id.rvSubtype)?.let(::updateSubtypeGridColumns)
    }

    private fun updateSubtypeGridColumns(recycler: RecyclerView) {
        val manager = recycler.layoutManager as? GridLayoutManager ?: return
        val items = (recycler.adapter as? EventSubtypeAdapter)?.items.orEmpty()
        val available = recycler.width - recycler.paddingLeft - recycler.paddingRight
        if (available <= 0 || items.isEmpty()) return
        val label = TextView(recycler.context).apply {
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_BabyCare_Body2)
            if (items.first().category == EventCategory.DIAPER) {
                // 用选中项的字重和同一份分词文案试排，避免切换后才出现拥挤或截字。
                setTypeface(null, Typeface.BOLD)
            }
        }
        val spacing = (8 * recycler.resources.displayMetrics.density).toInt()
        val minimumWidth = (56 * recycler.resources.displayMetrics.density).toInt()
        // 按当前语言和真实字号试排两行文字，避免固定四列将不同事件类型截成相同省略标签。
        val columns = (SUBTYPE_SPAN_COUNT downTo 1).first { count ->
            val itemWidth = (available - spacing * (count - 1)) / count
            itemWidth >= minimumWidth && items.all { item ->
                val text = recycler.context.getString(item.cardLabelResId)
                StaticLayout.Builder.obtain(text, 0, text.length, label.paint, (itemWidth - spacing).coerceAtLeast(1))
                    .build().lineCount <= 2
            } || count == 1
        }
        if (manager.spanCount != columns) {
            manager.spanCount = columns
            // 间距装饰器读取 LayoutManager 的实际列数，重新分配后同步刷新其偏移。
            recycler.invalidateItemDecorations()
        }
    }

    private fun setupNoteSection() {
        binding.etNote.doAfterTextChanged { if (!isProgrammaticChange) hasUnsavedChanges = true }
        binding.noteCollapsedRow.setOnClickListener {
            setNoteExpanded(true)
        }
    }

    companion object {
        private const val SUBTYPE_SPAN_COUNT = 4

        fun create(): EventRecordFragment = EventRecordFragment()
    }

    private fun setupSaveButton() {
        binding.btnSave.setOnClickListener {
            saveRecord()
        }
    }

    private fun observeViewModel() {
        launchInLifecycle {
            vm.selectedCategory.collect { category ->
                categoryAdapter.setSelectedCategory(category)
                if (category != null) {
                    val subtypes = EventSubtype.getSubtypes(category)
                    subtypeAdapter.submitList(subtypes)
                    updateSubtypeGridColumns(binding.rvSubtype)
                    scrollCategoryIntoView(category)
                } else {
                    subtypeAdapter.submitList(emptyList())
                }
            }
        }

        launchInLifecycle {
            vm.selectedSubtype.collect { subtype ->
                subtypeAdapter.setSelectedType(subtype?.type)
                // 编辑加载最后统一重建详情，避免同步 Flow 重入提前消耗 pendingExtraData。
                if (!isProgrammaticChange) updateDetailView(subtype)
                updateUIVisibility(subtype != null)
                if (!isProgrammaticChange) openCurrentTimer()
            }
        }

        launchInLifecycle {
            vm.eventTime.collect { time ->
                updateTimeDisplay(time)
            }
        }
    }

    private fun updateTimeDisplay(timestamp: Long) {
        val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
        binding.tvDate.text = dateFormat.format(calendar.time)
        binding.tvTime.text = timeFormat.format(calendar.time)
    }

    private fun showDateTimePicker() {
        val expectedView = view
        val expectedDraft = draftId
        val expectedType = vm.selectedSubtype.value?.type
        DialogHelper.showDateTimeSheet(
            context = requireContext(),
            title = getString(R.string.select_time),
            initialTime = vm.eventTime.value,
            mode = DialogHelper.DateTimeMode.DATE_TIME,
            maxTime = System.currentTimeMillis(),
            onConfirm = { timestamp ->
                if (view !== expectedView || isHidden || draftId != expectedDraft ||
                    vm.selectedSubtype.value?.type != expectedType) return@showDateTimeSheet
                hasUnsavedChanges = true
                val subtype = vm.selectedSubtype.value
                if (subtype != null && EventType.hasDuration(subtype.type) && vm.durationStartTime.value != null) {
                    syncActivityStartTime(timestamp)
                } else {
                    vm.setEventTime(timestamp)
                }
            }
        )
    }

    private fun syncActivityStartTime(timestamp: Long) {
        val root = binding.containerDetail
        if (root.childCount == 0) return
        val detailBinding = LayoutEventDetailActivityBinding.bind(root.getChildAt(0))
        val hasExplicitStartTime = vm.durationStartTime.value != null ||
            detailBinding.timerPanel.startInput.hasValidTime()
        if (!hasExplicitStartTime) return
        if (detailBinding.timerPanel.startInput.getTimestamp() == timestamp) return

        currentPersistentTimer()?.snapshot?.let {
            vm.timer.editStart(timestamp)
            return
        }

        activityTimerController?.let { controller ->
            controller.syncStartTime(
                timestamp = timestamp,
                clearEnd = true,
                resetTimer = true,
                notify = false,
                notifyEndClear = true
            )
            vm.setDurationStartTime(timestamp)
            return
        }

        detailBinding.timerPanel.startInput.setTimestamp(timestamp)
        detailBinding.timerPanel.endInput.clear()
        detailBinding.timerPanel.endInput.setReferenceTimestamp(timestamp)
        detailBinding.timerPanel.timerView.reset()
        vm.setDurationStartTime(timestamp)
        vm.setEndTime(null)
    }

    private fun updateDetailView(subtype: EventSubtype?) {
        if (detailRendered && renderedDetailType == subtype?.type) return
        if (numericInputType != subtype?.type) {
            numericInputType = subtype?.type
            numericInput = subtype?.let {
                RecordNumberInput(if (EventType.isGrowth(it.type)) getGrowthUnitValue(it.type) else "")
            }
        }
        detailRendered = true
        renderedDetailType = subtype?.type
        releaseActivityTimer()
        binding.containerDetail.removeAllViews()
        activityTimerController = null

        if (subtype == null) {
            binding.sectionDetail.visibility = View.GONE
            return
        }

        binding.sectionDetail.visibility = View.VISIBLE
        binding.containerDetail.visibility = View.VISIBLE
        val inflater = LayoutInflater.from(requireContext())

        when {
            EventType.isDiaper(subtype.type) -> {
                inflateDiaperDetail(inflater, subtype.type)
            }
            EventType.isGrowth(subtype.type) -> {
                inflateGrowthDetail(inflater, subtype.type)
            }
            subtype.type == EventType.HEALTH_TEMPERATURE -> {
                inflateTemperatureDetail(inflater)
            }
            subtype.type == EventType.HEALTH_MEDICINE -> {
                inflateMedicineDetail(inflater)
            }
            subtype.type == EventType.HEALTH_VACCINE -> {
                inflateVaccineDetail(inflater)
            }
            subtype.type == EventType.HEALTH_SYMPTOM -> {
                inflateSymptomDetail(inflater)
            }
            EventType.isMilestone(subtype.type) -> {
                inflateMilestoneDetail(inflater, subtype.type)
            }
            EventType.hasDuration(subtype.type) -> {
                inflateActivityDetail(inflater)
            }
            subtype.type == EventType.OTHER_CUSTOM -> {
                inflateCustomDetail(inflater)
            }
            else -> {
                // 简单事件无详情
                binding.sectionDetail.visibility = View.GONE
            }
        }

        applyEditDetailIfNeeded(subtype)
        // 动态表单随类型重建；只监听输入更改，不在 View 重复业务校验。
        EventRecordViewModel.ValidationField.entries.forEach { target ->
            validationInput(target)?.doAfterTextChanged {
                if (isViewStateRestored && !isProgrammaticChange) vm.clearFieldValidation(target)
            }
        }
        binding.containerDetail.findViewById<EditText>(com.zero.babycare.R.id.etCustomEventDescription)
            ?.doAfterTextChanged {
                // 自定义事件允许名称或描述任一项，编辑替代输入也应撤销这一组的旧反馈。
                if (isViewStateRestored && !isProgrammaticChange) {
                    vm.clearFieldValidation(EventRecordViewModel.ValidationField.CUSTOM_EVENT_NAME)
                }
            }
        if (!hasUnsavedChanges) cleanInputs = RecordDraftState.inputValues(binding.root)
    }

    private fun validationInput(target: EventRecordViewModel.ValidationField): TextView? = when (target) {
        EventRecordViewModel.ValidationField.START_TIME -> activityTimerPanel()?.startInput
        EventRecordViewModel.ValidationField.END_TIME -> activityTimerPanel()?.endInput
        else -> binding.containerDetail.findViewById(when (target) {
            EventRecordViewModel.ValidationField.GROWTH_VALUE -> com.zero.babycare.R.id.etValue
            EventRecordViewModel.ValidationField.TEMPERATURE -> com.zero.babycare.R.id.etTemperature
            EventRecordViewModel.ValidationField.MEDICINE_NAME -> com.zero.babycare.R.id.etMedicineName
            EventRecordViewModel.ValidationField.VACCINE_NAME -> com.zero.babycare.R.id.etVaccineName
            EventRecordViewModel.ValidationField.SYMPTOM -> com.zero.babycare.R.id.etSymptomDescription
            EventRecordViewModel.ValidationField.MILESTONE_NAME -> com.zero.babycare.R.id.etMilestoneName
            EventRecordViewModel.ValidationField.CUSTOM_EVENT_NAME -> com.zero.babycare.R.id.etCustomEventName
            else -> error("时间字段应由计时面板提供")
        })
    }

    /** 错误留在原字段；焦点复用基类键盘滚动能力，不排队访问可能销毁的 binding。 */
    @Suppress("DEPRECATION")
    private fun renderFieldValidation(validation: EventRecordViewModel.FieldValidation?) {
        EventRecordViewModel.ValidationField.entries.forEach { validationInput(it)?.error = null }
        if (validation == null) return
        val field = validationInput(validation.field) ?: return
        val message = getString(validation.messageResId)
        field.error = message
        // 系统恢复输入不算用户修正；恢复完成后再定位，避免旧层级状态覆盖焦点。
        if (isViewStateRestored && !isHidden) {
            field.requestFocus()
            field.requestRectangleOnScreen(Rect(0, 0, field.width, field.height), true)
            field.announceForAccessibility(message)
        }
    }

    private fun applyEditDetailIfNeeded(subtype: EventSubtype) {
        if (!isEditMode) return

        val root = binding.containerDetail
        val data = pendingExtraData

        when {
            EventType.isDiaper(subtype.type) -> {
                val consistencyGroup = root.findViewById<RadioGroup>(com.zero.babycare.R.id.rgStoolConsistency)
                val colorGroup = root.findViewById<RadioGroup>(com.zero.babycare.R.id.rgStoolColor)
                val urineGroup = root.findViewById<RadioGroup>(com.zero.babycare.R.id.rgUrineAmount)
                if (data is DiaperData) {
                    when (data.consistency) {
                        DiaperData.CONSISTENCY_WATERY -> consistencyGroup?.check(com.zero.babycare.R.id.rbWatery)
                        DiaperData.CONSISTENCY_HARD -> consistencyGroup?.check(com.zero.babycare.R.id.rbHard)
                        DiaperData.CONSISTENCY_NORMAL -> consistencyGroup?.check(com.zero.babycare.R.id.rbSoft)
                    }
                    when (data.color) {
                        DiaperData.COLOR_YELLOW -> colorGroup?.check(com.zero.babycare.R.id.rbYellow)
                        DiaperData.COLOR_GREEN -> colorGroup?.check(com.zero.babycare.R.id.rbGreen)
                        DiaperData.COLOR_BROWN -> colorGroup?.check(com.zero.babycare.R.id.rbBrown)
                        DiaperData.COLOR_BLACK -> colorGroup?.check(com.zero.babycare.R.id.rbBlack)
                    }
                    when (data.urineAmount) {
                        DiaperData.URINE_AMOUNT_LITTLE -> urineGroup?.check(com.zero.babycare.R.id.rbLittle)
                        DiaperData.URINE_AMOUNT_NORMAL -> urineGroup?.check(com.zero.babycare.R.id.rbNormal)
                        DiaperData.URINE_AMOUNT_MUCH -> urineGroup?.check(com.zero.babycare.R.id.rbMuch)
                        else -> if (data.abnormal) {
                            urineGroup?.check(com.zero.babycare.R.id.rbMuch)
                        }
                    }
                }
            }
            EventType.isGrowth(subtype.type) -> {
                val valueView = root.findViewById<EditText>(com.zero.babycare.R.id.etValue)
                if (data is GrowthData) {
                    val targetUnit = getGrowthUnitValue(subtype.type)
                    val displayValue = when (subtype.type) {
                        EventType.GROWTH_WEIGHT -> UnitConverter.weightToDisplay(data.value, data.unit, targetUnit)
                        EventType.GROWTH_HEIGHT, EventType.GROWTH_HEAD -> {
                            UnitConverter.heightToDisplay(data.value, data.unit, targetUnit)
                        }
                        else -> data.value
                    }
                    valueView?.setText(UnitConverter.formatDecimal(displayValue))
                    numericInput = RecordNumberInput(targetUnit).withOriginal(valueView?.text.toString(), data.value, data.unit)
                }
            }
            subtype.type == EventType.HEALTH_TEMPERATURE -> {
                val tempView = root.findViewById<EditText>(com.zero.babycare.R.id.etTemperature)
                if (data is TemperatureData) {
                    tempView?.setText(UnitConverter.formatDecimal(data.value))
                    numericInput = RecordNumberInput("").withOriginal(tempView?.text.toString(), data.value)
                    val detailBinding = LayoutEventDetailTemperatureBinding.bind(root.getChildAt(0))
                    selectTemperatureLocation(detailBinding, data.location)
                    updateTemperatureStatus(detailBinding, data.value)
                }
            }
            subtype.type == EventType.HEALTH_MEDICINE -> {
                val nameView = root.findViewById<EditText>(com.zero.babycare.R.id.etMedicineName)
                val dosageView = root.findViewById<EditText>(com.zero.babycare.R.id.etDosage)
                val unitView = root.findViewById<Spinner>(com.zero.babycare.R.id.spinnerUnit)
                if (data is MedicineData) {
                    nameView?.setText(data.name)
                    dosageView?.setText(data.dosage)
                    val adapter = unitView?.adapter
                    if (adapter != null) {
                        for (index in 0 until adapter.count) {
                            if (MedicineUnit.canonicalValue((adapter.getItem(index) as? MedicineUnitOption)?.value) == MedicineUnit.canonicalValue(data.unit)) {
                                unitView.setSelection(index)
                                break
                            }
                        }
                    }
                }
            }
            subtype.type == EventType.HEALTH_VACCINE -> {
                val nameView = root.findViewById<EditText>(com.zero.babycare.R.id.etVaccineName)
                val doseView = root.findViewById<EditText>(com.zero.babycare.R.id.etVaccineDose)
                val siteView = root.findViewById<EditText>(com.zero.babycare.R.id.etVaccineSite)
                val clinicView = root.findViewById<EditText>(com.zero.babycare.R.id.etVaccineClinic)
                val batchView = root.findViewById<EditText>(com.zero.babycare.R.id.etVaccineBatch)
                if (data is VaccineData) {
                    nameView?.setText(data.name)
                    doseView?.setText(
                        data.dose?.let { String.format(Locale.getDefault(), "%d", it) }.orEmpty()
                    )
                    siteView?.setText(data.site.orEmpty())
                    clinicView?.setText(data.clinic.orEmpty())
                    batchView?.setText(data.batchNumber.orEmpty())
                }
            }
            subtype.type == EventType.HEALTH_SYMPTOM -> {
                val descView = root.findViewById<EditText>(com.zero.babycare.R.id.etSymptomDescription)
                if (data is SymptomData) {
                    descView?.setText(data.description.orEmpty())
                }
            }
            EventType.isMilestone(subtype.type) -> {
                val nameView = root.findViewById<EditText>(com.zero.babycare.R.id.etMilestoneName)
                val descView = root.findViewById<EditText>(com.zero.babycare.R.id.etDescription)
                if (data is MilestoneData) {
                    nameView?.setText(data.name.orEmpty())
                    descView?.setText(data.description.orEmpty())
                }
            }
            subtype.type == EventType.OTHER_CUSTOM -> {
                val nameView = root.findViewById<EditText>(com.zero.babycare.R.id.etCustomEventName)
                val descView = root.findViewById<EditText>(com.zero.babycare.R.id.etCustomEventDescription)
                if (data is CustomEventData) {
                    nameView?.setText(data.name.orEmpty())
                    descView?.setText(data.description.orEmpty())
                }
            }
        }

        if (EventType.hasDuration(subtype.type)) {
            val record = editingRecord
            val startTime = when {
                record != null && record.type == subtype.type -> record.time
                else -> vm.durationStartTime.value
            }
            val endTime = when {
                record != null && record.type == subtype.type && record.endTime > 0L -> record.endTime
                else -> vm.endTime.value
            }
            if (startTime != null && startTime > 0L) {
                activityTimerController?.setStartTime(startTime, notify = false)
            }
            if (endTime != null && endTime > 0L) {
                activityTimerController?.setEndTime(endTime, notify = false, updateDuration = true)
            } else {
                activityTimerController?.clearEndTime(notify = false)
            }
            if (record != null) {
                // 已完成记录只编辑内容，不通过原计时按钮开启另一个实时会话。
                val panel = activityTimerPanel()
                val duration = record.timerData?.let(TimerMetadataCodec::decode)?.let { TimerCalculations.totalDuration(it) }
                    ?: record.activeDurationMs ?: (record.endTime - record.time).coerceAtLeast(0L)
                panel?.timerView?.showDurationWithoutTimer(duration)
                panel?.timerView?.isEnabled = false
            }
        }

        pendingExtraData = null
    }

    private fun inflateDiaperDetail(inflater: LayoutInflater, type: Int) {
        val detailBinding = LayoutEventDetailDiaperBinding.inflate(inflater, binding.containerDetail, true)

        // 根据类型显示/隐藏选项
        val showStool = type == EventType.DIAPER_DIRTY || type == EventType.DIAPER_MIXED
        val showUrine = type == EventType.DIAPER_WET || type == EventType.DIAPER_MIXED

        detailBinding.llStoolConsistency.visibility = if (showStool) View.VISIBLE else View.GONE
        detailBinding.llUrineAmount.visibility = if (showUrine) View.VISIBLE else View.GONE
        (detailBinding.llUrineAmount.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            // 混合排泄会同时显示两组详情，需要额外分组间距；单独小便时保持卡片顶部紧凑。
            params.topMargin = if (showStool && showUrine) {
                resources.getDimensionPixelSize(R.dimen.event_detail_field_gap)
            } else {
                0
            }
            detailBinding.llUrineAmount.layoutParams = params
        }

        // 监听选择变化
        detailBinding.rgStoolConsistency.setOnCheckedChangeListener { _, checkedId ->
            updateDiaperData(detailBinding)
        }
        detailBinding.rgStoolColor.setOnCheckedChangeListener { _, checkedId ->
            updateDiaperData(detailBinding)
        }
        detailBinding.rgUrineAmount.setOnCheckedChangeListener { _, checkedId ->
            updateDiaperData(detailBinding)
        }
    }

    private fun updateDiaperData(detailBinding: LayoutEventDetailDiaperBinding) {
        val consistency = when (detailBinding.rgStoolConsistency.checkedRadioButtonId) {
            com.zero.babycare.R.id.rbWatery -> DiaperData.CONSISTENCY_WATERY
            com.zero.babycare.R.id.rbSoft -> DiaperData.CONSISTENCY_NORMAL
            com.zero.babycare.R.id.rbHard -> DiaperData.CONSISTENCY_HARD
            else -> null
        }
        val color = when (detailBinding.rgStoolColor.checkedRadioButtonId) {
            com.zero.babycare.R.id.rbYellow -> DiaperData.COLOR_YELLOW
            com.zero.babycare.R.id.rbGreen -> DiaperData.COLOR_GREEN
            com.zero.babycare.R.id.rbBrown -> DiaperData.COLOR_BROWN
            com.zero.babycare.R.id.rbBlack -> DiaperData.COLOR_BLACK
            else -> null
        }
        val urineAmount = when (detailBinding.rgUrineAmount.checkedRadioButtonId) {
            com.zero.babycare.R.id.rbLittle -> DiaperData.URINE_AMOUNT_LITTLE
            com.zero.babycare.R.id.rbNormal -> DiaperData.URINE_AMOUNT_NORMAL
            com.zero.babycare.R.id.rbMuch -> DiaperData.URINE_AMOUNT_MUCH
            else -> null
        }
        val abnormal = urineAmount == DiaperData.URINE_AMOUNT_MUCH

        vm.setExtraData(
            DiaperData(
                color = color,
                consistency = consistency,
                abnormal = abnormal,
                urineAmount = urineAmount
            )
        )
    }

    private fun inflateGrowthDetail(inflater: LayoutInflater, type: Int) {
        val detailBinding = LayoutEventDetailGrowthBinding.inflate(inflater, binding.containerDetail, true)

        // 设置单位
        val unitResId = getGrowthUnitLabelResId(type)
        detailBinding.tvUnit.text = getString(unitResId)

        // 监听输入变化
        detailBinding.etValue.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                vm.setExtraData(numericInput?.growth(detailBinding.etValue.text.toString()))
            }
        }
    }

    private fun inflateTemperatureDetail(inflater: LayoutInflater) {
        val detailBinding = LayoutEventDetailTemperatureBinding.inflate(inflater, binding.containerDetail, true)
        detailBinding.llTemperatureStatus.visibility = View.GONE
        detailBinding.tvWarning.visibility = View.GONE

        detailBinding.etTemperature.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                updateTemperatureData(detailBinding)
            }
        }
        detailBinding.etTemperature.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                // 体温状态是用户输入后的即时反馈，不能等到输入框失焦才出现。
                updateTemperatureData(detailBinding)
            }
        })

        detailBinding.rgTemperatureLocation.setOnCheckedChangeListener { _, _ ->
            updateTemperatureData(detailBinding)
        }

        selectTemperatureLocation(detailBinding, TemperatureData.LOCATION_EAR)
    }

    private fun updateTemperatureData(detailBinding: LayoutEventDetailTemperatureBinding) {
        val value = numericInput?.number(detailBinding.etTemperature.text.toString())
        if (value != null) {
            val location = getTemperatureLocation(detailBinding)
            vm.setExtraData(TemperatureData(value, location))
            updateTemperatureStatus(detailBinding, value)
        } else {
            vm.setExtraData(null)
            detailBinding.llTemperatureStatus.visibility = View.GONE
            detailBinding.tvWarning.visibility = View.GONE
        }
    }

    private fun getTemperatureLocation(detailBinding: LayoutEventDetailTemperatureBinding): String {
        return when (detailBinding.rgTemperatureLocation.checkedRadioButtonId) {
            com.zero.babycare.R.id.rbTempForehead -> TemperatureData.LOCATION_FOREHEAD
            com.zero.babycare.R.id.rbTempArmpit -> TemperatureData.LOCATION_ARMPIT
            com.zero.babycare.R.id.rbTempOral -> TemperatureData.LOCATION_ORAL
            com.zero.babycare.R.id.rbTempRectal -> TemperatureData.LOCATION_RECTAL
            else -> TemperatureData.LOCATION_EAR
        }
    }

    private fun selectTemperatureLocation(
        detailBinding: LayoutEventDetailTemperatureBinding,
        location: String
    ) {
        val targetId = when (location) {
            TemperatureData.LOCATION_FOREHEAD -> com.zero.babycare.R.id.rbTempForehead
            TemperatureData.LOCATION_ARMPIT -> com.zero.babycare.R.id.rbTempArmpit
            TemperatureData.LOCATION_ORAL -> com.zero.babycare.R.id.rbTempOral
            TemperatureData.LOCATION_RECTAL -> com.zero.babycare.R.id.rbTempRectal
            else -> com.zero.babycare.R.id.rbTempEar
        }
        detailBinding.rgTemperatureLocation.check(targetId)
    }

    private fun updateTemperatureStatus(detailBinding: LayoutEventDetailTemperatureBinding, temp: Double) {
        val status = EventTemperatureStatusResolver.resolve(temp, getTemperatureLocation(detailBinding))
        val color = resources.getColor(status.colorResId, null)

        detailBinding.llTemperatureStatus.visibility = View.VISIBLE
        detailBinding.ivStatusIcon.setImageResource(status.iconResId)
        detailBinding.ivStatusIcon.setColorFilter(color)
        detailBinding.tvStatus.setText(status.labelResId)
        detailBinding.tvStatus.setTextColor(color)
        status.warningResId?.let { warningResId ->
            detailBinding.tvWarning.setText(warningResId)
            detailBinding.tvWarning.setTextColor(color)
            detailBinding.tvWarning.visibility = View.VISIBLE
        } ?: run {
            detailBinding.tvWarning.visibility = View.GONE
        }
    }

    private fun inflateMedicineDetail(inflater: LayoutInflater) {
        val detailBinding = LayoutEventDetailMedicineBinding.inflate(inflater, binding.containerDetail, true)
        val original = editingRecord?.takeIf { it.type == EventType.HEALTH_MEDICINE }
            ?.let { EventExtraData.parse(it.type, it.extraData) as? MedicineData }
            ?: if (hasMedicineDraftUnit) MedicineData("", unit = medicineDraftUnit) else null
            ?: (vm.extraData.value as? MedicineData) ?: (pendingExtraData as? MedicineData)
        val medicineUnits = MedicineUnit.entries.map { unit ->
            // 旧单位未改变时保留原始值，避免仅改备注或重试旧草稿改变持久回执的业务摘要。
            val value = if (original != null && MedicineUnit.fromStored(original.unit) == unit) original.unit else unit.code
            MedicineUnitOption(value, requireNotNull(MedicineUnitText.label(unit.code, ::getString)))
        }.toMutableList()
        if (original != null && MedicineUnit.fromStored(original.unit) == null) {
            // 未识别或未填写的旧单位单独保留，不能在仅改备注时静默替换为默认 ml。
            medicineUnits += MedicineUnitOption(original.unit, original.unit?.takeIf { it.isNotBlank() } ?: getString(R.string.please_select))
        }
        detailBinding.spinnerUnit.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            medicineUnits
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        detailBinding.etMedicineName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val name = detailBinding.etMedicineName.text.toString()
                val dosage = detailBinding.etDosage.text.toString()
                val unit = (detailBinding.spinnerUnit.selectedItem as? MedicineUnitOption)?.value
                if (name.isNotBlank()) {
                    vm.setExtraData(MedicineData(name, dosage, unit))
                }
            }
        }
    }

    private fun inflateVaccineDetail(inflater: LayoutInflater) {
        val detailBinding = LayoutEventDetailVaccineBinding.inflate(inflater, binding.containerDetail, true)

        detailBinding.etVaccineName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                updateVaccineData(detailBinding)
            }
        }
        detailBinding.etVaccineDose.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                updateVaccineData(detailBinding)
            }
        }
        detailBinding.etVaccineSite.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                updateVaccineData(detailBinding)
            }
        }
        detailBinding.etVaccineClinic.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                updateVaccineData(detailBinding)
            }
        }
        detailBinding.etVaccineBatch.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                updateVaccineData(detailBinding)
            }
        }
    }

    private fun updateVaccineData(detailBinding: LayoutEventDetailVaccineBinding) {
        val name = detailBinding.etVaccineName.text.toString().trim()
        val dose = detailBinding.etVaccineDose.text.toString().trim().toIntOrNull()
        val site = detailBinding.etVaccineSite.text.toString().trim()
        val clinic = detailBinding.etVaccineClinic.text.toString().trim()
        val batch = detailBinding.etVaccineBatch.text.toString().trim()
        val doseValue = dose?.takeIf { it > 0 }
        if (name.isNotBlank() || site.isNotBlank() || clinic.isNotBlank() || batch.isNotBlank() || doseValue != null) {
            vm.setExtraData(
                VaccineData(
                    name = name,
                    dose = doseValue,
                    site = site.ifBlank { null },
                    batchNumber = batch.ifBlank { null },
                    clinic = clinic.ifBlank { null }
                )
            )
        } else {
            vm.setExtraData(null)
        }
    }

    private fun inflateSymptomDetail(inflater: LayoutInflater) {
        val detailBinding = LayoutEventDetailSymptomBinding.inflate(inflater, binding.containerDetail, true)

        detailBinding.etSymptomDescription.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val description = detailBinding.etSymptomDescription.text.toString().trim()
                if (description.isNotBlank()) {
                    vm.setExtraData(SymptomData(description))
                } else {
                    vm.setExtraData(null)
                }
            }
        }
    }

    private fun inflateMilestoneDetail(inflater: LayoutInflater, type: Int) {
        val detailBinding = LayoutEventDetailMilestoneBinding.inflate(inflater, binding.containerDetail, true)

        // 自定义里程碑显示名称输入
        detailBinding.llCustomName.visibility = if (type == EventType.MILESTONE_CUSTOM) {
            View.VISIBLE
        } else {
            View.GONE
        }

        if (type != EventType.MILESTONE_CUSTOM) {
            // 预设里程碑，直接设置数据
            vm.setExtraData(MilestoneData(isFirst = true))
        }

        detailBinding.etMilestoneName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && type == EventType.MILESTONE_CUSTOM) {
                val name = detailBinding.etMilestoneName.text.toString()
                val description = detailBinding.etDescription.text.toString()
                vm.setExtraData(MilestoneData(name, description, true))
            }
        }

        detailBinding.etDescription.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val name = if (type == EventType.MILESTONE_CUSTOM) {
                    detailBinding.etMilestoneName.text.toString()
                } else null
                val description = detailBinding.etDescription.text.toString()
                vm.setExtraData(MilestoneData(name, description, true))
            }
        }
    }

    private fun inflateCustomDetail(inflater: LayoutInflater) {
        val detailBinding = LayoutEventDetailCustomBinding.inflate(inflater, binding.containerDetail, true)

        detailBinding.etCustomEventName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                updateCustomData(detailBinding)
            }
        }

        detailBinding.etCustomEventDescription.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                updateCustomData(detailBinding)
            }
        }
    }

    private fun inflateActivityDetail(inflater: LayoutInflater) {
        val detailBinding = LayoutEventDetailActivityBinding.inflate(inflater, binding.containerDetail, true)

        activityTimerController = RecordTimerController(
            context = requireContext(),
            timerView = detailBinding.timerPanel.timerView,
            startInput = detailBinding.timerPanel.startInput,
            endInput = detailBinding.timerPanel.endInput,
            startPicker = detailBinding.timerPanel.startPicker,
            endPicker = detailBinding.timerPanel.endPicker,
            config = RecordTimerController.Config(
                invalidEndTimeMessageRes = R.string.event_end_time_invalid,
                mode = TimerMode.ACTIVITY,
                persistent = true,
                shouldIgnoreInput = { isProgrammaticChange },
                durationPreview = { start, end ->
                    CompletedTimerDurationPreview.calculate(editingRecord?.timerData, start, end)?.map { it.durationMs }
                }
            ),
            callbacks = RecordTimerController.Callbacks(
                onStartTimeChanged = { timestamp ->
                    if (currentPersistentTimer()?.snapshot != null) vm.timer.editStart(timestamp)
                    else vm.setDurationStartTime(timestamp)
                },
                onEndTimeChanged = { timestamp ->
                    if (timestamp != null && currentPersistentTimer()?.snapshot != null) vm.timer.editEnd(timestamp)
                    else vm.setEndTime(timestamp)
                },
                onTimerStart = { timestamp ->
                    if (!isEditMode) draftBabyId?.let { babyId ->
                        vm.startTimer(babyId, timestamp, activityTimerController?.isStartTimeAutomatic == true,
                            binding.etNote.text.toString().trim())
                    }
                },
                onTimerPause = { if (!isEditMode) vm.timer.pause() },
                onTimerResume = { if (!isEditMode) vm.timer.resume() },
                onDirty = { if (!isProgrammaticChange) hasUnsavedChanges = true }
            )
        )

        // 创建模式下不预填开始时间；只有已有显式开始时间时才恢复计时表单。
        val durationStartTime = vm.durationStartTime.value
        val endTime = vm.endTime.value
        if (durationStartTime != null && durationStartTime > 0L) {
            activityTimerController?.setStartTime(durationStartTime, notify = false)
        }
        if (durationStartTime != null && endTime != null && endTime > 0L) {
            activityTimerController?.setEndTime(endTime, notify = false, updateDuration = true)
        } else {
            activityTimerController?.clearEndTime(notify = false)
        }
    }

    private fun updateUIVisibility(hasSubtype: Boolean) {
        binding.sectionNote.visibility = if (hasSubtype) View.VISIBLE else View.GONE
        binding.btnSave.visibility = if (hasSubtype) View.VISIBLE else View.GONE
    }

    private fun setNoteExpanded(expanded: Boolean) {
        val collapsedVisibility = if (expanded) View.GONE else View.VISIBLE
        if (noteExpanded == expanded && binding.noteCollapsedRow.visibility == collapsedVisibility) {
            return
        }
        noteExpanded = expanded
        binding.noteCollapsedRow.visibility = collapsedVisibility
        binding.noteExpandedContent.visibility = if (expanded) View.VISIBLE else View.GONE
    }

    private fun scrollCategoryIntoView(category: EventCategory) {
        val position = EventCategory.entries.indexOf(category)
        if (position < 0) return
        val expectedView = binding.root
        val recycler = binding.rvCategory
        pendingCategoryScroll?.let(recycler::removeCallbacks)
        val scroll = object : Runnable {
            override fun run() {
                // 连续配置重建时旧队列可能晚到；仅当前 View 的最新请求可以滚动。
                if (pendingCategoryScroll !== this) return
                pendingCategoryScroll = null
                if (view !== expectedView || !recycler.isAttachedToWindow) return
                recycler.smoothScrollToPosition(position)
            }
        }
        pendingCategoryScroll = scroll
        recycler.post(scroll)
    }

    private fun saveRecord() {
        val timerState = currentPersistentTimer()
        if (vm.mutation.isPending || timerState?.busy == true ||
            (timerState?.snapshot == null && timerState?.errorRes != null) || (isEditMode && editingRecord == null)) return

        val babyId = mainVm.getCurrentBabyInfo()?.babyId
        if (babyId == null || (draftBabyId != null && draftBabyId != babyId)) {
            ToastUtils.showShort(getString(R.string.no_baby_selected))
            return
        }
        if (isEditMode && editingRecord?.babyId != babyId) return

        activityTimerPanel()?.let { panel ->
            // 原始未完成输入优先于旧 VM 时间，避免自动暂停覆盖用户正在修改的结束时间。
            panel.startInput.error = null
            panel.endInput.error = null
            if (!panel.startInput.hasValidTime()) {
                panel.startInput.error = getString(if (panel.startInput.text.isNullOrBlank()) R.string.start_time_required else R.string.invalid_time)
                panel.startInput.requestFocus()
                return
            }
            val running = currentPersistentTimer()?.snapshot?.status == TimerStatus.RUNNING
            if (!panel.endInput.hasValidTime() && (!running || !panel.endInput.text.isNullOrBlank())) {
                panel.endInput.error = getString(if (panel.endInput.text.isNullOrBlank()) R.string.end_time_required else R.string.invalid_time)
                panel.endInput.requestFocus()
                return
            }
            if (isEditMode && activityTimerController?.updateDurationFromInputs(previewOnly = true) == false) return
        }

        syncRunningActivityTimerBeforeSave()
        syncDetailInputs()

        // 获取备注
        vm.setNote(binding.etNote.text.toString().trim())

        vm.saveRecord(
            target = mutationTarget(babyId),
            createdAt = editingRecord?.createdAt,
            extraDataEdit = editingRecord?.let {
                EventRecordViewModel.ExtraDataEdit(it, originalExtraInputs, captureExtraInputs())
            }
        )
    }

    /** 时间与备注不属于扩展详情；这里只采集当前详情表单实际可编辑的 View 输入。 */
    private fun captureExtraInputs(): EventRecordViewModel.ExtraDataInputs? {
        val type = vm.selectedSubtype.value?.type ?: return null
        val values = if (activityTimerPanel() != null) emptyList()
            else RecordDraftState.inputValues(binding.containerDetail)
        return EventRecordViewModel.ExtraDataInputs(type, values)
    }

    private fun setSaveInProgress(inProgress: Boolean) {
        val timerState = currentPersistentTimer()
        saveInProgress = inProgress || timerState?.busy == true ||
            (timerState?.snapshot == null && timerState?.errorRes != null)
        if (view == null) return
        binding.btnSave.isEnabled = !saveInProgress
        binding.btnSave.alpha = if (saveInProgress) 0.7f else 1f
        binding.cardTime.isEnabled = !saveInProgress
        binding.etNote.isEnabled = !saveInProgress
        fun enableInputs(root: View) {
            root.isEnabled = !saveInProgress
            if (root is ViewGroup) for (index in 0 until root.childCount) enableInputs(root.getChildAt(index))
        }
        enableInputs(binding.containerDetail)
        val status = currentPersistentTimer()?.snapshot?.status
        activityTimerPanel()?.timerView?.isEnabled = !saveInProgress && !isEditMode && status != TimerStatus.STOPPED
        applyCategoryLockState()
        updateToolbarAction()
    }

    private fun mutationTarget(babyId: Int) = RecordMutationController.Target(
        draftId, babyId, editRecordId, mainVm.navTarget.value, getReturnTarget()
    )

    private fun observeMutation() {
        launchInLifecycle {
            vm.mutation.state.collect { state ->
                setSaveInProgress(state is RecordMutationController.State.Running)
                if (state is RecordMutationController.State.Finished && vm.mutation.consume(state)) {
                    val belongs = state.target.belongsTo(draftId, mainVm.getCurrentBabyInfo()?.babyId, mainVm.navTarget.value)
                    if (state.target.draftId == draftId && state.errorRes == null) {
                        prepareCreateModeState()
                    }
                    if (belongs && !isHidden) {
                        val message = state.errorRes ?: if (state.kind == RecordMutationController.Kind.DELETE)
                            R.string.record_delete_success else R.string.save_success
                        ToastUtils.showShort(getString(message))
                        if (state.errorRes == null) mainVm.navigateTo(state.target.destination)
                    }
                }
            }
        }
    }

    /** 编辑态提供删除入口，让错误记录可以被纠正并同步刷新派生数据。 */
    private fun showDeleteConfirmDialog() {
        val record = editingRecord ?: return
        if (vm.mutation.isPending) return
        val target = mutationTarget(record.babyId)
        DialogHelper.showConfirmDialog(
            context = requireContext(),
            title = getString(R.string.delete_record),
            content = getString(R.string.delete_record_confirm),
            confirmText = getString(R.string.delete_record),
            cancelText = getString(R.string.cancel),
            onConfirm = {
                if (target.belongsTo(draftId, mainVm.getCurrentBabyInfo()?.babyId, mainVm.navTarget.value)) {
                    vm.deleteRecord(record.eventId, target)
                }
            }
        )
    }

    private fun syncRunningActivityTimerBeforeSave() {
        val subtype = vm.selectedSubtype.value ?: return
        if (!EventType.hasDuration(subtype.type)) return

        val root = binding.containerDetail
        if (root.childCount == 0) return

        val detailBinding = LayoutEventDetailActivityBinding.bind(root.getChildAt(0))
        if (currentPersistentTimer()?.snapshot != null) return
        // VM 只接收本次表单已提交的时间，清空旧字段后不能继续保存 VM 中残留的旧时间。
        vm.setDurationStartTime(detailBinding.timerPanel.startInput.getTimestamp().takeIf { it > 0L })
        vm.setEndTime(detailBinding.timerPanel.endInput.getTimestamp().takeIf { it > 0L })
    }

    private fun syncDetailInputs() {
        val subtype = vm.selectedSubtype.value ?: return
        val root = binding.containerDetail
        when {
            EventType.isDiaper(subtype.type) -> {
                val consistencyGroup = root.findViewById<RadioGroup>(com.zero.babycare.R.id.rgStoolConsistency)
                val colorGroup = root.findViewById<RadioGroup>(com.zero.babycare.R.id.rgStoolColor)
                val urineGroup = root.findViewById<RadioGroup>(com.zero.babycare.R.id.rgUrineAmount)
                if (consistencyGroup == null || colorGroup == null || urineGroup == null) {
                    return
                }

                val consistency = when (consistencyGroup.checkedRadioButtonId) {
                    com.zero.babycare.R.id.rbWatery -> DiaperData.CONSISTENCY_WATERY
                    com.zero.babycare.R.id.rbSoft -> DiaperData.CONSISTENCY_NORMAL
                    com.zero.babycare.R.id.rbHard -> DiaperData.CONSISTENCY_HARD
                    else -> null
                }
                val color = when (colorGroup.checkedRadioButtonId) {
                    com.zero.babycare.R.id.rbYellow -> DiaperData.COLOR_YELLOW
                    com.zero.babycare.R.id.rbGreen -> DiaperData.COLOR_GREEN
                    com.zero.babycare.R.id.rbBrown -> DiaperData.COLOR_BROWN
                    com.zero.babycare.R.id.rbBlack -> DiaperData.COLOR_BLACK
                    else -> null
                }
                val urineAmount = when (urineGroup.checkedRadioButtonId) {
                    com.zero.babycare.R.id.rbLittle -> DiaperData.URINE_AMOUNT_LITTLE
                    com.zero.babycare.R.id.rbNormal -> DiaperData.URINE_AMOUNT_NORMAL
                    com.zero.babycare.R.id.rbMuch -> DiaperData.URINE_AMOUNT_MUCH
                    else -> null
                }
                val abnormal = urineAmount == DiaperData.URINE_AMOUNT_MUCH
                vm.setExtraData(
                    DiaperData(
                        color = color,
                        consistency = consistency,
                        abnormal = abnormal,
                        urineAmount = urineAmount
                    )
                )
            }
            EventType.isGrowth(subtype.type) -> {
                val valueView = root.findViewById<EditText>(com.zero.babycare.R.id.etValue)
                vm.setExtraData(numericInput?.growth(valueView?.text?.toString().orEmpty()))
            }
            subtype.type == EventType.HEALTH_TEMPERATURE -> {
                val tempView = root.findViewById<EditText>(com.zero.babycare.R.id.etTemperature)
                val locationGroup = root.findViewById<RadioGroup>(com.zero.babycare.R.id.rgTemperatureLocation)
                val value = numericInput?.number(tempView?.text?.toString().orEmpty())
                if (value != null) {
                    val location = when (locationGroup?.checkedRadioButtonId) {
                        com.zero.babycare.R.id.rbTempForehead -> TemperatureData.LOCATION_FOREHEAD
                        com.zero.babycare.R.id.rbTempArmpit -> TemperatureData.LOCATION_ARMPIT
                        com.zero.babycare.R.id.rbTempOral -> TemperatureData.LOCATION_ORAL
                        com.zero.babycare.R.id.rbTempRectal -> TemperatureData.LOCATION_RECTAL
                        else -> TemperatureData.LOCATION_EAR
                    }
                    vm.setExtraData(TemperatureData(value, location))
                } else {
                    vm.setExtraData(null)
                }
            }
            subtype.type == EventType.HEALTH_MEDICINE -> {
                val nameView = root.findViewById<EditText>(com.zero.babycare.R.id.etMedicineName)
                val dosageView = root.findViewById<EditText>(com.zero.babycare.R.id.etDosage)
                val unitView = root.findViewById<Spinner>(com.zero.babycare.R.id.spinnerUnit)
                val name = nameView?.text?.toString()?.trim().orEmpty()
                val dosage = dosageView?.text?.toString()?.trim().orEmpty()
                val unit = (unitView?.selectedItem as? MedicineUnitOption)?.value
                if (name.isNotBlank()) {
                    vm.setExtraData(MedicineData(name, dosage, unit))
                } else {
                    vm.setExtraData(null)
                }
            }
            subtype.type == EventType.HEALTH_VACCINE -> {
                val nameView = root.findViewById<EditText>(com.zero.babycare.R.id.etVaccineName)
                val doseView = root.findViewById<EditText>(com.zero.babycare.R.id.etVaccineDose)
                val siteView = root.findViewById<EditText>(com.zero.babycare.R.id.etVaccineSite)
                val clinicView = root.findViewById<EditText>(com.zero.babycare.R.id.etVaccineClinic)
                val batchView = root.findViewById<EditText>(com.zero.babycare.R.id.etVaccineBatch)
                val name = nameView?.text?.toString()?.trim().orEmpty()
                val dose = doseView?.text?.toString()?.trim()?.toIntOrNull()
                val site = siteView?.text?.toString()?.trim().orEmpty()
                val clinic = clinicView?.text?.toString()?.trim().orEmpty()
                val batch = batchView?.text?.toString()?.trim().orEmpty()
                val doseValue = dose?.takeIf { it > 0 }
                if (name.isNotBlank() || site.isNotBlank() || clinic.isNotBlank() || batch.isNotBlank() || doseValue != null) {
                    vm.setExtraData(
                        VaccineData(
                            name = name,
                            dose = doseValue,
                            site = site.ifBlank { null },
                            batchNumber = batch.ifBlank { null },
                            clinic = clinic.ifBlank { null }
                        )
                    )
                } else {
                    vm.setExtraData(null)
                }
            }
            subtype.type == EventType.HEALTH_SYMPTOM -> {
                val descView = root.findViewById<EditText>(com.zero.babycare.R.id.etSymptomDescription)
                val description = descView?.text?.toString()?.trim().orEmpty()
                if (description.isNotBlank()) {
                    vm.setExtraData(SymptomData(description))
                } else {
                    vm.setExtraData(null)
                }
            }
            subtype.type == EventType.MILESTONE_CUSTOM -> {
                val nameView = root.findViewById<EditText>(com.zero.babycare.R.id.etMilestoneName)
                val descView = root.findViewById<EditText>(com.zero.babycare.R.id.etDescription)
                val name = nameView?.text?.toString()?.trim().orEmpty()
                val description = descView?.text?.toString()?.trim().orEmpty()
                if (name.isNotBlank()) {
                    vm.setExtraData(MilestoneData(name, description, true))
                } else {
                    vm.setExtraData(null)
                }
            }
            subtype.type == EventType.OTHER_CUSTOM -> {
                val nameView = root.findViewById<EditText>(com.zero.babycare.R.id.etCustomEventName)
                val descView = root.findViewById<EditText>(com.zero.babycare.R.id.etCustomEventDescription)
                val name = nameView?.text?.toString()?.trim().orEmpty()
                val description = descView?.text?.toString()?.trim().orEmpty()
                if (name.isNotBlank() || description.isNotBlank()) {
                    vm.setExtraData(CustomEventData(name, description))
                } else {
                    vm.setExtraData(null)
                }
            }
        }
    }

    private fun updateCustomData(detailBinding: LayoutEventDetailCustomBinding) {
        val name = detailBinding.etCustomEventName.text.toString().trim()
        val description = detailBinding.etCustomEventDescription.text.toString().trim()
        if (name.isNotBlank() || description.isNotBlank()) {
            vm.setExtraData(CustomEventData(name, description))
        } else {
            vm.setExtraData(null)
        }
    }

    /**
     * 生长类单位资源
     */
    private fun getGrowthUnitLabelResId(type: Int): Int {
        return when (getGrowthUnitValue(type)) {
            UnitConfig.WEIGHT_UNIT_LB -> R.string.unit_lb_abbr
            UnitConfig.HEIGHT_UNIT_IN -> R.string.unit_in_abbr
            UnitConfig.WEIGHT_UNIT_KG -> R.string.weight_unit
            else -> R.string.height_unit
        }
    }

    /**
     * 生长类单位值
     */
    private fun getGrowthUnitValue(type: Int): String {
        return when (type) {
            EventType.GROWTH_WEIGHT -> weightInputUnit
            else -> heightInputUnit
        }
    }

    private fun getReturnTarget(): NavTarget {
        return (mainVm.navTarget.value as? NavTarget.EventRecord)?.returnTarget ?: NavTarget.Dashboard
    }

    override fun onSystemBackPressed(): Boolean {
        handleBack()
        return true
    }

    private fun handleBack() {
        if (hasUnsavedChanges || RecordDraftState.inputValues(binding.root) != cleanInputs) {
            DialogHelper.showConfirmDialog(
                context = requireContext(),
                title = getString(R.string.tip),
                content = getString(if (currentPersistentTimer()?.snapshot != null)
                    R.string.timer_leave_tip else R.string.unsaved_record_tip),
                confirmText = getString(R.string.confirm),
                cancelText = getString(R.string.cancel),
                onConfirm = {
                    hasUnsavedChanges = false
                    mainVm.navigateTo(getReturnTarget())
                }
            )
        } else {
            mainVm.navigateTo(getReturnTarget())
        }
    }

    private fun applyCategoryLockState() {
        if (!::categoryAdapter.isInitialized || !::subtypeAdapter.isInitialized) {
            return
        }
        val locked = isCategoryLocked || saveInProgress
        categoryAdapter.setLocked(locked)
        subtypeAdapter.setLocked(locked)
        binding.rvCategory.isEnabled = !locked
        binding.rvSubtype.isEnabled = !locked
    }

    private fun releaseActivityTimer() {
        if (activityTimerController == null) return
        activityTimerController?.dispose()
        val detailRoot = binding.containerDetail.getChildAt(0)
        if (detailRoot != null) {
            runCatching {
                LayoutEventDetailActivityBinding.bind(detailRoot).timerPanel.timerView.release()
            }
        }
        activityTimerController = null
        renderedTimerRef = null
        openedTimerKey = null
        pendingDiscardRef = null
        expectedTimerSessionId = null
        reviewTimerRef = null
    }

    override fun onDestroyView() {
        pendingCategoryScroll?.let(binding.rvCategory::removeCallbacks)
        pendingCategoryScroll = null
        binding.rvSubtype.removeOnLayoutChangeListener(subtypeLayoutListener)
        binding.rvSubtype.removeCallbacks(subtypeGridUpdate)
        binding.rvSubtype.adapter = null
        retainedDraft = if (!isHidden) captureDraft() else null
        detailRendered = false
        releaseActivityTimer()
        super.onDestroyView()
    }
}

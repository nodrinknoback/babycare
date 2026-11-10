package com.zero.babycare.home.record

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.widget.doAfterTextChanged
import com.google.gson.Gson
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import com.blankj.utilcode.util.ToastUtils
import com.zero.babycare.MainViewModel
import com.zero.babycare.databinding.FragmentSleepRecordBinding
import com.zero.babycare.home.OngoingRecordManager
import com.zero.babycare.navigation.NavTarget
import com.zero.babycare.navigation.BackPressHandler
import com.zero.babydata.entity.SleepRecord
import com.zero.common.R
import com.zero.common.util.DateUtils
import com.zero.common.ext.launchInLifecycle
import kotlinx.coroutines.flow.combine
import java.util.UUID
import com.zero.components.base.BaseFragment
import com.zero.components.base.util.DialogHelper
import com.zero.components.widget.RecordView.RecordState
import com.zero.components.widget.ToolbarAction

/** 睡眠记录的输入与渲染入口。
 * View 只采集输入、展示错误和执行导航；冻结草稿与完整保存流程由 ViewModel 的协调器持有。
 */
class SleepRecordFragment : BaseFragment<FragmentSleepRecordBinding>(), BackPressHandler {

    companion object {
        fun create(): SleepRecordFragment {
            return SleepRecordFragment()
        }
    }

    private val vm by viewModels<SleepRecordViewModel>()
    private val mainVm: MainViewModel by lazy {
        activityViewModels<MainViewModel>().value
    }

    private var editRecordId: Int? = null
    private var editingRecord: SleepRecord? = null
    private var isEditMode = false
    private var mutationInProgress = false
    private val formFreeze = RecordFormFreeze()
    private var sessionChecking = false
    private var sessionCheckFailed = false
    private var sessionCommitting = false
    private var preserveRecoveredDraft = false
    private var renderedRecovery: SessionCommitCoordinator.Recovery? = null

    // 是否有未保存的记录
    private var hasUnsavedChanges = false

    // 标记是否是程序设置时间（避免循环触发）
    private var isProgrammaticChange = false

    private var discardPending = false
    private var reviewRevision = -1L
    private var renderedTimerRef: com.zero.babydata.domain.TimerRef? = null
    private var pendingTimerRawDraft: Bundle? = null
    private var timerBusy = false
    private var timerController: RecordTimerController? = null
    private var retainedDraft: Bundle? = null
    private var draftForView: Bundle? = null
    private var draftBabyId: Int? = null
    private var draftId = UUID.randomUUID().toString()

    override fun initView(view: View, savedInstanceState: Bundle?) {
        super.initView(view, savedInstanceState)
        binding.btn.title = getString(R.string.sleep_record)

        setupTimerController()
        setupToolbar()
        setupSaveButton()
        binding.etNote.doAfterTextChanged { if (!isProgrammaticChange) markAsUnsaved() }

        resolveEditMode()
        draftForView = (savedInstanceState?.getBundle(RecordDraftState.KEY) ?: retainedDraft)
            ?.takeIf { mainVm.navTarget.value is NavTarget.SleepRecord &&
                RecordDraftState.matches(it, mainVm.getCurrentBabyInfo()?.babyId, editRecordId) }
        draftBabyId = draftForView?.getInt("baby_id")?.takeIf { it > 0 }
            ?: mainVm.getCurrentBabyInfo()?.babyId
        draftId = draftForView?.getString("draft_id") ?: UUID.randomUUID().toString()
        if (draftForView == null) resetPage()
        if (isEditMode && draftForView?.getString("editing_record") == null) {
            loadEditRecord()
        }
        launchInLifecycle {
            mainVm.currentBaby.combine(mainVm.navTarget) { baby, target -> baby?.babyId to target }
                .collect { (babyId, target) ->
                    if (!isHidden && target is NavTarget.SleepRecord && babyId != null) {
                        val changed = (draftBabyId != null && draftBabyId != babyId) || editRecordId != target.editRecordId
                        if (draftBabyId == null) draftBabyId = babyId
                        if (changed) openCurrentTarget()
                    }
                }
        }
        observeMutation()
        observeEditRecord()
        observeSessionRecovery()
        observePersistentTimer()
        // 读取状态绑定 View 生命周期，切换宝宝时清除上一份摘要。
        observeRecordRead(vm.lastRecord, accepts = { it == currentDraftBabyId() }, clearWhileLoading = true) { _, record ->
            if (record != null && record.babyId == currentDraftBabyId()) {
                binding.tvLastSleep.text = getString(
                    R.string.last_sleep_time,
                    DateUtils.timestampToMMddHHmm(record.sleepEnd)
                )
                binding.tvLastSleep.visibility = View.VISIBLE
            } else {
                binding.tvLastSleep.visibility = View.GONE
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (view != null && !isHidden) outState.putBundle(RecordDraftState.KEY, captureDraft())
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        val restoredDraft = draftForView != null
        draftForView?.let { draft ->
            isProgrammaticChange = true
            editingRecord = draft.getString("editing_record")?.let { Gson().fromJson(it, SleepRecord::class.java) }
                ?: editingRecord
            editRecordId = draft.getInt("edit_id").takeIf { it > 0 }
            isEditMode = editRecordId != null
            RecordDraftState.restoreHierarchy(binding.root, draft)
            draft.getBundle("timer")?.let { timer ->
                if (!isEditMode && timer.getString("ongoing_session_id") != null) {
                    // 活动事实由 Room 恢复；Bundle 只保留原身份和未完成文本，不重启旧秒表。
                    pendingTimerRawDraft = timer
                    timerController?.restoreAutomaticStart(timer.getBoolean("automatic_start"),
                        sessionId = timer.getString("ongoing_session_id"))
                    binding.timerPanel.startInput.restoreInputState(timer.getLong("start"), timer.getString("start_raw").orEmpty())
                    binding.timerPanel.endInput.restoreInputState(timer.getLong("end"), timer.getString("end_raw").orEmpty())
                } else RecordDraftState.restoreTimer(binding.timerPanel, timerController, timer)
            }
            if (isEditMode) timerController?.updateDurationFromInputs(previewOnly = true)
            hasUnsavedChanges = draft.getBoolean("dirty")
            isProgrammaticChange = false
            applyTimerLockState()
            updateToolbarAction()
        }
        draftForView = null
        retainedDraft = null
        if (!isHidden && mainVm.navTarget.value is NavTarget.SleepRecord) {
            requestSessionRecovery(preserveDraft = restoredDraft)
        }
    }

    private fun captureDraft(): Bundle = Bundle().apply {
        putInt("edit_id", editRecordId ?: 0)
        putString("draft_id", draftId)
        putInt("baby_id", draftBabyId ?: mainVm.getCurrentBabyInfo()?.babyId ?: 0)
        putBoolean("dirty", hasUnsavedChanges)
        editingRecord?.let { putString("editing_record", Gson().toJson(it)) }
        putBundle("timer", RecordDraftState.saveTimer(binding.timerPanel, timerController))
        RecordDraftState.saveHierarchy(binding.root, this)
    }

    override fun onDestroyView() {
        renderedTimerRef = null
        retainedDraft = if (!isHidden) captureDraft() else null
        timerController?.dispose()
        timerController = null
        formFreeze.clear()
        binding.timerPanel.timerView.release()
        super.onDestroyView()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // View 未创建时不执行
        if (view == null) return
        
        if (hidden) {
            timerController?.dismissPendingUi()
            retainedDraft = null
            // 隐藏时不重置计时器（保留进行中状态）
        } else {
            openCurrentTarget()
        }
    }

    /** 同一个常驻页面也可能收到另一宝宝/编辑目标，必须先切换表单归属。 */
    private fun openCurrentTarget() {
        sessionChecking = false
        sessionCheckFailed = false
        sessionCommitting = false
        formFreeze.render(false)
        draftId = UUID.randomUUID().toString()
        draftBabyId = mainVm.getCurrentBabyInfo()?.babyId
        resolveEditMode()
        resetPage()
        if (isEditMode) {
            loadEditRecord()
        } else {
            requestSessionRecovery(preserveDraft = false)
            loadLastSleepRecord()
        }
    }

    private fun currentDraftBabyId(): Int? = mainVm.getCurrentBabyInfo()?.babyId
        ?.takeIf { draftBabyId == null || draftBabyId == it }

    /**
     * 恢复进行中的睡眠记录
     */
    private fun restoreOngoingSleep(session: OngoingRecordManager.OngoingSession) {
        if (!preserveRecoveredDraft) {
            session.pendingSleep?.let(::applyRecordToUi)
            renderedTimerRef = null
        }
        isProgrammaticChange = true
        session.snapshot?.let { renderPersistentSnapshot(it) }
        hasUnsavedChanges = true
        isProgrammaticChange = false
    }

    /** 写入完成后才渲染计时状态，失败保持原片段并显示可重试错误。 */
    private fun observePersistentTimer() {
        launchInLifecycle {
            vm.timer.state.collect { state ->
                if (isEditMode || isHidden || state.babyId != currentDraftBabyId()) return@collect
                timerBusy = state.busy
                if (!state.busy) {
                    isProgrammaticChange = true
                    renderPersistentSnapshot(state.snapshot, force = state.errorRes != null)
                    isProgrammaticChange = false
                    state.errorRes?.let {
                        discardPending = false
                        ToastUtils.showShort(getString(it))
                        vm.timer.clearError()
                    }
                }
                renderInputFreeze()
                if (!state.busy && state.errorRes == null && discardPending && state.snapshot == null) {
                    discardPending = false
                    resetPage()
                }
                val snapshot = state.snapshot
                if (!state.busy && snapshot?.metadata?.requiresReview == true &&
                    snapshot.ref.revision != reviewRevision) {
                    reviewRevision = snapshot.ref.revision
                    timerController?.showReviewConfirmation {
                        if (vm.timer.state.value.snapshot?.ref == snapshot.ref) vm.timer.acknowledgeReview()
                    }
                }

            }
        }
    }

    /** 相同版本的恢复/错误重发不覆盖编辑；新版本最后叠回未完成原文。 */
    private fun renderPersistentSnapshot(snapshot: com.zero.babydata.domain.TimerRecordSnapshot?, force: Boolean = false) {
        if (snapshot == null) {
            timerController?.renderPersistent(null)
            renderedTimerRef = null
            return
        }
        if (!force && renderedTimerRef == snapshot.ref && pendingTimerRawDraft == null) return
        val panel = binding.timerPanel
        val draft = pendingTimerRawDraft?.takeIf { it.getString("ongoing_session_id") == snapshot.ref.sessionId }
        val startRaw = if (draft?.getLong("start") == 0L) draft.getString("start_raw")
            else panel.startInput.text.toString().takeIf { !panel.startInput.hasValidTime() && it.isNotBlank() }
        val endRaw = if (draft?.getLong("end") == 0L &&
            (draft.getString("end_raw").orEmpty().isNotBlank() || draft.getLong("pause_end") > 0L)) draft.getString("end_raw")
            else panel.endInput.text.toString().takeIf { !panel.endInput.hasValidTime() && it.isNotBlank() }
        timerController?.renderPersistent(snapshot)
        renderedTimerRef = snapshot.ref
        pendingTimerRawDraft = null
        startRaw?.let { panel.startInput.restoreInputState(0L, it) }
        endRaw?.let { panel.endInput.restoreInputState(0L, it) }
    }

    private fun setupTimerController() {
        timerController = RecordTimerController(
            context = requireContext(),
            timerView = binding.timerPanel.timerView,
            startInput = binding.timerPanel.startInput,
            endInput = binding.timerPanel.endInput,
            startPicker = binding.timerPanel.startPicker,
            endPicker = binding.timerPanel.endPicker,
            config = RecordTimerController.Config(
                invalidEndTimeMessageRes = R.string.sleep_end_must_after_start,
                mode = TimerMode.SLEEP,
                persistent = true,
                shouldIgnoreInput = { isProgrammaticChange },
                durationPreview = { start, end ->
                    CompletedTimerDurationPreview.calculate(editingRecord?.timerData, start, end)?.map { it.durationMs }
                }
            ),
            callbacks = RecordTimerController.Callbacks(
                onStartTimeChanged = { startTime ->
                    markAsUnsaved()
                    if (!isEditMode && timerController?.ongoingSessionId != null) vm.timer.editStart(startTime)
                },
                onEndTimeChanged = { endTime ->
                    markAsUnsaved()
                    if (!isEditMode && timerController?.ongoingSessionId != null && endTime != null) vm.timer.editEnd(endTime)
                },
                onTimerStart = { startTime ->
                    if (!isEditMode) currentDraftBabyId()?.let { babyId ->
                        vm.timer.startSleep(SleepRecord(babyId = babyId, sleepStart = startTime, note = binding.etNote.text.toString(), createdAt = System.currentTimeMillis()), timerController?.isStartTimeAutomatic == true)
                    }
                },
                onTimerResume = { if (!isEditMode) vm.timer.resume() },
                onTimerPause = { if (!isEditMode) vm.timer.pause() },
                onDirty = { markAsUnsaved() }
            )
        )
    }

    /**
     * 设置工具栏
     */
    private fun setupToolbar() {
        binding.btn.showBackButton {
            handleBack()
        }
        updateToolbarAction()
    }

    private fun updateToolbarAction() {
        if (!isEditMode) {
            if (vm.timer.state.value.snapshot == null) binding.btn.hideAction()
            else {
                val label = getString(R.string.timer_discard)
                binding.btn.setActions(listOf(ToolbarAction(text = label, contentDescription = label,
                    enabled = !timerBusy && !mutationInProgress))) {
                    val expectedSession = timerController?.ongoingSessionId
                    DialogHelper.showConfirmDialog(requireContext(), getString(R.string.timer_discard),
                        getString(R.string.timer_discard_confirm), getString(R.string.timer_discard), getString(R.string.cancel),
                        onConfirm = {
                            if (expectedSession == timerController?.ongoingSessionId && !isHidden) {
                                discardPending = true
                                vm.timer.discard()
                            }
                        })
                }
            }
            return
        }
        val label = getString(R.string.delete_record)
        binding.btn.setActions(
            listOf(
                ToolbarAction(
                    text = label,
                    contentDescription = label,
                    enabled = !mutationInProgress && editingRecord != null
                )
            )
        ) { showDeleteConfirmDialog() }
    }

    /**
     * 设置保存按钮
     */
    private fun setupSaveButton() {
        binding.tvSave.setOnClickListener {
            saveSleepRecord()
        }
    }

    private fun resolveEditMode() {
        val navTarget = mainVm.navTarget.value as? NavTarget.SleepRecord
        editRecordId = navTarget?.editRecordId
        isEditMode = editRecordId != null
        if (!isEditMode || editingRecord?.sleepId != editRecordId) {
            editingRecord = null
        }
        applyTimerLockState()
        updateToolbarAction()
    }

    private fun applyTimerLockState() {
        val returnTarget = (mainVm.navTarget.value as? NavTarget.SleepRecord)?.returnTarget
        val locked = isEditMode || timerBusy ||
            vm.timer.state.value.snapshot?.status == com.zero.babydata.domain.TimerStatus.STOPPED ||
            mutationInProgress || sessionChecking || sessionCommitting || sessionCheckFailed
        binding.timerPanel.timerView.isEnabled = !locked
        binding.timerPanel.timerView.isClickable = !locked
        binding.timerPanel.timerView.alpha = if (locked) 0.4f else 1f
    }

    private fun loadEditRecord() {
        val recordId = editRecordId ?: return
        vm.loadSleepRecordById(com.zero.babycare.home.record.EditRecordKey(recordId, draftBabyId, draftId))
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
            // 等待数据库期间输入的草稿优先，只补齐保存编辑所需的原始记录身份。
            if (!hasUnsavedChanges) applyRecordToUi(record)
            else timerController?.updateDurationFromInputs(previewOnly = true)
        }
    }

    private fun applyRecordToUi(record: SleepRecord) {
        isProgrammaticChange = true

        timerController?.setStartTime(record.sleepStart, notify = false)
        timerController?.setEndTime(record.sleepEnd, notify = false)
        binding.timerPanel.timerView.showDurationWithoutTimer(record.sleepDuration)
        binding.etNote.setText(record.note)

        hasUnsavedChanges = false
        isProgrammaticChange = false
    }

    /**
     * 保存睡眠记录
     */
    private fun saveSleepRecord() {
        if (sessionCheckFailed) {
            requestSessionRecovery(preserveDraft = true)
            return
        }
        if (sessionChecking || sessionCommitting || timerBusy) return
        if (vm.mutation.isPending) return
        if (isEditMode && editingRecord == null) { loadEditRecord(); return }
        val babyId = currentDraftBabyId()
        if (babyId == null) {
            ToastUtils.showShort(getString(R.string.no_baby_selected))
            return
        }
        if (isEditMode && editingRecord?.babyId != babyId) return
        clearValidationErrors()

        // 未完成的手动时间必须先纠正，不能让自动暂停填入“现在”覆盖用户输入。
        val startInput = binding.timerPanel.startInput
        if (!startInput.hasValidTime()) {
            showFieldValidationError(startInput,
                if (startInput.text.isNullOrBlank()) R.string.sleep_start_required else R.string.invalid_time)
            return
        }
        val endInput = binding.timerPanel.endInput
        if (!endInput.text.isNullOrBlank() && !endInput.hasValidTime()) {
            showFieldValidationError(endInput, R.string.invalid_time)
            return
        }

        val startTime = startInput.getTimestamp()
        val endTime = endInput.getTimestamp()
        val duration = binding.timerPanel.timerView.getDuration()

        val sleepRecord = SleepRecord(
            sleepId = editingRecord?.sleepId ?: 0,
            babyId = babyId,
            sleepStart = startTime,
            sleepEnd = endTime,
            sleepDuration = duration,
            note = binding.etNote.text.toString(),
            createdAt = editingRecord?.createdAt ?: System.currentTimeMillis(),
            timerData = editingRecord?.timerData, timerSessionId = editingRecord?.timerSessionId,
            timerRevision = editingRecord?.timerRevision ?: 0L
        )

        vm.save(sleepRecord, mutationTarget(babyId),
            if (isEditMode) null else timerController?.ongoingSessionId)
    }

    /** 删除是记录纠错的一部分，成功后同步触发统计与预测刷新。 */
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
                if (target.belongsTo(draftId, currentDraftBabyId(), mainVm.navTarget.value)) {
                    vm.delete(record.sleepId, target)
                }
            }
        )
    }

    private fun setMutationInProgress(inProgress: Boolean) {
        mutationInProgress = inProgress
        renderInputFreeze()
    }

    private fun renderInputFreeze() {
        if (view == null) return
        val inProgress = mutationInProgress || sessionChecking || sessionCommitting || timerBusy
        binding.tvSave.setText(if (!isEditMode && vm.timer.state.value.snapshot?.status == com.zero.babydata.domain.TimerStatus.RUNNING) R.string.timer_sleep_finish else R.string.save)
        binding.tvSave.isEnabled = !inProgress
        binding.tvSave.alpha = if (inProgress) 0.7f else 1f
        val frozen = inProgress || sessionCheckFailed
        if (frozen) timerController?.dismissPendingUi()
        formFreeze.render(frozen, binding.cardTimer)
        applyTimerLockState()
        updateToolbarAction()
    }

    /** 草稿恢复先核对同一会话的数据库结果；核验完成前不能继续计时或改写冻结输入。 */
    private fun requestSessionRecovery(preserveDraft: Boolean) {
        sessionChecking = false
        sessionCheckFailed = false
        sessionCommitting = false
        renderedRecovery = null
        val babyId = currentDraftBabyId()
        val sessionId = timerController?.ongoingSessionId
        if (isEditMode || babyId == null) {
            renderInputFreeze()
            return
        }
        preserveRecoveredDraft = preserveDraft
        sessionChecking = true
        renderInputFreeze()
        vm.sessions.recover(mutationTarget(babyId), if (preserveDraft) sessionId else null)
    }

    private fun observeSessionRecovery() {
        launchInLifecycle {
            vm.sessions.committingSessions.collect { sessions ->
                sessionCommitting = !isEditMode && timerController?.ongoingSessionId?.let { it in sessions } == true
                renderInputFreeze()
            }
        }
        launchInLifecycle {
            vm.sessions.recovery.collect { state ->
                if (state == null || state === renderedRecovery ||
                    !state.target.belongsTo(draftId, currentDraftBabyId(), mainVm.navTarget.value) || isHidden) return@collect
                renderedRecovery = state
                sessionChecking = state.loading
                sessionCheckFailed = state.errorRes != null
                if (!state.loading) {
                    when {
                        state.errorRes != null -> {
                            if (!preserveRecoveredDraft) state.session?.let(::restoreOngoingSleep)
                            ToastUtils.showShort(getString(state.errorRes))
                        }
                        state.committed -> {
                            resetPage()
                            ToastUtils.showShort(getString(R.string.sleep_save_success))
                        }
                        else -> state.session?.let(::restoreOngoingSleep)
                    }
                }
                renderInputFreeze()
            }
        }
    }

    private fun mutationTarget(babyId: Int) = RecordMutationController.Target(
        draftId, babyId, editRecordId, mainVm.navTarget.value, getReturnTarget()
    )

    /** 生命周期重建后由新 View 消费结果，旧 Fragment 不再被保存协程持有。 */
    private fun observeMutation() {
        launchInLifecycle {
            vm.mutation.state.collect { state ->
                setMutationInProgress(state is RecordMutationController.State.Running)
                if (state is RecordMutationController.State.Finished && vm.mutation.consume(state)) {
                    val belongs = state.target.belongsTo(draftId, currentDraftBabyId(), mainVm.navTarget.value)
                    if (state.target.draftId == draftId && state.errorRes == null) {
                        hasUnsavedChanges = false
                        resetPage()
                    }
                    if (belongs && !isHidden) {
                        if (state.errorRes != null && state.field != null) {
                            val field = when (state.field) {
                                RecordMutationController.Field.START -> binding.timerPanel.startInput
                                RecordMutationController.Field.END -> binding.timerPanel.endInput
                                else -> binding.timerPanel.endInput
                            }
                            showFieldValidationError(field, state.errorRes)
                        }

                        val message = state.errorRes ?: if (state.kind == RecordMutationController.Kind.DELETE)
                            R.string.record_delete_success else R.string.sleep_save_success
                        ToastUtils.showShort(getString(message))
                        if (state.errorRes == null) mainVm.navigateTo(state.target.destination)
                        else if (!isEditMode && timerController?.ongoingSessionId != null) {
                            requestSessionRecovery(preserveDraft = true)
                        }
                    }
                }
            }
        }
    }

    /** 错误与具体字段绑定，确保用户能立即定位并修正记录。 */
    @Suppress("DEPRECATION")
    private fun showFieldValidationError(field: TextView, @StringRes messageRes: Int) {
        val message = getString(messageRes)
        field.error = message
        field.requestFocus()
        field.announceForAccessibility(message)
    }

    private fun clearValidationErrors() {
        binding.timerPanel.startInput.error = null
        binding.timerPanel.endInput.error = null
    }

    /**
     * 加载上次睡眠记录
     */
    private fun loadLastSleepRecord() {
        val babyId = currentDraftBabyId() ?: return
        vm.loadLastSleepRecord(babyId)
    }

    /**
     * 重置页面数据
     */
    private fun resetPage() {
        renderedTimerRef = null
        pendingTimerRawDraft = null
        timerBusy = false
        discardPending = false
        reviewRevision = -1L
        isProgrammaticChange = true
        
        // 清空时间输入与计时器
        timerController?.reset()
        
        // 清空备注
        binding.etNote.setText("")
        
        // 重置未保存标记
        hasUnsavedChanges = false
        
        isProgrammaticChange = false
    }

    /**
     * 标记为有未保存的更改
     */
    private fun markAsUnsaved() {
        if (!isProgrammaticChange) hasUnsavedChanges = true
    }

    /**
     * 处理返回
     */
    override fun onSystemBackPressed(): Boolean {
        handleBack()
        return true
    }

    private fun handleBack() {
        if (mutationInProgress || sessionChecking || sessionCommitting) {
            mainVm.navigateTo(getReturnTarget())
            return
        }
        if (hasUnsavedChanges) {
            val expectedDraftId = draftId
            val expectedBabyId = currentDraftBabyId()
            val expectedSessionId = timerController?.ongoingSessionId
            DialogHelper.showConfirmDialog(
                context = requireContext(),
                title = getString(R.string.tip),
                content = getString(if (expectedSessionId != null) R.string.timer_leave_tip else R.string.unsaved_record_tip),
                confirmText = getString(R.string.confirm),
                cancelText = getString(R.string.cancel),
                onConfirm = {
                    if (isHidden || view == null || draftId != expectedDraftId || currentDraftBabyId() != expectedBabyId) return@showConfirmDialog
                    hasUnsavedChanges = false
                    mainVm.navigateTo(getReturnTarget())
                }
            )
        } else {
            mainVm.navigateTo(getReturnTarget())
        }
    }

    private fun getReturnTarget(): NavTarget {
        return (mainVm.navTarget.value as? NavTarget.SleepRecord)?.returnTarget ?: NavTarget.Dashboard
    }
}

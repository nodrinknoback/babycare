package com.zero.babycare.home.record.event

import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import com.zero.babycare.home.BabyRecordChangeBus
import com.zero.babydata.domain.BabyDataHelper
import com.zero.babydata.room.BabyRepository
import com.zero.babydata.entity.EventExtraData
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.EventType
import com.zero.babydata.entity.GenericData
import com.zero.components.base.vm.BaseViewModel
import com.zero.common.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.zero.babycare.home.record.RecordMutationController
import com.zero.babycare.home.record.PersistentTimerEditor
import com.zero.babydata.domain.TimerRecordKind
import com.zero.babydata.domain.TimerStatus

/**
 * 事件记录 ViewModel
 */
class EventRecordViewModel(private val injectedRepository: BabyRepository? = null) : BaseViewModel() {
    // 延迟获取默认数据源，让草稿状态恢复独立于数据库初始化，并支持隔离的数据层验证。
    private val repository by lazy { injectedRepository ?: BabyDataHelper.repository }
    internal val mutation = RecordMutationController(viewModelScope)
    internal val timer = PersistentTimerEditor(viewModelScope, { repository }, TimerRecordKind.EVENT)

    /** 活动槽位由具体类型决定；切换页面仅打开对应业务行，不结束之前的活动。 */
    internal fun openTimer(babyId: Int, eventType: Int, expectedSessionId: String? = null) {
        if (EventType.hasDuration(eventType)) timer.open(babyId, eventType, expectedSessionId)
    }

    /** 开始时只冻结当前活动输入，运行事实统一交给公共持久计时入口。 */
    internal fun startTimer(babyId: Int, startAt: Long, automaticStart: Boolean, note: String) {
        val subtype = _selectedSubtype.value ?: return
        if (!EventType.hasDuration(subtype.type) || mutation.isPending) return
        val state = timer.state.value
        if (state.babyId != babyId || state.eventType != subtype.type || state.busy || state.errorRes != null) return
        timer.startEvent(EventRecord(babyId = babyId, type = subtype.type, time = startAt,
            note = note, createdAt = System.currentTimeMillis()), automaticStart)
    }

    /** 校验反馈保留字段身份；重复保存有独立请求号，用户回到备注后仍能重新定位。 */
    enum class ValidationField {
        START_TIME, END_TIME, GROWTH_VALUE, TEMPERATURE, MEDICINE_NAME,
        VACCINE_NAME, SYMPTOM, MILESTONE_NAME, CUSTOM_EVENT_NAME
    }

    data class FieldValidation(
        val field: ValidationField,
        @param:StringRes val messageResId: Int,
        val requestId: Long
    )

    private var validationRequestId = 0L
    private val _fieldValidation = MutableStateFlow<FieldValidation?>(null)
    val fieldValidation: StateFlow<FieldValidation?> = _fieldValidation.asStateFlow()

    /** 输入更改仅撤销该字段的旧反馈；是否可保存仍由下次完整校验决定。 */
    fun clearFieldValidation(field: ValidationField? = null) {
        if (field == null || _fieldValidation.value?.field == field) _fieldValidation.value = null
    }

    /** 草稿只保存未提交的页面状态，详情原始输入由 View 层层级状态保留。 */
    data class Draft(
        val categoryId: Int?,
        val subtypeType: Int?,
        val eventTime: Long,
        val durationStartTime: Long?,
        val endTime: Long?
    )

    /** 只比较页面可编辑字段的原始输入，不对旧 JSON 对象图执行深层 equals/hash。 */
    internal data class ExtraDataInputs(val type: Int, val values: List<String>)

    internal data class ExtraDataEdit(
        val originalRecord: EventRecord,
        val originalInputs: ExtraDataInputs?,
        val currentInputs: ExtraDataInputs?
    )

    fun captureDraft(): Draft = Draft(
        _selectedCategory.value?.id, _selectedSubtype.value?.type, _eventTime.value,
        _durationStartTime.value, _endTime.value
    )

    /** 恢复依赖字段后再发布类型，防止详情重建时清空原有时间。 */
    fun restoreDraft(draft: Draft) {
        _extraData.value = null
        _eventTime.value = draft.eventTime
        _durationStartTime.value = draft.durationStartTime
        _endTime.value = draft.endTime
        _selectedCategory.value = draft.categoryId?.let(EventCategory::fromId)
        _selectedSubtype.value = draft.subtypeType?.let(EventSubtype::fromType)
    }

    /** 当前选中的大类 */
    private val _selectedCategory = MutableStateFlow<EventCategory?>(null)
    val selectedCategory: StateFlow<EventCategory?> = _selectedCategory

    /** 当前选中的子类型 */
    private val _selectedSubtype = MutableStateFlow<EventSubtype?>(null)
    val selectedSubtype: StateFlow<EventSubtype?> = _selectedSubtype

    /** 事件时间 */
    private val _eventTime = MutableStateFlow(System.currentTimeMillis())
    val eventTime: StateFlow<Long> = _eventTime

    /** 活动类事件的显式开始时间，只有点击开始计时或手动输入开始时间后才写入 */
    private val _durationStartTime = MutableStateFlow<Long?>(null)
    val durationStartTime: StateFlow<Long?> = _durationStartTime

    /** 结束时间（活动类事件） */
    private val _endTime = MutableStateFlow<Long?>(null)
    val endTime: StateFlow<Long?> = _endTime

    /** 扩展数据 */
    private val _extraData = MutableStateFlow<EventExtraData?>(null)
    val extraData: StateFlow<EventExtraData?> = _extraData

    /** 备注 */
    private val _note = MutableStateFlow("")
    val note: StateFlow<String> = _note

    /**
     * 选择大类
     */
    fun selectCategory(category: EventCategory) {
        clearFieldValidation()
        // 先清理依赖旧类型的详情状态，再发出分类/子类型变化，避免页面重建时读到旧表单数据。
        clearDurationTiming()
        _extraData.value = null
        _selectedCategory.value = category
        _selectedSubtype.value = null
    }

    /**
     * 选择子类型
     */
    fun selectSubtype(subtype: EventSubtype) {
        clearFieldValidation()
        val subtypeChanged = _selectedSubtype.value?.type != subtype.type
        if (subtypeChanged) {
            // selectedSubtype 的观察者会同步重建详情区，必须在发出新类型前清空旧计时状态。
            clearDurationTiming()
        }
        _extraData.value = null
        _selectedSubtype.value = subtype
    }

    /**
     * 设置事件时间
     */
    fun setEventTime(time: Long) {
        _eventTime.value = time
    }

    /**
     * 设置活动类事件的开始时间。
     *
     * 页面顶部事件时间是普通事件的默认发生时间；活动类事件只有用户明确开始计时
     * 或手动选择开始时间后，才把该值作为可保存的开始时间。
     */
    fun setDurationStartTime(time: Long?) {
        val normalizedTime = time?.takeIf { it > 0L }
        _durationStartTime.value = normalizedTime
        if (normalizedTime != null) {
            _eventTime.value = normalizedTime
        }
    }

    /**
     * 设置结束时间
     */
    fun setEndTime(time: Long?) {
        _endTime.value = time
    }

    /**
     * 设置扩展数据
     */
    fun setExtraData(data: EventExtraData?) {
        // 通用扩展没有编辑表单；旧嵌套对象只能沿用原文，不能进入 StateFlow 的深层相等比较。
        _extraData.value = data.takeUnless { it is GenericData }
    }

    /** 没有详情表单的事件从加载开始就保留原文，避免把旧深层 JSON 解析成通用对象图。 */
    internal fun editableExtraData(record: EventRecord): EventExtraData? =
        if (hasEditableExtraData(record.type)) EventExtraData.parse(record.type, record.extraData) else null

    private fun hasEditableExtraData(type: Int): Boolean =
        EventType.isDiaper(type) || EventType.isGrowth(type) || EventType.isMilestone(type) ||
            type == EventType.HEALTH_TEMPERATURE || type == EventType.HEALTH_MEDICINE ||
            type == EventType.HEALTH_VACCINE || type == EventType.HEALTH_SYMPTOM || type == EventType.OTHER_CUSTOM

    /**
     * 设置备注
     */
    fun setNote(note: String) {
        _note.value = note
    }

    internal val editedRecord = com.zero.babycare.home.record.RecordReadController<com.zero.babycare.home.record.EditRecordKey, EventRecord>(viewModelScope) {
        repository.getEventRecordById(it.recordId)
    }
    internal fun loadEventRecordById(key: com.zero.babycare.home.record.EditRecordKey) = editedRecord.load(key)

    /**
     * 判断当前事件类型是否需要时长
     */
    fun requiresDuration(): Boolean {
        val subtype = _selectedSubtype.value ?: return false
        return EventType.hasDuration(subtype.type)
    }

    /**
     * 验证数据是否完整
     */
    fun validateData(): ValidationResult {
        val subtype = _selectedSubtype.value
            ?: return ValidationResult.Error(R.string.please_select_event_type)

        // 需要时长的事件验证结束时间
        if (requiresDuration()) {
            val start = _durationStartTime.value
            if (start == null || start <= 0L) {
                return ValidationResult.Error(R.string.start_time_required, ValidationField.START_TIME)
            }
            val end = _endTime.value
            if (end == null || end <= start) {
                return ValidationResult.Error(R.string.event_end_time_invalid, ValidationField.END_TIME)
            }
        }

        // 生长类验证数值
        if (EventType.isGrowth(subtype.type)) {
            val data = _extraData.value
            if (data !is com.zero.babydata.entity.GrowthData) {
                return ValidationResult.Error(R.string.event_value_required, ValidationField.GROWTH_VALUE)
            }
        }

        // 体温验证
        if (subtype.type == EventType.HEALTH_TEMPERATURE) {
            val data = _extraData.value
            if (data !is com.zero.babydata.entity.TemperatureData) {
                return ValidationResult.Error(R.string.temperature_value_required, ValidationField.TEMPERATURE)
            }
        }

        // 用药验证
        if (subtype.type == EventType.HEALTH_MEDICINE) {
            val data = _extraData.value
            if (data !is com.zero.babydata.entity.MedicineData || data.name.isBlank()) {
                return ValidationResult.Error(R.string.medicine_name_hint, ValidationField.MEDICINE_NAME)
            }
        }

        // 疫苗验证
        if (subtype.type == EventType.HEALTH_VACCINE) {
            val data = _extraData.value
            if (data !is com.zero.babydata.entity.VaccineData || data.name.isBlank()) {
                return ValidationResult.Error(R.string.vaccine_name_hint, ValidationField.VACCINE_NAME)
            }
        }

        // 症状验证
        if (subtype.type == EventType.HEALTH_SYMPTOM) {
            val data = _extraData.value
            if (data !is com.zero.babydata.entity.SymptomData || data.description.isNullOrBlank()) {
                return ValidationResult.Error(R.string.symptom_hint, ValidationField.SYMPTOM)
            }
        }

        // 自定义里程碑验证
        if (subtype.type == EventType.MILESTONE_CUSTOM) {
            val data = _extraData.value
            if (data !is com.zero.babydata.entity.MilestoneData || data.name.isNullOrBlank()) {
                return ValidationResult.Error(R.string.milestone_name_hint, ValidationField.MILESTONE_NAME)
            }
        }

        // 自定义事件验证
        if (subtype.type == EventType.OTHER_CUSTOM) {
            val data = _extraData.value
            val hasValue = data is com.zero.babydata.entity.CustomEventData &&
                (!data.name.isNullOrBlank() || !data.description.isNullOrBlank())
            if (!hasValue) {
                return ValidationResult.Error(R.string.custom_event_required, ValidationField.CUSTOM_EVENT_NAME)
            }
        }

        return ValidationResult.Success
    }

    /**
     * 保存事件记录
     */
    internal fun saveRecord(
        target: RecordMutationController.Target,
        createdAt: Long? = null,
        extraDataEdit: ExtraDataEdit? = null,
    ) {
        val timerState = timer.state.value.takeIf {
            target.recordId == null && it.babyId == target.babyId && it.eventType == _selectedSubtype.value?.type
        }
        if (mutation.isPending || timerState?.busy == true ||
            (timerState?.snapshot == null && timerState?.errorRes != null)) return
        val timedSnapshot = timer.state.value.snapshot?.takeIf {
            target.recordId == null && it.ref.babyId == target.babyId &&
                it.event?.type == _selectedSubtype.value?.type
        }
        if (timedSnapshot?.status == TimerStatus.COMPLETED) {
            mutation.reject(target, RecordMutationController.Kind.SAVE, R.string.record_draft_already_saved)
            return
        }
        // 实时活动先关闭当前片段，不能为了满足结束字段校验在 View 里另算一次结束时间。
        val validation = if (timedSnapshot != null) ValidationResult.Success else validateData()
        if (validation is ValidationResult.Error) {
            // 字段校验不伪装成数据库失败，页面能保留说明并定位到可修正输入。
            if (validation.field != null) {
                _fieldValidation.value = FieldValidation(validation.field, validation.messageResId, ++validationRequestId)
            } else {
                mutation.reject(target, RecordMutationController.Kind.SAVE, validation.messageResId)
            }
            return
        }
        clearFieldValidation()

        val subtype = _selectedSubtype.value ?: return
        val recordTime = if (EventType.hasDuration(subtype.type)) {
            _durationStartTime.value ?: _eventTime.value
        } else {
            _eventTime.value
        }

        // 原文只属于同一宝宝、记录和类型；恢复后的输入仍须与首次回填基线一致。
        val original = extraDataEdit?.originalRecord?.copy()
        val originalInputs = extraDataEdit?.originalInputs
        val currentInputs = extraDataEdit?.currentInputs
        val hasOriginalExtra = original != null && original.eventId == target.recordId &&
            original.babyId == target.babyId && original.type == subtype.type
        val inputsUnchanged = originalInputs != null && currentInputs != null &&
            originalInputs.type == subtype.type && currentInputs.type == subtype.type &&
            originalInputs.values == currentInputs.values

        // 冻结所有输入，再在受控保存协程内序列化；失败不写空值，也不绕过错误状态通道。
        val extraSnapshot = _extraData.value
        val record = (original?.takeIf { hasOriginalExtra } ?: EventRecord()).copy(
                eventId = target.recordId ?: 0,
                babyId = target.babyId,
                type = subtype.type,
                time = recordTime,
                endTime = _endTime.value ?: 0L,
                note = _note.value,
                createdAt = createdAt ?: System.currentTimeMillis()
            )

        mutation.submit(target, RecordMutationController.Kind.SAVE, R.string.save_failed) {
            if (timedSnapshot != null) {
                val stopped = requireNotNull(timer.stopForSave(timedSnapshot.ref.sessionId))
                val stoppedRecord = requireNotNull(stopped.event)
                // 保存失败仍保留 STOPPED 行与原片段；重试不能插入另一条事件或延长结束时间。
                timer.finishEvent(stoppedRecord.copy(note = record.note), target.draftId,
                    expectedSessionId = timedSnapshot.ref.sessionId)
                BabyRecordChangeBus.notifyChanged(record.babyId)
                return@submit
            }
            withContext(Dispatchers.IO) {
                // 旧草稿没有输入基线：无详情表单可直接保真；有表单只接受扁平已知值严格相等。
                // GenericData 从不解析或比较，类型切换也不能把旧通用扩展带入新事件。
                val keepOriginalExtra = hasOriginalExtra && (
                    !hasEditableExtraData(subtype.type) || inputsUnchanged ||
                        (originalInputs == null && extraSnapshot != null &&
                            extraSnapshot == editableExtraData(requireNotNull(original)))
                    )
                record.extraData = if (keepOriginalExtra) requireNotNull(original).extraData
                    else extraSnapshot?.toJson() ?: ""
                if (target.recordId != null) repository.updateEventRecord(record)
                else repository.insertEventRecordOnce(record, target.draftId)
            }
            BabyRecordChangeBus.notifyChanged(record.babyId)
        }
    }

    /** 删除事件后通知所有记录消费者，保证时间轴、统计和预测立即失效重算。 */
    internal fun deleteRecord(
        eventId: Int,
        target: RecordMutationController.Target
    ) {
        mutation.submit(target, RecordMutationController.Kind.DELETE, R.string.record_delete_failed) {
            repository.deleteEventRecordById(eventId)
            BabyRecordChangeBus.notifyChanged(target.babyId)
        }
    }

    /**
     * 重置状态
     */
    fun reset() {
        clearFieldValidation()
        _selectedCategory.value = null
        _selectedSubtype.value = null
        _eventTime.value = System.currentTimeMillis()
        clearDurationTiming()
        _extraData.value = null
        _note.value = ""
    }

    private fun clearDurationTiming() {
        _durationStartTime.value = null
        _endTime.value = null
    }

    /**
     * 验证结果
     */
    sealed class ValidationResult {
        object Success : ValidationResult()
        data class Error(
            @param:StringRes val messageResId: Int,
            val field: ValidationField? = null
        ) : ValidationResult()
    }
}

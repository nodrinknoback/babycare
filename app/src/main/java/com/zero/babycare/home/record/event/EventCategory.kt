package com.zero.babycare.home.record.event

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.zero.babydata.entity.EventType
import com.zero.common.R

/**
 * 事件大类定义
 */
enum class EventCategory(
    val id: Int,
    @param:StringRes val nameResId: Int,
    @param:DrawableRes val iconResId: Int,
    @param:ColorRes val colorResId: Int
) {
    DIAPER(
        id = EventType.CATEGORY_DIAPER,
        nameResId = R.string.event_category_diaper,
        iconResId = R.drawable.ic_event_diaper,
        colorResId = R.color.event_diaper
    ),
    HEALTH(
        id = EventType.CATEGORY_HEALTH,
        nameResId = R.string.event_category_health,
        iconResId = R.drawable.ic_event_health,
        colorResId = R.color.event_health
    ),
    GROWTH(
        id = EventType.CATEGORY_GROWTH,
        nameResId = R.string.event_category_growth,
        iconResId = R.drawable.ic_event_growth,
        colorResId = R.color.event_growth
    ),
    MILESTONE(
        id = EventType.CATEGORY_MILESTONE,
        nameResId = R.string.event_category_milestone,
        iconResId = R.drawable.ic_event_milestone,
        colorResId = R.color.event_milestone
    ),
    CARE(
        id = EventType.CATEGORY_CARE,
        nameResId = R.string.event_category_care,
        iconResId = R.drawable.ic_event_care,
        colorResId = R.color.event_care
    ),
    ACTIVITY(
        id = EventType.CATEGORY_ACTIVITY,
        nameResId = R.string.event_category_activity,
        iconResId = R.drawable.ic_event_activity,
        colorResId = R.color.event_activity
    ),
    OTHER(
        id = EventType.CATEGORY_OTHER,
        nameResId = R.string.event_category_other,
        iconResId = R.drawable.ic_event_other,
        colorResId = R.color.event_other
    );

    companion object {
        fun fromId(id: Int): EventCategory? = entries.find { it.id == id }
    }
}

/**
 * 事件子类型定义
 */
data class EventSubtype(
    val type: Int,
    @param:StringRes val nameResId: Int,
    @param:DrawableRes val iconResId: Int,
    val category: EventCategory
) {
    /** 卡片的分词换行不进入记录名称，保证时间轴、导出和读屏仍使用连续文案。 */
    val cardLabelResId: Int
        @StringRes get() = if (type == EventType.DIAPER_DRY) R.string.event_diaper_dry_card_label else nameResId

    /** 本次线稿只用于记录页四张排泄卡，其他页面继续使用原图标资源。 */
    val cardIconResId: Int
        @DrawableRes get() = when (type) {
            EventType.DIAPER_WET -> com.zero.babycare.R.drawable.ic_record_diaper_wet
            EventType.DIAPER_DIRTY -> com.zero.babycare.R.drawable.ic_record_diaper_dirty
            EventType.DIAPER_MIXED -> com.zero.babycare.R.drawable.ic_record_diaper_mixed
            EventType.DIAPER_DRY -> com.zero.babycare.R.drawable.ic_record_diaper_dry
            else -> iconResId
        }

    companion object {
        /**
         * 获取指定大类下的所有子类型
         */
        fun getSubtypes(category: EventCategory): List<EventSubtype> = when (category) {
            EventCategory.DIAPER -> listOf(
                EventSubtype(EventType.DIAPER_WET, R.string.event_diaper_wet, R.drawable.ic_event_diaper_wet, category),
                EventSubtype(EventType.DIAPER_DIRTY, R.string.event_diaper_dirty, R.drawable.ic_event_diaper_dirty, category),
                EventSubtype(EventType.DIAPER_MIXED, R.string.event_diaper_mixed, R.drawable.ic_event_diaper_mixed, category),
                EventSubtype(EventType.DIAPER_DRY, R.string.event_diaper_dry, R.drawable.ic_event_diaper_dry, category)
            )
            EventCategory.HEALTH -> listOf(
                EventSubtype(EventType.HEALTH_TEMPERATURE, R.string.event_health_temperature, R.drawable.ic_event_temperature, category),
                EventSubtype(EventType.HEALTH_MEDICINE, R.string.event_health_medicine, R.drawable.ic_event_medicine, category),
                EventSubtype(EventType.HEALTH_VACCINE, R.string.event_health_vaccine, R.drawable.ic_event_vaccine, category),
                EventSubtype(EventType.HEALTH_DOCTOR, R.string.event_health_doctor, R.drawable.ic_event_doctor, category),
                EventSubtype(EventType.HEALTH_SYMPTOM, R.string.event_health_symptom, R.drawable.ic_event_symptom, category)
            )
            EventCategory.GROWTH -> listOf(
                EventSubtype(EventType.GROWTH_WEIGHT, R.string.event_growth_weight, R.drawable.ic_event_weight, category),
                EventSubtype(EventType.GROWTH_HEIGHT, R.string.event_growth_height, R.drawable.ic_event_height, category),
                EventSubtype(EventType.GROWTH_HEAD, R.string.event_growth_head, R.drawable.ic_event_head, category)
            )
            EventCategory.MILESTONE -> listOf(
                EventSubtype(EventType.MILESTONE_ROLL, R.string.event_milestone_roll, R.drawable.ic_event_roll, category),
                EventSubtype(EventType.MILESTONE_SIT, R.string.event_milestone_sit, R.drawable.ic_event_sit, category),
                EventSubtype(EventType.MILESTONE_CRAWL, R.string.event_milestone_crawl, R.drawable.ic_event_crawl, category),
                EventSubtype(EventType.MILESTONE_STAND, R.string.event_milestone_stand, R.drawable.ic_event_stand, category),
                EventSubtype(EventType.MILESTONE_WALK, R.string.event_milestone_walk, R.drawable.ic_event_walk, category),
                EventSubtype(EventType.MILESTONE_FIRST_WORD, R.string.event_milestone_first_word, R.drawable.ic_event_talk, category),
                EventSubtype(EventType.MILESTONE_FIRST_TOOTH, R.string.event_milestone_first_tooth, R.drawable.ic_event_tooth, category),
                EventSubtype(EventType.MILESTONE_CUSTOM, R.string.event_milestone_custom, R.drawable.ic_event_milestone_custom, category)
            )
            EventCategory.CARE -> listOf(
                EventSubtype(EventType.CARE_BATH, R.string.event_care_bath, R.drawable.ic_event_bath, category),
                EventSubtype(EventType.CARE_NAIL, R.string.event_care_nail, R.drawable.ic_event_nail, category),
                EventSubtype(EventType.CARE_SKINCARE, R.string.event_care_skincare, R.drawable.ic_event_skincare, category),
                EventSubtype(EventType.CARE_MASSAGE, R.string.event_care_massage, R.drawable.ic_event_massage, category),
                EventSubtype(EventType.CARE_NOSE, R.string.event_care_nose, R.drawable.ic_event_nose, category),
                EventSubtype(EventType.CARE_EAR, R.string.event_care_ear, R.drawable.ic_event_ear, category)
            )
            EventCategory.ACTIVITY -> listOf(
                EventSubtype(EventType.ACTIVITY_OUTDOOR, R.string.event_activity_outdoor, R.drawable.ic_event_outdoor, category),
                EventSubtype(EventType.ACTIVITY_TUMMY_TIME, R.string.event_activity_tummy, R.drawable.ic_event_tummy, category),
                EventSubtype(EventType.ACTIVITY_SWIMMING, R.string.event_activity_swimming, R.drawable.ic_event_swimming, category),
                EventSubtype(EventType.ACTIVITY_PLAY, R.string.event_activity_play, R.drawable.ic_event_play, category)
            )
            EventCategory.OTHER -> listOf(
                EventSubtype(EventType.OTHER_BURP, R.string.event_other_burp, R.drawable.ic_event_burp, category),
                EventSubtype(EventType.OTHER_CRY, R.string.event_other_cry, R.drawable.ic_event_cry, category),
                EventSubtype(EventType.OTHER_SPIT_UP, R.string.event_other_spit_up, R.drawable.ic_event_spit_up, category),
                EventSubtype(EventType.OTHER_CUSTOM, R.string.event_other_custom, R.drawable.ic_event_custom, category)
            )
        }
        
        /**
         * 根据事件类型获取子类型
         */
        fun fromType(type: Int): EventSubtype? {
            val category = EventCategory.fromId(EventType.getCategory(type)) ?: return null
            return getSubtypes(category).find { it.type == type }
        }
    }
}


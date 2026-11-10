package com.zero.babycare.home.record.event

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseQuickAdapter
import com.zero.babycare.databinding.ItemEventSubtypeBinding
import com.zero.common.ext.getThemeColor

/**
 * 事件子类型网格适配器
 */
class EventSubtypeAdapter(
    private val onSubtypeSelected: (EventSubtype) -> Unit
) : BaseQuickAdapter<EventSubtype, EventSubtypeAdapter.VH>() {

    private var selectedType: Int? = null
    private var isLocked = false
    private val lockedAlpha = 0.4f
    private var selectionHost: RecyclerView? = null

    inner class VH(
        parent: ViewGroup,
        viewType: Int,
        // 两种卡片保留相同绑定节点；独立 viewType 避免切换类别时复用到排泄专属排版。
        val binding: ItemEventSubtypeBinding = ItemEventSubtypeBinding.bind(
            LayoutInflater.from(parent.context).inflate(viewType, parent, false)
        )
    ) : RecyclerView.ViewHolder(binding.root) {
        init {
            // 使用卡片自身的 Checkable 语义；RecyclerView 等待局部更新时也不能丢失选中状态。
            binding.cardRoot.isCheckable = true
            binding.cardRoot.checkedIcon = null
            binding.cardRoot.isFocusable = true
            binding.tvName.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        selectionHost = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        selectionHost = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun onCreateViewHolder(context: Context, parent: ViewGroup, viewType: Int): VH {
        return VH(parent, viewType)
    }

    override fun getItemViewType(position: Int, list: List<EventSubtype>): Int {
        return if (list[position].category == EventCategory.DIAPER) {
            com.zero.babycare.R.layout.item_record_diaper_subtype
        } else {
            com.zero.babycare.R.layout.item_event_subtype
        }
    }

    override fun onBindViewHolder(holder: VH, position: Int, item: EventSubtype?) {
        if (item == null) return

        val context = holder.binding.root.context

        with(holder.binding) {
            ivIcon.setImageResource(item.cardIconResId)
            tvName.setText(item.cardLabelResId)
            cardRoot.contentDescription = context.getString(item.nameResId)

            if (item.category == EventCategory.DIAPER) {
                // 排泄卡使用已确认的单色线稿，图标占位透明，避免叠加第二块色底。
                iconContainer.background = null
                ivIcon.setColorFilter(context.getThemeColor(com.zero.common.R.attr.colorDiaperSubtypeIcon))
            } else {
                // 设置图标背景颜色（使用分类对应的浅色）
                val lightColorResId = when (item.category) {
                    EventCategory.DIAPER -> com.zero.common.R.color.event_diaper_light
                    EventCategory.HEALTH -> com.zero.common.R.color.event_health_light
                    EventCategory.GROWTH -> com.zero.common.R.color.event_growth_light
                    EventCategory.MILESTONE -> com.zero.common.R.color.event_milestone_light
                    EventCategory.CARE -> com.zero.common.R.color.event_care_light
                    EventCategory.ACTIVITY -> com.zero.common.R.color.event_activity_light
                    EventCategory.OTHER -> com.zero.common.R.color.event_other_light
                }

                val bgDrawable = iconContainer.background as? GradientDrawable
                    ?: GradientDrawable().also { iconContainer.background = it }
                bgDrawable.setColor(ContextCompat.getColor(context, lightColorResId))
                bgDrawable.shape = GradientDrawable.OVAL

                // 设置图标颜色
                val categoryColor = ContextCompat.getColor(context, item.category.colorResId)
                ivIcon.setColorFilter(categoryColor)
            }

            cardRoot.setOnClickListener {
                if (!isLocked && item.type != selectedType) {
                    setSelectedType(item.type)
                    onSubtypeSelected(item)
                }
            }
        }
        renderSelection(holder, item)
    }

    override fun onBindViewHolder(holder: VH, position: Int, item: EventSubtype?, payloads: List<Any>) {
        if (item != null && payloads.all { it === SELECTION_PAYLOAD }) renderSelection(holder, item)
        else super.onBindViewHolder(holder, position, item, payloads)
    }

    /** 原节点同步视觉和语义，再派发局部更新，避免读屏在下一帧前仍读到旧选择。 */
    private fun renderSelection(holder: VH, item: EventSubtype) {
        val isSelected = item.type == selectedType
        with(holder.binding) {
            val context = cardRoot.context
            cardRoot.isSelected = isSelected
            if (cardRoot.isChecked != isSelected) {
                // MaterialCardView 仅在 enabled 时接受 checked；程序回填锁定项也必须同步状态。
                cardRoot.isEnabled = true
                cardRoot.isChecked = isSelected
            }
            cardRoot.isEnabled = !isLocked
            cardRoot.alpha = if (isLocked) lockedAlpha else 1f
            if (item.category == EventCategory.DIAPER) {
                renderDiaperSelection(holder, isSelected)
                return
            }
            val brandColor = context.getThemeColor(com.zero.common.R.attr.colorBrandForeground)
            cardRoot.setCardBackgroundColor(
                ContextCompat.getColor(
                    context,
                    if (isSelected) com.zero.common.R.color.control_surface_selected
                    else com.zero.common.R.color.control_surface_default
                )
            )

            // 浅暖底与品牌前景共同表达选中；前景 token 在深色模式独立提高明度。
            if (isSelected) {
                cardRoot.strokeWidth = context.resources
                    .getDimensionPixelSize(com.zero.common.R.dimen.surface_stroke_width)
                    .coerceAtLeast(1)
                cardRoot.strokeColor = brandColor
            } else {
                cardRoot.strokeWidth = 0
            }
            tvName.setTextColor(
                if (isSelected) {
                    brandColor
                } else {
                    context.getThemeColor(com.zero.common.R.attr.colorTextPrimary)
                }
            )

        }
    }

    /** 专属色板只用于排泄四卡，日夜值由主题资源提供；不改变其他事件的状态。 */
    private fun renderDiaperSelection(holder: VH, isSelected: Boolean) {
        with(holder.binding) {
            val context = cardRoot.context
            cardRoot.setCardBackgroundColor(context.getThemeColor(
                if (isSelected) com.zero.common.R.attr.colorDiaperSubtypeSelectedSurface
                else com.zero.common.R.attr.colorDiaperSubtypeSurface
            ))
            cardRoot.strokeWidth = context.resources
                .getDimensionPixelSize(
                    if (isSelected) com.zero.common.R.dimen.dp_1
                    else com.zero.common.R.dimen.surface_stroke_width
                ).coerceAtLeast(1)
            cardRoot.strokeColor = context.getThemeColor(
                if (isSelected) com.zero.common.R.attr.colorDiaperSubtypeSelectedBorder
                else com.zero.common.R.attr.colorDiaperSubtypeBorder
            )
            tvName.setTextColor(context.getThemeColor(
                if (isSelected) com.zero.common.R.attr.colorDiaperSubtypeSelectedText
                else com.zero.common.R.attr.colorTextPrimary
            ))
            tvName.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    /**
     * 设置选中的子类型
     */
    fun setSelectedType(type: Int?) {
        if (type != selectedType) {
            val oldPosition = items.indexOfFirst { it.type == selectedType }
            selectedType = type
            refreshPositions(listOf(oldPosition, items.indexOfFirst { it.type == type }))
        }
    }

    /**
     * 获取选中的子类型
     */
    fun getSelectedType(): Int? = selectedType

    fun setLocked(locked: Boolean) {
        if (isLocked != locked) {
            isLocked = locked
            refreshPositions(items.indices.toList())
        }
    }

    private fun refreshPositions(positions: List<Int>) {
        positions.distinct().filter { it in items.indices }.forEach { position ->
            (selectionHost?.findViewHolderForAdapterPosition(position) as? VH)?.let { holder ->
                renderSelection(holder, items[position])
            }
            // 非空 payload 允许 RecyclerView 复用原 holder，保留键盘和读屏焦点。
            notifyItemChanged(position, SELECTION_PAYLOAD)
        }
    }

    private companion object {
        val SELECTION_PAYLOAD = Any()
    }
}

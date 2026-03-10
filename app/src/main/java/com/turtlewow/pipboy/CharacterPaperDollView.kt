package com.turtlewow.pipboy

import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.min

class CharacterPaperDollView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {
  data class SlotUi(
    val frame: FrameLayout,
    val icon: ImageView,
    val durability: TextView
  )

  private val leftSlots = listOf(
    "HeadSlot",
    "NeckSlot",
    "ShoulderSlot",
    "BackSlot",
    "ChestSlot",
    "ShirtSlot",
    "TabardSlot",
    "WristSlot"
  )

  private val rightSlots = listOf(
    "HandsSlot",
    "WaistSlot",
    "LegsSlot",
    "FeetSlot",
    "Finger0Slot",
    "Finger1Slot",
    "Trinket0Slot",
    "Trinket1Slot"
  )

  private val weaponSlots = listOf(
    "MainHandSlot",
    "SecondaryHandSlot",
    "AmmoSlot"
  )

  private val visibleSlots = leftSlots + rightSlots + weaponSlots

  private val slotViews = LinkedHashMap<String, SlotUi>()
  private val itemBySlot = HashMap<String, EquipItem>()
  private val slotRects = HashMap<String, Rect>()
  private var centerRect = Rect()
  private val emptySlotColorFilter = ColorMatrixColorFilter(
    ColorMatrix().apply { setSaturation(0f) }
  )
  private var iconBinder: ((ImageView, String, EquipItem?) -> Unit)? = null
  private var selectedSlot: String? = null
  var onSlotTap: ((String, EquipItem?) -> Unit)? = null
  var onCharacterFrameTap: (() -> Unit)? = null

  private val centerBuddy = PipBoyBuddyView(context)
  private val centerPlate = FrameLayout(context).apply {
    background = GradientDrawable().apply {
      shape = GradientDrawable.RECTANGLE
      cornerRadius = dp(6).toFloat()
      setColor(Color.parseColor("#1A130F"))
      setStroke(dp(1), Color.parseColor("#6B5536"))
    }
    addView(
      centerBuddy,
      FrameLayout.LayoutParams(
        LayoutParams.MATCH_PARENT,
        LayoutParams.MATCH_PARENT
      ).apply {
        gravity = Gravity.CENTER
      }
    )
  }

  init {
    setBackgroundColor(Color.parseColor("#15100D"))
    centerPlate.setOnClickListener {
      onCharacterFrameTap?.invoke()
    }
    addView(centerPlate)
    for (slot in visibleSlots) {
      val frame = FrameLayout(context)
      val icon = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
      }
      val durability = TextView(context).apply {
        gravity = Gravity.END
        includeFontPadding = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setShadowLayer(0f, 1f, 1f, Color.BLACK)
        visibility = View.GONE
        setPadding(0, dp(1), dp(2), 0)
      }
      frame.addView(icon, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
      frame.addView(
        durability,
        FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
          gravity = Gravity.TOP or Gravity.END
        }
      )
      addView(frame)
      slotViews[slot] = SlotUi(frame, icon, durability)
    }
  }

  fun setIconBinder(binder: (ImageView, String, EquipItem?) -> Unit) {
    iconBinder = binder
    refreshSlots()
  }

  fun setItems(items: List<EquipItem>) {
    itemBySlot.clear()
    for (item in items) {
      itemBySlot[item.slot] = item
    }
    refreshSlots()
  }

  fun setSelectedSlot(slot: String?) {
    selectedSlot = if (slot != null && slotViews.containsKey(slot)) slot else null
    refreshSlots()
  }

  fun setBuddyState(state: PipBoyBuddyView.State) {
    centerBuddy.setState(state)
  }

  private fun refreshSlots() {
    for ((slot, ui) in slotViews) {
      val item = itemBySlot[slot]
      iconBinder?.invoke(ui.icon, slot, item)
      applySlotStyle(slot, ui, item)
      ui.frame.setOnClickListener {
        selectedSlot = slot
        onSlotTap?.invoke(slot, itemBySlot[slot])
        refreshSlots()
      }
    }
    invalidate()
  }

  private fun applySlotStyle(slot: String, ui: SlotUi, item: EquipItem?) {
    val equipped = when {
      item == null -> false
      item.equipped -> true
      item.itemId > 0 -> true
      !item.iconTex.isNullOrBlank() -> true
      !item.name.isNullOrBlank() -> true
      else -> false
    }
    val warn = (item?.warn ?: "").lowercase()
    val borderColor = when {
      !equipped -> Color.parseColor("#5F4B31")
      warn == "red" -> Color.parseColor("#FF4B4B")
      warn == "yellow" -> Color.parseColor("#FFD35A")
      else -> qualityColor(item?.quality ?: 0)
    }
    val baseBorderWidth = when {
      warn == "red" || warn == "yellow" -> dp(3)
      equipped -> dp(2)
      else -> dp(1)
    }
    val isSelected = selectedSlot != null && selectedSlot == slot
    val borderWidth = if (isSelected) baseBorderWidth + dp(0) else baseBorderWidth
    val finalBorderColor = borderColor

    ui.frame.background = GradientDrawable().apply {
      shape = GradientDrawable.RECTANGLE
      cornerRadius = dp(5).toFloat()
      setColor(Color.parseColor("#1E1712"))
    }
    ui.frame.foreground = GradientDrawable().apply {
      shape = GradientDrawable.RECTANGLE
      cornerRadius = dp(5).toFloat()
      setColor(Color.TRANSPARENT)
      setStroke(borderWidth, finalBorderColor)
    }

    if (equipped) {
      ui.icon.clearColorFilter()
      ui.icon.imageAlpha = 255
    } else {
      ui.icon.colorFilter = emptySlotColorFilter
      ui.icon.imageAlpha = 210
    }

    if (equipped && (item?.durabilityPct ?: -1) >= 0) {
      val pct = item?.durabilityPct ?: -1
      ui.durability.visibility = View.VISIBLE
      ui.durability.text = "$pct%"
      ui.durability.setTextColor(
        when {
          pct <= 20 -> Color.parseColor("#FF5A5A")
          pct <= 50 -> Color.parseColor("#FFD35A")
          else -> Color.parseColor("#BFEAC1")
        }
      )
    } else {
      ui.durability.visibility = View.GONE
    }
  }

  private fun qualityColor(quality: Int): Int {
    return when {
      quality >= 5 -> Color.parseColor("#FF8000")
      quality == 4 -> Color.parseColor("#A335EE")
      quality == 3 -> Color.parseColor("#0070DD")
      quality == 2 -> Color.parseColor("#1EFF00")
      quality == 1 -> Color.parseColor("#FFFFFF")
      else -> Color.parseColor("#9D9D9D")
    }
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    val width = MeasureSpec.getSize(widthMeasureSpec)
    val height = MeasureSpec.getSize(heightMeasureSpec)
    rebuildLayoutRects(width, height)

    for ((slot, ui) in slotViews) {
      val rect = slotRects[slot]
      val specW = MeasureSpec.makeMeasureSpec(rect?.width() ?: 0, MeasureSpec.EXACTLY)
      val specH = MeasureSpec.makeMeasureSpec(rect?.height() ?: 0, MeasureSpec.EXACTLY)
      ui.frame.measure(specW, specH)
    }

    centerPlate.measure(
      MeasureSpec.makeMeasureSpec(centerRect.width(), MeasureSpec.EXACTLY),
      MeasureSpec.makeMeasureSpec(centerRect.height(), MeasureSpec.EXACTLY)
    )
    setMeasuredDimension(width, height)
  }

  override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
    rebuildLayoutRects(width, height)
    centerPlate.layout(centerRect.left, centerRect.top, centerRect.right, centerRect.bottom)

    for ((slot, rect) in slotRects) {
      val ui = slotViews[slot] ?: continue
      ui.frame.layout(rect.left, rect.top, rect.right, rect.bottom)
    }
  }

  private fun rebuildLayoutRects(width: Int, height: Int) {
    slotRects.clear()

    val outerPad = dp(8)
    val colGap = dp(8)
    val minGap = dp(2)
    val minCenterWidth = dp(96)

    val preferredRowGap = dp(6)
    val maxByWidth = ((width - outerPad * 2 - colGap * 2 - minCenterWidth) / 2f).toInt()
    val maxByHeight = ((height - outerPad * 2 - preferredRowGap * 7) / 8f).toInt()
    val slot = min(min(maxByWidth, maxByHeight), dp(44)).coerceAtLeast(dp(30))

    val availableH = (height - outerPad * 2).coerceAtLeast(slot * 8 + minGap * 7)
    val rowGap = ((availableH - slot * 8) / 7f).toInt().coerceAtLeast(minGap)
    val contentHeight = slot * 8 + rowGap * 7
    val top = outerPad + ((availableH - contentHeight) / 2).coerceAtLeast(0)
    val bottom = top + contentHeight

    val leftX = outerPad
    val rightX = (width - outerPad - slot).coerceAtLeast(leftX + slot + colGap + minCenterWidth + colGap)
    val centerLeft = leftX + slot + colGap
    val centerRight = (rightX - colGap).coerceAtLeast(centerLeft + minCenterWidth)

    for (i in leftSlots.indices) {
      val y = top + i * (slot + rowGap)
      slotRects[leftSlots[i]] = Rect(leftX, y, leftX + slot, y + slot)
    }
    for (i in rightSlots.indices) {
      val y = top + i * (slot + rowGap)
      slotRects[rightSlots[i]] = Rect(rightX, y, rightX + slot, y + slot)
    }

    centerRect = Rect(centerLeft, top, centerRight, bottom)

    val innerPad = dp(8)
    val weaponGap = dp(6)
    val weaponCount = weaponSlots.size
    val weaponSize = min(slot, ((centerRect.width() - innerPad * 2 - weaponGap * (weaponCount - 1)) / weaponCount.toFloat()).toInt())
      .coerceAtLeast(dp(30))
    val weaponsY = (centerRect.bottom - innerPad - weaponSize).coerceAtLeast(centerRect.top + innerPad)
    val totalWeaponsWidth = weaponSize * weaponCount + weaponGap * (weaponCount - 1)
    val weaponsLeft = centerRect.left + (centerRect.width() - totalWeaponsWidth) / 2
    for (i in weaponSlots.indices) {
      val x = weaponsLeft + i * (weaponSize + weaponGap)
      val slotName = weaponSlots[i]
      slotRects[slotName] = Rect(x, weaponsY, x + weaponSize, weaponsY + weaponSize)
    }
  }

  private fun dp(v: Int): Int {
    return (v * resources.displayMetrics.density).toInt()
  }
}

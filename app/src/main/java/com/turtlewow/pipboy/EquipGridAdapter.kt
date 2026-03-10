package com.turtlewow.pipboy

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class EquipGridAdapter(
  private val bindIcon: (ImageView, EquipItem) -> Unit,
  private val slotLabel: (String) -> String
) : RecyclerView.Adapter<EquipGridAdapter.EquipVH>() {
  private val items = ArrayList<EquipItem>()

  fun submitItems(newItems: List<EquipItem>) {
    items.clear()
    items.addAll(newItems)
    notifyDataSetChanged()
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EquipVH {
    val view = LayoutInflater.from(parent.context).inflate(R.layout.item_equip_slot, parent, false)
    return EquipVH(view)
  }

  override fun onBindViewHolder(holder: EquipVH, position: Int) {
    holder.bind(items[position], bindIcon, slotLabel)
  }

  override fun getItemCount(): Int = items.size

  class EquipVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
    private val frame: View = itemView.findViewById(R.id.equipSlotFrame)
    private val icon: ImageView = itemView.findViewById(R.id.equipSlotIcon)
    private val durability: TextView = itemView.findViewById(R.id.equipDurability)
    private val slotName: TextView = itemView.findViewById(R.id.equipSlotName)

    fun bind(item: EquipItem, bindIcon: (ImageView, EquipItem) -> Unit, slotLabel: (String) -> String) {
      slotName.text = slotLabel(item.slot)
      frame.background = createSlotBackground(item.quality, item.equipped)

      if (item.equipped) {
        bindIcon(icon, item)
      } else {
        icon.setImageResource(R.drawable.bag_slot_placeholder)
      }

      if (item.durabilityPct >= 0 && item.equipped) {
        durability.visibility = View.VISIBLE
        durability.text = "${item.durabilityPct}%"
        durability.setTextColor(
          when {
            item.durabilityPct <= 20 -> Color.parseColor("#FF5A5A")
            item.durabilityPct <= 50 -> Color.parseColor("#FFD35A")
            else -> Color.parseColor("#BFEAC1")
          }
        )
      } else {
        durability.visibility = View.GONE
      }
    }

    private fun createSlotBackground(quality: Int, equipped: Boolean): GradientDrawable {
      val strokeColor = if (!equipped) {
        Color.parseColor("#5F4B31")
      } else {
        qualityColor(quality)
      }
      return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 6f
        setColor(Color.parseColor("#1E1712"))
        setStroke(if (equipped) 2 else 1, strokeColor)
      }
    }

    private fun qualityColor(quality: Int): Int {
      return when {
        quality >= 5 -> Color.parseColor("#FF8000")
        quality == 4 -> Color.parseColor("#A335EE")
        quality == 3 -> Color.parseColor("#0070DD")
        quality == 2 -> Color.parseColor("#1EFF00")
        quality == 1 -> Color.parseColor("#E6E6E6")
        else -> Color.parseColor("#9D9D9D")
      }
    }
  }
}

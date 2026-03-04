package com.turtlewow.pipboy

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class BagGridAdapter(
  private val onItemTap: (BagItem) -> Unit,
  private val onItemDoubleTap: (BagItem) -> Unit,
  private val bindIcon: (ImageView, BagItem) -> Unit
) : RecyclerView.Adapter<BagGridAdapter.SlotVH>() {
  private val items = ArrayList<BagItem>()
  private var selectedBag = -1
  private var selectedSlot = -1
  private var lastTapKey: String? = null
  private var lastTapMs: Long = 0L

  fun submitItems(newItems: List<BagItem>) {
    items.clear()
    items.addAll(newItems)
    notifyDataSetChanged()
  }

  fun setSelected(item: BagItem?) {
    if (item == null) {
      selectedBag = -1
      selectedSlot = -1
    } else {
      selectedBag = item.bag
      selectedSlot = item.slot
    }
    notifyDataSetChanged()
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SlotVH {
    val view = LayoutInflater.from(parent.context).inflate(R.layout.item_bag_slot, parent, false)
    return SlotVH(view)
  }

  override fun onBindViewHolder(holder: SlotVH, position: Int) {
    val item = items[position]
    val selected = item.bag == selectedBag && item.slot == selectedSlot
    holder.bind(item, selected, bindIcon)
    holder.itemView.setOnClickListener {
      val now = System.currentTimeMillis()
      val key = "${item.bag}:${item.slot}"
      val isDoubleTap = lastTapKey == key && (now - lastTapMs) <= 350L
      lastTapKey = key
      lastTapMs = now

      selectedBag = item.bag
      selectedSlot = item.slot
      notifyDataSetChanged()
      onItemTap(item)
      if (isDoubleTap) {
        onItemDoubleTap(item)
      }
    }
  }

  override fun getItemCount(): Int = items.size

  class SlotVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
    private val icon: ImageView = itemView.findViewById(R.id.slotIcon)
    private val countText: TextView = itemView.findViewById(R.id.slotCount)

    fun bind(item: BagItem, selected: Boolean, bindIcon: (ImageView, BagItem) -> Unit) {
      itemView.isSelected = selected
      if (item.count > 1) {
        countText.visibility = View.VISIBLE
        countText.text = item.count.toString()
      } else {
        countText.visibility = View.GONE
      }
      bindIcon(icon, item)
    }
  }
}

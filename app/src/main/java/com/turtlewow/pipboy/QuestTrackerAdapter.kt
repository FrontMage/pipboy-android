package com.turtlewow.pipboy

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class QuestTrackerAdapter(
  private val onTap: (MainActivity.QuestTrackerRow) -> Unit
) : RecyclerView.Adapter<QuestTrackerAdapter.QuestVH>() {
  private val rows = ArrayList<MainActivity.QuestTrackerRow>()
  private var selectedQuestId: Int? = null

  fun submitRows(newRows: List<MainActivity.QuestTrackerRow>) {
    rows.clear()
    rows.addAll(newRows)
    notifyDataSetChanged()
  }

  fun setSelectedQuest(questId: Int?) {
    selectedQuestId = questId
    notifyDataSetChanged()
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QuestVH {
    val view = LayoutInflater.from(parent.context).inflate(R.layout.item_quest_tracker, parent, false)
    return QuestVH(view)
  }

  override fun getItemCount(): Int = rows.size

  override fun onBindViewHolder(holder: QuestVH, position: Int) {
    val row = rows[position]
    val selected = selectedQuestId != null && row.questId > 0 && row.questId == selectedQuestId
    holder.bind(row, selected)
    holder.itemView.setOnClickListener {
      onTap(row)
    }
  }

  class QuestVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
    private val title: TextView = itemView.findViewById(R.id.questRowTitle)
    private val meta: TextView = itemView.findViewById(R.id.questRowMeta)

    fun bind(row: MainActivity.QuestTrackerRow, selected: Boolean) {
      title.text = row.title
      title.setTextColor(row.titleColor)
      meta.text = row.meta
      itemView.setBackgroundResource(
        if (selected) R.drawable.quest_row_bg_selected else R.drawable.quest_row_bg
      )
    }
  }
}

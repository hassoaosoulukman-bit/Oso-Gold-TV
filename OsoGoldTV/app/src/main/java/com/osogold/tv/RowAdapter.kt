package com.osogold.tv

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/** Adapter of the list panel (channels, countries, favourites). */
class RowAdapter(
    private val onClick: (Row) -> Unit,
    private val onStar: (Row) -> Unit,
    private val isFavorite: (Channel) -> Boolean
) : RecyclerView.Adapter<RowAdapter.Holder>() {

    private var rows: List<Row> = emptyList()

    /** URL of the entry that is playing right now (highlighted). */
    var playingUrl: String? = null
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    fun submit(list: List<Row>) {
        rows = list
        notifyDataSetChanged()
    }

    /** Re-binds all rows (used after a favourite was toggled). */
    fun refresh() {
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view.findViewById(R.id.rowRoot)
        val badge: TextView = view.findViewById(R.id.tvBadge)
        val title: TextView = view.findViewById(R.id.tvTitle)
        val subtitle: TextView = view.findViewById(R.id.tvSubtitle)
        val star: ImageView = view.findViewById(R.id.ivStar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        val context = holder.itemView.context

        if (row.isHeader) {
            holder.badge.visibility = View.GONE
            holder.star.visibility = View.GONE
            holder.subtitle.visibility = View.GONE
            holder.title.text = row.title
            holder.title.setTextColor(ContextCompat.getColor(context, R.color.gold_light))
            holder.title.setTypeface(null, Typeface.BOLD)
            holder.root.background = null
            holder.root.setOnClickListener(null)
            holder.root.isClickable = false
            return
        }

        holder.badge.visibility = View.VISIBLE
        holder.badge.text = row.leading
        holder.title.text = row.title
        holder.title.setTextColor(ContextCompat.getColor(context, R.color.text_main))
        holder.title.setTypeface(null, Typeface.NORMAL)

        if (row.subtitle.isEmpty()) {
            holder.subtitle.visibility = View.GONE
        } else {
            holder.subtitle.visibility = View.VISIBLE
            holder.subtitle.text = row.subtitle
        }

        val channel = row.channel
        val playing = channel != null && channel.url == playingUrl
        holder.root.setBackgroundResource(if (playing) R.drawable.bg_row_active else R.drawable.bg_row)
        holder.root.setOnClickListener { onClick(row) }

        if (channel != null) {
            holder.star.visibility = View.VISIBLE
            holder.star.setImageResource(
                if (isFavorite(channel)) R.drawable.ic_star_filled else R.drawable.ic_star
            )
            holder.star.setOnClickListener { onStar(row) }
        } else {
            holder.star.visibility = View.GONE
            holder.star.setOnClickListener(null)
        }
    }
}

package com.osogold.tv

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** "My lists": every M3U / M3U8 file, link and Xtream / Stalker subscription by its own name; one tap switches to it. */
class MyListsActivity : BaseActivity() {

    private lateinit var prefs: Prefs
    private lateinit var empty: TextView
    private var items: List<SectionDef> = emptyList()

    private val adapter = object : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_mylist, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val def = items[position]
            holder.icon.setImageResource(def.iconRes)
            holder.name.text = def.badge + "  " + def.title
            holder.itemView.setOnClickListener {
                startActivity(
                    Intent(this@MyListsActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.EXTRA_COMMAND, Assistant.CMD_OPEN_LIST)
                        .putExtra(MainActivity.EXTRA_ARGUMENT, def.id)
                )
                finish()
            }
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.listIcon)
        val name: TextView = view.findViewById(R.id.listName)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mylists)
        prefs = Prefs(this)
        empty = findViewById(R.id.listsEmpty)
        val rv = findViewById<RecyclerView>(R.id.listsList)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        findViewById<View>(R.id.listsBack).setOnClickListener { finish() }
        findViewById<View>(R.id.listsAdd).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        items = Catalog.subscriptions(prefs)
        adapter.notifyDataSetChanged()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }
}

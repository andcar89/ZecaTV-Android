package com.ze.iptvplayer.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ze.iptvplayer.R
import com.ze.iptvplayer.databinding.DialogSearchPickerBinding

/** A dialog with a search box over a long list (playlists can have thousands of channels —
 *  a plain spinner or radio list is unusable on a phone). Calls [onPick] with the index into [items]. */
object SearchPicker {

    fun show(context: Context, title: String, items: List<String>, onPick: (Int) -> Unit) {
        val binding = DialogSearchPickerBinding.inflate(LayoutInflater.from(context))
        var shown: List<Int> = items.indices.toList()

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = shown.size
            override fun getItem(position: Int): Any = items[shown[position]]
            override fun getItemId(position: Int): Long = shown[position].toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = (convertView as? TextView)
                    ?: LayoutInflater.from(context).inflate(R.layout.item_picker_choice, parent, false) as TextView
                view.text = items[shown[position]]
                return view
            }
        }
        binding.pickerList.adapter = adapter

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(binding.root)
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        binding.pickerList.setOnItemClickListener { _, _, position, _ ->
            onPick(shown[position])
            dialog.dismiss()
        }
        binding.pickerSearch.addTextChangedListener {
            val q = it?.toString().orEmpty().trim()
            shown = if (q.isEmpty()) items.indices.toList() else items.indices.filter { i -> items[i].contains(q, ignoreCase = true) }
            adapter.notifyDataSetChanged()
        }
        dialog.show()
    }
}

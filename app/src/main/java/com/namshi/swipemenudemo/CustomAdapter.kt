package com.namshi.swipemenudemo

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.namshi.swipemenudemo.databinding.StateRowItemBinding

class CustomAdapter(private val dataSet: List<String>, private val listener: CustomClickInterface) :
    RecyclerView.Adapter<CustomAdapter.ViewHolder>() {

    class ViewHolder(private val itemBinding: StateRowItemBinding,
                     private val listener: CustomClickInterface) : RecyclerView.ViewHolder(itemBinding.root) {
        fun bind(state: String) {
            itemBinding.stateName.text = state
            itemBinding.menuDelete.setOnClickListener {
                listener.onRemoveClick(adapterPosition)
            }
        }
    }

    override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ViewHolder {
        val itemBinding = StateRowItemBinding.inflate(LayoutInflater.from(viewGroup.context), viewGroup, false)
        return ViewHolder(itemBinding, listener)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        viewHolder.bind(dataSet[position])
    }

    override fun getItemCount() = dataSet.size

    interface CustomClickInterface {
        fun onRemoveClick(position: Int)
    }
}

package com.namshi.swipemenudemo

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.namshi.swipemenudemo.databinding.ActivityMainBinding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.DividerItemDecoration
import com.namshi.swipemenu.RecyclerTouchListener

class MainActivity : AppCompatActivity(), CustomAdapter.CustomClickInterface {

  private lateinit var binding: ActivityMainBinding

  private val states =
      mutableListOf(
          "Alabama",
          "Alaska",
          "Arizona",
          "Arkansas",
          "California",
          "Colorado",
          "Connecticut",
          "Delaware",
          "Florida",
          "Georgia",
          "Hawaii",
          "Idaho",
          "Illinois",
          "Indiana",
          "Iowa",
          "Kansas",
          "Kentucky",
          "Louisiana",
          "Maine",
          "Maryland",
          "Massachusetts",
          "Michigan",
          "Minnesota",
          "Mississippi",
          "Missouri",
          "Montana",
          "Nebraska")

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityMainBinding.inflate(layoutInflater)
    val view = binding.root
    val statesListView = binding.allStates
      val adapter = CustomAdapter(states, this)
      statesListView.adapter = adapter
      val linearLayoutManager = LinearLayoutManager(this)
      statesListView.layoutManager = linearLayoutManager
      val dividerItemDecoration = DividerItemDecoration(
          statesListView.context,
          linearLayoutManager.orientation
      )
      statesListView.addItemDecoration(dividerItemDecoration)

      val touchListener = RecyclerTouchListener(this, statesListView, false)
      touchListener
          .setSwipeOptionViews(R.id.menu_delete)
          .setSwipeable(R.id.content, R.id.menu_delete
          ) { _, _ -> touchListener.closeVisibleBG(null) }
      statesListView.addOnItemTouchListener(touchListener)


    setContentView(view)
  }

    override fun onRemoveClick(position: Int) {
        states.removeAt(position)

    }
}

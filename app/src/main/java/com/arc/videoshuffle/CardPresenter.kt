package com.arc.videoshuffle

import android.graphics.Color
import android.view.ViewGroup
import android.widget.TextView
import androidx.leanback.widget.Presenter

class CardPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = TextView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(313, 176)
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
        }
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val cardView = viewHolder.view as TextView
        cardView.text = item.toString()
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        // Nothing to clean up
    }
}
package com.arc.videoshuffle

import android.os.Bundle
import android.view.View
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter

class MainFragment : BrowseSupportFragment() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        title = "Video Shuffle"
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true

        loadRows()
    }

    private fun loadRows() {
        val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        
        // Header 1
        val header1 = HeaderItem(0, "Category 1")
        val listRowAdapter1 = ArrayObjectAdapter(CardPresenter())
        listRowAdapter1.add("Item 1")
        listRowAdapter1.add("Item 2")
        listRowAdapter1.add("Item 3")
        rowsAdapter.add(ListRow(header1, listRowAdapter1))

         // Header 2
        val header2 = HeaderItem(1, "Category 2")
        val listRowAdapter2 = ArrayObjectAdapter(CardPresenter())
        listRowAdapter2.add("Item 4")
        listRowAdapter2.add("Item 5")
        rowsAdapter.add(ListRow(header2, listRowAdapter2))

        adapter = rowsAdapter
    }
}
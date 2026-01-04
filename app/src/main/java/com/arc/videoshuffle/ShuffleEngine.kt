package com.arc.videoshuffle

import android.util.Log

class ShuffleEngine(private val myHostname: String) {

    // Tracks how many times we have watched a specific peer
    // Key: Hostname, Value: Count
    private val history = mutableMapOf<String, Int>()

    /**
     * Decides which peer to watch next based on the "Least Shown" algorithm.
     * 
     * @param availablePeers List of currently online peer hostnames (excluding self).
     * @return The hostname of the peer to connect to, or null if none available.
     */
    fun pickNextPeer(availablePeers: List<String>): String? {
        if (availablePeers.isEmpty()) return null

        // Sort peers by:
        // 1. Interaction Count (Ascending) - Watch the one we've seen the least
        // 2. Alphabetical (Ascending) - Tie-breaker to keep it deterministic
        val sortedPeers = availablePeers.sortedWith(compareBy({ history.getOrDefault(it, 0) }, { it }))

        val selectedPeer = sortedPeers.first()

        // Increment history for the selected peer
        val currentCount = history.getOrDefault(selectedPeer, 0)
        history[selectedPeer] = currentCount + 1
        
        Log.d("ShuffleEngine", "Decision: Selected $selectedPeer (Seen $currentCount times)")
        return selectedPeer
    }
    
    fun reset() {
        history.clear()
    }
}
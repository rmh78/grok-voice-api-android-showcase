package com.example.voiceapitest

import android.util.Log
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class ExpandableListViewModel : ViewModel() {

    private val _expandedIndex = MutableStateFlow<Int?>(null)
    val expandedIndex: StateFlow<Int?> = _expandedIndex.asStateFlow()

    private val _scrollCommands = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 1)
    val scrollCommands: SharedFlow<Int> = _scrollCommands.asSharedFlow()

    fun setExpandedIndex(index: Int?) {
        _expandedIndex.value = index
    }

    fun scrollToAndExpand(index: Int) {
        Log.i(LOG_TAG, "scrollToAndExpand: $index")
        setExpandedIndex(index)
        _scrollCommands.tryEmit(index) // Non-blocking
    }

    companion object {
        private const val LOG_TAG = "ExpandableListViewModel"
    }
}
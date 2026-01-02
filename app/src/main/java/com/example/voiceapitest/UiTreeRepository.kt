package com.example.voiceapitest

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

object UiTreeRepository {
    private val _uiTree = MutableSharedFlow<String>(replay = 0)
    val uiTree: SharedFlow<String> = _uiTree

    suspend fun sendUiTree(uiTreeString: String) {
        _uiTree.emit(uiTreeString)
    }
}
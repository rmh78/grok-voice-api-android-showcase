package com.example.voiceapitest

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@SuppressLint("AccessibilityPolicy")
class UiTreeDumpService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit // noop

    override fun onInterrupt() = Unit // noop

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(LOG_TAG, "Accessibility Service connected")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "DUMP_UI_TREE") {
            val dumpId = intent.getStringExtra("dumpId")
            Log.d(LOG_TAG, "Dumping UI tree ${if (dumpId != null) "for dumpId=$dumpId" else "(full screen)"}")
            dumpUiTreeAsCompactList(dumpId)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun dumpUiTreeAsCompactList(targetDumpId: String?) {
        val rootNode = rootInActiveWindow ?: run {
            Log.e(LOG_TAG, "rootInActiveWindow is null")
            GlobalScope.launch(Dispatchers.Main) {
                UiTreeRepository.sendUiTree("[]")
            }
            return
        }

        val elements = mutableListOf<JSONObject>()
        val boundsRect = Rect()

        fun findStartNode(node: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
            if (node.contentDescription == id) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    findStartNode(child, id)?.let { found ->
                        return found
                    }
                }
            }
            return null
        }

        val startNode = if (targetDumpId != null) {
            findStartNode(rootNode, targetDumpId) ?: run {
                Log.w(LOG_TAG, "dumpId '$targetDumpId' not found → fallback to root node")
                rootNode
            }
        } else {
            rootNode
        }

        fun traverse(node: AccessibilityNodeInfo) {
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()
            val label = if (!text.isNullOrBlank()) text else desc

            // Handle only interactive or labeled elements
            if (node.isClickable || node.isEditable || node.isCheckable || !label.isNullOrBlank()) {
                // Only add if bounds are valid (not empty and visible)
                node.getBoundsInScreen(boundsRect)
                if (!boundsRect.isEmpty && boundsRect.width() > 0 && boundsRect.height() > 0) {
                    val obj = JSONObject().apply {
                        label?.let { put("label", it) }
                        put("role", when {
                            node.isEditable -> "input"
                            node.isClickable && label != null -> "button"
                            node.isCheckable -> "switch"
                            node.isClickable -> "tap"
                            else -> "element"
                        })
                        if (node.isCheckable) put("checked", node.isChecked)

                        // Put bounds as compact array: [left, top, right, bottom]
                        put("bounds", JSONArray().apply {
                            put(boundsRect.left)
                            put(boundsRect.top)
                            put(boundsRect.right)
                            put(boundsRect.bottom)
                        })
                    }
                    if (obj.length() > 1 || obj.has("label")) {
                        elements.add(obj)
                    }
                }
            }

            // Handle children of the current node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    traverse(child)
                }
            }
        }

        // Handle only children of start-node
        for (i in 0 until startNode.childCount) {
            startNode.getChild(i)?.let { child ->
                traverse(child)
            }
        }

        GlobalScope.launch(Dispatchers.Main) {
            val dump = JSONArray(elements).toString()
            Log.d(LOG_TAG, "UI-Tree dump: $dump")
            UiTreeRepository.sendUiTree(dump)
        }
    }

    companion object {
        private const val LOG_TAG = "UiTreeDumpService"
    }
}
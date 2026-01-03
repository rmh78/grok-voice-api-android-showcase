package com.example.voiceapitest

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class Item(
    val number: Int,
    val name: String,
    val description: String
)

@Composable
fun ExpandableItemList(
    items: List<Item>,
    expandedIndex: Int?,
    onExpandedChange: (Int?) -> Unit,
    listState: LazyListState
) {
    val coroutineScope = rememberCoroutineScope()

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items, key = { it.number }) { item ->
            val itemIndex = items.indexOf(item)
            val isExpanded = expandedIndex == itemIndex

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val newIndex = if (isExpanded) null else itemIndex
                        onExpandedChange(newIndex)
                    }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${item.number}. ${item.name}",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (isExpanded) "▲" else "▼",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    AnimatedVisibility(visible = isExpanded) {
                        Box(
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                .padding(12.dp)
                        ) {
                            Text(item.description)
                        }
                    }
                }
            }
        }
    }
}
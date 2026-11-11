package com.iseohyun.mylore.sync.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iseohyun.mylore.sync.data.local.AppDatabase
import com.iseohyun.mylore.sync.data.local.entity.BusinessCardEntity
import com.iseohyun.mylore.sync.ui.businesscard.LiveCardScannerActivity
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun BusinessCardWalletScreen(
    onOpenDrawer: () -> Unit
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }

    var searchQuery by remember { mutableStateOf("") }
    val cardList by remember(searchQuery) {
        if (searchQuery.isBlank()) {
            db.businessCardDao().getAllCards()
        } else {
            db.businessCardDao().searchCards(searchQuery.trim())
        }
    }.collectAsState(initial = emptyList())

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.KOREA) }

    val timeGroups = remember(cardList) {
        groupItemsByTimeSplit(cardList) { card ->
            if (card.updatedAt > 0L) card.updatedAt else card.createdAt
        }
    }
    var collapsedKeys by remember { mutableStateOf(setOf<String>()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onOpenDrawer) {
                    Text("☰", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("스마트 명함 지갑", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }

            // + 버튼 (새로 생성)
            IconButton(
                onClick = {
                    val intent = Intent(context, LiveCardScannerActivity::class.java)
                    context.startActivity(intent)
                }
            ) {
                Text("➕", fontSize = 22.sp)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("이름, 회사명, 전화번호로 검색...") },
            singleLine = true,
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Text("✖️", fontSize = 14.sp)
                    }
                } else {
                    Text("🔍", fontSize = 16.sp)
                }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "보관 중인 명함: ${cardList.size}건",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(6.dp))

        TimeSplitTabControlBar(
            groups = timeGroups,
            collapsedKeys = collapsedKeys,
            onToggleGroup = { key ->
                collapsedKeys = if (collapsedKeys.contains(key)) collapsedKeys - key else collapsedKeys + key
            },
            onExpandAll = { collapsedKeys = emptySet() },
            onCollapseAll = { collapsedKeys = timeGroups.map { it.key }.toSet() }
        )

        Spacer(modifier = Modifier.height(6.dp))

        if (cardList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📇", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (searchQuery.isBlank()) "보관 중인 명함이 없습니다." else "검색 결과가 없습니다.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = {
                        val intent = Intent(context, LiveCardScannerActivity::class.java)
                        context.startActivity(intent)
                    }) {
                        Text("➕ 새 명함 스캔하기")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                timeGroups.forEach { group ->
                    val isExpanded = !collapsedKeys.contains(group.key)

                    item(key = "split_${group.key}") {
                        TimeSplitSectionHeader(
                            title = group.title,
                            count = group.items.size,
                            isExpanded = isExpanded,
                            onToggle = {
                                collapsedKeys = if (isExpanded) {
                                    collapsedKeys + group.key
                                } else {
                                    collapsedKeys - group.key
                                }
                            }
                        )
                    }

                    if (isExpanded) {
                        items(
                            items = group.items,
                            key = { "${group.key}_${it.id}" }
                        ) { card ->
                            BusinessCardItemCard(card = card, dateFormat = dateFormat)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BusinessCardItemCard(
    card: BusinessCardEntity,
    dateFormat: SimpleDateFormat
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(card.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    if (card.jobTitle.isNotBlank()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(card.jobTitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(
                    dateFormat.format(Date(card.updatedAt)),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            if (card.company.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(card.company, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (card.mobile.isNotBlank()) {
                Text("📞 ${card.mobile}", fontSize = 13.sp)
            }
            if (card.email.isNotBlank()) {
                Text("📧 ${card.email}", fontSize = 13.sp)
            }
            if (card.memo.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("📝 ${card.memo}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

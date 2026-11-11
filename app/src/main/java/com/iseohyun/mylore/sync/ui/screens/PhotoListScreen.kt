package com.iseohyun.mylore.sync.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iseohyun.mylore.sync.collector.KakaoChatFolderWatcher
import com.iseohyun.mylore.sync.collector.PhotoMediaCollector
import com.iseohyun.mylore.sync.data.local.AppDatabase
import com.iseohyun.mylore.sync.data.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoListScreen(
    appPreferences: AppPreferences? = null,
    onOpenDrawer: () -> Unit
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val prefs = remember { appPreferences ?: AppPreferences(context) }

    val allPhotoMedia by db.syncCacheDao().getAllPhotoMediaFlow().collectAsState(initial = emptyList())
    val lastPhotoTs by prefs.lastPhotoTs.collectAsState(initial = 0L)
    var selectedCategory by remember { mutableStateOf("전체") }
    var isRefreshing by remember { mutableStateOf(true) }
    val verifiedTimeMs = remember { System.currentTimeMillis() }

    // 백그라운드에서 5대 폴더(Camera, Pictures, Screen recordings, Screenshots, Videocaptures) 증분 스캔
    LaunchedEffect(lastPhotoTs) {
        isRefreshing = true
        withContext(Dispatchers.IO) {
            PhotoMediaCollector().refreshPhotoMediaCache(db, prefs)
        }
        isRefreshing = false
    }

    // 전송성공은 확실하나 시각정보(syncedAt)가 없다면 검증시각으로 DB 덮어쓰기
    LaunchedEffect(allPhotoMedia) {
        val needsBackfill = allPhotoMedia.any { it.isSynced && it.syncedAt <= 0L }
        if (needsBackfill) {
            withContext(Dispatchers.IO) {
                db.syncCacheDao().backfillMissingPhotoMediaSyncedAt(System.currentTimeMillis())
            }
        }
    }

    val filteredList = remember(allPhotoMedia, selectedCategory) {
        if (selectedCategory == "전체") {
            allPhotoMedia
        } else {
            allPhotoMedia.filter { it.folderCategory.equals(selectedCategory, ignoreCase = true) }
        }
    }

    val totalCount = filteredList.size
    val pendingCount = remember(filteredList) {
        filteredList.count { !it.isSynced && it.syncedAt <= 0L }
    }
    val totalBytes = remember(filteredList) {
        filteredList.sumOf { it.fileSize }
    }

    val filterTabs = remember {
        listOf("전체") + PhotoMediaCollector.TARGET_CATEGORIES
    }

    val timeGroups = remember(filteredList) {
        groupItemsByTimeSplit(filteredList) { it.takenAt }
    }
    var collapsedKeys by remember { mutableStateOf(setOf<String>()) }

    val columns = remember {
        listOf(
            TableColumnSpec("폴더", 0.22f, TextAlign.Center),
            TableColumnSpec("파일명", 0.27f, TextAlign.Start),
            TableColumnSpec("촬영/생성시기", 0.22f, TextAlign.Center),
            TableColumnSpec("용량", 0.11f, TextAlign.Center),
            TableColumnSpec("전송시기", 0.18f, TextAlign.Center)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
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
                Spacer(modifier = Modifier.width(6.dp))
                Text("사진 및 미디어 동기화", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }

            if (isRefreshing) {
                Text(
                    "🔄 스캔 중...",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Summary Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "🖼️ ${selectedCategory} 미디어: ${totalCount}개 (${KakaoChatFolderWatcher.formatFileSize(totalBytes)})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Text(
                        "완료: ${totalCount - pendingCount} · 미전송: $pendingCount",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (pendingCount > 0) MaterialTheme.colorScheme.error else Color(0xFF2E7D32)
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    "대상 폴더: Camera · Pictures · Screen recordings · Screenshots · Videocaptures",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Category Filter Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            filterTabs.forEach { tab ->
                FilterChip(
                    selected = selectedCategory == tab,
                    onClick = { selectedCategory = tab },
                    label = {
                        Text(
                            tab,
                            fontSize = 11.sp,
                            fontWeight = if (selectedCategory == tab) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

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

        if (filteredList.isEmpty() && isRefreshing) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (filteredList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "선택한 폴더(${selectedCategory})에 사진/동영상 파일이 없습니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(8.dp),
                tonalElevation = 1.dp
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    stickyHeader {
                        StickyTableHeader(columns = columns)
                    }

                    timeGroups.forEach { group ->
                        val isExpanded = !collapsedKeys.contains(group.key)
                        val groupBytes = group.items.sumOf { it.fileSize }

                        item(key = "split_${group.key}") {
                            TimeSplitSectionHeader(
                                title = group.title,
                                count = group.items.size,
                                isExpanded = isExpanded,
                                extraSummary = KakaoChatFolderWatcher.formatFileSize(groupBytes),
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
                            itemsIndexed(
                                items = group.items,
                                key = { _, item -> "${group.key}_${item.filePath}" }
                            ) { index, item ->
                                val isDefinitelySynced = item.syncedAt > 0L || item.isSynced
                                val effectiveSyncedAt = when {
                                    item.syncedAt > 0L -> item.syncedAt
                                    isDefinitelySynced -> verifiedTimeMs
                                    else -> 0L
                                }

                                val rowBg = if (index % 2 == 0) {
                                    MaterialTheme.colorScheme.surface
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(rowBg)
                                        .padding(horizontal = 6.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = item.folderCategory.ifBlank { "-" },
                                        modifier = Modifier
                                            .weight(columns[0].weight)
                                            .padding(horizontal = 2.dp),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    Text(
                                        text = item.fileName.ifBlank { "-" },
                                        modifier = Modifier
                                            .weight(columns[1].weight)
                                            .padding(horizontal = 2.dp),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    Text(
                                        text = formatTableDateTime(item.takenAt),
                                        modifier = Modifier
                                            .weight(columns[2].weight)
                                            .padding(horizontal = 1.dp),
                                        fontSize = 10.sp,
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    Text(
                                        text = if (item.fileSize > 0L) KakaoChatFolderWatcher.formatFileSize(item.fileSize) else "-",
                                        modifier = Modifier
                                            .weight(columns[3].weight)
                                            .padding(horizontal = 1.dp),
                                        fontSize = 10.sp,
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    Text(
                                        text = formatTableDateTime(effectiveSyncedAt),
                                        modifier = Modifier
                                            .weight(columns[4].weight)
                                            .padding(horizontal = 1.dp),
                                        fontSize = 10.sp,
                                        fontWeight = if (effectiveSyncedAt > 0L) FontWeight.SemiBold else FontWeight.Normal,
                                        textAlign = TextAlign.Center,
                                        color = if (effectiveSyncedAt > 0L) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline
                                    )
                                }
                                HorizontalDivider(
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

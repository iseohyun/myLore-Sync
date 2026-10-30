package com.iseohyun.mylore.sync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iseohyun.mylore.sync.collector.SmsAndMmsCollector
import com.iseohyun.mylore.sync.data.prefs.AppPreferences
import com.iseohyun.mylore.sync.data.remote.dto.SyncSmsItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SmsListScreen(
    appPreferences: AppPreferences,
    onOpenDrawer: () -> Unit
) {
    val context = LocalContext.current
    var smsList by remember { mutableStateOf<List<SyncSmsItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val lastSmsTs by appPreferences.lastSmsTs.collectAsState(initial = 0L)

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val collector = SmsAndMmsCollector(context)
            // 전체 문자(0L부터) 수집
            smsList = collector.collectSmsSince(0L).reversed()
            isLoading = false
        }
    }

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA) }

    val pendingList = smsList.filter { it.ts > lastSmsTs }
    val oldestPendingTs = pendingList.minOfOrNull { it.ts }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onOpenDrawer) {
                Text("☰", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("문자(SMS / MMS) 동기화", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Summary Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("✉️ 문자 보유 현황", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("보유 총 문자: ${smsList.size}건", fontSize = 13.sp)
                    Text("미전송: ${pendingList.size}건", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (pendingList.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "아직 서버로 전송하지 않은 가장 오래된 문자 시점: " +
                            if (oldestPendingTs != null) dateFormat.format(Date(oldestPendingTs)) else "모두 전송됨",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (smsList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("기기에 저장된 문자가 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(smsList) { item ->
                    val isSynced = item.ts <= lastSmsTs
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (item.type == "sent") "📤 발신" else "📥 수신",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(item.address, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                }

                                Box(
                                    modifier = Modifier
                                        .background(
                                            if (isSynced) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                                            RoundedCornerShape(4.dp)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        if (isSynced) "서버 전송 완료" else "전송 대기",
                                        fontSize = 11.sp,
                                        color = if (isSynced) Color(0xFF2E7D32) else Color(0xFFC62828),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                item.body,
                                fontSize = 13.sp,
                                maxLines = 4,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                dateFormat.format(Date(item.ts)),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.End)
                            )
                        }
                    }
                }
            }
        }
    }
}

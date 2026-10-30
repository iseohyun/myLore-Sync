package com.iseohyun.mylore.sync.ui.businesscard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import coil.compose.rememberAsyncImagePainter
import com.iseohyun.mylore.sync.data.local.AppDatabase
import com.iseohyun.mylore.sync.data.local.entity.BusinessCardEntity
import com.iseohyun.mylore.sync.ui.theme.MyLoreSyncTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BusinessCardWalletActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val db = AppDatabase.getInstance(applicationContext)

        setContent {
            MyLoreSyncTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    BusinessCardWalletScreen(
                        db = db,
                        onOpenLiveScanner = { targetId ->
                            val intent = Intent(this, LiveCardScannerActivity::class.java).apply {
                                if (targetId != null) putExtra("TARGET_CARD_ID", targetId)
                            }
                            startActivity(intent)
                        },
                        onOpenDocScanner = {
                            val intent = Intent(this, BusinessCardScanActivity::class.java)
                            startActivity(intent)
                        },
                        onClose = { finish() }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessCardWalletScreen(
    db: AppDatabase,
    onOpenLiveScanner: (Long?) -> Unit,
    onOpenDocScanner: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    val cardListFlow = remember(searchQuery) {
        if (searchQuery.isBlank()) db.businessCardDao().getAllCards()
        else db.businessCardDao().searchCards(searchQuery.trim())
    }
    val cardList by cardListFlow.collectAsState(initial = emptyList())

    var selectedDetailCard by remember { mutableStateOf<BusinessCardEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("스마트 명함 지갑", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onOpenLiveScanner(null) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White
            ) {
                Text("📷 실시간 누적 스캔", fontWeight = FontWeight.Bold)
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            // 1. 검색창
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("이름, 회사명, 전화번호 검색") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 2. 상단 액션 바 (보강 스캔 옵션)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onOpenLiveScanner(null) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("📷 실시간 스캔", fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = onOpenDocScanner,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("📄 고화질 문서스캔", fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 3. 명함 리스트
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("보관 중인 명함 (${cardList.size}장)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (cardList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📇", fontSize = 48.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            if (searchQuery.isNotBlank()) "검색 결과가 없습니다." else "저장된 명함이 없습니다.\n아래 버튼을 눌러 명함을 스캔해보세요.",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(cardList, key = { it.id }) { card ->
                        BusinessCardItemRow(
                            card = card,
                            onClick = { selectedDetailCard = card }
                        )
                    }
                }
            }
        }
    }

    // 상세 뷰 다이얼로그
    selectedDetailCard?.let { card ->
        BusinessCardDetailDialog(
            card = card,
            onDismiss = { selectedDetailCard = null },
            onRescan = {
                selectedDetailCard = null
                onOpenLiveScanner(card.id)
            },
            onDelete = {
                (context as? ComponentActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
                    db.businessCardDao().delete(card)
                    withContext(Dispatchers.Main) {
                        selectedDetailCard = null
                        Toast.makeText(context, "명함이 삭제되었습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }
}

@Composable
fun BusinessCardItemRow(
    card: BusinessCardEntity,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 앞면 명함 썸네일
            if (card.frontImageUri.isNotBlank()) {
                Image(
                    painter = rememberAsyncImagePainter(card.frontImageUri),
                    contentDescription = null,
                    modifier = Modifier
                        .size(width = 80.dp, height = 50.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(width = 80.dp, height = 50.dp)
                        .background(Color.Gray.copy(alpha = 0.2f), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("📇", fontSize = 20.sp)
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        card.name.ifBlank { "(이름 미입력)" },
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    if (card.jobTitle.isNotBlank()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            card.jobTitle,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                if (card.company.isNotBlank()) {
                    Text(card.company, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Text(
                    card.mobile.ifBlank { card.telephone.ifBlank { "번호 없음" } },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text("›", fontSize = 24.sp, color = Color.Gray)
        }
    }
}

@Composable
fun BusinessCardDetailDialog(
    card: BusinessCardEntity,
    onDismiss: () -> Unit,
    onRescan: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(card.updatedAt))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(card.name.ifBlank { "명함 상세" }, fontWeight = FontWeight.Bold)
                Text(dateStr, fontSize = 12.sp, color = Color.Gray)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // 앞/뒷면 사진
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (card.frontImageUri.isNotBlank()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("앞면", style = MaterialTheme.typography.labelSmall)
                            Image(
                                painter = rememberAsyncImagePainter(card.frontImageUri),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(90.dp)
                                    .clip(RoundedCornerShape(6.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                    if (card.backImageUri.isNotBlank()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("뒷면", style = MaterialTheme.typography.labelSmall)
                            Image(
                                painter = rememberAsyncImagePainter(card.backImageUri),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(90.dp)
                                    .clip(RoundedCornerShape(6.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                DetailItem("회사 / 직책", "${card.company} ${card.jobTitle}".trim())
                DetailItem("휴대폰", card.mobile)
                DetailItem("회사전화", card.telephone)
                DetailItem("이메일", card.email)
                DetailItem("주소", card.address)
                DetailItem("웹사이트", card.website)
                if (card.memo.isNotBlank()) {
                    DetailItem("메모", card.memo)
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 전화걸기 / 문자하기 바로가기
                if (card.mobile.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${card.mobile}"))
                                context.startActivity(intent)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("📞 통화")
                        }
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${card.mobile}"))
                                context.startActivity(intent)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("💬 문자")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onRescan,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("🔄 재스캔하여 정보 갱신")
            }
        },
        dismissButton = {
            TextButton(onClick = onDelete) {
                Text("삭제", color = MaterialTheme.colorScheme.error)
            }
        }
    )
}

@Composable
fun DetailItem(label: String, value: String) {
    if (value.isBlank()) return
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
        Text(value, fontSize = 14.sp)
    }
}

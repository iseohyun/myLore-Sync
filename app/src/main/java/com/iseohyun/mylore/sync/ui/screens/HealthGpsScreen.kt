package com.iseohyun.mylore.sync.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.RoundCap
import com.google.maps.android.compose.*
import com.iseohyun.mylore.sync.collector.GpsBackgroundProcessor
import com.iseohyun.mylore.sync.collector.GpsProcessState
import com.iseohyun.mylore.sync.collector.HealthGpsCollector
import com.iseohyun.mylore.sync.data.local.AppDatabase
import com.iseohyun.mylore.sync.data.local.entity.HealthGpsDaySummaryEntity
import com.iseohyun.mylore.sync.data.local.entity.HealthGpsPointEntity
import com.iseohyun.mylore.sync.data.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * 날짜 문자열(YYYY-MM-DD)을 아이콘 없이 YYYY-MM-DD(ddd)로 표기하되,
 * 올해와 같은 해라면 YYYY-를 생략하여 MM-DD(ddd)로 반환합니다.
 */
private fun formatGpsHeaderDate(dateStr: String): String {
    return try {
        val parser = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
        val parsed = parser.parse(dateStr) ?: return dateStr
        val cal = Calendar.getInstance().apply { time = parsed }
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val targetYear = cal.get(Calendar.YEAR)
        val pattern = if (targetYear == currentYear) "MM-dd(E)" else "yyyy-MM-dd(E)"
        SimpleDateFormat(pattern, Locale.KOREA).format(parsed)
    } catch (_: Exception) {
        dateStr
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthGpsScreen(
    appPreferences: AppPreferences? = null,
    onOpenDrawer: () -> Unit
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val prefs = remember { appPreferences ?: AppPreferences(context) }
    val scope = rememberCoroutineScope()

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.KOREA) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.KOREA) }

    var selectedDateStr by remember { mutableStateOf(dateFormat.format(Date())) }
    var pointsForDay by remember { mutableStateOf<List<HealthGpsPointEntity>>(emptyList()) }
    var summaryForDay by remember { mutableStateOf<HealthGpsDaySummaryEntity?>(null) }
    var allSummaries by remember { mutableStateOf<List<HealthGpsDaySummaryEntity>>(emptyList()) }

    var isRefreshing by remember { mutableStateOf(false) }
    var showAllRecordsSheet by remember { mutableStateOf(false) }

    val backgroundState by GpsBackgroundProcessor.processState.collectAsState()

    // 데이터 로드 & 백그라운드 프로세스로 필요 시 파일 동기화
    fun loadGpsData(targetDate: String, forceSync: Boolean = false) {
        scope.launch {
            isRefreshing = true
            withContext(Dispatchers.IO) {
                if (forceSync || HealthGpsCollector().shouldUpdateGps(prefs)) {
                    GpsBackgroundProcessor.startHealthGpsSync(
                        db = db,
                        prefs = prefs,
                        force = forceSync,
                        context = context
                    ) { _, _ ->
                        scope.launch(Dispatchers.IO) {
                            val dao = db.healthGpsDao()
                            val updatedSummaries = dao.getAllDaySummaries()
                            allSummaries = updatedSummaries
                            if (dao.getDaySummary(selectedDateStr) == null && updatedSummaries.isNotEmpty()) {
                                selectedDateStr = updatedSummaries.first().date
                            }
                            summaryForDay = dao.getDaySummary(selectedDateStr)
                            pointsForDay = dao.getPointsForDate(selectedDateStr)
                        }
                    }
                }

                val dao = db.healthGpsDao()
                val summaries = dao.getAllDaySummaries()
                allSummaries = summaries

                var dateToLoad = targetDate
                if (dao.getDaySummary(dateToLoad) == null && summaries.isNotEmpty() && targetDate == dateFormat.format(Date())) {
                    dateToLoad = summaries.first().date
                }

                selectedDateStr = dateToLoad
                summaryForDay = dao.getDaySummary(dateToLoad)
                pointsForDay = dao.getPointsForDate(dateToLoad)
            }
            isRefreshing = false
        }
    }

    LaunchedEffect(Unit) {
        loadGpsData(selectedDateStr, forceSync = false)
    }

    // 날짜 변경 시 해당 날짜의 포인트 로드
    LaunchedEffect(selectedDateStr) {
        withContext(Dispatchers.IO) {
            val dao = db.healthGpsDao()
            summaryForDay = dao.getDaySummary(selectedDateStr)
            pointsForDay = dao.getPointsForDate(selectedDateStr)
        }
    }

    // allSummaries는 date DESC 정렬 상태이므로:
    // - 이전 기록(과거): date < selectedDateStr 중 가장 큰(가까운) 날짜 -> firstOrNull { it.date < selectedDateStr }
    // - 다음 기록(미래): date > selectedDateStr 중 가장 작은(가까운) 날짜 -> lastOrNull { it.date > selectedDateStr }
    val prevRecordDate = remember(allSummaries, selectedDateStr) {
        allSummaries.firstOrNull { it.date < selectedDateStr }?.date
    }
    val nextRecordDate = remember(allSummaries, selectedDateStr) {
        allSummaries.lastOrNull { it.date > selectedDateStr }?.date
    }

    val selectedDateDisplay = remember(selectedDateStr) {
        formatGpsHeaderDate(selectedDateStr)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // 상단 헤더: [☰] [◀ 이전기록] [날짜(클릭 시 전체기록)] [다음기록 ▶] [🔄]
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onOpenDrawer) {
                        Text("☰", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }

                    // 탐색 바: [◀ 이전기록] [날짜] [다음기록 ▶]
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(
                            onClick = { prevRecordDate?.let { selectedDateStr = it } },
                            enabled = prevRecordDate != null
                        ) {
                            Text(
                                "◀",
                                fontSize = 18.sp,
                                color = if (prevRecordDate != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                            modifier = Modifier
                                .clickable { showAllRecordsSheet = true }
                                .padding(horizontal = 4.dp)
                        ) {
                            Text(
                                text = selectedDateDisplay,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }

                        IconButton(
                            onClick = { nextRecordDate?.let { selectedDateStr = it } },
                            enabled = nextRecordDate != null
                        ) {
                            Text(
                                "▶",
                                fontSize = 18.sp,
                                color = if (nextRecordDate != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // 우측 대칭 여백/새로고침 아이콘
                    IconButton(onClick = { loadGpsData(selectedDateStr, forceSync = true) }) {
                        Text("🔄", fontSize = 16.sp)
                    }
                }

                // 하단 요약 상태바
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val summary = summaryForDay
                    if (summary != null && summary.pointCount > 0) {
                        val distKm = String.format(Locale.US, "%.1f", summary.totalDistanceMeters / 1000.0)
                        val startT = timeFormat.format(Date(summary.startTime))
                        val endT = timeFormat.format(Date(summary.endTime))
                        Text(
                            "이동 $distKm km (${startT}~${endT} · ${summary.pointCount}pt)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (summary.stayCount > 0) {
                            Text(
                                "⏱️ 체류 ${summary.stayCount}곳 (${HealthGpsCollector.formatDurationText(summary.totalStayDurationMinutes)})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        Text(
                            if (isRefreshing || backgroundState is GpsProcessState.Processing) "백그라운드에서 휴대폰 전체 GPS 데이터를 수집·분석 중..." else "해당 일자의 GPS 이동 기록이 없습니다.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                if (backgroundState is GpsProcessState.Processing) {
                    Spacer(modifier = Modifier.height(2.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // 공식 네이티브 Google Maps SDK for Android 영역
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            NativeGoogleMapView(
                points = pointsForDay,
                timeFormat = timeFormat
            )

            // 데이터 없는 날 안내 오버레이
            if (pointsForDay.isEmpty() && !isRefreshing && backgroundState !is GpsProcessState.Processing) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    shadowElevation = 4.dp
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("📍", fontSize = 36.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "$selectedDateDisplay 에는 GPS 기록이 없습니다.",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "상단 날짜를 눌러 기록이 있는 날짜를 선택하거나, 좌/우 화살표로 이전·다음 기록으로 이동하세요.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        if (allSummaries.isNotEmpty()) {
                            Button(
                                onClick = { selectedDateStr = allSummaries.first().date },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("최근 기록일 (${formatGpsHeaderDate(allSummaries.first().date)}) 로 이동", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            if (isRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }

    if (showAllRecordsSheet) {
        AllGpsRecordsBottomSheet(
            summaries = allSummaries,
            selectedDate = selectedDateStr,
            onDismiss = { showAllRecordsSheet = false },
            onSelectDate = { date ->
                selectedDateStr = date
                showAllRecordsSheet = false
            }
        )
    }
}

/**
 * 공식 네이티브 Google Maps SDK for Android 렌더링 뷰
 * (Google Cloud의 패키지명 + SHA-1 인증서 보호 정책과 100% 호환)
 */
@Composable
private fun NativeGoogleMapView(
    points: List<HealthGpsPointEntity>,
    timeFormat: SimpleDateFormat
) {
    val defaultCenter = remember { LatLng(37.5665, 126.9780) }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(defaultCenter, 13f)
    }

    var isMapLoaded by remember { mutableStateOf(false) }

    // 폴리라인용 좌표 리스트
    val routeLatLngs = remember(points) {
        points.map { LatLng(it.latitude, it.longitude) }
    }

    // 체류 배지 포인트
    val stayPoints = remember(points) {
        points.filter { it.isStayPoint }
    }

    // 단일 위치 여부 판별 (모든 좌표가 반경 15m 이내인 경우)
    val isSingleArea = remember(routeLatLngs) {
        if (routeLatLngs.isEmpty()) true
        else if (routeLatLngs.size == 1) true
        else {
            val first = routeLatLngs.first()
            routeLatLngs.all {
                HealthGpsCollector.calculateDistanceMeters(first.latitude, first.longitude, it.latitude, it.longitude) < 15.0
            }
        }
    }

    // 동선이 한눈에 다 보이도록 자동 줌아웃 (CameraUpdateFactory.newLatLngBounds)
    LaunchedEffect(points, isMapLoaded) {
        if (!isMapLoaded || routeLatLngs.isEmpty()) return@LaunchedEffect
        try {
            if (isSingleArea) {
                cameraPositionState.animate(
                    CameraUpdateFactory.newLatLngZoom(routeLatLngs.first(), 16f),
                    durationMs = 500
                )
            } else {
                val boundsBuilder = LatLngBounds.builder()
                routeLatLngs.forEach { boundsBuilder.include(it) }
                val bounds = boundsBuilder.build()
                cameraPositionState.animate(
                    CameraUpdateFactory.newLatLngBounds(bounds, 140),
                    durationMs = 600
                )
            }
        } catch (_: Exception) {
            cameraPositionState.move(CameraUpdateFactory.newLatLngZoom(routeLatLngs.first(), 15f))
        }
    }

    GoogleMap(
        modifier = Modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        properties = MapProperties(
            mapType = MapType.NORMAL,
            isMyLocationEnabled = false
        ),
        uiSettings = MapUiSettings(
            zoomControlsEnabled = true,
            compassEnabled = true,
            mapToolbarEnabled = false,
            myLocationButtonEnabled = false
        ),
        onMapLoaded = { isMapLoaded = true }
    ) {
        // 1. 이동 동선 연결 선 (Polyline)
        if (!isSingleArea && routeLatLngs.size >= 2) {
            Polyline(
                points = routeLatLngs,
                color = Color(0xFF1E88E5),
                width = 12f,
                geodesic = true,
                jointType = JointType.ROUND,
                startCap = RoundCap(),
                endCap = RoundCap()
            )
        }

        // 2. 출발지 / 도착지 또는 단일 거점 배지
        if (points.isNotEmpty()) {
            val firstPt = points.first()
            val firstPos = LatLng(firstPt.latitude, firstPt.longitude)
            val firstTime = timeFormat.format(Date(firstPt.timestamp))

            if (isSingleArea) {
                MarkerComposable(
                    keys = arrayOf<Any>("single", firstPt.timestamp, points.size),
                    state = rememberUpdatedMarkerState(position = firstPos),
                    anchor = Offset(0.5f, 1.0f),
                    zIndex = 2f
                ) {
                    MapBadgeChip(
                        text = "📍 위치 기록 ($firstTime)",
                        backgroundColor = Color(0xFF1976D2)
                    )
                }
            } else {
                // 출발 마커
                MarkerComposable(
                    keys = arrayOf<Any>("start", firstPt.timestamp),
                    state = rememberUpdatedMarkerState(position = firstPos),
                    anchor = Offset(0.5f, 1.0f),
                    zIndex = 2f
                ) {
                    MapBadgeChip(
                        text = "🚩 출발 $firstTime",
                        backgroundColor = Color(0xFF2E7D32)
                    )
                }

                // 도착 마커
                val lastPt = points.last()
                val lastPos = LatLng(lastPt.latitude, lastPt.longitude)
                val lastTime = timeFormat.format(Date(lastPt.timestamp))
                MarkerComposable(
                    keys = arrayOf<Any>("end", lastPt.timestamp),
                    state = rememberUpdatedMarkerState(position = lastPos),
                    anchor = Offset(0.5f, 1.0f),
                    zIndex = 2f
                ) {
                    MapBadgeChip(
                        text = "🏁 도착 $lastTime",
                        backgroundColor = Color(0xFFC62828)
                    )
                }
            }
        }

        // 3. 이동이 거의 없는 시간만 체류 배지로 표시
        stayPoints.forEachIndexed { idx, stayPt ->
            val stayPos = LatLng(stayPt.latitude, stayPt.longitude)
            val startStr = timeFormat.format(Date(stayPt.stayStartTime))
            val endStr = timeFormat.format(Date(stayPt.stayEndTime))
            val durStr = HealthGpsCollector.formatDurationText(stayPt.stayDurationMinutes)

            MarkerComposable(
                keys = arrayOf<Any>("stay", idx, stayPt.timestamp, stayPt.stayDurationMinutes),
                state = rememberUpdatedMarkerState(position = stayPos),
                anchor = Offset(0.5f, 0.0f),
                zIndex = 3f
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF6200EE),
                    border = BorderStroke(1.5.dp, Color.White),
                    shadowElevation = 4.dp
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "⏱️ $durStr",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "$startStr ~ $endStr",
                            color = Color(0xFFE0E0E0),
                            fontSize = 9.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MapBadgeChip(
    text: String,
    backgroundColor: Color
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = backgroundColor,
        border = BorderStroke(1.5.dp, Color.White),
        shadowElevation = 3.dp
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

/**
 * 모든기록 바텀시트
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllGpsRecordsBottomSheet(
    summaries: List<HealthGpsDaySummaryEntity>,
    selectedDate: String,
    onDismiss: () -> Unit,
    onSelectDate: (String) -> Unit
) {
    val modalBottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.KOREA) }

    val groupedRecords = remember(summaries) {
        groupItemsByTimeSplit(summaries) { it.startTime }
    }

    var collapsedKeys by remember {
        mutableStateOf(emptySet<String>())
    }

    fun toggleSection(key: String) {
        collapsedKeys = if (collapsedKeys.contains(key)) {
            collapsedKeys - key
        } else {
            collapsedKeys + key
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = modalBottomSheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "📍 GPS 이동 기록 전체 목록",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "총 ${summaries.size}일의 동선 기록 보관 중",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                TimeSplitTabControlBar(
                    groups = groupedRecords,
                    collapsedKeys = collapsedKeys,
                    onToggleGroup = { toggleSection(it) },
                    onExpandAll = { collapsedKeys = emptySet() },
                    onCollapseAll = { collapsedKeys = groupedRecords.map { it.key }.toSet() }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (summaries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "기록된 GPS 동선 데이터가 없습니다.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    groupedRecords.forEach { section ->
                        val isExpanded = !collapsedKeys.contains(section.key)
                        val totalKm = String.format(Locale.US, "%.1f", section.items.sumOf { it.totalDistanceMeters } / 1000.0)

                        stickyHeader(key = "hdr_${section.key}") {
                            TimeSplitSectionHeader(
                                title = section.title,
                                count = section.items.size,
                                isExpanded = isExpanded,
                                onToggle = { toggleSection(section.key) },
                                extraSummary = "총 $totalKm km"
                            )
                        }

                        if (isExpanded) {
                            items(section.items, key = { it.date }) { item ->
                                val isSelected = item.date == selectedDate
                                val distKm = String.format(Locale.US, "%.1f", item.totalDistanceMeters / 1000.0)
                                val startT = timeFormat.format(Date(item.startTime))
                                val endT = timeFormat.format(Date(item.endTime))

                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectDate(item.date) },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    tonalElevation = if (isSelected) 3.dp else 1.dp
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = formatGpsHeaderDate(item.date),
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "$distKm km",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                "활동: $startT ~ $endT (${item.pointCount}pt)",
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            if (item.stayCount > 0) {
                                                Text(
                                                    "⏱️ ${HealthGpsCollector.formatDurationText(item.totalStayDurationMinutes)}",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color(0xFF6200EE)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

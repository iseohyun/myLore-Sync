package com.iseohyun.mylore.sync.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.iseohyun.mylore.sync.data.local.AppDatabase
import kotlinx.coroutines.flow.firstOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class TableColumnSpec(
    val title: String,
    val weight: Float,
    val align: TextAlign = TextAlign.Start
)

/**
 * 시간 구간별 스플릿 그룹 (오늘 / 이번주 / 이번달 / 2026년 / 2025년 / 2024년 ...)
 */
data class TimeSplitGroup<T>(
    val key: String,
    val title: String,
    val items: List<T>
)

/**
 * 전체 아이템 리스트를 타임스탬프 기준으로
 * [오늘] -> [이번주] -> [이번달] -> [연도(2026, 2025, 2024 ...)] 순으로 중복 없이 분류합니다.
 */
fun <T> groupItemsByTimeSplit(
    items: List<T>,
    nowMillis: Long = System.currentTimeMillis(),
    timestampSelector: (T) -> Long
): List<TimeSplitGroup<T>> {
    if (items.isEmpty()) return emptyList()

    val todayCal = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val startOfToday = todayCal.timeInMillis

    val weekCal = (todayCal.clone() as Calendar).apply {
        firstDayOfWeek = Calendar.MONDAY
        val dow = get(Calendar.DAY_OF_WEEK)
        val diffToMonday = if (dow == Calendar.SUNDAY) -6 else (Calendar.MONDAY - dow)
        add(Calendar.DAY_OF_MONTH, diffToMonday)
    }
    val startOfThisWeek = weekCal.timeInMillis

    val monthCal = (todayCal.clone() as Calendar).apply {
        set(Calendar.DAY_OF_MONTH, 1)
    }
    val startOfThisMonth = monthCal.timeInMillis

    val todayList = ArrayList<T>()
    val thisWeekList = ArrayList<T>()
    val thisMonthList = ArrayList<T>()
    val yearMap = HashMap<Int, ArrayList<T>>()
    val unknownList = ArrayList<T>()

    val itemCal = Calendar.getInstance()

    for (item in items) {
        val ts = timestampSelector(item)
        when {
            ts <= 0L -> unknownList.add(item)
            ts >= startOfToday -> todayList.add(item)
            ts >= startOfThisWeek -> thisWeekList.add(item)
            ts >= startOfThisMonth -> thisMonthList.add(item)
            else -> {
                itemCal.timeInMillis = ts
                val year = itemCal.get(Calendar.YEAR)
                yearMap.getOrPut(year) { ArrayList() }.add(item)
            }
        }
    }

    val result = ArrayList<TimeSplitGroup<T>>()
    if (todayList.isNotEmpty()) {
        result.add(TimeSplitGroup(key = "TODAY", title = "오늘", items = todayList))
    }
    if (thisWeekList.isNotEmpty()) {
        result.add(TimeSplitGroup(key = "THIS_WEEK", title = "이번주", items = thisWeekList))
    }
    if (thisMonthList.isNotEmpty()) {
        result.add(TimeSplitGroup(key = "THIS_MONTH", title = "이번달", items = thisMonthList))
    }

    val sortedYears = yearMap.keys.sortedDescending()
    for (year in sortedYears) {
        val list = yearMap[year].orEmpty()
        if (list.isNotEmpty()) {
            result.add(TimeSplitGroup(key = "YEAR_$year", title = "${year}년", items = list))
        }
    }

    if (unknownList.isNotEmpty()) {
        result.add(TimeSplitGroup(key = "UNKNOWN", title = "날짜 미상", items = unknownList))
    }

    return result
}

/**
 * 상단 스플릿 탭 바: 각 시간 구간(오늘, 이번주, 이번달, 2026년, 2025년...)을 탭하여 열기/접기하거나
 * 전체 열기/전체 접기를 수행할 수 있는 컨트롤 바입니다.
 */
@Composable
fun TimeSplitTabControlBar(
    groups: List<TimeSplitGroup<*>>,
    collapsedKeys: Set<String>,
    onToggleGroup: (String) -> Unit,
    onExpandAll: () -> Unit,
    onCollapseAll: () -> Unit
) {
    if (groups.isEmpty()) return
    val allCollapsed = collapsedKeys.size >= groups.size

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable {
                    if (allCollapsed) onExpandAll() else onCollapseAll()
                }
        ) {
            Text(
                text = if (allCollapsed) "▼ 전체열기" else "▲ 전체접기",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
            )
        }

        groups.forEach { group ->
            val isExpanded = !collapsedKeys.contains(group.key)
            val arrow = if (isExpanded) "▼" else "▶"
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (isExpanded) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onToggleGroup(group.key) }
            ) {
                Text(
                    text = "$arrow ${group.title} (${group.items.size})",
                    fontSize = 11.sp,
                    fontWeight = if (isExpanded) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isExpanded) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                )
            }
        }
    }
}

/**
 * 표(Table) 또는 리스트 내부에 삽입되는 구간 구분선(Split Header Row).
 * 탭(클릭)하면 해당 구간(오늘/이번주/이번달/연도)의 행들을 열기/접기합니다.
 */
@Composable
fun TimeSplitSectionHeader(
    title: String,
    count: Int,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    extraSummary: String? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f))
            .clickable { onToggle() }
    ) {
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isExpanded) "▼" else "▶",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${count}건",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!extraSummary.isNullOrBlank()) {
                    Text(
                        text = extraSummary,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = if (isExpanded) "접기" else "열기",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                )
            }
        }
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
fun StickyTableHeader(columns: List<TableColumnSpec>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
    ) {
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            columns.forEach { col ->
                Text(
                    text = col.title,
                    modifier = Modifier
                        .weight(col.weight)
                        .padding(horizontal = 2.dp),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    textAlign = col.align,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        HorizontalDivider(thickness = 1.5.dp, color = MaterialTheme.colorScheme.outline)
    }
}

fun formatTableDateTime(ts: Long): String {
    if (ts <= 0L) return "-"
    val sdf = SimpleDateFormat("yy-MM-dd HH:mm", Locale.KOREA)
    return sdf.format(Date(ts))
}

fun formatFullDateTime(ts: Long): String {
    if (ts <= 0L) return "-"
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA)
    return sdf.format(Date(ts))
}

fun formatDurationHhMmSs(durationSec: Long): String {
    if (durationSec < 0L) return "-"
    val hours = durationSec / 3600L
    val minutes = (durationSec % 3600L) / 60L
    val seconds = durationSec % 60L
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}

private fun normalizePhoneKey(raw: String): String {
    val digits = raw.filter { it.isDigit() }
    return if (digits.startsWith("82") && digits.length >= 11) {
        "0" + digits.removePrefix("82")
    } else {
        digits
    }
}

/**
 * 기기 주소록(ContactsContract) 및 명함 지갑 DB에서 전화번호 -> 이름 매핑을 로드합니다.
 */
suspend fun loadPhoneToNameMap(context: Context): Map<String, String> {
    val result = HashMap<String, String>()

    // 1. 기기 주소록 조회
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
        PackageManager.PERMISSION_GRANTED
    ) {
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val rawNum = if (numIdx >= 0) cursor.getString(numIdx) ?: "" else ""
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx)?.trim() ?: "" else ""
                    if (rawNum.isNotBlank() && name.isNotBlank()) {
                        val key = normalizePhoneKey(rawNum)
                        if (key.isNotBlank()) {
                            result.putIfAbsent(key, name)
                            if (key.length >= 8) {
                                result.putIfAbsent(key.takeLast(8), name)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    // 2. 로컬 명함 DB 보조 조회
    try {
        val cards = AppDatabase.getInstance(context).businessCardDao().getAllCards().firstOrNull().orEmpty()
        for (card in cards) {
            if (card.name.isNotBlank()) {
                for (rawPhone in listOf(card.mobile, card.telephone)) {
                    if (rawPhone.isNotBlank()) {
                        val key = normalizePhoneKey(rawPhone)
                        if (key.isNotBlank()) {
                            result.putIfAbsent(key, card.name)
                            if (key.length >= 8) {
                                result.putIfAbsent(key.takeLast(8), card.name)
                            }
                        }
                    }
                }
            }
        }
    } catch (_: Exception) {}

    return result
}

/**
 * 주소록상 이름이 있으면 이름을 반환하고, 없으면 전화번호(둘 다 없으면 '-')를 반환합니다.
 */
fun resolveContactOrPhone(
    rawPhoneOrAddress: String,
    cachedName: String = "",
    contactMap: Map<String, String>
): String {
    val cleanCached = cachedName.trim()
    if (cleanCached.isNotBlank() &&
        cleanCached != "Voice Recorder" &&
        cleanCached != "Watch Voice Memo"
    ) {
        return cleanCached
    }

    val cleanPhone = rawPhoneOrAddress.trim()
    if (cleanPhone.isBlank()) return "-"

    val key = normalizePhoneKey(cleanPhone)
    if (key.isNotBlank()) {
        contactMap[key]?.let { return it }
        if (key.length >= 8) {
            contactMap[key.takeLast(8)]?.let { return it }
        }
    }

    return cleanPhone
}

package com.iseohyun.mylore.sync.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.iseohyun.mylore.sync.data.local.AppDatabase
import kotlinx.coroutines.flow.firstOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TableColumnSpec(
    val title: String,
    val weight: Float,
    val align: TextAlign = TextAlign.Start
)

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

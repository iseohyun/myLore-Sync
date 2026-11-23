package com.iseohyun.mylore.sync.engine

import android.os.Environment
import android.util.Log
import com.iseohyun.mylore.sync.data.remote.MyLoreApiService
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.net.URLEncoder

/**
 * 휴대폰 `Download/` 바로 아래 파일을 PC로 보냅니다 (동기화 주기마다, 사용자 조작 없이).
 *
 * - 카드사 앱에서 받은 명세서 PDF(`P….PDF`)처럼 Download에 떨어진 파일을 PC가 받아 바로 읽게 하려는 것입니다
 *   (PC `mobile/http_server/routes_downloads.rs` → 데이터 폴더 `download\`, 카드 명세서면 바로 넣음).
 * - 하위 폴더(`Samsung Health/` 등)는 보내지 않습니다. 숨김 파일 · 받는 중인 파일(`.crdownload` · `.part` · `.tmp` · `.pending-…`),
 *   1분 안에 바뀐 파일, 빈 파일, 300MB 넘는 파일은 건너뜁니다.
 * - PC가 `ok`로 받으면 휴대폰의 원본을 지웁니다(PC에 같은 이름 · 같은 내용이 이미 있을 때도). 실패하면 그대로 두고 다음 주기에 다시 보냅니다.
 * (2026-11-23 사용자 요청: "휴대폰에서 기능이 들어가야 하고, 앱에서 따로 수락이나 조작은 필요 없음")
 */
object DownloadFolderUploader {
    private const val TAG = "DownloadUploader"
    private const val MAX_BYTES = 300L * 1024 * 1024
    private const val SETTLE_MS = 60_000L
    private val PARTIAL_SUFFIXES = listOf(".crdownload", ".part", ".tmp", ".download")

    /** 보낼 파일 (오래된 것부터) */
    fun pendingFiles(dir: File, nowMs: Long = System.currentTimeMillis()): List<File> =
        (dir.listFiles() ?: emptyArray())
            .filter { f ->
                val name = f.name
                val lower = name.lowercase()
                f.isFile && !name.startsWith(".") && PARTIAL_SUFFIXES.none { lower.endsWith(it) } &&
                    f.length() in 1..MAX_BYTES && nowMs - f.lastModified() >= SETTLE_MS
            }
            .sortedBy { it.lastModified() }

    /** 보내고 지운 파일 수 */
    suspend fun uploadAll(api: MyLoreApiService, token: String, canContinue: () -> Boolean): Int {
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        var sent = 0
        for (file in pendingFiles(dir)) {
            if (!canContinue()) break
            try {
                val res = api.uploadDownloadFile(
                    token,
                    URLEncoder.encode(file.name, "UTF-8").replace("+", "%20"),
                    file.lastModified().toString(),
                    file.asRequestBody("application/octet-stream".toMediaTypeOrNull())
                )
                if (res.isSuccessful && res.body()?.ok == true) {
                    sent++
                    if (!file.delete()) Log.w(TAG, "보냈지만 지우지 못함: ${file.name}")
                } else {
                    Log.w(TAG, "PC가 받지 않음 (${res.code()}): ${file.name}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "보내기 실패: ${file.name}", e)
            }
        }
        if (sent > 0) Log.i(TAG, "Download 파일 ${sent}개를 PC로 보내고 지움")
        return sent
    }
}

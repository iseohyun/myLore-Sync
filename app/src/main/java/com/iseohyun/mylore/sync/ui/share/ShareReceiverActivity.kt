package com.iseohyun.mylore.sync.ui.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.iseohyun.mylore.sync.data.local.AppDatabase
import com.iseohyun.mylore.sync.data.local.entity.SharedNoteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 다른 앱의 공유 메뉴에서 "myLore에 저장"을 골랐을 때 글을 받아 저장하는 화면 없는 액티비티.
 *
 * 에이닷 전화의 통화 요약·스크립트처럼 다른 앱 내부에만 있는 내용을, 사용자가 직접 공유했을 때에 한해 받습니다.
 * 받은 글은 [SharedNoteEntity]로 원문 그대로 저장하고 바로 닫힙니다.
 */
class ShareReceiverActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ShareReceiver"

        /** 파일로 공유된 글을 읽을 때의 최대 크기. 통화 스크립트로 보기 어려운 큰 파일은 받지 않습니다. */
        private const val MAX_SHARED_FILE_BYTES = 2 * 1024 * 1024
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shareIntent = intent

        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { saveSharedContent(shareIntent) }
            Toast.makeText(
                this@ShareReceiverActivity,
                if (saved) "myLore에 저장했습니다." else "저장할 글을 찾지 못했습니다.",
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }
    }

    private suspend fun saveSharedContent(shareIntent: Intent?): Boolean {
        if (shareIntent?.action != Intent.ACTION_SEND) return false

        val subject = (
            shareIntent.getStringExtra(Intent.EXTRA_SUBJECT)
                ?: shareIntent.getStringExtra(Intent.EXTRA_TITLE)
            ).orEmpty().trim()

        // 글이 본문으로 온 경우와 파일(첨부)로 온 경우를 모두 받음
        val bodyText = shareIntent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        val fileText = readSharedTextFile(shareIntent)
        val rawText = listOf(bodyText, fileText).filter { it.isNotBlank() }.joinToString("\n\n").trim()
        if (rawText.isEmpty()) return false

        return try {
            AppDatabase.getInstance(applicationContext).sharedNoteDao().insert(
                SharedNoteEntity(
                    sourcePackage = resolveSourcePackage(),
                    subject = subject,
                    rawText = rawText
                )
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save shared note", e)
            false
        }
    }

    private fun readSharedTextFile(shareIntent: Intent): String {
        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shareIntent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            shareIntent.getParcelableExtra(Intent.EXTRA_STREAM)
        } ?: return ""

        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readNBytesCompat(MAX_SHARED_FILE_BYTES)
                String(bytes, Charsets.UTF_8)
            }.orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read shared file: ${e.message}")
            ""
        }
    }

    /** 공유를 시작한 앱의 패키지명 (확인할 수 없으면 빈 문자열) */
    private fun resolveSourcePackage(): String {
        val fromReferrer = referrer?.takeIf { it.scheme == "android-app" }?.host
        return (fromReferrer ?: callingPackage).orEmpty()
    }

    /** minSdk 24에서도 쓸 수 있는 "최대 N바이트까지 읽기" */
    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (total < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - total))
            if (read < 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toByteArray()
    }
}

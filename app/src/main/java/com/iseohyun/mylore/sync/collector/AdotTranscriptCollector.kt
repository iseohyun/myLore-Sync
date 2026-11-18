package com.iseohyun.mylore.sync.collector

import android.os.Environment
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * 에이닷 전화가 "텍스트로 저장"으로 내보낸 통화 요약·스크립트 파일 하나.
 *
 * @param fileName 원래 파일 이름 (예: `홍길동_010…_20261116162937.txt`). 통화 녹음 파일과 이름 규칙이 같아 PC가 통화를 찾는 열쇠가 됩니다.
 * @param text UTF-8로 풀어낸 본문
 * @param contentKey 본문 바이트의 해시 앞부분. 같은 통화를 두 번 저장해 생긴 `… (1).txt` 복사본을 걸러내는 데 씁니다.
 */
data class AdotTranscriptFile(
    val fileName: String,
    val text: String,
    val modifiedAt: Long,
    val contentKey: String
)

/**
 * 에이닷 전화(`com.skt.prod.dialer`)의 통화 요약·스크립트 수집기.
 *
 * 에이닷은 요약·스크립트를 앱 내부에만 보관하므로 직접 읽을 수 없습니다. 대신 사용자가 통화 화면에서
 * "텍스트로 저장"을 누르면 공용 폴더 `Download/A.phone/`에 텍스트 파일이 생기고, 이 수집기는 그 폴더만 읽습니다.
 */
class AdotTranscriptCollector {

    companion object {
        private const val TAG = "AdotTranscriptCollector"

        /** 에이닷 전화가 텍스트를 내보내는 공용 폴더 (내장 저장소 기준 상대 경로) */
        private const val EXPORT_RELATIVE_DIR = "Download/A.phone"

        /** 통화 스크립트로 보기 어려운 큰 파일은 읽지 않습니다. (15분 통화가 약 10KB) */
        private const val MAX_FILE_BYTES = 1024 * 1024L

        /**
         * 에이닷이 쓰는 한글 인코딩(CP949) 후보. 기기마다 등록된 이름이 달라 차례로 시도합니다.
         */
        private val KOREAN_CHARSET_NAMES = listOf("x-windows-949", "windows-949", "MS949", "EUC-KR")

        private val koreanCharset: Charset? by lazy {
            KOREAN_CHARSET_NAMES.firstNotNullOfOrNull { name ->
                try {
                    Charset.forName(name)
                } catch (_: Exception) {
                    null
                }
            }
        }

        /**
         * 파일 바이트를 글자로 풉니다. UTF-8로 온전히 풀리면 UTF-8로, 아니면 한글 인코딩(CP949)으로 풉니다.
         * 에이닷은 CP949로 저장하지만, 버전이 바뀌어 UTF-8이 되어도 그대로 동작하게 하기 위함입니다.
         */
        fun decodeText(bytes: ByteArray): String {
            val withoutBom = if (bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
            ) {
                bytes.copyOfRange(3, bytes.size)
            } else {
                bytes
            }

            try {
                return Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(withoutBom))
                    .toString()
            } catch (_: CharacterCodingException) {
                // UTF-8이 아님 → 한글 인코딩으로 시도
            }
            return String(withoutBom, koreanCharset ?: Charsets.UTF_8)
        }
    }

    /**
     * 내보내기 폴더의 텍스트 파일을 오래된 것부터 읽어 돌려줍니다.
     * [alreadyUploadedKeys]에 있는 내용(이미 PC로 보낸 것)과, 이번 목록 안에서 내용이 겹치는 복사본은 뺍니다.
     */
    fun collectNewTranscripts(alreadyUploadedKeys: Set<String>): List<AdotTranscriptFile> {
        val exportDir = File(Environment.getExternalStorageDirectory(), EXPORT_RELATIVE_DIR)
        if (!exportDir.isDirectory) return emptyList()

        val files = exportDir.listFiles { f ->
            f.isFile && f.name.endsWith(".txt", ignoreCase = true) && f.length() in 1..MAX_FILE_BYTES
        }?.sortedBy { it.lastModified() } ?: return emptyList()

        val seenKeys = HashSet<String>()
        val result = ArrayList<AdotTranscriptFile>()
        for (file in files) {
            try {
                val bytes = file.readBytes()
                val key = contentKeyOf(bytes)
                if (key in alreadyUploadedKeys || !seenKeys.add(key)) continue

                val text = decodeText(bytes).trim()
                if (text.isEmpty()) continue
                result.add(
                    AdotTranscriptFile(
                        fileName = file.name,
                        text = text,
                        modifiedAt = file.lastModified(),
                        contentKey = key
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read transcript file: ${file.name}", e)
            }
        }
        return result
    }

    private fun contentKeyOf(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}

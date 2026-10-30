package com.iseohyun.mylore.sync.collector

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.iseohyun.mylore.sync.data.model.BusinessCardInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class BusinessCardOcrHelper(private val context: Context) {

    private val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

    suspend fun recognizeText(imageUri: Uri): String = suspendCancellableCoroutine { cont ->
        try {
            val inputImage = InputImage.fromFilePath(context, imageUri)
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText: Text ->
                    cont.resume(visionText.text)
                }
                .addOnFailureListener { e ->
                    cont.resumeWithException(e)
                }
        } catch (e: Exception) {
            cont.resumeWithException(e)
        }
    }

    fun parse(frontText: String, backText: String = "", frontUri: String = "", backUri: String = ""): BusinessCardInfo {
        val lines = frontText.lines().map { it.trim() }.filter { it.isNotBlank() }

        var mobile = ""
        var tel = ""
        var email = ""
        var website = ""
        var jobTitle = ""
        var company = ""
        var name = ""
        var address = ""

        // 정규식
        val mobileRegex = Regex("(?:01[016789])[-\\s]?[0-9]{3,4}[-\\s]?[0-9]{4}")
        val telRegex = Regex("(?:0[2-6][1-5]?|15[0-9]{2}|16[0-9]{2}|18[0-9]{2})[-\\s]?[0-9]{3,4}[-\\s]?[0-9]{4}")
        val emailRegex = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
        val urlRegex = Regex("(?:https?://)?(?:www\\.)?[a-zA-Z0-9-]+\\.[a-zA-Z]{2,}(?:/[^\\s]*)?")

        val jobKeywords = listOf(
            "대표이사", "대표", "CEO", "CTO", "COO", "CFO", "본부장", "실장", "팀장", "지점장",
            "수석연구원", "책임연구원", "선임연구원", "연구원",
            "전무", "상무", "이사", "부장", "차장", "과장", "대리", "주임", "사원", "매니저", "파트장"
        )
        val companyKeywords = listOf("주식회사", "(주)", "㈜", "유한회사", "합자회사", "Corp", "Inc", "Co.,", "Ltd", "랩", "Lab", "스튜디오", "컴퍼니", "센터", "연구소", "그룹")

        val unusedLines = mutableListOf<String>()

        for (line in lines) {
            var matched = false

            // 1. Email
            if (email.isBlank()) {
                val m = emailRegex.find(line)
                if (m != null) {
                    email = m.value
                    matched = true
                }
            }

            // 2. Mobile
            val mobMatch = mobileRegex.find(line)
            if (mobMatch != null && mobile.isBlank()) {
                mobile = mobMatch.value.replace(" ", "-")
                matched = true
            }

            // 3. Tel (Mobile과 다른 경우)
            val telMatch = telRegex.find(line)
            if (telMatch != null && tel.isBlank() && telMatch.value != mobile) {
                tel = telMatch.value.replace(" ", "-")
                matched = true
            }

            // 4. Website
            if (website.isBlank() && !matched) {
                val uMatch = urlRegex.find(line)
                if (uMatch != null && !uMatch.value.contains("@")) {
                    website = uMatch.value
                    matched = true
                }
            }

            // 5. Address (시/도/구/군/로/길/동)
            if (address.isBlank() && (line.contains("시 ") || line.contains("구 ") || line.contains("로 ") || line.contains("길 ") || line.contains("동 "))) {
                if (line.length >= 8) {
                    address = line
                    matched = true
                }
            }

            // 6. Job Title
            if (jobTitle.isBlank()) {
                val foundJob = jobKeywords.firstOrNull { line.contains(it) }
                if (foundJob != null) {
                    jobTitle = foundJob
                    // "홍길동 대표" 처럼 이름과 직책이 한 줄에 있는 경우
                    val remaining = line.replace(foundJob, "").trim()
                    if (name.isBlank() && remaining.matches(Regex("^[가-힣]{2,4}$"))) {
                        name = remaining
                    }
                    matched = true
                }
            }

            // 7. Company
            if (company.isBlank()) {
                if (companyKeywords.any { line.contains(it) }) {
                    company = line
                    matched = true
                }
            }

            if (!matched) {
                unusedLines.add(line)
            }
        }

        // 이름 추출: 아직 이름을 못 찾았다면 2~4글자 한글 단어 우선
        if (name.isBlank()) {
            for (cand in unusedLines) {
                val trimmed = cand.replace(" ", "")
                if (trimmed.matches(Regex("^[가-힣]{2,4}$"))) {
                    name = trimmed
                    unusedLines.remove(cand)
                    break
                }
            }
        }

        // 회사 추출: 아직 회사를 못 찾았다면 첫 번째나 남은 라인 중 적절한 것
        if (company.isBlank() && unusedLines.isNotEmpty()) {
            val cand = unusedLines.firstOrNull { it != name && it.length >= 3 && !it.contains("@") }
            if (cand != null) {
                company = cand
                unusedLines.remove(cand)
            }
        }

        val memoBuilder = StringBuilder()
        if (backText.isNotBlank()) {
            memoBuilder.append("[명함 뒷면 정보]\n").append(backText.trim()).append("\n\n")
        }
        if (unusedLines.isNotEmpty()) {
            memoBuilder.append("[기타 인식 내용]\n").append(unusedLines.joinToString("\n"))
        }

        return BusinessCardInfo(
            name = name,
            mobile = mobile,
            telephone = tel,
            company = company,
            jobTitle = jobTitle,
            email = email,
            address = address,
            website = website,
            memo = memoBuilder.toString().trim(),
            frontImageUri = frontUri,
            backImageUri = backUri,
            rawText = (frontText + if (backText.isNotBlank()) "\n--- [Back] ---\n$backText" else "").trim()
        )
    }
}

package com.iseohyun.mylore.sync.ui.settings

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.PowerManager
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.iseohyun.mylore.sync.data.remote.ApiClient
import com.iseohyun.mylore.sync.data.remote.dto.VoiceprintEnrollRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun MobileVoiceCaptureDialog(
    pcIp: String,
    pcPort: Int,
    pairingToken: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isSpeakerMode by remember { mutableStateOf(false) }
    var isNearEar by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }
    var elapsedSec by remember { mutableStateOf(0) }

    var phraseIdx by remember { mutableStateOf(0) }
    var totalPhrases by remember { mutableStateOf(5) }
    var phraseTitle by remember { mutableStateOf("서버 문구 동기화 중...") }
    var phraseText by remember { mutableStateOf("여보세요? 네, 안녕하세요. 정서현입니다. 말씀하세요.") }
    var sampleCount by remember { mutableStateOf(0) }
    var crossScore by remember { mutableStateOf(0.0) }
    var consistencyA by remember { mutableStateOf(0.0) }
    var consistencyB by remember { mutableStateOf(0.0) }
    var statusText by remember {
        mutableStateOf("📞 실제 통화처럼 휴대폰을 귀에 대고 PC 모니터의 문구를 읽어주세요.")
    }

    val recorderRef = remember { mutableStateOf<MediaRecorder?>(null) }
    val audioFileRef = remember { mutableStateOf<File?>(null) }

    fun stopMediaRecorderSafely(): File? {
        val rec = recorderRef.value
        val file = audioFileRef.value
        recorderRef.value = null
        isRecording = false
        if (rec != null) {
            try {
                rec.stop()
            } catch (_: Exception) {
            }
            try {
                rec.release()
            } catch (_: Exception) {
            }
        }
        return file
    }

    fun applyAudioRouting(speakerOn: Boolean) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = speakerOn
        } catch (_: Exception) {
        }
    }

    fun startRecordingSession() {
        stopMediaRecorderSafely()
        try {
            applyAudioRouting(isSpeakerMode)
            val outFile = File(context.cacheDir, "voiceprint_capture_${System.currentTimeMillis()}.m4a")
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(16000)
            recorder.setAudioEncodingBitRate(64000)
            recorder.setOutputFile(outFile.absolutePath)
            recorder.prepare()
            recorder.start()

            recorderRef.value = recorder
            audioFileRef.value = outFile
            elapsedSec = 0
            isRecording = true
            statusText = if (isSpeakerMode) {
                "🔴 [스피커 모드 녹음 중] 모니터의 문구를 읽고 [음성녹음 종료]를 누르세요."
            } else {
                "🔴 [수화기 모드 녹음 중 · 볼 터치 방지 작동] 귀에 대고 모니터 문구를 읽은 뒤 [음성녹음 종료]를 1초간 길게 누르세요."
            }
        } catch (e: Exception) {
            statusText = "❌ 마이크 녹음 시작 실패: ${e.message}"
        }
    }

    fun finishAndUploadRecording(continueNext: Boolean) {
        if (isUploading) return
        val recordedFile = stopMediaRecorderSafely()
        if (recordedFile == null || !recordedFile.exists() || recordedFile.length() < 400) {
            statusText = "⚠️ 녹음된 음성이 너무 짧습니다. 다시 녹음을 시작해 주세요."
            try {
                recordedFile?.delete()
            } catch (_: Exception) {
            }
            return
        }

        isUploading = true
        statusText = "⏳ 서버로 음성 파일 전송 및 2종 오픈소스(CAM++ & ResNet34) 교차검증 분석 중..."
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    val bytes = recordedFile.readBytes()
                    try {
                        recordedFile.delete()
                    } catch (_: Exception) {
                    }
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    val api = ApiClient.createService(pcIp, pcPort)
                    api.enrollMobileVoiceprint(
                        token = pairingToken,
                        request = VoiceprintEnrollRequest(
                            audioBase64 = b64,
                            phraseText = phraseText,
                            fileExt = "m4a"
                        )
                    )
                }

                if (resp.isSuccessful && resp.body()?.ok == true) {
                    val body = resp.body()!!
                    sampleCount = body.sampleCount
                    crossScore = body.crossAgreementScore
                    consistencyA = body.consistencyA
                    consistencyB = body.consistencyB
                    phraseIdx = body.nextPhraseIdx
                    totalPhrases = body.totalPhrases
                    if (body.nextPhraseTitle.isNotBlank()) phraseTitle = body.nextPhraseTitle
                    if (body.nextPhraseText.isNotBlank()) phraseText = body.nextPhraseText

                    statusText = "✅ 음성 채취 완료! (누적 ${sampleCount}회 · 교차검증 수렴도 ${crossScore}%)"
                    Toast.makeText(
                        context,
                        "✅ 서버 음성지문 반영 완료! (수렴도 ${crossScore}%)",
                        Toast.LENGTH_SHORT
                    ).show()

                    if (continueNext) {
                        startRecordingSession()
                    }
                } else {
                    val errMsg = resp.body()?.error ?: "HTTP ${resp.code()}"
                    statusText = "❌ 서버 분석 실패: $errMsg"
                }
            } catch (e: Exception) {
                statusText = "❌ 서버 통신 오류 ($pcIp:$pcPort): ${e.message}"
            } finally {
                isUploading = false
            }
        }
    }

    // 타이머 증가
    LaunchedEffect(isRecording) {
        while (isRecording) {
            delay(1000L)
            elapsedSec += 1
        }
    }

    // 진입 시 서버 세션 조회 및 녹음 자동 시작
    LaunchedEffect(Unit) {
        try {
            val resp = withContext(Dispatchers.IO) {
                ApiClient.createService(pcIp, pcPort).getVoiceprintSession(pairingToken)
            }
            if (resp.isSuccessful && resp.body()?.ok == true) {
                val b = resp.body()!!
                phraseIdx = b.phraseIdx
                totalPhrases = b.totalPhrases
                if (b.phraseTitle.isNotBlank()) phraseTitle = b.phraseTitle
                if (b.phraseText.isNotBlank()) phraseText = b.phraseText
                sampleCount = b.sampleCount
                crossScore = b.crossAgreementScore
                consistencyA = b.consistencyA
                consistencyB = b.consistencyB
            }
        } catch (_: Exception) {
        }
        startRecordingSession()
    }

    // 근접 센서(Proximity) + 통화용 화면 꺼짐 WakeLock (스피커 모드가 아닐 때만 활성화)
    DisposableEffect(isSpeakerMode) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        var wakeLock: PowerManager.WakeLock? = null

        if (!isSpeakerMode) {
            try {
                if (powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                    wakeLock = powerManager.newWakeLock(
                        PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                        "myLoreSync:VoiceCaptureProximityWakeLock"
                    ).apply {
                        acquire(10 * 60 * 1000L)
                    }
                }
            } catch (_: Exception) {
            }
        } else {
            isNearEar = false
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type == Sensor.TYPE_PROXIMITY) {
                    val dist = event.values.firstOrNull() ?: 5f
                    val maxRange = proximitySensor?.maximumRange ?: 5f
                    isNearEar = !isSpeakerMode && (dist < maxRange && dist < 3.0f)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        if (!isSpeakerMode && proximitySensor != null) {
            sensorManager.registerListener(listener, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL)
        }

        onDispose {
            sensorManager.unregisterListener(listener)
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock.release()
                }
            } catch (_: Exception) {
            }
        }
    }

    // 다이얼로그 종료 시 정리
    DisposableEffect(Unit) {
        onDispose {
            val f = stopMediaRecorderSafely()
            try {
                f?.delete()
            } catch (_: Exception) {
            }
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.mode = AudioManager.MODE_NORMAL
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            } catch (_: Exception) {
            }
        }
    }

    Dialog(
        onDismissRequest = {
            // 볼이나 귀가 화면 가장자리에 닿아 제스처 뒤로가기/외부 탭이 발생해도 절대 닫히지 않도록 차단
        },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        BackHandler(enabled = true) {
            // 수화기 모드나 녹음 중에는 화면 가장자리 스와이프(뒤로가기 제스처)로 절대 창이 닫히지 않도록 방어
            if (!isRecording && !isUploading && isSpeakerMode) {
                onDismiss()
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B1120))
                // 볼 터치 흡수 레이어
                .pointerInput(isSpeakerMode, isNearEar) {
                    detectTapGestures { /* 배경 볼 터치 완전 무시 */ }
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 상단: 통화 스타일 헤더 & 서버 수렴도
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isRecording) "🔴 통화 자세 음성 채취 중 (${elapsedSec}초)" else "🎙️ 모바일 음성지문 채취 (서버 연동)",
                        color = if (isRecording) Color(0xFFF87171) else Color(0xFF38BDF8),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "누적 샘플: ${sampleCount}회 | 교차검증 수렴도: ${crossScore}% (CAM++ ${consistencyA}% · ResNet34 ${consistencyB}%)",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // 스피커 모드 전환 버튼
                    Button(
                        onClick = {
                            val nextMode = !isSpeakerMode
                            isSpeakerMode = nextMode
                            applyAudioRouting(nextMode)
                            statusText = if (nextMode) {
                                "🔊 [스피커 모드 ON] 일반 터치로 녹음 종료 버튼을 바로 누를 수 있습니다."
                            } else {
                                "📞 [수화기 모드 · 볼 터치 방지 ON] 화면에 볼이 닿아도 동작하지 않습니다."
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSpeakerMode) Color(0xFF22C55E) else Color(0xFF1E293B)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (isSpeakerMode) "🔊 스피커 모드 ON (일반 터치 활성)" else "📞 수화기 모드 (스피커 OFF · 볼 터치 방지 중)",
                            color = if (isSpeakerMode) Color(0xFF052E16) else Color(0xFFF8FAFC),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // 중단: 현재 서버 모니터 문구 및 볼 터치 방지 안내
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E293B), RoundedCornerShape(14.dp))
                        .border(1.dp, Color(0xFF334155), RoundedCornerShape(14.dp))
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "🖥️ PC 모니터 표시 문구 (${phraseIdx + 1} / $totalPhrases) — $phraseTitle",
                        color = Color(0xFF38BDF8),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "“$phraseText”",
                        color = Color(0xFFFDE047),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        lineHeight = 24.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = statusText,
                        color = Color(0xFFCBD5E1),
                        fontSize = 12.5.sp,
                        textAlign = TextAlign.Center
                    )

                    if (!isSpeakerMode) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            color = Color(0xFF0F172A),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "🛡️ 볼 터치 보호 활성: 스피커 모드가 아닐 때는 화면이 눌려도 아무 동작도 하지 않으며, 아래 [음성녹음 종료]를 1초간 길게 눌러야만 채취가 완료됩니다.",
                                color = Color(0xFFFBBF24),
                                fontSize = 11.5.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }

                // 하단: 음성녹음 종료(채취 완료) & 다음 문구 계속 버튼
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (isRecording) {
                        // 메인 종료 및 전송 박스 (스피커 모드: 단일 탭 가능 / 비스피커 모드: 볼 터치 무시 + 길게 누르기 1초로만 동작)
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(62.dp)
                                .background(Color(0xFFEF4444), RoundedCornerShape(12.dp))
                                .pointerInput(isSpeakerMode, isUploading) {
                                    detectTapGestures(
                                        onTap = {
                                            if (isSpeakerMode) {
                                                finishAndUploadRecording(continueNext = false)
                                            } else {
                                                Toast
                                                    .makeText(
                                                        context,
                                                        "🛡️ 볼 터치 방지 중: [음성녹음 종료]를 1초간 길게 누르거나 스피커 모드를 켜주세요.",
                                                        Toast.LENGTH_SHORT
                                                    )
                                                    .show()
                                            }
                                        },
                                        onLongPress = {
                                            finishAndUploadRecording(continueNext = false)
                                        }
                                    )
                                }
                        ) {
                            Text(
                                text = if (isSpeakerMode) {
                                    "⏹️ 음성녹음 종료 및 서버 전송 (탭)"
                                } else {
                                    "⏹️ 음성녹음 종료 및 채취 완료 (1초 길게 누르기)"
                                },
                                color = Color.White,
                                fontSize = 15.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // 연속 문구 채취 버튼
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .background(Color(0xFF0284C7), RoundedCornerShape(10.dp))
                                .pointerInput(isSpeakerMode, isUploading) {
                                    detectTapGestures(
                                        onTap = {
                                            if (isSpeakerMode) {
                                                finishAndUploadRecording(continueNext = true)
                                            } else {
                                                Toast
                                                    .makeText(
                                                        context,
                                                        "🛡️ 볼 터치 방지 중: 1초간 길게 누르면 전송 후 다음 문구를 바로 녹음합니다.",
                                                        Toast.LENGTH_SHORT
                                                    )
                                                    .show()
                                            }
                                        },
                                        onLongPress = {
                                            finishAndUploadRecording(continueNext = true)
                                        }
                                    )
                                }
                        ) {
                            Text(
                                text = if (isSpeakerMode) {
                                    "⏭️ 전송 후 다음 문구 계속 녹음 (탭)"
                                } else {
                                    "⏭️ 전송 후 다음 문구 계속 녹음 (길게 누르기)"
                                },
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Button(
                            onClick = { startRecordingSession() },
                            enabled = !isUploading,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                        ) {
                            Text(
                                text = if (isUploading) "⏳ 서버로 전송 및 분석 중..." else "🎙️ 다음 문구 다시 녹음 시작하기",
                                color = Color(0xFF052E16),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // 창 닫기 버튼 (녹음 중 비스피커 모드에서는 길게 눌러야 닫힘)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .background(Color(0xFF334155), RoundedCornerShape(8.dp))
                            .pointerInput(isSpeakerMode, isRecording, isUploading) {
                                detectTapGestures(
                                    onTap = {
                                        if (!isRecording || isSpeakerMode) {
                                            stopMediaRecorderSafely()?.delete()
                                            onDismiss()
                                        }
                                    },
                                    onLongPress = {
                                        stopMediaRecorderSafely()?.delete()
                                        onDismiss()
                                    }
                                )
                            }
                    ) {
                        Text(
                            text = if (isRecording && !isSpeakerMode) "✖ 취소 및 닫기 (길게 누르기)" else "✖ 창 닫기",
                            color = Color(0xFFE2E8F0),
                            fontSize = 13.5.sp
                        )
                    }
                }
            }

            // 근접 센서 감지 시(귀에 닿았을 때) 전체 화면 완전 차단 블랙 오버레이
            if (isNearEar && !isSpeakerMode) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { /* 볼 터치 완전 차단 */ },
                                onLongPress = { /* 볼 길게 닿음도 차단 */ },
                                onDoubleTap = { /* 더블 탭도 차단 */ }
                            )
                        }
                ) {
                    Text(
                        text = "📞 통화 자세 감지됨 (볼 터치 완전 잠금 중)\n귀에서 휴대폰을 떼면 종료 버튼이 나타납니다.",
                        color = Color(0xFF475569),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

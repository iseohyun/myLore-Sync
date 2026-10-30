package com.iseohyun.mylore.sync.ui.businesscard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.iseohyun.mylore.sync.collector.BusinessCardAccumulator
import com.iseohyun.mylore.sync.collector.BusinessCardOcrHelper
import com.iseohyun.mylore.sync.data.local.AppDatabase
import com.iseohyun.mylore.sync.data.model.BusinessCardInfo
import com.iseohyun.mylore.sync.ui.theme.MyLoreSyncTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LiveCardScannerActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    private val accumulator = BusinessCardAccumulator()
    private lateinit var ocrHelper: BusinessCardOcrHelper

    private lateinit var docScannerLauncher: ActivityResultLauncher<IntentSenderRequest>
    private var currentDocScanSide: String = "FRONT"

    // 실시간 누적된 정보 상태 (1순위 기본값으로 사용)
    private val accumulatedInfoState = mutableStateOf(BusinessCardInfo())
    private val frameCountState = mutableIntStateOf(0)
    private var targetCardId: Long = -1L

    private var capturedFrontUri: String = ""
    private var isCapturingBackState = mutableStateOf(false)

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "실시간 스캔을 위해 카메라 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        ocrHelper = BusinessCardOcrHelper(this)

        targetCardId = intent.getLongExtra("TARGET_CARD_ID", -1L)
        if (targetCardId > 0) {
            // 과거 명함 갱신/보강 모드: 기존 데이터 로드
            lifecycleScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getInstance(applicationContext)
                val existing = db.businessCardDao().getCardById(targetCardId)
                if (existing != null) {
                    val baseInfo = BusinessCardInfo(
                        name = existing.name,
                        mobile = existing.mobile,
                        telephone = existing.telephone,
                        company = existing.company,
                        department = existing.department,
                        jobTitle = existing.jobTitle,
                        email = existing.email,
                        address = existing.address,
                        website = existing.website,
                        memo = existing.memo,
                        frontImageUri = existing.frontImageUri,
                        backImageUri = existing.backImageUri
                    )
                    withContext(Dispatchers.Main) {
                        accumulatedInfoState.value = baseInfo
                    }
                }
            }
        }

        val singleSide = intent.getStringExtra("EXTRA_SCAN_SIDE")
        if (singleSide == "BACK") {
            isCapturingBackState.value = true
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        // 촬영 버튼 클릭 시 실행할 Google Document Scanner (3D 모서리 원근 보정 및 화면 꽉 찬 크롭)
        docScannerLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val gmsResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                val pages = gmsResult?.pages
                if (!pages.isNullOrEmpty()) {
                    val scannedUri = pages[0].imageUri.toString()
                    val singleSideMode = intent.getStringExtra("EXTRA_SCAN_SIDE")

                    if (singleSideMode == "FRONT") {
                        accumulatedInfoState.value = accumulatedInfoState.value.copy(frontImageUri = scannedUri)
                        proceedToEditor()
                    } else if (singleSideMode == "BACK") {
                        accumulatedInfoState.value = accumulatedInfoState.value.copy(backImageUri = scannedUri)
                        proceedToEditor()
                    } else if (currentDocScanSide == "FRONT") {
                        capturedFrontUri = scannedUri
                        accumulatedInfoState.value = accumulatedInfoState.value.copy(frontImageUri = scannedUri)
                        isCapturingBackState.value = true
                        Toast.makeText(this, "앞면 3D 스캔 완료! 뒷면도 실시간 누적 후 촬영하거나 [완료]를 누르세요.", Toast.LENGTH_SHORT).show()
                    } else {
                        accumulatedInfoState.value = accumulatedInfoState.value.copy(backImageUri = scannedUri)
                        proceedToEditor()
                    }
                }
            }
        }

        setContent {
            MyLoreSyncTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val liveInfo = accumulatedInfoState.value
                    val frameCount = frameCountState.intValue
                    val isBackSide = isCapturingBackState.value

                    LiveScannerScreen(
                        liveInfo = liveInfo,
                        frameCount = frameCount,
                        isBackSide = isBackSide,
                        onCapture = {
                            // 촬영 누르는 순간 Google Document Scanner로 3D 모서리 원근 보정 편집 실행!
                            // OCR 텍스트 정보는 방금까지 모아둔 실시간 누적 정보를 기본값으로 유지
                            startGoogleDocumentScanner(if (isCapturingBackState.value) "BACK" else "FRONT")
                        },
                        onDone = { proceedToEditor() },
                        onClose = { finish() }
                    )
                }
            }
        }
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    fun startCamera(previewView: PreviewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            var isAnalyzing = false

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage != null && !isAnalyzing) {
                            isAnalyzing = true
                            val rotation = imageProxy.imageInfo.rotationDegrees
                            val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

                            recognizer.process(inputImage)
                                .addOnSuccessListener { visionText ->
                                    if (visionText.text.isNotBlank()) {
                                        val frameInfo = ocrHelper.parse(visionText.text)
                                        // 실시간 지능형 누적 병합
                                        val updated = accumulator.merge(accumulatedInfoState.value, frameInfo)

                                        lifecycleScope.launch(Dispatchers.Main) {
                                            accumulatedInfoState.value = updated
                                            frameCountState.intValue += 1
                                        }
                                    }
                                }
                                .addOnCompleteListener {
                                    isAnalyzing = false
                                    imageProxy.close()
                                }
                        } else {
                            imageProxy.close()
                        }
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture,
                    imageAnalyzer
                )
            } catch (e: Exception) {
                Log.e("LiveCardScanner", "Use case binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startGoogleDocumentScanner(side: String) {
        currentDocScanSide = side
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(1)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

        val scanner = GmsDocumentScanning.getClient(options)
        scanner.getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                docScannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "문서 스캐너 실행 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                takePhoto()
            }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return

        val photoFile = File(
            getExternalFilesDir(null),
            "card_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}_${if (isCapturingBackState.value) "back" else "front"}.jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    // 회전 및 1.75:1 명함 화면 가득 차게 크롭 보정 실행
                    com.iseohyun.mylore.sync.collector.ImageCropHelper.cropAndFillCard(photoFile)
                    val savedUri = Uri.fromFile(photoFile).toString()

                    lifecycleScope.launch(Dispatchers.Main) {
                        val singleSideMode = intent.getStringExtra("EXTRA_SCAN_SIDE")
                        if (singleSideMode == "FRONT") {
                            accumulatedInfoState.value = accumulatedInfoState.value.copy(frontImageUri = savedUri)
                            proceedToEditor()
                        } else if (singleSideMode == "BACK") {
                            accumulatedInfoState.value = accumulatedInfoState.value.copy(backImageUri = savedUri)
                            proceedToEditor()
                        } else if (!isCapturingBackState.value) {
                            capturedFrontUri = savedUri
                            accumulatedInfoState.value = accumulatedInfoState.value.copy(frontImageUri = savedUri)
                            isCapturingBackState.value = true
                            Toast.makeText(this@LiveCardScannerActivity, "앞면 촬영 완료! 뒷면도 촬영하거나 [완료]를 누르세요.", Toast.LENGTH_SHORT).show()
                        } else {
                            accumulatedInfoState.value = accumulatedInfoState.value.copy(backImageUri = savedUri)
                            proceedToEditor()
                        }
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        Toast.makeText(this@LiveCardScannerActivity, "사진 캡처 실패: ${exc.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    private fun proceedToEditor() {
        val info = accumulatedInfoState.value
        val intent = Intent(this, BusinessCardScanActivity::class.java).apply {
            putExtra("EXTRA_NAME", info.name)
            putExtra("EXTRA_MOBILE", info.mobile)
            putExtra("EXTRA_TEL", info.telephone)
            putExtra("EXTRA_COMPANY", info.company)
            putExtra("EXTRA_JOB", info.jobTitle)
            putExtra("EXTRA_EMAIL", info.email)
            putExtra("EXTRA_ADDRESS", info.address)
            putExtra("EXTRA_WEBSITE", info.website)
            putExtra("EXTRA_MEMO", info.memo)
            putExtra("EXTRA_FRONT_URI", info.frontImageUri.ifBlank { capturedFrontUri })
            putExtra("EXTRA_BACK_URI", info.backImageUri)
            putExtra("TARGET_CARD_ID", targetCardId)
        }
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        recognizer.close()
    }
}

@Composable
fun LiveScannerScreen(
    liveInfo: BusinessCardInfo,
    frameCount: Int,
    isBackSide: Boolean,
    onCapture: () -> Unit,
    onDone: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var previewView: PreviewView? by remember { mutableStateOf(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. CameraX Preview
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    previewView = this
                    (ctx as? LiveCardScannerActivity)?.startCamera(this)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. 명함 가이드라인 오버레이 (9:5 비율)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 80.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.75f) // 일반 명함 90mm x 50mm
                    .border(2.dp, if (frameCount > 0) Color.Green else Color.White, RoundedCornerShape(12.dp))
            )
        }

        // 3. 상단 헤더 & 카운터 (상태바 영역 회피)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            ) {
                Text("✕", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }

            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = if (isBackSide) "📷 뒷면 촬영 중" else "📷 앞면 실시간 누적 중 (${frameCount} frames)",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    fontSize = 13.sp
                )
            }

            if (liveInfo.name.isNotBlank() || liveInfo.mobile.isNotBlank()) {
                Button(
                    onClick = onDone,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("완료", fontSize = 13.sp)
                }
            } else {
                Spacer(modifier = Modifier.width(48.dp))
            }
        }

        // 4. 하단 실시간 인식된 정보 칩 카드 & 셔터 버튼 (안드로이드 기본 내비게이션 바 겹침 완벽 방지)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.85f))
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 18.dp)
        ) {
            Text(
                "실시간 누적 인식 결과 (높은 정확도로 보정 중)",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoBadge(label = "이름", value = liveInfo.name, modifier = Modifier.weight(1f))
                InfoBadge(label = "휴대폰", value = liveInfo.mobile, modifier = Modifier.weight(1.3f))
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoBadge(label = "회사", value = liveInfo.company, modifier = Modifier.weight(1.2f))
                InfoBadge(label = "직책", value = liveInfo.jobTitle, modifier = Modifier.weight(0.8f))
            }
            if (liveInfo.email.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                InfoBadge(label = "이메일", value = liveInfo.email, modifier = Modifier.fillMaxWidth())
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 셔터 버튼
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onCapture,
                    modifier = Modifier
                        .height(52.dp)
                        .fillMaxWidth(0.7f),
                    shape = RoundedCornerShape(26.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(
                        if (isBackSide) "📸 뒷면 촬영 및 확정" else "📸 앞면 촬영 (다음)",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun InfoBadge(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = if (value.isNotBlank()) Color(0xFF1E3A8A) else Color.White.copy(alpha = 0.15f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("$label: ", color = Color.LightGray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(
                if (value.isNotBlank()) value else "(인식 대기중)",
                color = if (value.isNotBlank()) Color.White else Color.Gray,
                fontSize = 12.sp,
                fontWeight = if (value.isNotBlank()) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1
            )
        }
    }
}

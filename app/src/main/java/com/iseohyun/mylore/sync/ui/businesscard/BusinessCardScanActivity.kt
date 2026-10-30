package com.iseohyun.mylore.sync.ui.businesscard

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import coil.compose.rememberAsyncImagePainter
import com.iseohyun.mylore.sync.collector.ImageCropHelper
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.*
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.iseohyun.mylore.sync.collector.BusinessCardOcrHelper
import com.iseohyun.mylore.sync.collector.ContactManager
import com.iseohyun.mylore.sync.data.local.AppDatabase
import com.iseohyun.mylore.sync.data.local.entity.BusinessCardEntity
import com.iseohyun.mylore.sync.data.model.BusinessCardInfo
import com.iseohyun.mylore.sync.data.model.ExistingContact
import com.iseohyun.mylore.sync.ui.theme.MyLoreSyncTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BusinessCardScanActivity : ComponentActivity() {

    private lateinit var scannerLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var ocrHelper: BusinessCardOcrHelper
    private lateinit var contactManager: ContactManager

    private var parsedInfoState = mutableStateOf<BusinessCardInfo?>(null)
    private var existingContactState = mutableStateOf<ExistingContact?>(null)
    private var isProcessingOcrState = mutableStateOf(false)
    private var targetCardId: Long = -1L

    private var currentScanningSide: String? = null

    private var pendingSaveInfo: Pair<BusinessCardInfo, Boolean>? = null
    private val contactPermissions = arrayOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS
    )

    private val contactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val writeGranted = permissions[Manifest.permission.WRITE_CONTACTS] == true
        val readGranted = permissions[Manifest.permission.READ_CONTACTS] == true
        if (writeGranted && readGranted) {
            pendingSaveInfo?.let { (info, saveAsNew) ->
                saveContact(info, saveAsNew)
            }
            pendingSaveInfo = null
        } else {
            showPermissionDeniedDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ocrHelper = BusinessCardOcrHelper(this)
        contactManager = ContactManager(this)

        targetCardId = intent.getLongExtra("TARGET_CARD_ID", -1L)
        val extraName = intent.getStringExtra("EXTRA_NAME")

        if (!extraName.isNullOrBlank() || intent.hasExtra("EXTRA_MOBILE")) {
            // LiveCardScanner 등에서 전달받은 경우
            val initialInfo = BusinessCardInfo(
                name = intent.getStringExtra("EXTRA_NAME") ?: "",
                mobile = intent.getStringExtra("EXTRA_MOBILE") ?: "",
                telephone = intent.getStringExtra("EXTRA_TEL") ?: "",
                company = intent.getStringExtra("EXTRA_COMPANY") ?: "",
                jobTitle = intent.getStringExtra("EXTRA_JOB") ?: "",
                email = intent.getStringExtra("EXTRA_EMAIL") ?: "",
                address = intent.getStringExtra("EXTRA_ADDRESS") ?: "",
                website = intent.getStringExtra("EXTRA_WEBSITE") ?: "",
                memo = intent.getStringExtra("EXTRA_MEMO") ?: "",
                frontImageUri = intent.getStringExtra("EXTRA_FRONT_URI") ?: "",
                backImageUri = intent.getStringExtra("EXTRA_BACK_URI") ?: ""
            )
            parsedInfoState.value = initialInfo
            existingContactState.value = contactManager.findExistingContact(initialInfo.mobile, initialInfo.name)
        } else {
            // 외부에서 첫 실행 시 구글 문서 스캐너로 앞/뒷면 3D 모서리 원근 보정 스캔 실행
            startDocumentScanner()
        }

        scannerLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val gmsResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                gmsResult?.let { handleScanResult(it) }
            } else {
                if (parsedInfoState.value == null) {
                    finish()
                }
            }
        }

        setContent {
            MyLoreSyncTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val info = parsedInfoState.value
                    val existing = existingContactState.value
                    val isProcessing = isProcessingOcrState.value

                    BusinessCardEditorScreen(
                        cardInfo = info,
                        existingContact = existing,
                        isProcessing = isProcessing,
                        onRescan = { startDocumentScanner() },
                        onRescanSide = { side ->
                            // 사진 재촬영은 구글 문서 스캐너를 통해 3D 모서리 원근 보정으로 완벽히 획득!
                            startDocumentScanner(targetSide = side)
                        },
                        onFocusScanField = { fieldKey, fieldName ->
                            // 세부 글자 재스캔은 실시간 누적 카메라로 집중 누적 추출!
                            Toast.makeText(this@BusinessCardScanActivity, "🎯 [$fieldName] 실시간 집중 재스캔을 시작합니다.", Toast.LENGTH_SHORT).show()
                            val intent = Intent(this@BusinessCardScanActivity, LiveCardScannerActivity::class.java).apply {
                                putExtra("EXTRA_FOCUS_FIELD", fieldKey)
                                putExtra("TARGET_CARD_ID", targetCardId)
                                putExtra("EXTRA_NAME", info?.name)
                                putExtra("EXTRA_MOBILE", info?.mobile)
                                putExtra("EXTRA_TEL", info?.telephone)
                                putExtra("EXTRA_COMPANY", info?.company)
                                putExtra("EXTRA_JOB", info?.jobTitle)
                                putExtra("EXTRA_EMAIL", info?.email)
                                putExtra("EXTRA_ADDRESS", info?.address)
                                putExtra("EXTRA_WEBSITE", info?.website)
                                putExtra("EXTRA_MEMO", info?.memo)
                                putExtra("EXTRA_FRONT_URI", info?.frontImageUri)
                                putExtra("EXTRA_BACK_URI", info?.backImageUri)
                            }
                            startActivity(intent)
                            finish()
                        },
                        onSave = { updatedInfo, saveAsNew ->
                            checkPermissionsAndSave(updatedInfo, saveAsNew)
                        },
                        onBack = { finish() }
                    )
                }
            }
        }
    }

    private fun hasContactPermissions(): Boolean {
        val write = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val read = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        return write && read
    }

    private fun checkPermissionsAndSave(info: BusinessCardInfo, saveAsNew: Boolean) {
        if (info.name.isBlank()) {
            Toast.makeText(this, "연락처에 등록할 '이름'을 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        if (info.mobile.isBlank() && info.telephone.isBlank() && info.email.isBlank()) {
            Toast.makeText(this, "전화번호 또는 이메일 중 최소 하나를 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        if (hasContactPermissions()) {
            saveContact(info, saveAsNew)
        } else {
            pendingSaveInfo = Pair(info, saveAsNew)
            contactsPermissionLauncher.launch(contactPermissions)
        }
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("연락처 권한 필요")
            .setMessage("연락처를 생성하고 저장하기 위해 '연락처' 권한 허용이 필요합니다.\n\n권한 설정을 위해 앱 정보 화면으로 이동하시겠습니까?")
            .setPositiveButton("설정으로 이동") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("취소") { _, _ ->
                pendingSaveInfo = null
            }
            .show()
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            Toast.makeText(this, "'권한' 항목에서 '연락처'를 허용해주세요.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "설정 화면 열기 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingSaveInfo != null && hasContactPermissions()) {
            val (info, saveAsNew) = pendingSaveInfo!!
            pendingSaveInfo = null
            Toast.makeText(this, "연락처 권한이 확인되었습니다. 저장을 진행합니다.", Toast.LENGTH_SHORT).show()
            saveContact(info, saveAsNew)
        }
    }

    private fun startDocumentScanner(targetSide: String? = null) {
        currentScanningSide = targetSide
        val pageLimit = if (targetSide != null) 1 else 2
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(pageLimit)
            .setResultFormats(RESULT_FORMAT_JPEG)
            .setScannerMode(SCANNER_MODE_FULL) // 3D 모서리 인식 + 원근 보정 + 그림자 제거
            .build()

        val scanner = GmsDocumentScanning.getClient(options)
        scanner.getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "문서 스캐너 실행 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                if (parsedInfoState.value == null) {
                    finish()
                }
            }
    }

    private fun handleScanResult(result: GmsDocumentScanningResult) {
        val pages = result.pages ?: return
        if (pages.isEmpty()) return

        val side = currentScanningSide
        val currentInfo = parsedInfoState.value ?: BusinessCardInfo()

        isProcessingOcrState.value = true

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (side == "FRONT") {
                    val newFrontUri = pages[0].imageUri.toString()
                    val frontText = ocrHelper.recognizeText(pages[0].imageUri)
                    val ocrResult = ocrHelper.parse(frontText = frontText, frontUri = newFrontUri)
                    val merged = com.iseohyun.mylore.sync.collector.BusinessCardAccumulator().merge(currentInfo, ocrResult)
                    val matched = contactManager.findExistingContact(merged.mobile, merged.name)
                    withContext(Dispatchers.Main) {
                        parsedInfoState.value = merged.copy(frontImageUri = newFrontUri)
                        existingContactState.value = matched
                        isProcessingOcrState.value = false
                        Toast.makeText(this@BusinessCardScanActivity, "앞면 사진이 3D 모서리 보정되어 교체되었습니다.", Toast.LENGTH_SHORT).show()
                    }
                } else if (side == "BACK") {
                    val newBackUri = pages[0].imageUri.toString()
                    val backText = ocrHelper.recognizeText(pages[0].imageUri)
                    val updatedMemo = if (currentInfo.memo.isNotBlank()) "${currentInfo.memo}\n\n[뒷면]\n$backText" else "[뒷면]\n$backText"
                    withContext(Dispatchers.Main) {
                        parsedInfoState.value = currentInfo.copy(backImageUri = newBackUri, memo = updatedMemo.trim())
                        isProcessingOcrState.value = false
                        Toast.makeText(this@BusinessCardScanActivity, "뒷면 사진이 3D 모서리 보정되어 교체되었습니다.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val frontUri = pages[0].imageUri
                    val backUri = if (pages.size > 1) pages[1].imageUri else null

                    val frontText = ocrHelper.recognizeText(frontUri)
                    val backText = if (backUri != null) ocrHelper.recognizeText(backUri) else ""

                    val cardInfo = ocrHelper.parse(
                        frontText = frontText,
                        backText = backText,
                        frontUri = frontUri.toString(),
                        backUri = backUri?.toString() ?: ""
                    )

                    val matched = contactManager.findExistingContact(cardInfo.mobile, cardInfo.name)

                    withContext(Dispatchers.Main) {
                        parsedInfoState.value = cardInfo
                        existingContactState.value = matched
                        isProcessingOcrState.value = false
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isProcessingOcrState.value = false
                    Toast.makeText(this@BusinessCardScanActivity, "OCR 텍스트 인식 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveContact(info: BusinessCardInfo, forceNew: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val frontBitmap = loadBitmapFromUri(info.frontImageUri)
            var existing = existingContactState.value
            if (existing == null && hasContactPermissions()) {
                existing = contactManager.findExistingContact(info.mobile, info.name)
            }

            var saveException: Exception? = null
            val success = try {
                if (existing != null && !forceNew) {
                    contactManager.updateContact(existing.rawContactId, info, frontBitmap)
                } else {
                    contactManager.createContact(info, frontBitmap)
                }
            } catch (e: Exception) {
                saveException = e
                Log.e("BusinessCardScan", "Failed to save contact via ContactManager", e)
                false
            }

            // Room DB 명함 보관함에도 저장 또는 갱신
            try {
                val db = AppDatabase.getInstance(applicationContext)
                val cardEntity = BusinessCardEntity(
                    id = if (targetCardId > 0) targetCardId else 0L,
                    name = info.name,
                    mobile = info.mobile,
                    telephone = info.telephone,
                    company = info.company,
                    jobTitle = info.jobTitle,
                    email = info.email,
                    address = info.address,
                    website = info.website,
                    memo = info.memo,
                    frontImageUri = info.frontImageUri,
                    backImageUri = info.backImageUri,
                    contactId = existing?.contactId,
                    rawContactId = existing?.rawContactId,
                    updatedAt = System.currentTimeMillis()
                )
                if (targetCardId > 0) {
                    db.businessCardDao().update(cardEntity)
                } else {
                    db.businessCardDao().insert(cardEntity)
                }
            } catch (e: Exception) {
                Log.e("BusinessCardScan", "Failed to save card entity in DB", e)
            }

            withContext(Dispatchers.Main) {
                if (success) {
                    val msg = if (existing != null && !forceNew) {
                        "기존 연락처 '${existing.displayName}'에 명함 정보가 성공적으로 업데이트되었습니다."
                    } else {
                        "새 연락처 '${info.name}'(이)가 명함 사진과 함께 등록되었습니다."
                    }
                    Toast.makeText(this@BusinessCardScanActivity, msg, Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    if (!hasContactPermissions() || saveException is SecurityException) {
                        showPermissionDeniedDialog()
                    } else {
                        val detail = saveException?.localizedMessage ?: "기기 연락처 계정 또는 입력값을 확인해주세요."
                        Toast.makeText(this@BusinessCardScanActivity, "연락처 저장 실패: $detail", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun loadBitmapFromUri(uriString: String): Bitmap? {
        if (uriString.isBlank()) return null
        return try {
            val uri = Uri.parse(uriString)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
        } catch (_: Exception) {
            null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessCardEditorScreen(
    cardInfo: BusinessCardInfo?,
    existingContact: ExistingContact?,
    isProcessing: Boolean,
    onRescan: () -> Unit,
    onRescanSide: (String) -> Unit,
    onFocusScanField: (String, String) -> Unit,
    onSave: (BusinessCardInfo, Boolean) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var name by remember(cardInfo) { mutableStateOf(cardInfo?.name ?: "") }
    var mobile by remember(cardInfo) { mutableStateOf(cardInfo?.mobile ?: "") }
    var telephone by remember(cardInfo) { mutableStateOf(cardInfo?.telephone ?: "") }
    var company by remember(cardInfo) { mutableStateOf(cardInfo?.company ?: "") }
    var jobTitle by remember(cardInfo) { mutableStateOf(cardInfo?.jobTitle ?: "") }
    var email by remember(cardInfo) { mutableStateOf(cardInfo?.email ?: "") }
    var address by remember(cardInfo) { mutableStateOf(cardInfo?.address ?: "") }
    var website by remember(cardInfo) { mutableStateOf(cardInfo?.website ?: "") }
    var memo by remember(cardInfo) { mutableStateOf(cardInfo?.memo ?: "") }

    var frontImageUri by remember(cardInfo) { mutableStateOf(cardInfo?.frontImageUri ?: "") }
    var backImageUri by remember(cardInfo) { mutableStateOf(cardInfo?.backImageUri ?: "") }

    var saveAsNewOption by remember { mutableStateOf(false) }

    // 사용자가 길게 눌러 확정(Lock)한 필드 목록
    var lockedFields by remember { mutableStateOf(setOf<String>()) }

    // 수동 편집 다이얼로그용 상태
    var editingPhotoSide by remember { mutableStateOf<String?>(null) } // "FRONT" or "BACK"

    fun toggleLock(fieldKey: String, fieldName: String) {
        lockedFields = if (lockedFields.contains(fieldKey)) {
            Toast.makeText(context, "🔓 [$fieldName] 확정 해제됨", Toast.LENGTH_SHORT).show()
            lockedFields - fieldKey
        } else {
            Toast.makeText(context, "🔒 [$fieldName] 필드가 확정되었습니다. 이후 스캔 시 보존됩니다.", Toast.LENGTH_SHORT).show()
            lockedFields + fieldKey
        }
    }

    // 마일스톤 UI 피드백: 필수 항목 에러 상태 및 포커스 제어
    var nameError by remember { mutableStateOf(false) }
    var contactError by remember { mutableStateOf(false) }
    val nameFocusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    val triggerSave = {
        if (cardInfo != null) {
            val trimmedName = name.trim()
            val trimmedMobile = mobile.trim()
            val trimmedTel = telephone.trim()
            val trimmedEmail = email.trim()

            if (trimmedName.isBlank()) {
                nameError = true
                Toast.makeText(context, "⚠️ 이름은 필수 입력 항목입니다.", Toast.LENGTH_SHORT).show()
                coroutineScope.launch {
                    scrollState.animateScrollTo(0)
                    try { nameFocusRequester.requestFocus() } catch (_: Exception) {}
                }
            } else if (trimmedMobile.isBlank() && trimmedTel.isBlank() && trimmedEmail.isBlank()) {
                contactError = true
                Toast.makeText(context, "⚠️ 전화번호 또는 이메일 중 최소 하나를 입력해주세요.", Toast.LENGTH_SHORT).show()
            } else {
                nameError = false
                contactError = false
                val updated = cardInfo.copy(
                    name = trimmedName,
                    mobile = trimmedMobile,
                    telephone = trimmedTel,
                    company = company.trim(),
                    jobTitle = jobTitle.trim(),
                    email = trimmedEmail,
                    address = address.trim(),
                    website = website.trim(),
                    memo = memo.trim(),
                    frontImageUri = frontImageUri,
                    backImageUri = backImageUri
                )
                onSave(updated, saveAsNewOption)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("스마트 명함 스캐너", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    // 초록색 겹친 v자 모양의 저장 아이콘 버튼
                    IconButton(
                        onClick = triggerSave,
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        DoubleCheckIcon(color = Color(0xFF16A34A))
                    }
                }
            )
        }
    ) { paddingValues ->
        if (isProcessing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("명함 모서리 보정 및 OCR 인식 중...", style = MaterialTheme.typography.bodyLarge)
                }
            }
        } else if (cardInfo == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Button(onClick = onRescan) {
                    Text("📷", fontSize = 18.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("명함 촬영하기 (앞/뒷면)")
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(scrollState)
                    .padding(16.dp)
            ) {
                // 1. 이미지 프리뷰 (앞면, 뒷면) - 사진 터치 시 수동 편집, 우상단 배지 터치 시 재촬영
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("보정된 명함 스캔본", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("사진 탭: 수동 편집 | 우상단 🔄: 재촬영", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 앞면 사진 카드
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("앞면", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                TextButton(
                                    onClick = {
                                        if (frontImageUri.isNotBlank()) {
                                            frontImageUri = ImageCropHelper.autoEnhanceFromUri(frontImageUri, context)
                                            Toast.makeText(context, "앞면 명함이 화면에 가득 차게 자동 보정되었습니다.", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("✨자동보정", fontSize = 11.sp)
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(115.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            ) {
                                if (frontImageUri.isNotBlank()) {
                                    Image(
                                        painter = rememberAsyncImagePainter(frontImageUri),
                                        contentDescription = "명함 앞면",
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clickable { editingPhotoSide = "FRONT" },
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Gray.copy(alpha = 0.2f))
                                            .clickable { onRescanSide("FRONT") },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("+ 앞면 촬영", color = Color.Gray, fontSize = 12.sp)
                                    }
                                }

                                // 우상단 재촬영 배지 (원형)
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .size(30.dp)
                                        .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                                        .clickable { onRescanSide("FRONT") },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("🔄", fontSize = 14.sp)
                                }
                            }
                        }
                    }

                    // 뒷면 사진 카드
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("뒷면", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                TextButton(
                                    onClick = {
                                        if (backImageUri.isNotBlank()) {
                                            backImageUri = ImageCropHelper.autoEnhanceFromUri(backImageUri, context)
                                            Toast.makeText(context, "뒷면 명함이 화면에 가득 차게 자동 보정되었습니다.", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("✨자동보정", fontSize = 11.sp)
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(115.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            ) {
                                if (backImageUri.isNotBlank()) {
                                    Image(
                                        painter = rememberAsyncImagePainter(backImageUri),
                                        contentDescription = "명함 뒷면",
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clickable { editingPhotoSide = "BACK" },
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Gray.copy(alpha = 0.15f))
                                            .clickable { onRescanSide("BACK") },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("+ 뒷면 촬영", color = Color.Gray, fontSize = 12.sp)
                                    }
                                }

                                // 우상단 재촬영 배지 (원형)
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .size(30.dp)
                                        .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                                        .clickable { onRescanSide("BACK") },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("🔄", fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 2. 기존 연락처 매칭 배지
                if (existingContact != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("👤", fontSize = 18.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "기존 연락처 일치: ${existingContact.displayName}",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Text(
                                "기존 연락처에 새로운 명함 정보(직책, 이메일, 메모, 사진 등)를 병합 업데이트합니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )

                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = saveAsNewOption,
                                    onCheckedChange = { saveAsNewOption = it }
                                )
                                Text("병합하지 않고 신규 연락처로 별도 생성", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                } else {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("＋", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "새 연락처로 등록되며, 앞면 명함이 프로필 사진으로 저장됩니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 3. 편집 필드들 (길게 눌러 확정, 짧게 눌러 집중 재스캔)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("인식된 정보 확인 및 수정", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("💡 길게 누르면 확정 | 🔍 탭하면 집중 재스캔", fontSize = 10.sp, color = Color.Gray)
                }
                Spacer(modifier = Modifier.height(8.dp))

                SmartFieldRow(
                    fieldKey = "name",
                    label = "이름 *",
                    value = name,
                    isLocked = lockedFields.contains("name"),
                    onValueChange = {
                        name = it
                        if (it.isNotBlank()) nameError = false
                    },
                    onToggleLock = { toggleLock("name", "이름") },
                    onFocusScan = { onFocusScanField("name", "이름") },
                    isError = nameError,
                    errorMessage = if (nameError) "이름을 입력해주세요 (필수 입력)" else null,
                    focusRequester = nameFocusRequester,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                SmartFieldRow(
                    fieldKey = "mobile",
                    label = "휴대폰 번호",
                    value = mobile,
                    isLocked = lockedFields.contains("mobile"),
                    onValueChange = {
                        mobile = it
                        if (it.isNotBlank()) contactError = false
                    },
                    onToggleLock = { toggleLock("mobile", "휴대폰 번호") },
                    onFocusScan = { onFocusScanField("mobile", "휴대폰 번호") },
                    isError = contactError,
                    errorMessage = if (contactError) "전화번호 또는 이메일 중 최소 하나를 입력해주세요" else null,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                SmartFieldRow(
                    fieldKey = "telephone",
                    label = "회사/일반 전화",
                    value = telephone,
                    isLocked = lockedFields.contains("telephone"),
                    onValueChange = {
                        telephone = it
                        if (it.isNotBlank()) contactError = false
                    },
                    onToggleLock = { toggleLock("telephone", "회사 전화") },
                    onFocusScan = { onFocusScanField("telephone", "회사 전화") },
                    isError = contactError,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                SmartFieldRow(
                    fieldKey = "company",
                    label = "회사명",
                    value = company,
                    isLocked = lockedFields.contains("company"),
                    onValueChange = { company = it },
                    onToggleLock = { toggleLock("company", "회사명") },
                    onFocusScan = { onFocusScanField("company", "회사명") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                SmartFieldRow(
                    fieldKey = "jobTitle",
                    label = "직책/직급",
                    value = jobTitle,
                    isLocked = lockedFields.contains("jobTitle"),
                    onValueChange = { jobTitle = it },
                    onToggleLock = { toggleLock("jobTitle", "직책/직급") },
                    onFocusScan = { onFocusScanField("jobTitle", "직책/직급") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                SmartFieldRow(
                    fieldKey = "email",
                    label = "이메일",
                    value = email,
                    isLocked = lockedFields.contains("email"),
                    onValueChange = {
                        email = it
                        if (it.isNotBlank()) contactError = false
                    },
                    onToggleLock = { toggleLock("email", "이메일") },
                    onFocusScan = { onFocusScanField("email", "이메일") },
                    isError = contactError,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                SmartFieldRow(
                    fieldKey = "address",
                    label = "주소",
                    value = address,
                    isLocked = lockedFields.contains("address"),
                    onValueChange = { address = it },
                    onToggleLock = { toggleLock("address", "주소") },
                    onFocusScan = { onFocusScanField("address", "주소") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                SmartFieldRow(
                    fieldKey = "website",
                    label = "웹사이트",
                    value = website,
                    isLocked = lockedFields.contains("website"),
                    onValueChange = { website = it },
                    onToggleLock = { toggleLock("website", "웹사이트") },
                    onFocusScan = { onFocusScanField("website", "웹사이트") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                SmartFieldRow(
                    fieldKey = "memo",
                    label = "메모 (뒷면 내용 / 추가 정보)",
                    value = memo,
                    isLocked = lockedFields.contains("memo"),
                    onValueChange = { memo = it },
                    onToggleLock = { toggleLock("memo", "메모") },
                    onFocusScan = { onFocusScanField("memo", "메모") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )

                Spacer(modifier = Modifier.height(24.dp))

                // 하단 저장 버튼
                Button(
                    onClick = triggerSave,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    DoubleCheckIcon(color = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (existingContact != null && !saveAsNewOption) "기존 연락처에 병합 저장" else "새 연락처로 저장",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    // 사진 수동 편집 및 회전 다이얼로그
    editingPhotoSide?.let { side ->
        val currentUri = if (side == "FRONT") frontImageUri else backImageUri
        CardPhotoEditDialog(
            sideName = if (side == "FRONT") "앞면 명함" else "뒷면 명함",
            imageUri = currentUri,
            onRotate = {
                val rotated = ImageCropHelper.rotateImageUri(currentUri, 90f, context)
                if (side == "FRONT") frontImageUri = rotated else backImageUri = rotated
            },
            onAutoEnhance = {
                val enhanced = ImageCropHelper.autoEnhanceFromUri(currentUri, context)
                if (side == "FRONT") frontImageUri = enhanced else backImageUri = enhanced
                Toast.makeText(context, "화면에 가득 차도록 자동 크롭 및 보정되었습니다.", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { editingPhotoSide = null }
        )
    }
}

/**
 * v자가 2개 겹친 초록색 더블 체크 아이콘
 */
@Composable
fun DoubleCheckIcon(
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF16A34A)
) {
    Canvas(modifier = modifier.size(24.dp)) {
        val stroke = Stroke(width = 2.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        // 첫 번째 v자
        val p1 = Path().apply {
            moveTo(size.width * 0.12f, size.height * 0.54f)
            lineTo(size.width * 0.36f, size.height * 0.78f)
            lineTo(size.width * 0.70f, size.height * 0.28f)
        }
        drawPath(p1, color = color, style = stroke)

        // 두 번째 겹친 v자
        val p2 = Path().apply {
            moveTo(size.width * 0.36f, size.height * 0.54f)
            lineTo(size.width * 0.56f, size.height * 0.78f)
            lineTo(size.width * 0.90f, size.height * 0.28f)
        }
        drawPath(p2, color = color, style = stroke)
    }
}

@Composable
fun CardPhotoEditDialog(
    sideName: String,
    imageUri: String,
    onRotate: () -> Unit,
    onAutoEnhance: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("$sideName 사진 편집", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (imageUri.isNotBlank()) {
                    Image(
                        painter = rememberAsyncImagePainter(imageUri),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, Color.Gray, RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text("이미지가 없습니다.")
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onRotate,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("⟲ 90° 회전")
                    }

                    Button(
                        onClick = onAutoEnhance,
                        modifier = Modifier.weight(1.2f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("✨ 가득 차게 보정")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("완료")
            }
        }
    )
}

@Composable
fun SmartFieldRow(
    fieldKey: String,
    label: String,
    value: String,
    isLocked: Boolean,
    onValueChange: (String) -> Unit,
    onToggleLock: () -> Unit,
    onFocusScan: () -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    errorMessage: String? = null,
    focusRequester: FocusRequester? = null,
    minLines: Int = 1
) {
    val fieldModifier = if (focusRequester != null) {
        modifier.focusRequester(focusRequester)
    } else {
        modifier
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        isError = isError,
        supportingText = if (isError && !errorMessage.isNullOrBlank()) {
            { Text(errorMessage, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        } else null,
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label)
                if (isLocked) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("🔒 확정됨", fontSize = 10.sp, color = Color(0xFF16A34A), fontWeight = FontWeight.Bold)
                }
            }
        },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleLock) {
                    Text(
                        if (isLocked) "🔒" else "🔓",
                        fontSize = 16.sp
                    )
                }
                IconButton(onClick = onFocusScan) {
                    Text("🔍", fontSize = 16.sp)
                }
            }
        },
        colors = if (isLocked) {
            OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF16A34A),
                unfocusedBorderColor = Color(0xFF16A34A).copy(alpha = 0.6f)
            )
        } else {
            OutlinedTextFieldDefaults.colors()
        },
        modifier = fieldModifier,
        minLines = minLines
    )
}


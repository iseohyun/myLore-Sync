# myLore Sync (Mobile Agent) 총괄 시스템 아키텍처 명세서 (`MYLORE_SYNC_SPEC.md`)

> **문서 목적**: 안드로이드 동반 앱 (`myLore Sync`)의 **프로젝트 기본 규격**, **전체 아키텍처 및 패키지/폴더 구조**, **도메인별 로컬 수집기(Collector) 설계**, **로컬 Room DB 캐시(`AppDatabase` v5) 스키마 및 비파괴 마이그레이션**, **안드로이드 필수 권한 및 OS 제약 해결 요구사항**, 그리고 **배포 가이드 및 개발 마일스톤**을 정의하는 총괄 기술 사양서입니다.  
> **세부 분리 명세서**:
> - 📱 **모바일 UI/UX 및 10대 화면(Sticky Header 표) 상세 명세**: [`MYLORE_SYNC_UI_SPEC.md`](./MYLORE_SYNC_UI_SPEC.md)
> - 🔄 **QR 페어링 및 PC-모바일 통신/동기화 프로토콜 명세**: [`MYLORE_SYNC_COMM_SPEC.md`](./MYLORE_SYNC_COMM_SPEC.md)  
> **문서 위치**: `C:/git/myLoreSync/MYLORE_SYNC_SPEC.md`  
> **최종 수정일**: 2026-11-01

---

## 1. 프로젝트 기본 규격 및 문서 구성

### 1.1 기본 규격
- **앱 이름**: myLore Sync
- **패키지명**: `com.iseohyun.mylore.sync`
- **최소 SDK (`minSdk`)**: 24 (Android 7.0)
- **타겟 SDK (`targetSdk` / `compileSdk`)**: 37 (Android 15+)
- **UI 프레임워크**: Jetpack Compose + Material 3
- **핵심 기술 스택**:
  - **Local Storage**: Room DB (`AppDatabase` v5 — 미전송 알림 큐, 명함 지갑, 7대 동기화 로컬 캐시), DataStore Preferences (`AppPreferences` — 연결 설정, 동적 스캔 폴더 목록, 워터마크 캐시)
  - **Networking**: Retrofit2 + OkHttp3 + Gson (REST API & Multi-part 업로드)
  - **Background Engine**: Foreground Service (`dataSync`), BroadcastReceiver, ContentObserver
  - **Camera & OCR**: CameraX + ZXing (QR 코드 페어링) + ML Kit Text Recognition & Google Document Scanner (실시간 누적 명함 스캐너)

### 1.2 명세서 3분할 구조
| 문서 파일명 | 역할 및 포함 범위 |
| :--- | :--- |
| **[`MYLORE_SYNC_SPEC.md`](./MYLORE_SYNC_SPEC.md)**<br>*(본 문서)* | 프로젝트 기본 규격, PC 아카이브(`mobile/`) 및 안드로이드 패키지 구조, 8대 로컬 수집기(Collector) 동작 원리, Room DB(`AppDatabase` v5) 스키마, 안드로이드 권한/OS 제약 해결, 배포 및 마일스톤 |
| **[`MYLORE_SYNC_UI_SPEC.md`](./MYLORE_SYNC_UI_SPEC.md)** | 고정 상단바 및 좌측 오버레이 드로어(10대 탭), Sticky Header 표(Table) 공통 규칙, 전송시기 3대 판정 규칙, 카테고리별 표 컬럼 상세 규격(카톡/문자/통화/알림/음성녹음/사진/헬스/명함/상태/설정), `0ms` 선 캐시 렌더링 UX |
| **[`MYLORE_SYNC_COMM_SPEC.md`](./MYLORE_SYNC_COMM_SPEC.md)** | QR 페어링 JSON Schema 및 `myloresync://pair` 딥링크, 방식 A + 폴링 재시도 파이프라인, 다중 기기 헤더, v2.0 통합 동기화 프로토콜(2대 조건, `session-plan`, Oldest-First, 통화+녹음 결합, 서버 Background Check & `client_ack`), REST API 명세 및 PC 서버 내부 동작 |

---

## 2. 아카이브 폴더 구조(`mobile/`) 및 안드로이드 패키지 구조

### 2.1 PC 아카이브 폴더 구조 (`mobile/`)
PC 서버(`myLore`)는 모바일에서 수신한 모든 데이터를 아카이브 루트 하위의 **`mobile/`** 통합 폴더 내에 카테고리별로 분류하여 마크다운(`.md`) 스크립트 및 원본 미디어로 영구 보관합니다:

```text
<Archive Root (iseohyunDocs)>/
├── .agentSearchGuide.md             # 전역 AI 라우팅 가이드
└── mobile/                          # 스마트폰 통합 아카이브 폴더
    ├── kakao/                       # 1. 카카오톡 대화방별 .md 스크립트 및 원본 내보내기 폴더/미디어
    ├── sms/                         # 2. 문자(SMS/MMS/RCS) 상대방별 수발신 .md 스크립트
    ├── calls/                       # 3. 통화 수발신 이력 + 통화 녹음(Call/TPhoneCallRecords) 오디오(audio/) 및 Gemini STT 대화록 .md
    ├── recordings/                  # 4. 순수 일반 음성녹음(Voice Recorder/워치 음성메모) 오디오 및 STT .md
    ├── photos/ (또는 Media Inbox)    # 5. 스마트폰 5대 사진/영상 폴더(Camera, Pictures, Screen recordings, Screenshots, Videocaptures)
    ├── health/                      # 6. 삼성 헬스 운동 기록 .md 및 워치 GPS .gpx 파일 (gpx/)
    ├── contacts/                    # 7. 연락처/주소록 통합 및 인물별 .md 문서
    ├── notifications/               # 8. 앱 푸시 알림/알람 월별 .md 타임라인
    └── usage/                       # 9. 휴대폰 앱별 사용시간(Screen Time) 월별 .md 리포트
```

### 2.2 모바일 앱(`myLore Sync`) 패키지 구조
```text
com.iseohyun.mylore.sync/
├── data/
│   ├── local/
│   │   ├── AppDatabase.kt                 # Room Database (version = 5, 비파괴 마이그레이션 MIGRATION_3_4, MIGRATION_4_5)
│   │   ├── dao/
│   │   │   ├── NotificationDao.kt         # 실시간 알림 큐 및 전송 상태 관리
│   │   │   ├── SyncLogDao.kt              # 실시간 전송 로그 기록
│   │   │   ├── KakaoRoomOffsetDao.kt      # 카톡 대화방별 바이트 오프셋
│   │   │   ├── BusinessCardDao.kt         # 명함 지갑 영구 저장소
│   │   │   └── SyncCacheDao.kt            # 7대 동기화 화면 초고속 렌더링용 로컬 캐시 DAO
│   │   └── entity/
│   │       ├── NotificationEntity.kt      # pending_notifications 테이블 (syncedAt 포함)
│   │       ├── BusinessCardEntity.kt      # business_cards 테이블
│   │       └── SyncCacheEntities.kt       # KakaoRoom, HealthSync, Sms, CallLog, Recording, PhotoMedia 캐시 엔티티
│   ├── prefs/
│   │   └── AppPreferences.kt              # DataStore (페어링 설정, 워터마크, 통화녹음 활성 폴더 목록, 사진 활성 폴더 목록)
│   └── remote/
│       ├── MyLoreApiService.kt            # Retrofit2 REST API 인터페이스
│       ├── ApiClient.kt                   # OkHttp Client & 타임아웃/인증 헤더 인터셉터
│       └── dto/SyncDtos.kt                # 요청/응답 DTO 정의
├── collector/
│   ├── MyloreNotificationListener.kt      # 1. 상단바 알림 실시간 캡처 (금융/카톡/RCS/일반 앱)
│   ├── SmsAndMmsCollector.kt              # 2. SMS + MMS(Part/Addr) 통합 증분 수집
│   ├── CallLogCollector.kt                # 3. 통화 기록(수신/발신/부재중/거절) 증분 수집
│   ├── CallRecordingCollector.kt          # 4. 동적 후보 폴더 기반 통화녹음(Call/TPhoneCallRecords) 및 음성녹음 스캔
│   ├── PhotoMediaCollector.kt             # 5. 5대 사진/영상 폴더(Camera, Pictures, Screen recordings, Screenshots, Videocaptures) 스캔
│   ├── SamsungHealthCollector.kt          # 6. Download/Samsung Health 내 CSV 및 워치 GPS JSON 수집
│   ├── ContactsCollector.kt               # 7. ContactsContract 기반 연락처 증분 수집 및 번호↔이름 매핑
│   ├── AppUsageCollector.kt               # 8. UsageStatsManager 기반 일자별 앱 사용시간 수집
│   └── KakaoChatFolderWatcher.kt          # 9. Documents/KakaoTalk/Chats 대화방별 최신 폴더 선별 및 꼬리 16KB 고속 파싱
├── engine/
│   ├── SyncConditionMonitor.kt            # 배터리(80%/75% 히스테리시스) & 서버 연결 2대 조건 판별기
│   ├── WifiConnector.kt                   # 방식 A(ACTION_WIFI_ADD_NETWORKS) 및 폴링 기반 자동 Wi-Fi 연결기
│   ├── PcWakeListener.kt                  # UDP 18444 브로드캐스트(MYLORE_PC_ONLINE / MYLORE_FULL_RESYNC) 수신기
│   └── RealtimeSyncForegroundService.kt   # 통합 동기화 Foreground Service (v2.0 프로토콜 수행)
└── ui/
    ├── MainActivity.kt                    # 좌측 오버레이 드로어(10대 탭) 호스트 및 상태 대시보드
    ├── SyncTableComponents.kt             # 공통 Sticky Header 표 컴포넌트 및 전송시기 포맷터
    ├── KakaoListScreen.kt                 # 💬 카톡 표 화면
    ├── SmsListScreen.kt                   # ✉️ 문자 표 화면
    ├── CallListScreen.kt                  # 📞 통화(통화녹음/T전화녹음 통합) 표 화면
    ├── NotificationListScreen.kt          # 🔔 알림 표 화면 + 내용보기 모달
    ├── RecordingsListScreen.kt            # 🎙️ 순수 음성녹음 표 화면
    ├── PhotoListScreen.kt                 # 🖼️ 사진(5대 폴더) 표 화면
    ├── HealthSyncScreen.kt                # 🏃 삼성 헬스 화면
    ├── BusinessCardWalletScreen.kt        # 📇 명함 지갑 화면
    ├── LiveCardScannerActivity.kt         # 실시간 누적 OCR 명함 스캐너
    ├── QrScannerActivity.kt               # QR 페어링 스캐너
    └── SettingsScreen.kt                  # ⚙️ 권한 배지 및 연동 설정 화면
```

---

## 3. 도메인별 로컬 수집기(Collector) 핵심 설계

### 3.1 동적 후보 폴더 탐색 기반 통화녹음 & 음성녹음 수집 (`CallRecordingCollector.kt`)
- **하드코딩 배제 및 앱 초기화 시 동적 폴더 검증**:
  - 기기 제조사 및 앱(삼성 기본 전화, SKT T전화, 삼성 음성녹음, 갤럭시 워치)마다 녹음 저장 경로와 대소문자(`Recordings` vs `recordings`)가 다를 수 있습니다.
  - 따라서 후보 상대 경로 리스트(`CANDIDATE_RELATIVE_PATHS`: `Recordings/Call`, `Recordings/TPhoneCallRecords`, `recordings/TPhoneCallRecords`, `Call`, `TPhoneCallRecords`, `Recordings/Voice Recorder`, `Recordings/Sounds`, `Recordings` 등)를 상수로 관리하고, **앱 시작 시 실제 단말 스토리지에 존재하는 폴더만 검증하여 `AppPreferences.activeRecordingDirs`에 기본 조회 폴더로 등록**합니다.
- **통화녹음 vs 순수 음성녹음 자동 분류 (`isCallRecordingEntry`)**:
  - 상위 폴더가 `Call`, `TPhoneCallRecords`이거나 파일명이 `#이름_번호_일시`(T전화), `통화 녹음_` 패턴이거나 전화번호가 파싱된 파일은 **통화녹음**으로 분류하여 **통화(`CallListScreen`) 탭 및 통화 이벤트 동기화**로 귀속시킵니다.
  - 그 외 `Voice Recorder`, `Sounds/Watch` 등의 녹음 파일만 **음성녹음(`RecordingsListScreen`) 탭**에 표시합니다.

### 3.2 5대 미디어 폴더 기반 사진·영상 수집 (`PhotoMediaCollector.kt`)
- **기본 접근 5대 폴더 카테고리 (`PhotoFolderCategory`)**:
  1. **`Camera`**: `DCIM/Camera`, `Camera`
  2. **`Pictures`**: `Pictures` (하위 `Screenshots`, `Screen recordings`, `Videocaptures`는 각 전용 카테고리로 분리하여 중복 집계 방지)
  3. **`Screen recordings`**: `DCIM/Screen recordings`, `DCIM/Screen Recordings`, `Movies/Screen recordings`, `Pictures/Screen recordings`
  4. **`Screenshots`**: `DCIM/Screenshots`, `Pictures/Screenshots`, `Screenshots`
  5. **`Videocaptures`**: `DCIM/Videocaptures`, `DCIM/VideoCaptures`, `Movies/Videocaptures`, `Pictures/Videocaptures`, `Videocaptures`
- 앱 시작 시 `discoverAndRegisterDefaultDirectories()`가 실제 존재하는 폴더를 확인하여 `AppPreferences.activePhotoDirs`에 등록하고, 사진 및 동영상을 스캔하여 로컬 캐시(`photo_media_cache`)와 서버 동기화 파이프라인에 공급합니다.

### 3.3 MMS(장문 문자 / 첨부 미디어) 및 RCS 통합 수집 (`SmsAndMmsCollector.kt`)
- `content://sms`뿐만 아니라 `content://mms`, `content://mms/part`, `content://mms/{id}/addr`를 함께 파싱합니다.
- MMS `date`는 초(Second) 단위이므로 `date * 1000L`로 밀리초 변환하며, SMS ID와의 충돌을 막기 위해 MMS ID에는 `1_000_000_000L + mms_id` 오프셋을 부여합니다.
- 갤럭시 채팅+(RCS) 메시지는 `MyloreNotificationListener`와 2중 교차 수집하여 누락을 방지합니다.

### 3.4 카카오톡 내보내기 폴더 꼬리(Tail 16KB) 고속 파싱 (`KakaoChatFolderWatcher.kt`)
- `Documents/KakaoTalk/Chats` 하위 폴더 중 동일 대화방(`room_name`)은 가장 최신 내보내기 폴더 1개만 선별합니다.
- 수십 MB에 달하는 `KakaoTalkChats.txt` 전체를 매번 읽지 않고, 첫 줄에서 대화방 이름을 추출한 뒤 `RandomAccessFile.seek(length - 16384)`로 **파일 끝 16KB만 O(1) 속도로 읽어** 마지막 대화 내용과 마지막 대화 일시(`lastMessageTime`)를 즉시 추출합니다.

### 3.5 삼성 헬스 & 갤럭시 워치 GPS 데이터 수집 (`SamsungHealthCollector.kt`)
- `/sdcard/Download/Samsung Health/samsunghealth_{계정}_{YYYYMMDDHHMMSS}/` 폴더 중 타임스탬프가 가장 최신인 폴더를 판별하여 운동 요약 `.csv` 및 `jsons/com.samsung.shealth.exercise/.../location_data.json`(워치 GPS 경로)을 수집합니다.

### 3.6 헬스 GPS 데이터 비실시간 수집 및 체류 분석 (`HealthGpsCollector.kt`)
- **수집 주기 및 트리거**:
  - 배터리 및 시스템 리소스 보호를 위해 **실시간 상시 백그라운드 수집을 하지 않음**.
  - 1) 앱 시작 시 (`MainActivity.onCreate`), 2) 서버 연결 시 (`RealtimeSyncForegroundService`), 3) 서버 요청 시, 4) 24시간(하루) 경과 또는 날짜 변경 시 업데이트.
- **데이터 소스**: `/sdcard/Download/Samsung Health/` 하위 `*location_data*.json` 및 `*.gpx` 파일.
- **체류/정지 분석 알고리즘**:
  - 반경 45m 이내에 10분 이상 머무른 구간을 자동 검출하여 `isStayPoint = true, stayDurationMinutes = ...`로 마킹.
  - 일자별 요약(`HealthGpsDaySummaryEntity`) 및 개별 좌표 포인트(`HealthGpsPointEntity`)를 Room DB에 구축하여 지도 렌더링 지원.

---

## 4. 로컬 Room DB 스키마 및 비파괴 마이그레이션 (`AppDatabase` v6)

### 4.1 전체 엔티티(테이블) 명세

| 테이블명 (`Entity`) | Primary Key | 주요 컬럼 | 용도 및 특징 |
| :--- | :--- | :--- | :--- |
| **`kakao_room_cache`**<br>(`KakaoRoomCacheEntity`) | `roomName` | `folderName`, `lastMessage`, `lastMessageTime`, `lastSharedTime`, `totalBytes`, `textBytes`, `mediaBytes`, `mediaCount`, `syncedBytes`, `percent`, `isOnDevice`, `updatedAt` | 카톡 내보내기 폴더가 전송 후 삭제(`isOnDevice = false`)되어도 대화방명, 마지막 대화일시, 전송시기(`lastSharedTime`)를 영구 보존 |
| **`sms_cache`**<br>(`SmsCacheEntity`) | `id` | `address`, `body`, `ts`, `type`, `isSynced`, `syncedAt` | 최초 스캔 후 `ts > max(ts)`인 신규 문자만 증분 조회, `syncedAt`으로 전송시기 표시 |
| **`call_log_cache`**<br>(`CallLogCacheEntity`) | `id` | `number`, `name`, `callType`, `durationSec`, `ts`, `isSynced`, `syncedAt` | 통화 기록 증분 캐싱 및 전송완료시기(`syncedAt`) 관리 |
| **`recording_cache`**<br>(`RecordingCacheEntity`) | `filePath` | `fileName`, `parentFolderName`, `fileSize`, `lastModified`, `fileHash`, `phoneNumber`, `contactName`, `recordedAt`, `isSynced`, `syncedAt` | `(fileSize, lastModified)` 일치 시 SHA-256 해시 재계산 생략, 통화녹음(`Call`/`TPhoneCallRecords`)과 일반 음성녹음 통합 캐싱 |
| **`photo_media_cache`**<br>(`PhotoMediaCacheEntity`) | `filePath` | `fileName`, `folderCategory`, `parentFolderPath`, `fileSize`, `lastModified`, `takenAt`, `mimeType`, `fileHash`, `isSynced`, `syncedAt` | 5대 사진/영상 폴더 미디어 메타데이터 및 SHA-256 해시 캐싱, 전송시기(`syncedAt`) 관리 |
| **`health_sync_cache`**<br>(`HealthSyncCacheEntity`) | `folderName` | `latestRecordType`, `lastDataTime`, `lastSharedTime`, `csvCount`, `jsonCount`, `totalSizeKb`, `isSynced`, `isOnDevice`, `updatedAt` | 삼성 헬스 전송 완료 후 원본 폴더 삭제 시에도 최신 운동 종류 및 공유 시각 보존 |
| **`health_gps_points`**<br>(`HealthGpsPointEntity`) | `id` (Auto) | `date`, `timestamp`, `latitude`, `longitude`, `altitude`, `isStayPoint`, `stayDurationMinutes`, `stayStartTime`, `stayEndTime`, `sourceFileName` | 날짜별 GPS 좌표 포인트 및 체류 배지 데이터 |
| **`health_gps_day_summaries`**<br>(`HealthGpsDaySummaryEntity`) | `date` | `pointCount`, `startTime`, `endTime`, `totalDistanceMeters`, `stayCount`, `totalStayDurationMinutes`, `centerLat`, `centerLng`, `updatedAt` | 일자별 GPS 이동 총 거리, 활동 시간대, 체류 요약 |
| **`pending_notifications`**<br>(`NotificationEntity`) | `id` (Auto) | `packageName`, `title`, `text`, `subText`, `postedAt`, `isSynced`, `syncedAt` | 상단바 알림 실시간 적재, 전송시점(`syncedAt`) 및 전문 보기 데이터 제공 |
| **`business_cards`**<br>(`BusinessCardEntity`) | `id` (Auto) | `name`, `company`, `jobTitle`, `department`, `mobile`, `phone`, `email`, `address`, `website`, `memo`, `imagePath`, `contactUri`, `createdAt` | 명함 스캐너로 촬영·인식한 명함 지갑 영구 보관 |
| **`sync_logs`**<br>(`SyncLogEntity`) | `id` (Auto) | `timestamp`, `category`, `message`, `isError` | 상태 탭 실시간 전송 로그 뷰어용 이벤트 기록 |

### 4.2 데이터 유실 방지 비파괴 마이그레이션 원칙
- **절대 `fallbackToDestructiveMigration()`에 의존하여 사용자 데이터를 초기화하지 않습니다.**
- 스키마 변경 시 반드시 명시적 `Migration` 객체를 등록하여 기존 보관 중인 명함(`business_cards`), 알림(`pending_notifications`), 동기화 캐시를 100% 보존합니다:
  - **`MIGRATION_3_4`**: `sms_cache`, `call_log_cache`, `recording_cache`, `pending_notifications` 테이블에 `syncedAt INTEGER NOT NULL DEFAULT 0` 컬럼 추가.
  - **`MIGRATION_4_5`**: `photo_media_cache` 테이블 신규 생성 (`CREATE TABLE IF NOT EXISTS photo_media_cache ...`).
  - **`MIGRATION_5_6`**: `health_gps_points` 및 `health_gps_day_summaries` 테이블 신규 생성.

---

## 5. 안드로이드 필수 권한 및 OS 제약 해결 요구사항

### 5.1 필수 권한 목록 (`AndroidManifest.xml`)
1. **`MANAGE_EXTERNAL_STORAGE` (모든 파일 접근 권한)**:
   - `Documents/KakaoTalk/Chats`, `Recordings/{Call,TPhoneCallRecords,Voice Recorder}`, `Download/Samsung Health`, 그리고 5대 사진 폴더(`Camera`, `Pictures`, `Screen recordings`, `Screenshots`, `Videocaptures`)를 백그라운드에서 직접 스캔·읽기 위해 필수입니다.
2. **`BIND_NOTIFICATION_LISTENER_SERVICE` (알림 접근 허용)**: 상단바 푸시 알림 실시간 캡처.
3. **`READ_SMS` / `RECEIVE_SMS`**: 단문/장문 문자 읽기 및 실시간 수신 감지.
4. **`READ_CALL_LOG` / `READ_PHONE_STATE` / `READ_CONTACTS` / `WRITE_CONTACTS`**: 통화 이력 수집, 통화 종료 감지, 주소록 이름 매핑 및 명함 연락처 저장.
5. **`PACKAGE_USAGE_STATS` (사용정보 접근 허용)**: `UsageStatsManager`를 통한 앱별 사용시간 수집.
6. **`ACCESS_FINE_LOCATION` / `ACCESS_BACKGROUND_LOCATION`**: Android 8.1+ Wi-Fi SSID 조회용 위치 권한.
7. **`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` / `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC`**: 절전 모드 우회 및 실시간 동기화 Foreground Service 유지.
8. **`CAMERA`**: QR 코드 페어링 및 실시간 누적 명함 스캔.

### 5.2 주요 안드로이드 OS 제약 해결 명세
1. **Wi-Fi `<unknown ssid>` 문제 해결**:
   - 위치 권한 미허용이나 GPS 꺼짐으로 인해 `ssid == "<unknown ssid>"`가 반환되더라도, 현재 기기가 Wi-Fi(`TRANSPORT_WIFI`)에 연결되어 있고 PC 서버(`GET /api/v1/status`)가 1.5초 이내에 정상 응답하면 동기화를 즉시 수행합니다.
2. **삼성 One UI 절전 모드 및 Doze 모드 대응**:
   - 배터리 사용량 '제한 없음' 등록을 유도하고, 동기화 활성 시 `ForegroundService`(`dataSync`)와 `WifiManager.WifiLock`(`WIFI_MODE_FULL_HIGH_PERF`)을 획득하여 화면이 꺼진 상태에서도 소켓 동결을 방지합니다.
3. **삼성/안드로이드 클라우드 기본 연락처 계정 바인딩 (`ContactManager`)**:
   - 최신 갤럭시 기기에서 기본 연락처 저장 위치가 Google 또는 Samsung Cloud 계정으로 설정된 경우 `ACCOUNT_NAME = null`로 저장 시 발생하는 `IllegalArgumentException: Cannot add contacts to local or SIM accounts when default account is set to cloud` 오류를 방지하기 위해, `getPreferredAccount()`로 활성 클라우드 계정을 조회하여 바인딩하고 프로필 썸네일을 480px(80% 압축)로 다운샘플링하여 저장합니다.
4. **Android 13(API 33)+ 사이드로딩 '제한된 설정(Restricted Settings)' 해제**:
   - APK 직접 설치 시 `설정 > 애플리케이션 > myLore Sync > 우측 상단(⋮) > [제한된 설정 허용]` 가이드를 제공하며, 개발 PC에서 ADB로 설치할 때는 아래 명령으로 한 번에 모든 권한을 부여할 수 있습니다:
     ```bat
     adb install -r -g app-debug.apk
     adb shell appops set com.iseohyun.mylore.sync SYSTEM_ALERT_WINDOW allow
     adb shell appops set com.iseohyun.mylore.sync MANAGE_EXTERNAL_STORAGE allow
     adb shell appops set com.iseohyun.mylore.sync GET_USAGE_STATS allow
     adb shell cmd notification allow_listener com.iseohyun.mylore.sync/.collector.MyloreNotificationListener
     ```

---

## 6. 배포 방식 및 단계별 개발 마일스톤

### 6.1 배포 방식 요약
| 설치 방식 | 장점 | 비고 |
| :--- | :--- | :--- |
| **방식 1: Android Studio / ADB 직접 설치** | 비용 $0, 심사 제약 없이 `SMS`, `통화기록`, `모든 파일 접근`, `알림` 권한 100% 자유 사용 | 개인용/개발용 최우선 권장 |
| **방식 2: Google Play Console `내부 테스트(Internal Testing)`** | 12명/14일 테스트 의무 면제, 정식 스토어 경로 설치로 Android 13+ '제한된 설정' 차단 자동 우회 | 계정 등록비 $25, 민감한 권한 선언서 제출 필요 |

### 6.2 개발 마일스톤 현황
- [x] **Phase 1**: 패키지 정제(`com.iseohyun.mylore.sync`), 의존성 구축, 로컬 Room DB / DataStore / Retrofit 레이어 구현 (`완료`)
- [x] **Phase 2**: QR 스캐너(`myloresync://pair` 딥링크 및 방식 A Wi-Fi 자동 등록) & 수집기(알림, 문자/MMS, 통화, 통화녹음, 연락처, 앱사용량, 카톡, 삼성헬스) 구현 (`완료`)
- [x] **Phase 3**: v2.0 통합 동기화 엔진(`SyncConditionMonitor` 2대 조건, `session-plan`, Oldest-First, `complete`/`ack` 상태 머신) 구현 (`완료`)
- [x] **Phase 4**: 스마트 명함 스캐너(ML Kit 누적 투표 + Document Scanner + 회전 보정 + 클라우드 계정 바인딩) 구현 (`완료`)
- [x] **Phase 5**: 10대 카테고리 좌측 오버레이 드로어 UI, Sticky Header 표 전면 개편(카톡/문자/통화[T전화녹음 통합]/알림/음성녹음/사진[5대 폴더]), 비파괴 DB 마이그레이션(`AppDatabase` v5) 완료 (`완료`)
- [x] **Phase 6**: 기술 명세서 3분할 체계화 (`MYLORE_SYNC_SPEC.md`, `MYLORE_SYNC_UI_SPEC.md`, `MYLORE_SYNC_COMM_SPEC.md`) (`완료`)

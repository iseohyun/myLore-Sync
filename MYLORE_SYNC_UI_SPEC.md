# myLore Sync 모바일 UI/UX 상세 명세서 (`MYLORE_SYNC_UI_SPEC.md`)

> **문서 목적**: `myLore Sync` 안드로이드 앱의 **상단 고정 헤더 및 좌측 슬라이드 오버레이 드로어(`ModalNavigationDrawer`)**, **10대 카테고리 화면 구성**, **Sticky Header 기반 표(Table) 관리 규격**, **전송시기 판정 및 표시 규칙**, **초고속 목록 렌더링(선 로컬 캐시 `0ms` 표시 · 후 지연 증분 스캔)**, 그리고 **명함 스캐너 및 권한 온보딩 UI 피드백**을 정의하는 전용 UI 기술 사양서입니다.  
> **관련 문서**:
> - 총괄 시스템 아키텍처 및 로컬 DB/권한 명세: [`MYLORE_SYNC_SPEC.md`](./MYLORE_SYNC_SPEC.md)
> - QR 페어링 및 PC-모바일 통신 프로토콜 명세: [`MYLORE_SYNC_COMM_SPEC.md`](./MYLORE_SYNC_COMM_SPEC.md)  
> **최종 수정일**: 2026-11-01

---

## 1. 전역 레이아웃 및 내비게이션 구조

### 1.1 상단 여백 보정 및 고정 상단바 헤더 (Fixed Top Bar)
- **상태바 및 펀치홀 카메라 여백**: 모든 화면 루트에 `Modifier.statusBarsPadding()`을 적용하여 안드로이드 상태바 및 디스플레이 노치(펀치홀)와 UI가 겹치지 않도록 안전 여백을 확보합니다.
- **고정 상단바 헤더**: 스크롤 가능한 본문 영역(`LazyColumn` / `verticalScroll`) 외부 상단에 고정 헤더(좌측 삼선 햄버거 메뉴 버튼 `☰`, 현재 화면 타이틀, 우측 액션 버튼)를 배치하여, 사용자가 표나 리스트를 아래로 스크롤한 상태에서도 언제든지 드로어 메뉴를 열 수 있도록 보장합니다.

### 1.2 좌측 슬라이드 오버레이 내비게이션 드로어 (`ModalNavigationDrawer`)
- 상단바 좌측 `☰` 버튼 클릭(또는 좌측 엣지 스와이프) 시 왼쪽에서 기본 화면을 오버레이하며 부드럽게 등장하는 슬라이드 인/아웃 애니메이션을 제공합니다.
- 메뉴 항목(`DrawerItem`)은 실시간 동기화 데이터 카테고리 **상단 그룹 7개**와 유틸리티/시스템 관리 **하단 그룹 3개**(구분선 `HorizontalDivider` 적용)로 총 10개 탭으로 구성됩니다:

```text
myLore Sync (스마트폰 라이프로그 동기화)
├── 💬 카톡      (DrawerItem.KAKAO)
├── ✉️ 문자      (DrawerItem.SMS)
├── 📞 통화      (DrawerItem.CALL)
├── 🔔 알림      (DrawerItem.NOTIFICATION)
├── 🎙️ 녹음      (DrawerItem.RECORDINGS)
├── 🖼️ 사진      (DrawerItem.PHOTO)
├── 🏃 헬스      (DrawerItem.HEALTH)
----------------------------------------- (HorizontalDivider)
├── 📇 명함      (DrawerItem.BUSINESS_CARD)
├── 📊 상태      (DrawerItem.STATUS)
└── ⚙️ 설정      (DrawerItem.SETTINGS)
```

### 1.3 앱 런처 아이콘 규격 (`ic_launcher_foreground.xml` / `ic_launcher.xml`)
- **어댑티브 아이콘 중심 스케일 및 도형 배치**: 두 도형의 크기는 유지하면서 도형 간 중심 거리를 기존 대비 **80% 수준**으로 밀착시키고, 전체 전경 그룹을 중심점(`54, 54`) 기준 **80% 스케일(`scaleX="0.8"`, `scaleY="0.8"`)**로 축소하여 런처 마스크 내부 여백을 확보합니다.
- **완전 투과 배경**: 아이콘 배경(`ic_launcher_background.xml`)은 불투명 단색 대신 투명(`#00000000`)을 적용하여 홈 화면 배경이 투과되도록 구성합니다.

---

## 2. 공통 표(Table) UI 및 전송시기 판정 표준 규칙 (`SyncTableComponents.kt`)

카톡, 문자, 통화, 알림, 음성녹음, 사진 화면은 모두 일관된 **Sticky Header 표(Table)** 컴포넌트를 사용하여 대량 데이터를 고밀도로 일목요연하게 관리합니다.

### 2.1 Sticky Header 고정 규칙
- 모든 표 화면은 `LazyColumn`의 `stickyHeader { SyncTableHeaderRow(...) }`를 사용하여, 수천 건의 행을 아래로 스크롤하더라도 **표의 컬럼 제목 행(Header Row)이 항상 상단에 고정**되도록 구현합니다.
- 각 행(`SyncTableBodyRow`)은 짝수/홀수 줄무늬(Zebra Striping) 배경색과 하단 미세 구분선(`HorizontalDivider`)을 적용하여 가독성을 높입니다.

### 2.2 전송시기 표시 및 판정 3대 규칙 (`formatSyncTimestamp`)
모든 표에서 **전송시기(또는 전송완료시기/전송시점)** 컬럼은 아래 3가지 원칙에 따라 판정 및 렌더링합니다:
1. **전송시기가 있으면 전송성공으로 치부**:
   - 레코드의 전송 시각(`syncedAt > 0` 또는 카톡 `lastSharedTime > 0`)이 존재하면 서버 전송 성공으로 간주하고 해당 시각(`yy-MM-dd HH:mm`)을 녹색 계열 텍스트로 표시합니다.
2. **전송실패나 데이터 없으면 `'-'`로 표시**:
   - 아직 전송되지 않았거나(`isSynced == false` 및 `syncedAt == 0L`), 전송 실패 상태이거나, 해당 셀의 데이터가 존재하지 않으면 **`'-'`**로 표시합니다.
3. **전송성공은 확실하나 시각정보가 없다면 검증시각으로 덮어쓰기**:
   - 로컬 DB나 서버 매니페스트 대조 결과 전송 성공(`isSynced == true`)임이 확실하지만 `syncedAt == 0L`인 레코드는, 확인(검증)된 즉시 현재 검증시각(`val verifiedNow = System.currentTimeMillis()`)으로 DB와 메모리의 `syncedAt`을 덮어쓰고 해당 시각을 표시합니다.

---

## 3. 10대 카테고리 화면별 상세 UI 규격

### 3.1 💬 카톡 (`KakaoListScreen.kt`)
- **관리 형태**: Sticky Header 표 (`LazyColumn` + `stickyHeader`)
- **상단 요약 바**: 총 대화방 수, 전송완료 대화방 수, 공유 대상 총 용량 대비 전송 완료 용량(`MB`) 요약 표시.
- **표 컬럼 구성 (4열)**:

| 컬럼명 | 가중치(`weight`) | 표시 데이터 및 포맷 규칙 |
| :--- | :---: | :--- |
| **이름** | `1.3f` | 카카오톡 대화방 이름 (`roomName`). 기기에서 내보내기 폴더가 삭제(`isOnDevice == false`)된 후에도 로컬 캐시 DB를 통해 유지. |
| **마지막대화일시** | `1.15f` | 해당 대화방의 마지막 메시지 시각 (`lastMessageTime > 0`이면 `yy-MM-dd HH:mm`, 없으면 `'-'`). |
| **PC공유** | `0.85f` | 전송 상태 및 용량 비율. 전송시기가 있거나 100%이면 `완료(용량)`, 일부 공유 중이면 `진행률%(용량)`, 미전송 시 `대기(용량)`, 데이터 없으면 `'-'`. |
| **전송시기** | `1.15f` | PC로 전송 완료된 시각 (`lastSharedTime`). 전송 완료이나 시각이 `0`이면 검증시각으로 덮어쓰기 후 표시, 미전송 시 `'-'`. |

---

### 3.2 ✉️ 문자 (`SmsListScreen.kt`)
- **관리 형태**: Sticky Header 표 (`LazyColumn` + `stickyHeader`)
- **데이터 범위**: 단문(`SMS`), 장문/멀티미디어(`MMS`, ID +10억 오프셋), 채팅+(`RCS`) 통합 시간순 내림차순(`DESC`) 정렬.
- **상단 요약 바**: 전체 문자 건수, 전송완료 건수, 미전송 가장 오래된 문자 시각 표시.
- **표 컬럼 구성 (4열)**:

| 컬럼명 | 가중치(`weight`) | 표시 데이터 및 포맷 규칙 |
| :--- | :---: | :--- |
| **송수신** | `0.65f` | `type == "sent"`이면 **`송신`**, `type == "inbox"`이면 **`수신`**, 기타/없음은 `'-'`. |
| **대상** | `1.25f` | 스마트폰 주소록(`ContactsContract`) 매핑 결과 **주소록상 이름**을 우선 표시하고, 미등록 번호는 **전화번호**(`address`) 표시 (없으면 `'-'`). |
| **이벤트시기** | `1.15f` | 문자 수·발신 발생 일시 (`ts > 0`이면 `yy-MM-dd HH:mm`, 없으면 `'-'`). |
| **전송시기** | `1.15f` | 서버 전송 완료 시각 (`syncedAt`). `isSynced == true`인데 `syncedAt == 0`이면 검증시각으로 덮어쓰기, 미전송 시 `'-'`. |

---

### 3.3 📞 통화 (`CallListScreen.kt` — T전화녹음 및 기본 통화녹음 통합)
- **관리 형태**: Sticky Header 표 (`LazyColumn` + `stickyHeader`)
- **핵심 통합 규칙**:
  - 기존 녹음 탭에 섞여 있던 **T전화 통화녹음(`Recordings/TPhoneCallRecords`)** 및 **기본 통화녹음(`Recordings/Call`, `Call`)**은 모두 **통화 항목으로 통합**하여 관리합니다.
  - 통화 기록(`CallLog`)과 통화 녹음 파일은 전화번호(끝 8자리 정규화) 및 시간 범위(`[통화시각 - 60초 ~ 통화종료 + 120초]`)로 자동 매칭되며, 통화 기록 없이 통화 녹음 파일만 존재하는 경우에도 가상 통화 행으로 병합하여 누락 없이 표시합니다.
- **표 컬럼 구성 (6열)**:

| 컬럼명 | 가중치(`weight`) | 표시 데이터 및 포맷 규칙 |
| :--- | :---: | :--- |
| **송수신** | `0.65f` | `outgoing` ➔ **`송신`**, `incoming` ➔ **`수신`**, `rejected`/`missed`/`blocked` ➔ **`거부`** (녹음 전용 행은 `녹음`). |
| **대상** | `1.1f` | 주소록상 이름(`name`)이 있으면 **주소록상 이름**, 없으면 **전화번호**(`number`), 둘 다 없으면 `'-'`. |
| **이벤트시기** | `1.1f` | 통화 시작 일시 (`ts > 0`이면 `yy-MM-dd HH:mm`, 없으면 `'-'`). |
| **통화시간** | `0.85f` | **`hh:mm:ss`** 포맷 (`formatDurationHhMmSs`, 예: `00:03:42`). 통화시간이 0초(거부/부재중 등)이면 `'-'`. |
| **음성여부** | `0.65f` | 매칭된 통화녹음(T전화/기본 통화녹음) 파일이 존재하면 **`O`**, 없으면 **`'-'`**. |
| **전송완료시기** | `1.1f` | 통화 기록과 (음성 보유 시) 통화 녹음 파일이 모두 서버로 전송 완료된 시각(`effectiveSyncedAt`). 미완료 시 `'-'`. |

---

### 3.4 🔔 알림 (`NotificationListScreen.kt`)
- **관리 형태**: Sticky Header 표 (`LazyColumn` + `stickyHeader`) + 알림 전문 모달 다이얼로그(`AlertDialog`)
- **표 컬럼 구성 (4열)**:

| 컬럼명 | 가중치(`weight`) | 표시 데이터 및 포맷 규칙 |
| :--- | :---: | :--- |
| **이벤트명** | `1.25f` | 알림 제목(`title`) 우선 표시, 제목이 비어 있으면 앱 패키지 약칭(`packageName.substringAfterLast('.')`) 표시. |
| **이벤트 시기** | `1.1f` | 알림 발생 시각 (`postedAt > 0`이면 `yy-MM-dd HH:mm`, 없으면 `'-'`). |
| **전송시점** | `1.1f` | 서버 전송 완료 시각 (`syncedAt`). `isSynced == true`이고 시각 정보 없으면 검증시각으로 덮어쓰기, 미전송 시 `'-'`. |
| **내용보기** | `0.75f` | **`[보기]`** 버튼 (`SyncTableActionButton`). 클릭 시 패키지명, 제목, 본문(`text`), 보조텍스트(`subText`), 발생/전송시각 전체를 보여주는 모달 팝업 표시. |

---

### 3.5 🎙️ 음성녹음 (`RecordingsListScreen.kt` — 순수 음성녹음 전용)
- **관리 형태**: Sticky Header 표 (`LazyColumn` + `stickyHeader`)
- **데이터 분리 규칙**:
  - **T전화 통화녹음(`TPhoneCallRecords`) 및 기본 통화녹음(`Call`)은 통화(`CallListScreen`) 탭으로 완전히 이전**되었으므로, 음성녹음 탭에서는 `CallRecordingCollector.isCallRecordingEntry(rec) == false`인 **순수 일반 음성녹음(`Voice Recorder`, 갤럭시 워치 `Sounds/Watch` 등)만 필터링**하여 표시합니다.
- **표 컬럼 구성 (4열)**:

| 컬럼명 | 가중치(`weight`) | 표시 데이터 및 포맷 규칙 |
| :--- | :---: | :--- |
| **파일명(구분)** | `1.45f` | 녹음 파일명 및 하위 분류 (`워치녹음` / `음성녹음`). |
| **녹음일시** | `1.15f` | 녹음 생성 일시 (`recordedAt > 0`이면 `yy-MM-dd HH:mm`, 없으면 `'-'`). |
| **용량** | `0.7f` | 파일 크기 (`KB` 또는 `MB`, 0이면 `'-'`). |
| **전송시기** | `1.15f` | 서버 전송 완료 시각 (`syncedAt`). 전송 성공이나 시각 정보 없으면 검증시각으로 덮어쓰기, 미전송 시 `'-'`. |

---

### 3.6 🖼️ 사진 (`PhotoListScreen.kt` — 5대 기본 미디어 폴더 통합)
- **관리 형태**: 상단 폴더 필터 칩(`FilterChip`) + Sticky Header 표 (`LazyColumn` + `stickyHeader`)
- **기본 접근 및 공유 대상 5대 폴더**:
  1. **`Camera`** (`DCIM/Camera`)
  2. **`Pictures`** (`Pictures` 루트 및 하위 일반 이미지)
  3. **`Screen recordings`** (`DCIM/Screen recordings`, `Movies/Screen recordings`, `Pictures/Screen recordings`)
  4. **`Screenshots`** (`DCIM/Screenshots`, `Pictures/Screenshots`)
  5. **`Videocaptures`** (`DCIM/Videocaptures`, `VideoCaptures`, `Movies/Videocaptures`, `Pictures/Videocaptures`)
- **상단 UI 구성**:
  - 전체 파일 수, 전송완료 수, 총 용량(`MB`/`GB`), 폴더별 보유 건수 요약 카드.
  - 가로 스크롤 폴더 필터 칩(`전체`, `Camera`, `Pictures`, `Screen recordings`, `Screenshots`, `Videocaptures`)으로 특정 폴더만 즉시 필터링 조회 가능.
- **표 컬럼 구성 (5열)**:

| 컬럼명 | 가중치(`weight`) | 표시 데이터 및 포맷 규칙 |
| :--- | :---: | :--- |
| **폴더** | `0.95f` | 5대 카테고리 폴더명 (`Camera`, `Pictures`, `Screen recordings`, `Screenshots`, `Videocaptures`). |
| **파일명** | `1.35f` | 사진/동영상 파일명 (`fileName`, 동영상인 경우 `🎬` 접두 표시). |
| **촬영/생성시기** | `1.1f` | 촬영 또는 파일 생성 시각 (`takenAt > 0`이면 `yy-MM-dd HH:mm`, 없으면 `'-'`). |
| **용량** | `0.65f` | 파일 크기 (`KB` / `MB`, 0이면 `'-'`). |
| **전송시기** | `1.1f` | 서버(`POST /api/v1/sync/media`) 전송 완료 시각 (`syncedAt`). 미전송 시 `'-'`. |

---

### 3.7 🏃 헬스 (`HealthSyncScreen.kt`)
- **관리 형태**: 현행 카드 리스트 UI 유지
- **주요 UI 구성**:
  - `/sdcard/Download/Samsung Health` 내 운동 세션 CSV 및 갤럭시 워치 GPS 경로 JSON 스캔 현황 요약 카드.
  - **"모든 데이터를 전송하고 완료되면 데이터 삭제"** 원클릭 버튼 및 안전 확인 다이얼로그(`AlertDialog` ➔ 전송 성공 후 `deleteRecursively()` 실행).
  - 각 헬스 백업 폴더별 최신 데이터/운동 종류(`latestRecordType`), 마지막 데이터 시각(`lastDataTime`), PC 공유 시각(`lastSharedTime`), CSV/JSON 파일 개수 및 용량 배지 표시.

---

### 3.8 📇 명함 (`BusinessCardWalletScreen.kt` & `LiveCardScannerActivity.kt`)
- **명함 지갑 목록 화면**:
  - 상단 우측 `➕` 버튼으로 실시간 누적 명함 스캐너(`LiveCardScannerActivity`) 실행.
  - 이름, 회사명, 직책, 전화번호 실시간 검색바 제공.
  - 보관 중인 명함 카드 리스트 렌더링 (회사명과 직책/직급을 별도 행으로 분리하여 가독성 확보, 명함 원본 이미지 썸네일 및 상세 편집/삭제/연락처 저장 지원).
  - 앱 업데이트 시에도 명함 데이터가 절대 유실되지 않도록 비파괴 마이그레이션(`MIGRATION_3_4`, `MIGRATION_4_5`) 보장.
- **명함 스캐너 및 편집 다이얼로그 UI 피드백 규격**:
  - 실시간 ML Kit 다수결 누적 투표 카메라 + Google Document Scanner 3D 모서리 원근 보정 하이브리드 촬영.
  - 명함 사진 수동/자동 90° 회전 보정 버튼 및 우상단 재촬영 원형 배지(`[🔄]`).
  - **필수 입력값 인라인 검증 피드백**:
    - 이름 미입력 시 빨간색 테두리 및 `'이름을 입력해주세요'` 인라인 에러 문구 표시 + 자동 포커스.
    - 휴대폰/일반전화/이메일이 모두 비어 있을 경우 `'전화번호 또는 이메일 중 최소 하나를 입력해주세요'` 안내.
  - **구체적 실패 원인 안내**: 연락처 저장 실패 시 단순 오류 문구 대신 시스템 예외를 분석하여 `[구글/삼성 클라우드 계정 바인딩 오류 / 연락처 권한 미허용 / 필수값 누락]` 등 구체적 원인 표시.

---

### 3.9 📊 상태 (`StatusDashboardContent` in `MainActivity.kt`)
- **2대 동기화 조건 실시간 상태 카드**:
  1. **배터리 조건**: 충전 중 여부 또는 배터리 잔량 표시 (시작 기준: 충전 중 or $\ge 80\%$, 유지 하한선: $\ge 75\%$).
  2. **myLore 서버 연결 상태**: 온라인(`Online`) / 오프라인(`Offline`). 오프라인 상태에서 카드를 탭하면 **QR 스캐너(`QrScannerActivity`)가 즉시 실행**되어 재연결 및 IP/포트/토큰/Wi-Fi 정보를 갱신합니다.
- **서버 Wi-Fi 원클릭 연결 카드**: QR로 등록된 서버 소속 Wi-Fi(SSID)와 현재 연결된 Wi-Fi가 다를 경우 탭 한 번으로 시스템 Wi-Fi 저장/연결(`Settings.ACTION_WIFI_ADD_NETWORKS`)을 호출합니다.
- **앱 사용량(`PACKAGE_USAGE_STATS`) 권한 미허용 경고 배너**: 권한 미허용 시 상단에 경고 카드 및 `[권한 허용]` 원클릭 이동 버튼을 노출합니다.
- **빠른 실행 버튼 & 실시간 전송 로그 뷰어**:
  - `🔄 수동 동기화` 및 `📷 실시간 누적 명함 스캔` 바로가기 버튼.
  - 하단에 `SyncLogDao`의 최근 전송 로그를 터미널 스타일 리스트로 실시간 표시.

---

### 3.10 ⚙️ 설정 (`SettingsScreen.kt`)
- **시스템 권한 실시간 상태 배지 (`[허용됨]` / `[미허용]`) 및 개별 `[열기]` 버튼**:
  - 알림 접근 권한, 앱 사용정보 접근 권한, 모든 파일 접근 권한(`MANAGE_EXTERNAL_STORAGE`), 연락처 권한, 문자 권한, 통화 기록 권한, 위치 권한, 카메라 권한, 배터리 최적화 예외 여부를 실시간 배지로 표시하고 원클릭 설정 이동을 지원합니다.
- **서버 소속 Wi-Fi (NAT) 및 연동 정보 카드**:
  - QR 페어링으로 자동 수신된 서버 Wi-Fi SSID/비밀번호 표시 및 원클릭 연결 버튼.
  - 현재 페어링된 PC 이름(`pcName`), IP 주소, 포트(`18443`), 보안 토큰 마스킹 정보 및 `[PC 연동 해제(Unpair)]` 버튼을 제공합니다.

---

## 4. ⚡ 초고속 목록 렌더링 UX 아키텍처 (선 로컬 캐시 `0ms` 표시 · 후 지연 증분 스캔)

### 4.1 설계 배경
- 사용자가 카톡, 문자, 통화, 알림, 음성녹음, 사진, 헬스 탭을 열 때마다 수천 건의 ContentResolver 쿼리나 수백 개 미디어 파일의 SHA-256 해시를 처음부터 다시 계산하면 수 초의 화면 멈춤(Loading Spinner 대기)이 발생합니다.
- 또한 카카오톡과 삼성 헬스는 서버 전송 완료 후 스마트폰 저장 공간 확보를 위해 내보내기 원본 폴더가 삭제(`Sync & Clean`)되므로, 파일 시스템만 스캔하면 과거 공유 이력이 표에서 사라집니다.

### 4.2 2단계 비동기 렌더링 파이프라인 (`Stale-While-Revalidate`)
1. **Step A — 로컬 Room 캐시 즉시 렌더링 (`0ms`)**:
   - 화면(`Composable`) 진입 즉시 `SyncCacheDao`의 `Flow<List<*Entity>>`를 구독하여 로컬 DB에 캐싱된 기존 표 데이터를 **대기 시간 0ms로 즉시 화면에 렌더링**합니다.
   - 전송시기가 기록되어 있으나(`isSynced == true`) 타임스탬프가 `0L`인 항목은 이 단계에서 검증시각으로 자동 보정하여 표에 즉시 반영합니다.
2. **Step B — 백그라운드 증분 지연 스캔 (`Delayed Delta Update`)**:
   - `LaunchedEffect(Unit)` 내 `Dispatchers.IO` 백그라운드 코루틴에서 `max(cached_ts)` 이후에 새로 발생한 문자/통화만 증분 조회하거나, 파일 크기·수정시각(`fileSize`, `lastModified`)이 변경된 녹음/사진 파일만 해시를 계산하여 `SyncCacheDao`에 `Upsert`합니다.
   - Room `Flow`가 변경 사항을 감지하여 스크롤 위치나 UI 깜빡임 없이 표에 신규 행을 부드럽게 반영합니다.

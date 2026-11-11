# MYLORE-SYNC-SPEC-REQUEST: 휴대폰 알림 로그(Notification) 전송 확정 후 자동 삭제 명세

- **작성일**: 2026-11-02
- **관련 태스크**: `ADD.md #9`, `BUGS.md #18`
- **대상 저장소**: `c:\git\myLoreSync` (Android 동반 앱) ↔ `c:\git\myLore` (PC Tauri 백엔드)

---

## 1. 배경 및 구조 분석

휴대폰 알림(Notification)은 다음 두 계층으로 존재합니다:

1. **Android OS 시스템 상태바 알림 (`StatusBarNotification`)**
   - `NotificationListenerService.cancelNotification(sbn.key)` 호출 시 스마트폰 상단바에서도 알림을 강제 삭제할 수 있습니다.
   - **권장 정책**: PC로 백그라운드 동기화되었다고 해서 스마트폰 상단바의 알림까지 즉시 지우면 사용자가 폰에서 아직 확인하지 않은 메신저·일정 알림을 놓칠 수 있으므로, **OS 상단바 알림은 유지**합니다.
2. **`myLoreSync` 앱 내부 Room DB 리스트 (`pending_notifications` 테이블)**
   - `MyloreNotificationListener`가 알림 발생 시 `pending_notifications` 테이블(`NotificationEntity`)에 리스트 형태로 적재합니다.
   - 기존에는 PC 전송 성공 후 `markAsSynced(ackIds)`로 `isSynced = 1` 플래그만 갱신하고 레코드를 삭제하지 않아, 휴대폰 DB에 알림 리스트가 무한 누적되고 재검증 시 불필요한 조회 부하가 발생했습니다.

---

## 2. `myLoreSync` 수정 요구사항 (전송 확정 이후 즉시 삭제)

PC 서버(`myLore`)로부터 전송 확정을 받은 알림 로그는 휴대폰 `myLoreSync` 내부 DB(`pending_notifications`)에서 즉시 삭제하도록 변경합니다.

### 2.1 `NotificationDao.kt` 삭제 쿼리 추가

파일 위치: `app/src/main/java/com/iseohyun/mylore/sync/data/local/dao/NotificationDao.kt`

```kotlin
@Query("DELETE FROM pending_notifications WHERE id IN (:ids)")
suspend fun deleteByIds(ids: List<Long>)

@Query("DELETE FROM pending_notifications WHERE postedAt <= :watermarkTs")
suspend fun deleteNotificationsUpTo(watermarkTs: Long)

@Query("DELETE FROM pending_notifications WHERE isSynced = 1")
suspend fun deleteSyncedNotifications()
```

### 2.2 `RealtimeSyncForegroundService.kt` 전송 확정(ACK) 시점 삭제 연동

파일 위치: `app/src/main/java/com/iseohyun/mylore/sync/engine/RealtimeSyncForegroundService.kt`

1. **단건/배치 전송 응답(`POST /api/v1/sync/notifications`) 수신 시**:
   - 서버가 `ok == true` 및 `ack_ids`를 반환하면 워터마크(`updateLastNotiTs`)를 갱신한 직후 해당 `ack_ids`를 `pending_notifications`에서 즉시 삭제합니다.
   ```kotlin
   if (res.isSuccessful && res.body()?.ok == true) {
       ctx.didTransmitData = true
       val ackIds = res.body()!!.ackIds.ifEmpty { listOf(entity.id) }
       ctx.db.notificationDao().deleteByIds(ackIds)
       ctx.prefs.updateLastNotiTs(entity.postedAt)
       ctx.notiSyncedCount++
   }
   ```

2. **세션 종료 검증(`POST /api/v1/sync/complete` → `POST /api/v1/sync/ack`) 수신 시**:
   - 서버가 확인한 `unacked_domains.notification.latest_ts` 이하의 알림 및 `isSynced = 1` 상태의 잔여 알림을 일괄 삭제합니다.
   ```kotlin
   domains.notification?.let { info ->
       if (info.latestTs > 0L) {
           prefs.updateLastNotiTs(info.latestTs)
           db.notificationDao().deleteNotificationsUpTo(info.latestTs)
           db.notificationDao().deleteSyncedNotifications()
           ackedDomains["notification"] = info.latestTs
       }
   }
   ```

---

## 3. 기대 효과

- **휴대폰 스토리지 및 메모리 절약**: 전송 완료된 수천~수만 건의 알림 텍스트가 모바일 SQLite(`pending_notifications`)에 남지 않고 즉시 비워집니다.
- **중복 전송 원천 차단**: 이미 PC 볼트/DB에 반영된 알림이 모바일 큐에서 물리적으로 제거되므로 재전송 루프(`BUGS #18`)가 발생하지 않습니다.
- **UI 명확성**: `myLoreSync`의 알림 화면(`NotificationListScreen`)에는 아직 PC로 전송되지 않은 **대기 중(Pending) 알림 리스트**만 깔끔하게 표시됩니다.

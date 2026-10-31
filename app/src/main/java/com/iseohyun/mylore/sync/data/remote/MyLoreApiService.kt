package com.iseohyun.mylore.sync.data.remote

import com.iseohyun.mylore.sync.data.remote.dto.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface MyLoreApiService {

    @GET("/api/v1/status")
    suspend fun getStatus(
        @Header("X-MyLore-Token") token: String
    ): Response<StatusResponse>

    @POST("/api/v1/sync/watermarks")
    suspend fun syncWatermarks(
        @Header("X-MyLore-Token") token: String,
        @Body request: SyncWatermarksRequest
    ): Response<StatusResponse>

    @GET("/api/v1/sync/manifest")
    suspend fun getSyncManifest(
        @Header("X-MyLore-Token") token: String
    ): Response<SyncManifestResponse>

    @POST("/api/v1/sync/reconcile")
    suspend fun reconcileSyncLists(
        @Header("X-MyLore-Token") token: String,
        @Body request: SyncReconcileRequest
    ): Response<SyncManifestResponse>

    @POST("/api/v1/sync/session-plan")
    suspend fun sendSessionPlan(
        @Header("X-MyLore-Token") token: String,
        @Body request: SessionPlanRequest
    ): Response<SyncResponse>

    @POST("/api/v1/sync/complete")
    suspend fun completeSyncSession(
        @Header("X-MyLore-Token") token: String,
        @Body request: SyncCompleteRequest
    ): Response<SyncCompleteResponse>

    @POST("/api/v1/sync/ack")
    suspend fun sendSyncAck(
        @Header("X-MyLore-Token") token: String,
        @Body request: SyncAckRequest
    ): Response<SyncAckResponse>

    @POST("/api/v1/sync/batch-init")
    suspend fun batchInit(
        @Header("X-MyLore-Token") token: String,
        @Body request: BatchInitRequest
    ): Response<SyncResponse>

    @POST("/api/v1/pair/confirm")
    suspend fun pairConfirm(
        @Header("X-MyLore-Token") token: String,
        @Body request: PairConfirmRequest
    ): Response<PairConfirmResponse>

    @POST("/api/v1/sync/check-hashes")
    suspend fun checkHashes(
        @Header("X-MyLore-Token") token: String,
        @Body request: CheckHashesRequest
    ): Response<CheckHashesResponse>

    @POST("/api/v1/sync/notifications")
    suspend fun syncNotifications(
        @Header("X-MyLore-Token") token: String,
        @Body request: SyncNotificationsRequest
    ): Response<SyncResponse>

    @POST("/api/v1/sync/sms")
    suspend fun syncSms(
        @Header("X-MyLore-Token") token: String,
        @Body request: SyncSmsRequest
    ): Response<SyncResponse>

    @POST("/api/v1/sync/call-logs")
    suspend fun syncCallLogs(
        @Header("X-MyLore-Token") token: String,
        @Body request: SyncCallLogsRequest
    ): Response<SyncResponse>

    @Multipart
    @POST("/api/v1/sync/kakao-chats")
    suspend fun uploadKakaoChatFile(
        @Header("X-MyLore-Token") token: String,
        @Part("folder_name") folderName: RequestBody,
        @Part("file_hash") fileHash: RequestBody,
        @Part("modified_at") modifiedAt: RequestBody,
        @Part("folder_index") folderIndex: RequestBody,
        @Part("total_folders") totalFolders: RequestBody,
        @Part file: MultipartBody.Part
    ): Response<SyncResponse>

    @POST("/api/v1/sync/contacts")
    suspend fun syncContacts(
        @Header("X-MyLore-Token") token: String,
        @Body request: SyncContactsRequest
    ): Response<SyncResponse>

    @POST("/api/v1/sync/app-usage")
    suspend fun syncAppUsage(
        @Header("X-MyLore-Token") token: String,
        @Body request: SyncAppUsageRequest
    ): Response<SyncResponse>

    @Multipart
    @POST("/api/v1/sync/call-recordings")
    suspend fun uploadCallRecordingFile(
        @Header("X-MyLore-Token") token: String,
        @Part("file_hash") fileHash: RequestBody,
        @Part("phone_number") phoneNumber: RequestBody,
        @Part("contact_name") contactName: RequestBody,
        @Part("recorded_at") recordedAt: RequestBody,
        @Part file: MultipartBody.Part
    ): Response<SyncResponse>

    @Multipart
    @POST("/api/v1/sync/media")
    suspend fun uploadMediaFile(
        @Header("X-MyLore-Token") token: String,
        @Part("taken_at") takenAt: RequestBody,
        @Part("source_type") sourceType: RequestBody,
        @Part file: MultipartBody.Part,
        @Part("folder_name") folderName: RequestBody? = null
    ): Response<SyncResponse>

    @POST("/api/v1/sync/samsung-health")
    suspend fun uploadSamsungHealth(
        @Header("X-MyLore-Token") token: String,
        @Body body: RequestBody
    ): Response<SyncResponse>
}

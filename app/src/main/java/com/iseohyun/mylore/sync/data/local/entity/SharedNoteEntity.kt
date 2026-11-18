package com.iseohyun.mylore.sync.data.local.entity

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 다른 앱의 "공유" 기능으로 myLore Sync에 넘겨받은 글 (예: 에이닷 전화의 통화 요약·스크립트).
 *
 * 다른 앱의 내부 데이터는 읽을 수 없으므로, 사용자가 그 앱에서 직접 공유한 내용만 여기에 저장합니다.
 * 받은 원문([rawText])은 가공하지 않고 그대로 보관합니다.
 */
@Entity(
    tableName = "shared_notes",
    indices = [Index(value = ["receivedAt"]), Index(value = ["isSynced"])]
)
data class SharedNoteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 공유한 앱의 패키지명. 알 수 없으면 빈 문자열 */
    val sourcePackage: String = "",
    /** 공유 시 함께 넘어온 제목(EXTRA_SUBJECT / EXTRA_TITLE). 없으면 빈 문자열 */
    val subject: String = "",
    val rawText: String,
    val receivedAt: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false,
    val syncedAt: Long = 0L
)

@Dao
interface SharedNoteDao {

    @Insert
    suspend fun insert(note: SharedNoteEntity): Long

    @Query("SELECT * FROM shared_notes ORDER BY receivedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<SharedNoteEntity>

    @Query("SELECT COUNT(*) FROM shared_notes")
    suspend fun count(): Int
}

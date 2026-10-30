package com.iseohyun.mylore.sync.data.local.dao

import androidx.room.*
import com.iseohyun.mylore.sync.data.local.entity.BusinessCardEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BusinessCardDao {

    @Query("SELECT * FROM business_cards ORDER BY updatedAt DESC")
    fun getAllCards(): Flow<List<BusinessCardEntity>>

    @Query("SELECT * FROM business_cards WHERE name LIKE '%' || :query || '%' OR company LIKE '%' || :query || '%' OR mobile LIKE '%' || :query || '%' ORDER BY updatedAt DESC")
    fun searchCards(query: String): Flow<List<BusinessCardEntity>>

    @Query("SELECT * FROM business_cards WHERE id = :id LIMIT 1")
    suspend fun getCardById(id: Long): BusinessCardEntity?

    @Query("SELECT * FROM business_cards WHERE mobile = :mobile LIMIT 1")
    suspend fun getCardByMobile(mobile: String): BusinessCardEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(card: BusinessCardEntity): Long

    @Update
    suspend fun update(card: BusinessCardEntity)

    @Delete
    suspend fun delete(card: BusinessCardEntity)
}

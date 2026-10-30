package com.iseohyun.mylore.sync.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "business_cards")
data class BusinessCardEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String = "",
    val mobile: String = "",
    val telephone: String = "",
    val company: String = "",
    val department: String = "",
    val jobTitle: String = "",
    val email: String = "",
    val address: String = "",
    val website: String = "",
    val memo: String = "",
    val frontImageUri: String = "",
    val backImageUri: String = "",
    val contactId: Long? = null,
    val rawContactId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

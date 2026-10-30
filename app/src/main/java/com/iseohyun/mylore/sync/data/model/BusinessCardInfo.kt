package com.iseohyun.mylore.sync.data.model

import android.os.Parcelable

data class BusinessCardInfo(
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
    val rawText: String = ""
)

data class ExistingContact(
    val contactId: Long,
    val rawContactId: Long,
    val displayName: String,
    val existingPhone: String = "",
    val existingEmail: String = "",
    val existingCompany: String = ""
)

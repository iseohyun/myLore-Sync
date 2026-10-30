package com.iseohyun.mylore.sync.collector

import android.content.ContentProviderOperation
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import com.iseohyun.mylore.sync.data.model.BusinessCardInfo
import com.iseohyun.mylore.sync.data.model.ExistingContact
import java.io.ByteArrayOutputStream

class ContactManager(private val context: Context) {

    companion object {
        private const val TAG = "ContactManager"
    }

    /**
     * 전화번호 또는 이름으로 기존 연락처가 있는지 확인합니다.
     */
    fun findExistingContact(phone: String, name: String): ExistingContact? {
        val cleanPhone = phone.replace(Regex("[^0-9]"), "")

        // 1. 전화번호로 조회
        if (cleanPhone.length >= 7) {
            val lookupUri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(cleanPhone)
            )
            val projection = arrayOf(
                ContactsContract.PhoneLookup._ID,
                ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.NUMBER
            )
            try {
                context.contentResolver.query(lookupUri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val contactId = cursor.getLong(cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup._ID))
                        val displayName = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME)) ?: ""
                        val matchedNum = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.NUMBER)) ?: ""
                        val rawId = getRawContactId(contactId)
                        return ExistingContact(
                            contactId = contactId,
                            rawContactId = rawId,
                            displayName = displayName,
                            existingPhone = matchedNum
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error looking up contact by phone", e)
            }
        }

        // 2. 이름으로 조회
        if (name.isNotBlank()) {
            val uri = ContactsContract.Contacts.CONTENT_URI
            val selection = "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} = ?"
            val selectionArgs = arrayOf(name)
            try {
                context.contentResolver.query(uri, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), selection, selectionArgs, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val contactId = cursor.getLong(cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                        val displayName = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)) ?: ""
                        val rawId = getRawContactId(contactId)
                        return ExistingContact(
                            contactId = contactId,
                            rawContactId = rawId,
                            displayName = displayName
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error looking up contact by name", e)
            }
        }

        return null
    }

    private fun getRawContactId(contactId: Long): Long {
        val uri = ContactsContract.RawContacts.CONTENT_URI
        val projection = arrayOf(ContactsContract.RawContacts._ID)
        val selection = "${ContactsContract.RawContacts.CONTACT_ID} = ?"
        val selectionArgs = arrayOf(contactId.toString())
        context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(cursor.getColumnIndexOrThrow(ContactsContract.RawContacts._ID))
            }
        }
        return contactId
    }

    private fun getPreferredAccount(): Pair<String?, String?> {
        try {
            val uri = ContactsContract.RawContacts.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.RawContacts.ACCOUNT_NAME,
                ContactsContract.RawContacts.ACCOUNT_TYPE
            )
            val selection = "${ContactsContract.RawContacts.ACCOUNT_NAME} IS NOT NULL AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} IS NOT NULL"
            val sort = "${ContactsContract.RawContacts._ID} DESC LIMIT 1"
            context.contentResolver.query(uri, projection, selection, null, sort)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    val type = cursor.getString(1)
                    if (!name.isNullOrBlank() && !type.isNullOrBlank()) {
                        Log.d(TAG, "Found default contact account from existing raw contacts: $name ($type)")
                        return Pair(name, type)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying RawContacts account", e)
        }

        try {
            val am = android.accounts.AccountManager.get(context)
            val google = am.getAccountsByType("com.google")
            if (google.isNotEmpty()) {
                Log.d(TAG, "Using primary Google account: ${google[0].name}")
                return Pair(google[0].name, google[0].type)
            }
            val all = am.accounts
            if (all.isNotEmpty()) {
                Log.d(TAG, "Using device account: ${all[0].name}")
                return Pair(all[0].name, all[0].type)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying AccountManager", e)
        }

        return Pair(null, null)
    }

    /**
     * 신규 연락처 생성
     */
    fun createContact(info: BusinessCardInfo, photoBitmap: Bitmap? = null): Boolean {
        val ops = ArrayList<ContentProviderOperation>()

        // 1. RawContact 추가 (기본 계정이 클라우드인 경우 해당 계정으로 지정)
        val (accountName, accountType) = getPreferredAccount()
        val rawContactInsertIndex = ops.size
        val insertOp = ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
        if (!accountName.isNullOrBlank() && !accountType.isNullOrBlank()) {
            insertOp.withValue(ContactsContract.RawContacts.ACCOUNT_NAME, accountName)
            insertOp.withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, accountType)
        } else {
            insertOp.withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            insertOp.withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
        }
        ops.add(insertOp.build())

        // 2. 이름
        if (info.name.isNotBlank()) {
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, info.name)
                    .build()
            )
        }

        // 3. 휴대폰 번호
        if (info.mobile.isNotBlank()) {
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, info.mobile)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build()
            )
        }

        // 4. 일반/회사 전화번호
        if (info.telephone.isNotBlank()) {
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, info.telephone)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_WORK)
                    .build()
            )
        }

        // 5. 이메일
        if (info.email.isNotBlank()) {
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Email.DATA, info.email)
                    .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_WORK)
                    .build()
            )
        }

        // 6. 회사 & 직책
        if (info.company.isNotBlank() || info.jobTitle.isNotBlank()) {
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, info.company)
                    .withValue(ContactsContract.CommonDataKinds.Organization.TITLE, info.jobTitle)
                    .withValue(ContactsContract.CommonDataKinds.Organization.TYPE, ContactsContract.CommonDataKinds.Organization.TYPE_WORK)
                    .build()
            )
        }

        // 7. 주소
        if (info.address.isNotBlank()) {
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS, info.address)
                    .withValue(ContactsContract.CommonDataKinds.StructuredPostal.TYPE, ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK)
                    .build()
            )
        }

        // 8. 웹사이트
        if (info.website.isNotBlank()) {
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Website.URL, info.website)
                    .withValue(ContactsContract.CommonDataKinds.Website.TYPE, ContactsContract.CommonDataKinds.Website.TYPE_WORK)
                    .build()
            )
        }

        // 9. 메모 (뒷면 정보 등)
        if (info.memo.isNotBlank()) {
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Note.NOTE, info.memo)
                    .build()
            )
        }

        // 10. 프로필 사진 (앞면 명함 이미지)
        if (photoBitmap != null) {
            val photoBytes = bitmapToByteArray(photoBitmap)
            if (photoBytes.isNotEmpty()) {
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, photoBytes)
                        .build()
                )
            }
        }

        return try {
            val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            Log.d(TAG, "Contact created successfully: ${results.size} operations")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create contact", e)
            false
        }
    }

    /**
     * 기존 연락처에 추가 정보 병합(Merge)
     */
    fun updateContact(rawContactId: Long, info: BusinessCardInfo, photoBitmap: Bitmap? = null): Boolean {
        val ops = ArrayList<ContentProviderOperation>()

        // 1. 휴대폰 번호 추가 (신규 번호인 경우)
        if (info.mobile.isNotBlank()) {
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, info.mobile)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build()
            )
        }

        // 2. 회사/일반 전화번호 추가
        if (info.telephone.isNotBlank()) {
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, info.telephone)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_WORK)
                    .build()
            )
        }

        // 3. 이메일 추가
        if (info.email.isNotBlank()) {
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Email.DATA, info.email)
                    .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_WORK)
                    .build()
            )
        }

        // 4. 회사 / 직책 추가
        if (info.company.isNotBlank() || info.jobTitle.isNotBlank()) {
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, info.company)
                    .withValue(ContactsContract.CommonDataKinds.Organization.TITLE, info.jobTitle)
                    .withValue(ContactsContract.CommonDataKinds.Organization.TYPE, ContactsContract.CommonDataKinds.Organization.TYPE_WORK)
                    .build()
            )
        }

        // 5. 메모 추가
        if (info.memo.isNotBlank()) {
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Note.NOTE, info.memo)
                    .build()
            )
        }

        // 6. 프로필 사진 업데이트 (사진이 있고 기존에 없거나 갱신할 때)
        if (photoBitmap != null) {
            val photoBytes = bitmapToByteArray(photoBitmap)
            if (photoBytes.isNotEmpty()) {
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, photoBytes)
                        .build()
                )
            }
        }

        return try {
            val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            Log.d(TAG, "Contact updated successfully: ${results.size} operations")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update contact", e)
            false
        }
    }

    private fun bitmapToByteArray(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        // 프로필 썸네일 크기 제한 및 Binder Buffer 초과 방지 (최대 480px, 압축률 80%)
        val maxDim = 480
        val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
            val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
            val w = if (ratio > 1) maxDim else (maxDim * ratio).toInt()
            val h = if (ratio > 1) (maxDim / ratio).toInt() else maxDim
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        } else {
            bitmap
        }
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        return stream.toByteArray()
    }
}

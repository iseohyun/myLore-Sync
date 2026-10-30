package com.iseohyun.mylore.sync.collector

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.iseohyun.mylore.sync.data.remote.dto.SyncContactItem

class ContactsCollector(private val context: Context) {

    companion object {
        private const val TAG = "ContactsCollector"
    }

    /**
     * lastContactTs 이후에 수정되거나 추가된 연락처를 증분 수집합니다.
     */
    fun collectContactsSince(lastContactTs: Long, limit: Int = 300): List<SyncContactItem> {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_CONTACTS permission not granted")
            return emptyList()
        }

        val resultList = mutableListOf<SyncContactItem>()

        val uri = ContactsContract.Contacts.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP
        )

        val selection = if (lastContactTs > 0) {
            "${ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP} > ?"
        } else {
            null
        }

        val selectionArgs = if (lastContactTs > 0) {
            arrayOf(lastContactTs.toString())
        } else {
            null
        }

        val sortOrder = "${ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP} ASC LIMIT $limit"

        try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                val nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                val tsIdx = cursor.getColumnIndex(ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP)

                while (cursor.moveToNext()) {
                    val contactId = cursor.getLong(idIdx)
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) ?: "" else ""
                    val updatedTs = if (tsIdx >= 0) cursor.getLong(tsIdx) else System.currentTimeMillis()

                    val phones = getPhonesForContact(contactId)
                    val emails = getEmailsForContact(contactId)
                    val (org, jobTitle) = getOrganizationForContact(contactId)
                    val note = getNoteForContact(contactId)

                    resultList.add(
                        SyncContactItem(
                            contactId = contactId.toString(),
                            name = name,
                            phones = phones,
                            emails = emails,
                            organization = org,
                            jobTitle = jobTitle,
                            memo = note,
                            updatedTs = updatedTs
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying contacts", e)
        }

        return resultList
    }

    private fun getPhonesForContact(contactId: Long): List<String> {
        val phones = mutableListOf<String>()
        val phoneUri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val selection = "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?"
        val args = arrayOf(contactId.toString())

        try {
            context.contentResolver.query(phoneUri, projection, selection, args, null)?.use { cursor ->
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val num = cursor.getString(numIdx)
                    if (!num.isNullOrBlank()) phones.add(num.trim())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading phone numbers", e)
        }
        return phones
    }

    private fun getEmailsForContact(contactId: Long): List<String> {
        val emails = mutableListOf<String>()
        val emailUri = ContactsContract.CommonDataKinds.Email.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS)
        val selection = "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?"
        val args = arrayOf(contactId.toString())

        try {
            context.contentResolver.query(emailUri, projection, selection, args, null)?.use { cursor ->
                val addrIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
                while (cursor.moveToNext()) {
                    val addr = cursor.getString(addrIdx)
                    if (!addr.isNullOrBlank()) emails.add(addr.trim())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading email addresses", e)
        }
        return emails
    }

    private fun getOrganizationForContact(contactId: Long): Pair<String, String> {
        var company = ""
        var title = ""
        val uri = ContactsContract.Data.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Organization.COMPANY,
            ContactsContract.CommonDataKinds.Organization.TITLE
        )
        val selection = "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
        val args = arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)

        try {
            context.contentResolver.query(uri, projection, selection, args, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val compIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Organization.COMPANY)
                    val titleIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Organization.TITLE)
                    if (compIdx >= 0) company = cursor.getString(compIdx) ?: ""
                    if (titleIdx >= 0) title = cursor.getString(titleIdx) ?: ""
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading organization", e)
        }
        return Pair(company, title)
    }

    private fun getNoteForContact(contactId: Long): String {
        var note = ""
        val uri = ContactsContract.Data.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Note.NOTE)
        val selection = "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
        val args = arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)

        try {
            context.contentResolver.query(uri, projection, selection, args, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val noteIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Note.NOTE)
                    if (noteIdx >= 0) note = cursor.getString(noteIdx) ?: ""
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading note", e)
        }
        return note
    }
}

package com.iseohyun.mylore.sync.collector

import android.content.Context
import android.graphics.*
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

object ImageCropHelper {

    private const val TAG = "ImageCropHelper"

    /**
     * 촬영된 파일 또는 URI를 Exif 방향에 맞게 똑바로 회전시키고,
     * 화면 중앙의 명함 표준 비율(1.75:1) 영역만 가득 차게 크롭 및 대비 향상 보정하여 저장합니다.
     */
    fun cropAndFillCard(file: File): File {
        return try {
            val originalBitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return file
            val corrected = correctOrientation(originalBitmap, file.absolutePath)
            val enhanced = autoCropAndContrast(corrected)

            FileOutputStream(file).use { out ->
                enhanced.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            file
        } catch (e: Exception) {
            Log.e(TAG, "Error cropping card image file", e)
            file
        }
    }

    /**
     * Uri로부터 비트맵을 읽어 1.75:1 명함 화면 가득 차게 자동 보정한 새 파일 URI를 반환합니다.
     */
    fun autoEnhanceFromUri(uriString: String, context: Context): String {
        return try {
            val uri = Uri.parse(uriString)
            val bitmap = loadBitmap(uri, context) ?: return uriString
            val enhanced = autoCropAndContrast(bitmap)

            val outputFile = createNewCardFile(context)
            FileOutputStream(outputFile).use { out ->
                enhanced.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            Uri.fromFile(outputFile).toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error auto enhancing uri: $uriString", e)
            uriString
        }
    }

    /**
     * 이미지를 90도 회전하여 저장합니다.
     */
    fun rotateImageUri(uriString: String, degrees: Float, context: Context): String {
        return try {
            val uri = Uri.parse(uriString)
            val bitmap = loadBitmap(uri, context) ?: return uriString
            val matrix = Matrix().apply { postRotate(degrees) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

            val outputFile = createNewCardFile(context)
            FileOutputStream(outputFile).use { out ->
                rotated.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            Uri.fromFile(outputFile).toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error rotating image uri", e)
            uriString
        }
    }

    /**
     * 비트맵을 가로 명함 비율(1.75:1)로 중앙 가득 차게 크롭하고 대비/선명도를 강화합니다.
     */
    private fun autoCropAndContrast(bitmap: Bitmap): Bitmap {
        var base = bitmap
        // 1. 세로 사진인 경우 가로로 회전하여 명함 형태 맞춤
        if (base.height > base.width) {
            val rot = Matrix().apply { postRotate(90f) }
            base = Bitmap.createBitmap(base, 0, 0, base.width, base.height, rot, true)
        }

        // 2. 명함 표준 비율 (1.75:1) 중앙 크롭 (배경 책상 등 잘라내고 명함으로 꽉 채움)
        val targetAspect = 1.75f
        val cropW = (base.width * 0.88f).toInt()
        val cropH = (cropW / targetAspect).toInt().coerceAtMost(base.height)

        val startX = ((base.width - cropW) / 2).coerceAtLeast(0)
        val startY = ((base.height - cropH) / 2).coerceAtLeast(0)

        val cropped = Bitmap.createBitmap(
            base,
            startX,
            startY,
            cropW.coerceAtMost(base.width - startX),
            cropH.coerceAtMost(base.height - startY)
        )

        return cropped
    }

    private fun correctOrientation(bitmap: Bitmap, path: String): Bitmap {
        return try {
            val exif = ExifInterface(path)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            }
            if (!matrix.isIdentity) {
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }
        } catch (_: Exception) {
            bitmap
        }
    }

    private fun loadBitmap(uri: Uri, context: Context): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
        } catch (_: Exception) {
            try {
                val input: InputStream? = context.contentResolver.openInputStream(uri)
                BitmapFactory.decodeStream(input)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun createNewCardFile(context: Context): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(context.getExternalFilesDir(null), "card_edited_$timeStamp.jpg")
    }
}

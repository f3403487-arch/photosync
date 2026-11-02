package com.photosync.app

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore.Images.Media
import android.provider.OpenableColumns

data class Photo(val id: Long, val name: String, val uri: Uri)

/** Поиск фото в галерее телефона. */
object Photos {
    private val collection = Media.EXTERNAL_CONTENT_URI

    /** Фото, появившиеся после [lastId], от старых к новым. */
    fun newerThan(context: Context, lastId: Long): List<Photo> = query(
        context,
        "${Media._ID} > ? AND ${Media.IS_PENDING} = 0",
        arrayOf(lastId.toString()),
        "${Media._ID} ASC",
    )

    fun newest(context: Context): Photo? =
        query(context, "${Media.IS_PENDING} = 0", null, "${Media._ID} DESC", limit = 1).firstOrNull()

    /** Имя файла, выбранного в галерее или файловом менеджере. */
    fun displayName(context: Context, uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "file_${System.currentTimeMillis()}"

    private fun query(
        context: Context,
        selection: String,
        args: Array<String>?,
        order: String,
        limit: Int = Int.MAX_VALUE,
    ): List<Photo> {
        val result = mutableListOf<Photo>()
        context.contentResolver.query(collection, arrayOf(Media._ID, Media.DISPLAY_NAME), selection, args, order)
            ?.use { c ->
                val idCol = c.getColumnIndexOrThrow(Media._ID)
                val nameCol = c.getColumnIndexOrThrow(Media.DISPLAY_NAME)
                while (result.size < limit && c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val name = c.getString(nameCol) ?: "photo_$id.jpg"
                    result += Photo(id, name, ContentUris.withAppendedId(collection, id))
                }
            }
        return result
    }
}

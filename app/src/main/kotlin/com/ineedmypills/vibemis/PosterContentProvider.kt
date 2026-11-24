package com.ineedmypills.vibemis

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.ineedmypills.vibemis.grid.assets.DiskAssetLoader
import java.io.FileNotFoundException
import java.util.UUID

class PosterContentProvider : ContentProvider() {
    private lateinit var mDiskAssetLoader: DiskAssetLoader

    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        return openBoxArtFile(uri, mode)
    }

    @Throws(FileNotFoundException::class)
    fun openBoxArtFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if ("r" != mode) {
            throw UnsupportedOperationException("This provider is only for read mode")
        }
        val segments = uri.pathSegments
        if (segments.size != 3 || segments[0] != BOXART_PATH) {
            throw FileNotFoundException()
        }
        val uuidStr = segments[COMPUTER_UUID_PATH_INDEX]
        val appIdStr = segments[APP_ID_PATH_INDEX]
        val parsedUuid = try {
            UUID.fromString(uuidStr).toString()
        } catch (_: IllegalArgumentException) {
            throw FileNotFoundException()
        }
        val parsedAppId = appIdStr.toIntOrNull()?.takeIf { it > 0 } ?: throw FileNotFoundException()

        val file = try {
            mDiskAssetLoader.getFile(parsedUuid, parsedAppId)
        } catch (_: SecurityException) {
            throw FileNotFoundException()
        }
        if (file.isFile && file.exists()) {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        throw FileNotFoundException()
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        throw UnsupportedOperationException("This provider is only for read mode")
    }

    override fun getType(uri: Uri): String = PNG_MIME_TYPE

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        throw UnsupportedOperationException("This provider is only for read mode")
    }

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        mDiskAssetLoader = DiskAssetLoader(ctx)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor {
        throw UnsupportedOperationException("This provider doesn't support query")
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int {
        throw UnsupportedOperationException("This provider is support read only")
    }

    companion object {
        @JvmField
        val AUTHORITY: String = "poster.${BuildConfig.APPLICATION_ID}"
        const val PNG_MIME_TYPE = "image/png"
        const val APP_ID_PATH_INDEX = 2
        const val COMPUTER_UUID_PATH_INDEX = 1
        private const val BOXART_PATH = "boxart"

        @JvmStatic
        fun createBoxArtUri(uuid: String, appId: String): Uri {
            return Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(AUTHORITY)
                .appendPath(BOXART_PATH)
                .appendPath(uuid)
                .appendPath(appId)
                .build()
        }
    }
}

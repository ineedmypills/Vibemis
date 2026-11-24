package com.ineedmypills.vibemis.utils

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets

object CacheHelper {
    private const val MAX_TEXT_CACHE_BYTES = 16 * 1024 * 1024

    @JvmStatic
    fun openPath(createPath: Boolean, root: File, vararg path: String?): File {
        val canonicalRoot = try {
            root.canonicalFile
        } catch (e: IOException) {
            throw SecurityException("Unable to canonicalize cache root", e)
        }

        var f = canonicalRoot
        for (i in path.indices) {
            val component = path[i]?.trim() ?: ""
            if (component.isEmpty() || component == "." || component == ".." ||
                component.contains('/') || component.contains('\\') || component.contains('\u0000')
            ) {
                throw SecurityException("Invalid cache path component")
            }
            if (i == path.size - 1 && createPath) {
                f.mkdirs()
            }
            f = File(f, component)
        }

        val canonicalTarget = try {
            f.canonicalFile
        } catch (e: IOException) {
            throw SecurityException("Unable to canonicalize cache target", e)
        }

        val rootPath = canonicalRoot.toPath()
        val targetPath = canonicalTarget.toPath()
        if (!targetPath.startsWith(rootPath) || targetPath == rootPath) {
            throw SecurityException("Cache path escapes root directory")
        }
        return canonicalTarget
    }

    @JvmStatic
    fun getFileSize(root: File, vararg path: String?): Long =
        openPath(false, root, *path).length()

    @JvmStatic
    fun deleteCacheFile(root: File, vararg path: String?): Boolean =
        openPath(false, root, *path).delete()

    @JvmStatic
    fun cacheFileExists(root: File, vararg path: String?): Boolean =
        openPath(false, root, *path).exists()

    @JvmStatic
    @Throws(FileNotFoundException::class)
    fun openCacheFileForInput(root: File, vararg path: String?): InputStream =
        BufferedInputStream(FileInputStream(openPath(false, root, *path)))

    @JvmStatic
    @Throws(FileNotFoundException::class)
    fun openCacheFileForOutput(root: File, vararg path: String?): OutputStream =
        BufferedOutputStream(FileOutputStream(openPath(true, root, *path)))

    @JvmStatic
    @Throws(IOException::class)
    fun writeInputStreamToOutputStream(`in`: InputStream, out: OutputStream, maxLength: Long) {
        var remaining = maxLength
        val buf = ByteArray(4096)
        var bytesRead: Int
        while (`in`.read(buf).also { bytesRead = it } != -1) {
            remaining -= bytesRead
            if (remaining <= 0) {
                throw IOException("Stream exceeded max size")
            }
            out.write(buf, 0, bytesRead)
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun readInputStreamToString(`in`: InputStream): String {
        val r = InputStreamReader(`in`, StandardCharsets.UTF_8)
        val sb = StringBuilder()
        val buf = CharArray(1024)
        var totalChars = 0
        var bytesRead: Int
        try {
            while (r.read(buf).also { bytesRead = it } != -1) {
                totalChars += bytesRead
                if (totalChars > MAX_TEXT_CACHE_BYTES) {
                    throw IOException("Cached text stream exceeded max size")
                }
                sb.append(buf, 0, bytesRead)
            }
        } finally {
            try {
                `in`.close()
            } catch (_: IOException) {
            }
        }
        return sb.toString()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun writeStringToOutputStream(out: OutputStream, str: String) {
        out.write(str.toByteArray(StandardCharsets.UTF_8))
    }
}

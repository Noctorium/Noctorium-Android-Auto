package app.noctorium.auto

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Covers for the car's menu, which Android Auto will only show from a `content://` address.
 *
 * The menu lists each song's cover as `content://<app>.carartwork/<token>`, the token standing for the cover's real
 * address, and [CarArtworkProvider] fetches it when the car asks. Only addresses the menu itself listed have a token:
 * the provider is open to Android Auto, which is another app, so it must not fetch whatever address it is handed.
 */
object CarArtwork {
    private const val KEPT = 4000

    private val known = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > KEPT
    }

    /** The authority the app's provider answers to: `<package>.carartwork`, as the library's manifest gives it. */
    fun authority(context: Context): String = "${context.packageName}.carartwork"

    /** The cover at [url] as the car can open it; null for no cover, or one that is not on the web. */
    fun uriFor(authority: String, url: String?): Uri? {
        if (url == null || !(url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true))) return null
        return Uri.Builder().scheme("content").authority(authority).appendPath(register(url)).build()
    }

    @Synchronized
    fun register(url: String): String {
        val token = tokenOf(url)
        known[token] = url
        return token
    }

    @Synchronized
    fun address(token: String): String? = known[token]

    fun tokenOf(url: String): String =
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
}

/** Hands Android Auto the covers [CarArtwork] listed, fetched once and kept a while in the app's cache. */
class CarArtworkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Covers are only read.")
        val token = uri.lastPathSegment?.takeIf { it.matches(Regex("[0-9a-f]{32}")) } ?: throw FileNotFoundException("No such cover.")
        val address = CarArtwork.address(token) ?: throw FileNotFoundException("Not a cover the car was shown.")
        val folder = File(requireNotNull(context).cacheDir, "car-artwork").apply { mkdirs() }
        val file = File(folder, token)
        if (!file.isFile || file.length() == 0L) {
            fetch(address, file)
            trim(folder)
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun fetch(address: String, into: File) {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode !in 200..299) throw FileNotFoundException("The cover's server said ${connection.responseCode}.")
            val partial = File(into.parentFile, "${into.name}.part")
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) throw FileNotFoundException("The cover is too large.")
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (!partial.renameTo(into)) throw FileNotFoundException("The cover could not be kept.")
        } finally {
            connection.disconnect()
        }
    }

    /** Keeps the newest covers and lets the rest go, so the cache stays the size of a few drives. */
    private fun trim(folder: File) {
        val files = folder.listFiles()?.filter { it.isFile } ?: return
        if (files.size <= KEEP_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - KEEP_FILES).forEach { it.delete() }
    }

    override fun getType(uri: Uri): String = "image/*"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val MAX_BYTES = 5L * 1024 * 1024
        const val KEEP_FILES = 400
    }
}

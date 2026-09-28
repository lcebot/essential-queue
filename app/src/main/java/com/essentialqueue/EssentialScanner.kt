package com.essentialqueue

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * Finds Leica Essential originals in DCIM/Camera without modifying anything.
 *
 * The rules mirror how com.miui.mediaeditor parses and validates a photo before it accepts a job,
 * so every photo listed here is one the editor will also accept:
 *  - In the XMP MiContainer directory, the item with SHA_Start is the primary image check, the item
 *    whose Mime is meta/protobuf (or whose name contains "meta") is the metadata block, and the first
 *    remaining item is the main Legend item.
 *  - Any main item named "Legend.*" counts, so new modes work without code changes. The editor treats
 *    Legend.MONOPAN as M3 and everything else as M9.
 *  - The primary hash, the main tail hash (skipped when UseMainImage=1) and the meta tail hash must all
 *    match. Result images keep the same XMP but change both the image data and the tail, so they fail
 *    this check and are never listed as originals.
 *  - Result images carry a "legend_<n>_<uuid>" EXIF tag. Their capture times mark which originals
 *    already have a result.
 *
 * Files are opened in "r" mode only.
 */
object EssentialScanner {

    data class Photo(
        val id: Long,
        val uri: Uri,
        val name: String,
        val size: Long,
        val dateModified: Long,
        val exifDateTime: String?,
        val mode: String,
        val takenMs: Long,
        var alreadyProcessed: Boolean = false,
        var sentBefore: Boolean = false,
    ) {
        /** Includes the modification time so an edited file is treated as a new entry. */
        val historyKey: String get() = "$id:$dateModified"
    }

    data class Result(val candidates: List<Photo>, val scanned: Int, val outputs: Int, val broken: Int)

    private const val HEADER_LIMIT = 1024 * 1024
    private const val MAX_SHA_LEN = 64 * 1024 * 1024

    private val itemRegex = Regex("""<MiContainer:Item\b(.*?)/?>""", RegexOption.DOT_MATCHES_ALL)
    private val attrRegex = Regex("""MiItem:([A-Za-z_]+)="([^"]*)"""")
    private val outputTagRegex = Regex("""legend_-?\d+_[0-9a-fA-F-]{36}""")
    private val exifDateRegex = Regex("""\d{4}:\d{2}:\d{2} \d{2}:\d{2}:\d{2}""")

    /**
     * @param fromMs inclusive start, compared against MediaStore.DATE_TAKEN in milliseconds
     * @param toMs   exclusive end
     */
    fun scan(context: Context, fromMs: Long, toMs: Long, progress: (done: Int, total: Int) -> Unit): Result {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_TAKEN,
        )
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.MIME_TYPE} = ?" +
            " AND ${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} < ?"
        // A result image usually shares DATE_TAKEN with its original, but look 30 days further so a
        // late-saved result is still matched. Rows past toMs are only used for that matching.
        val outputHorizon = toMs + 30L * 24 * 3600 * 1000
        val args = arrayOf("DCIM/Camera/", "image/jpeg", fromMs.toString(), outputHorizon.toString())

        data class Row(val id: Long, val name: String, val size: Long, val mod: Long, val taken: Long)
        val rows = ArrayList<Row>()
        resolver.query(collection, projection, selection, args,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC")?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val iMod = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val iTaken = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            while (c.moveToNext()) {
                rows += Row(c.getLong(iId), c.getString(iName) ?: "", c.getLong(iSize), c.getLong(iMod), c.getLong(iTaken))
            }
        }

        val candidates = ArrayList<Photo>()
        val processedDateTimes = HashSet<String>()
        var outputs = 0
        var broken = 0
        rows.forEachIndexed { idx, r ->
            progress(idx + 1, rows.size)
            val uri = ContentUris.withAppendedId(collection, r.id)
            try {
                when (val info = inspect(context, uri, outputsOnly = r.taken >= toMs)) {
                    is Inspect.Original -> if (r.taken < toMs) candidates += Photo(
                        r.id, uri, r.name, r.size, r.mod, info.exifDateTime, info.mode, r.taken)
                    is Inspect.Output -> {
                        outputs++
                        info.exifDateTime?.let { processedDateTimes += it }
                    }
                    Inspect.Broken -> if (r.taken < toMs) broken++
                    Inspect.Other -> Unit
                }
            } catch (_: Exception) {
                // An unreadable file should not stop the whole scan.
            }
        }
        // A result keeps the EXIF DateTime of its original, which is the only stable link between them.
        candidates.forEach { p ->
            p.alreadyProcessed = p.exifDateTime != null && p.exifDateTime in processedDateTimes
        }
        return Result(candidates, rows.count { it.taken < toMs }, outputs, broken)
    }

    private sealed class Inspect {
        data class Original(val mode: String, val exifDateTime: String?) : Inspect()
        data class Output(val exifDateTime: String?) : Inspect()
        /** Has a Legend marker but fails the integrity check, so the editor would reject it too. */
        object Broken : Inspect()
        object Other : Inspect()
    }

    private fun inspect(context: Context, uri: Uri, outputsOnly: Boolean): Inspect {
        context.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { fis ->
                val ch = fis.channel
                val fileLen = ch.size()
                val header = readJpegHeader(ch) ?: return Inspect.Other
                val exif = header.exif?.let { String(it, Charsets.ISO_8859_1) }
                val exifDate = exif?.let { exifDateRegex.find(it)?.value }

                if (exif != null && outputTagRegex.containsMatchIn(exif)) return Inspect.Output(exifDate)
                if (outputsOnly) return Inspect.Other

                val xmp = header.xmp?.let { String(it, Charsets.UTF_8) } ?: return Inspect.Other
                if (!xmp.contains("MiContainer")) return Inspect.Other

                var main: Map<String, String>? = null
                var primary: Map<String, String>? = null
                var meta: Map<String, String>? = null
                for (m in itemRegex.findAll(xmp)) {
                    val a = attrRegex.findAll(m.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
                    val name = a["name"] ?: ""
                    val mime = a["Mime"] ?: ""
                    when {
                        a.containsKey("SHA_Start") -> primary = a
                        mime.equals("meta/protobuf", true) || name.contains("meta", true) -> meta = a
                        main == null -> main = a
                    }
                }
                val mainName = main?.get("name") ?: return Inspect.Other
                if (!mainName.startsWith("Legend.")) return Inspect.Other
                val mode = when (mainName) {
                    "Legend.MONOPAN" -> "M3"
                    "Legend.M9" -> "M9"
                    else -> mainName.removePrefix("Legend.")
                }

                // The editor hashes SHA_length bytes starting at the FF of the first FF DA (SOS) marker.
                primary?.let { p ->
                    val len = p["SHA_length"]?.toIntOrNull() ?: 0
                    val sha = p["SHA"].orEmpty()
                    if (len > 0 && sha.isNotEmpty()) {
                        val sos = header.sosOffset
                        if (sos < 0 || !shaMatches(ch, sos, len, fileLen, sha)) return Inspect.Broken
                    }
                }
                // Some M3 photos set UseMainImage=1 and carry no RAW tail, so there is nothing to check.
                val useMainImage = main!!["UseMainImage"]?.toIntOrNull() == 1
                if (!useMainImage) {
                    if (!tailMatches(ch, fileLen, main!!)) return Inspect.Broken
                }
                meta?.let { if (!tailMatches(ch, fileLen, it)) return Inspect.Broken }

                return Inspect.Original(mode, exifDate)
            }
        }
    }

    /** Tail items are addressed from the end of the file, so their start is the file length minus Offset. */
    private fun tailMatches(ch: FileChannel, fileLen: Long, item: Map<String, String>): Boolean {
        val offset = item["Offset"]?.toLongOrNull() ?: return false
        val len = item["SHA_length"]?.toIntOrNull() ?: 0
        val sha = item["SHA"].orEmpty()
        val start = fileLen - offset
        if (offset <= 0 || start < 0) return false
        // Match the editor, which skips the check when no hash is present.
        if (len <= 0 || sha.isEmpty()) return true
        return shaMatches(ch, start, len, fileLen, sha)
    }

    private fun shaMatches(ch: FileChannel, start: Long, len: Int, fileLen: Long, sha: String): Boolean {
        if (len > MAX_SHA_LEN || start + len > fileLen) return false
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteBuffer.allocate(256 * 1024)
        var pos = start
        var remain = len.toLong()
        while (remain > 0) {
            buf.clear()
            buf.limit(minOf(buf.capacity().toLong(), remain).toInt())
            val n = ch.read(buf, pos)
            if (n <= 0) return false
            md.update(buf.array(), 0, n)
            pos += n; remain -= n
        }
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        return hex.equals(sha, ignoreCase = true)
    }

    private class Header(val exif: ByteArray?, val xmp: ByteArray?, val sosOffset: Long)

    /** Walks the JPEG segments up to the first SOS, since everything needed lives in the header. */
    private fun readJpegHeader(ch: FileChannel): Header? {
        fun readAt(pos: Long, n: Int): ByteArray? {
            val b = ByteBuffer.allocate(n)
            var p = pos
            while (b.hasRemaining()) { val r = ch.read(b, p); if (r <= 0) return null; p += r }
            return b.array()
        }
        val soi = readAt(0, 2) ?: return null
        if (soi[0] != 0xFF.toByte() || soi[1] != 0xD8.toByte()) return null
        var exif: ByteArray? = null
        var xmp: ByteArray? = null
        var sos = -1L
        var pos = 2L
        while (pos < HEADER_LIMIT) {
            val h = readAt(pos, 4) ?: break
            if (h[0] != 0xFF.toByte()) break
            val marker = h[1].toInt() and 0xFF
            if (marker == 0xDA) { sos = pos; break }
            if (marker == 0xD9) break
            val len = ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
            if (len < 2) break
            if (marker == 0xE1 && (exif == null || xmp == null)) {
                val body = readAt(pos + 4, len - 2) ?: break
                val s = String(body, 0, minOf(body.size, 40), Charsets.ISO_8859_1)
                when {
                    s.startsWith("Exif") && exif == null -> exif = body
                    s.startsWith("http://ns.adobe.com/xap/1.0/") && xmp == null -> xmp = body
                }
            }
            pos += 2 + len
        }
        // Result images store their XMP inside the EXIF XMLPacket tag instead of a separate APP1 segment.
        if (xmp == null && exif != null) {
            val s = String(exif, Charsets.ISO_8859_1)
            val i = s.indexOf("<x:xmpmeta")
            if (i >= 0) xmp = exif.copyOfRange(i, exif.size)
        }
        return Header(exif, xmp, sos)
    }
}

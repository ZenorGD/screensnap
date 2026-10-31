package com.tommyapps.recorder

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

object GifMaker {
    const val MAX_WIDTH = 360     // GIF width in pixels
    const val FPS = 10            // frames per second
    const val MAX_SECONDS = 20    // only the first 20 s of a video are used

    private class Out(val stream: OutputStream, val finish: () -> Unit, val abort: () -> Unit)

    private fun openOut(ctx: Context, baseName: String): Out {
        val name = baseName.substringBeforeLast('.') + ".gif"
        val cr = ctx.contentResolver
        val custom = Prefs.saveUri
        return when {
            custom.isNotEmpty() -> {
                val tree = Uri.parse(custom)
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val doc = DocumentsContract.createDocument(cr, parent, "image/gif", name)!!
                Out(cr.openOutputStream(doc)!!, {}, { try { DocumentsContract.deleteDocument(cr, doc) } catch (_: Exception) {} })
            }
            Build.VERSION.SDK_INT >= 29 -> {
                // Android doesn't allow GIFs in Movies, so they go to Pictures/ScreenSnap
                val col = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val v = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ScreenSnap")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = cr.insert(col, v)!!
                Out(
                    cr.openOutputStream(uri)!!,
                    { cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) },
                    { try { cr.delete(uri, null, null) } catch (_: Exception) {} }
                )
            }
            else -> {
                val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!.also { it.mkdirs() }
                val f = File(dir, name)
                Out(FileOutputStream(f), {}, { f.delete() })
            }
        }
    }

    /** Converts one MP4 to a GIF. Returns true on success. Cancellable. */
    suspend fun convert(ctx: Context, src: VideoItem, onProgress: (Float) -> Unit): Boolean {
        val mmr = MediaMetadataRetriever()
        val o = openOut(ctx, src.name)
        var ok = false
        try {
            mmr.setDataSource(ctx, src.uri)
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val frames = (minOf(durMs, MAX_SECONDS * 1000L) * FPS / 1000).toInt().coerceAtLeast(1)
            val buffered = BufferedOutputStream(o.stream, 64 * 1024)
            var writer: GifWriter? = null
            var pixels = IntArray(0)
            for (i in 0 until frames) {
                currentCoroutineContext().ensureActive()
                val frame = mmr.getFrameAtTime(i * 1_000_000L / FPS, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                val wr = writer ?: run {
                    val w = minOf(MAX_WIDTH, frame.width)
                    val h = maxOf(1, frame.height * w / frame.width)
                    pixels = IntArray(w * h)
                    GifWriter(buffered, w, h, 100 / FPS).also { writer = it }
                }
                val scaled = Bitmap.createScaledBitmap(frame, wr.width, wr.height, true)
                if (scaled !== frame) frame.recycle()
                scaled.getPixels(pixels, 0, wr.width, 0, 0, wr.width, wr.height)
                scaled.recycle()
                wr.addFrame(pixels)
                onProgress((i + 1f) / frames)
            }
            writer?.finish()
            ok = writer != null
        } finally {
            try { mmr.release() } catch (_: Exception) {}
            try { o.stream.close() } catch (_: Exception) {}
            if (ok) o.finish() else o.abort()
        }
        return ok
    }
}

/** Minimal GIF89a writer: a 256-colour palette per frame (median cut) plus LZW compression. */
class GifWriter(private val out: OutputStream, val width: Int, val height: Int, private val delayCs: Int) {
    private var started = false
    private val hist = IntArray(32768)
    private val lut = IntArray(32768)
    private val hKeys = IntArray(8209)
    private val hVals = IntArray(8209)
    private val block = ByteArray(255)
    private var blockLen = 0
    private var bitBuf = 0
    private var bitCnt = 0

    private class Entry(val key: Int, val n: Int)
    private class Box(var lo: Int, var hi: Int, var count: Long)

    private fun le(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }

    private fun writeHeader() {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        le(width); le(height)
        out.write(0x70); out.write(0); out.write(0)
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x03, 0x01, 0x00, 0x00, 0x00))   // loop forever
    }

    fun addFrame(px: IntArray) {
        if (!started) { writeHeader(); started = true }
        val (pal, idx) = quantize(px)
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 0x04, 0x04, (delayCs and 0xFF).toByte(), ((delayCs shr 8) and 0xFF).toByte(), 0x00, 0x00))
        out.write(0x2C); le(0); le(0); le(width); le(height); out.write(0x87)
        out.write(pal)
        lzw(idx)
    }

    fun finish() { out.write(0x3B); out.flush() }

    private fun key(p: Int) = (((p shr 19) and 31) shl 10) or (((p shr 11) and 31) shl 5) or ((p shr 3) and 31)

    private fun quantize(px: IntArray): Pair<ByteArray, ByteArray> {
        hist.fill(0)
        for (p in px) hist[key(p)]++
        val es = ArrayList<Entry>()
        for (k in 0 until 32768) if (hist[k] > 0) es.add(Entry(k, hist[k]))

        val boxes = ArrayList<Box>()
        boxes.add(Box(0, es.size, px.size.toLong()))
        while (boxes.size < 256) {
            var best = -1
            var bestCount = -1L
            for (i in boxes.indices) {
                val b = boxes[i]
                if (b.hi - b.lo >= 2 && b.count > bestCount) { best = i; bestCount = b.count }
            }
            if (best < 0) break
            val b = boxes[best]
            var rMin = 31; var rMax = 0; var gMin = 31; var gMax = 0; var bMin = 31; var bMax = 0
            for (j in b.lo until b.hi) {
                val k = es[j].key
                val r = k shr 10; val g = (k shr 5) and 31; val bl = k and 31
                if (r < rMin) rMin = r; if (r > rMax) rMax = r
                if (g < gMin) gMin = g; if (g > gMax) gMax = g
                if (bl < bMin) bMin = bl; if (bl > bMax) bMax = bl
            }
            val rr = rMax - rMin; val gr = gMax - gMin; val br = bMax - bMin
            val ch = if (rr >= gr && rr >= br) 0 else if (gr >= br) 1 else 2
            es.subList(b.lo, b.hi).sortBy { e ->
                when (ch) { 0 -> e.key shr 10; 1 -> (e.key shr 5) and 31; else -> e.key and 31 }
            }
            var acc = 0L
            var mid = b.lo
            val half = b.count / 2
            while (mid < b.hi - 1 && acc < half) { acc += es[mid].n; mid++ }
            if (mid == b.lo) { mid = b.lo + 1; acc = es[b.lo].n.toLong() }
            val nb = Box(mid, b.hi, b.count - acc)
            b.hi = mid
            b.count = acc
            boxes.add(nb)
        }

        val pal = ByteArray(768)
        for (bi in boxes.indices) {
            val b = boxes[bi]
            var r = 0L; var g = 0L; var bl = 0L; var n = 0L
            for (j in b.lo until b.hi) {
                val e = es[j]
                val k = e.key
                r += (k shr 10).toLong() * e.n
                g += ((k shr 5) and 31).toLong() * e.n
                bl += (k and 31).toLong() * e.n
                n += e.n
                lut[k] = bi
            }
            if (n < 1) n = 1
            pal[bi * 3] = ((r / n) * 255 / 31).toInt().toByte()
            pal[bi * 3 + 1] = ((g / n) * 255 / 31).toInt().toByte()
            pal[bi * 3 + 2] = ((bl / n) * 255 / 31).toInt().toByte()
        }
        val idx = ByteArray(px.size)
        for (i in px.indices) idx[i] = lut[key(px[i])].toByte()
        return pal to idx
    }

    private fun flushBlock() {
        if (blockLen > 0) { out.write(blockLen); out.write(block, 0, blockLen); blockLen = 0 }
    }

    private fun putByte(b: Int) { block[blockLen++] = b.toByte(); if (blockLen == 255) flushBlock() }

    private fun putCode(code: Int, size: Int) {
        bitBuf = bitBuf or (code shl bitCnt)
        bitCnt += size
        while (bitCnt >= 8) { putByte(bitBuf and 0xFF); bitBuf = bitBuf ushr 8; bitCnt -= 8 }
    }

    private fun lzw(idx: ByteArray) {
        out.write(8)                      // minimum code size
        val clear = 256
        val eoi = 257
        var size = 9
        var next = 258
        hKeys.fill(-1)
        blockLen = 0; bitBuf = 0; bitCnt = 0
        putCode(clear, size)
        var prefix = idx[0].toInt() and 0xFF
        for (i in 1 until idx.size) {
            val c = idx[i].toInt() and 0xFF
            val key = (prefix shl 8) or c
            var h = ((key shl 3) xor key) % 8209
            var found = -1
            while (hKeys[h] != -1) {
                if (hKeys[h] == key) { found = hVals[h]; break }
                h++
                if (h == 8209) h = 0
            }
            if (found >= 0) { prefix = found; continue }
            putCode(prefix, size)
            if (next == (1 shl size) && size < 12) size++
            if (next < 4096) {
                hKeys[h] = key; hVals[h] = next; next++
            } else {
                putCode(clear, size)
                hKeys.fill(-1)
                next = 258
                size = 9
            }
            prefix = c
        }
        putCode(prefix, size)
        if (next == (1 shl size) && size < 12) size++
        putCode(eoi, size)
        if (bitCnt > 0) { putByte(bitBuf and 0xFF); bitBuf = 0; bitCnt = 0 }
        flushBlock()
        out.write(0)
    }
}
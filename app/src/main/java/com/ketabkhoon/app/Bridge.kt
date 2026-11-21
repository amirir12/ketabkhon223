package com.ketabkhoon.app

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.content.FileProvider
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * Connects the web page (index.html) to the offline Persian voice.
 * Everything runs on the phone, no internet needed.
 */
class Bridge(
    private val ctx: MainActivity,
    private val web: WebView,
    private val exports: File
) {
    private val voices = setOf("amir", "ganji_adabi")
    private val exec = Executors.newSingleThreadExecutor()

    @Volatile
    private var cancelFlag = false
    private var tts: OfflineTts? = null
    private var loadedVoice: String? = null

    private fun js(code: String) {
        ctx.runOnUiThread { web.evaluateJavascript(code, null) }
    }

    private fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun copyAsset(assetPath: String, dest: File) {
        val children = ctx.assets.list(assetPath)
        if (children == null || children.isEmpty()) {
            dest.parentFile?.mkdirs()
            ctx.assets.open(assetPath).use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
        } else {
            dest.mkdirs()
            for (child in children) {
                copyAsset("$assetPath/$child", File(dest, child))
            }
        }
    }

    /** Copies the voice files out of the app package once, then loads the engine. */
    private fun engine(voice: String): OfflineTts {
        require(voice in voices) { "unknown voice" }
        val current = tts
        if (current != null && loadedVoice == voice) return current

        current?.release()
        tts = null
        loadedVoice = null

        val root = File(ctx.filesDir, "tts")
        root.mkdirs()

        val espeakDir = File(root, "espeak-ng-data")
        val espeakMarker = File(root, "espeak.ok")
        if (!espeakMarker.exists()) {
            espeakDir.deleteRecursively()
            copyAsset("tts/espeak-ng-data", espeakDir)
            espeakMarker.writeText("1")
        }

        val voiceDir = File(root, voice)
        val voiceMarker = File(root, "$voice.ok")
        if (!voiceMarker.exists()) {
            voiceDir.deleteRecursively()
            copyAsset("tts/$voice", voiceDir)
            voiceMarker.writeText("1")
        }

        val vits = OfflineTtsVitsModelConfig(
            model = File(voiceDir, "model.onnx").absolutePath,
            tokens = File(voiceDir, "tokens.txt").absolutePath,
            dataDir = espeakDir.absolutePath
        )
        val modelConfig = OfflineTtsModelConfig(
            vits = vits,
            numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
            debug = false,
            provider = "cpu"
        )
        val created = OfflineTts(config = OfflineTtsConfig(model = modelConfig))
        tts = created
        loadedVoice = voice
        return created
    }

    private fun toPcm16(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun wavHeader(sampleRate: Int, dataLen: Long): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray())
        b.putInt((36 + dataLen).toInt())
        b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray())
        b.putInt(16)
        b.putShort(1)
        b.putShort(1)
        b.putInt(sampleRate)
        b.putInt(sampleRate * 2)
        b.putShort(2)
        b.putShort(16)
        b.put("data".toByteArray())
        b.putInt(dataLen.toInt())
        return b.array()
    }

    /** Loads the voice in the background so the first sentence starts quickly. */
    @JavascriptInterface
    fun warmUp(voice: String) {
        exec.execute {
            try {
                engine(voice)
                js("window.__warmDone && window.__warmDone()")
            } catch (t: Throwable) {
                js("window.__warmFail && window.__warmFail(${JSONObject.quote(t.message ?: "error")})")
            }
        }
    }

    /** Reads one sentence aloud: returns raw audio to the page. */
    @JavascriptInterface
    fun synth(id: Int, text: String, voice: String, speed: Double) {
        exec.execute {
            try {
                val e = engine(voice)
                val audio = e.generate(text, 0, speed.toFloat())
                val b64 = Base64.encodeToString(toPcm16(audio.samples), Base64.NO_WRAP)
                js("window.__ttsDone($id,'$b64',${audio.sampleRate})")
            } catch (t: Throwable) {
                js("window.__ttsFail($id,${JSONObject.quote(t.message ?: "error")})")
            }
        }
    }

    /** Builds one audio file (WAV) from many sentences. */
    @JavascriptInterface
    fun buildAudio(name: String, textsJson: String, voice: String, speed: Double) {
        cancelFlag = false
        exec.execute {
            val safe = safeName(name)
            val file = File(exports, safe)
            var stopped = false
            try {
                val texts = JSONArray(textsJson)
                val e = engine(voice)
                val sr = e.sampleRate()
                val gap = ByteArray((sr * 0.35).toInt() * 2)
                RandomAccessFile(file, "rw").use { raf ->
                    raf.setLength(0)
                    raf.write(ByteArray(44))
                    var bytes = 0L
                    for (i in 0 until texts.length()) {
                        if (cancelFlag) {
                            stopped = true
                            break
                        }
                        val audio = e.generate(texts.getString(i), 0, speed.toFloat())
                        val pcm = toPcm16(audio.samples)
                        raf.write(pcm)
                        raf.write(gap)
                        bytes += pcm.size + gap.size
                        js("window.__buildProgress(${i + 1},${texts.length()})")
                    }
                    if (!stopped) {
                        raf.seek(0)
                        raf.write(wavHeader(sr, bytes))
                    }
                }
                if (stopped) {
                    file.delete()
                    js("window.__buildFail('stopped')")
                } else {
                    js("window.__buildDone(${JSONObject.quote(safe)})")
                }
            } catch (t: Throwable) {
                file.delete()
                js("window.__buildFail(${JSONObject.quote(t.message ?: "error")})")
            }
        }
    }

    @JavascriptInterface
    fun cancelBuild() {
        cancelFlag = true
    }

    /** Copies the finished file into the phone's Download folder. */
    @JavascriptInterface
    fun saveToDownloads(name: String) {
        Thread {
            try {
                val src = File(exports, safeName(name))
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, src.name)
                    put(MediaStore.Downloads.MIME_TYPE, if (src.name.endsWith(".json")) "application/json" else "audio/wav")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("no uri")
                ctx.contentResolver.openOutputStream(uri)!!.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                }
                js("window.__fileMsg('فایل در پوشهٔ Download گوشی ذخیره شد.',false)")
            } catch (t: Throwable) {
                js("window.__fileMsg('ذخیره‌سازی ممکن نشد.',true)")
            }
        }.start()
    }

    @Volatile
    private var pending: String? = null

    /** Returns the file that another app sent us ("name|file"), once. */
    @JavascriptInterface
    fun takeIncoming(): String {
        val p = pending
        pending = null
        return p ?: ""
    }

    fun receiveUri(uri: Uri) {
        Thread {
            try {
                val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (col >= 0 && c.moveToFirst()) c.getString(col) else null
                } ?: "book"
                val ext = name.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "bin" }
                val file = File(exports, "incoming-" + System.currentTimeMillis() + "." + ext)
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(file).use { out -> input.copyTo(out) }
                }
                pending = name.replace("|", "_") + "|" + file.name
                js("window.__checkIncoming && window.__checkIncoming()")
            } catch (t: Throwable) {
                js("window.__fileMsg('باز کردن فایل ممکن نشد.',true)")
            }
        }.start()
    }

    fun receiveText(text: String) {
        Thread {
            try {
                val file = File(exports, "incoming-" + System.currentTimeMillis() + ".txt")
                file.writeText(text, Charsets.UTF_8)
                pending = "متن دریافتی|" + file.name
                js("window.__checkIncoming && window.__checkIncoming()")
            } catch (t: Throwable) {
                js("window.__fileMsg('باز کردن متن ممکن نشد.',true)")
            }
        }.start()
    }

    @JavascriptInterface
    fun deleteExport(name: String) {
        try {
            File(exports, safeName(name)).delete()
        } catch (t: Throwable) {
        }
    }

    /** Writes a piece of a text file (used for backups). */
    @JavascriptInterface
    fun writeExport(name: String, chunk: String, first: Boolean) {
        try {
            val f = File(exports, safeName(name))
            if (first) f.writeText(chunk, Charsets.UTF_8) else f.appendText(chunk, Charsets.UTF_8)
        } catch (t: Throwable) {
            js("window.__fileMsg('نوشتن فایل ممکن نشد.',true)")
        }
    }

    /** Shares a picture (a quote card) through the Android share sheet. */
    @JavascriptInterface
    fun shareImage(name: String, b64: String) {
        try {
            val f = File(exports, safeName(name))
            f.writeBytes(Base64.decode(b64, Base64.DEFAULT))
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.runOnUiThread {
                ctx.startActivity(Intent.createChooser(send, "اشتراک‌گذاری تصویر"))
            }
        } catch (t: Throwable) {
            js("window.__fileMsg('اشتراک‌گذاری تصویر ممکن نشد.',true)")
        }
    }

    /** Keeps playback alive in the background and shows the lock-screen controls. */
    @JavascriptInterface
    fun playbackState(playing: Boolean, title: String, sub: String) {
        try {
            val i = Intent(ctx, PlaybackService::class.java)
                .putExtra("playing", playing)
                .putExtra("title", title)
                .putExtra("sub", sub)
            if (playing) ctx.startForegroundService(i) else ctx.startService(i)
        } catch (t: Throwable) {
        }
    }

    @JavascriptInterface
    fun playbackStop() {
        try {
            ctx.stopService(Intent(ctx, PlaybackService::class.java))
        } catch (t: Throwable) {
        }
    }

    @JavascriptInterface
    fun askNotifications() {
        ctx.runOnUiThread { ctx.askNotifications() }
    }

    /** Opens a web page (for example a Google search) in the phone's browser. */
    @JavascriptInterface
    fun openUrl(url: String) {
        if (!(url.startsWith("https://") || url.startsWith("http://"))) return
        ctx.runOnUiThread {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (t: Throwable) {
            }
        }
    }

    /** Opens the Android share sheet with a text message. */
    @JavascriptInterface
    fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        ctx.runOnUiThread {
            ctx.startActivity(Intent.createChooser(send, "اشتراک‌گذاری"))
        }
    }

    /** Opens the email app with a ready message (no server needed). */
    @JavascriptInterface
    fun sendEmail(to: String, subject: String, body: String) {
        val mail = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        ctx.runOnUiThread {
            try {
                ctx.startActivity(mail)
            } catch (t: Throwable) {
            }
        }
    }

    /** Opens the Android share sheet for the finished file. */
    @JavascriptInterface
    fun shareFile(name: String) {
        try {
            val file = File(exports, safeName(name))
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.runOnUiThread {
                ctx.startActivity(Intent.createChooser(send, "اشتراک‌گذاری فایل صوتی"))
            }
        } catch (t: Throwable) {
            js("window.__fileMsg('اشتراک‌گذاری ممکن نشد.',true)")
        }
    }
}

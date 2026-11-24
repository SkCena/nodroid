package dev.nodroid.nodes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import dev.nodroid.database.RoomKvStore
import dev.nodroid.engine.ExecutionContext
import dev.nodroid.engine.NodeExecutor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Adapts the Room KV store to the [KvStore] interface used by the KV node. */
class RoomKvAdapter(private val room: RoomKvStore) : KvStore {
    override suspend fun get(key: String): String? = room.get(key)
    override suspend fun set(key: String, value: String) = room.set(key, value)
    override suspend fun delete(key: String) = room.delete(key)
    override suspend fun increment(key: String, by: Long): Long = room.increment(key, by)
}

/** Executors that need an Android [Context]: OCR, image resize, notification, SAF files, clipboard, intents. */
class AndroidNodeExecutors(private val context: Context) {
    val ocr: NodeExecutor = NodeExecutor { ctx ->
        val uriString = ctx.rendered("imageUri")
        require(uriString.isNotBlank()) { "OCR image URI is blank" }
        val script = ctx.cfg("script", "latin")
        val bitmap = loadBitmap(uriString)
        val options = if (script == "devanagari") {
            DevanagariTextRecognizerOptions.Builder().build()
        } else {
            TextRecognizerOptions.DEFAULT_OPTIONS
        }
        val recognizer = TextRecognition.getClient(options)
        try {
            val text = suspendCancellableCoroutine<String> { cont ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { result -> cont.resume(result.text) }
                    .addOnFailureListener { e -> cont.resumeWithException(e) }
            }
            mapOf<String, Any?>("text" to text, "length" to text.length, "script" to script)
        } finally {
            recognizer.close()
        }
    }

    val imageResize: NodeExecutor = NodeExecutor { ctx ->
        val uriString = ctx.rendered("imageUri")
        require(uriString.isNotBlank()) { "Image URI is blank" }
        val maxDimension = dev.nodroid.engine.ConfigReader.int(ctx.node.config, "maxDimension", 1280).coerceIn(64, 8192)
        val quality = dev.nodroid.engine.ConfigReader.int(ctx.node.config, "quality", 85).coerceIn(1, 100)
        val formatName = ctx.cfg("format", "JPEG").uppercase()
        val bitmap = loadBitmap(uriString)
        val scale = minOf(1.0f, maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height).toFloat())
        val resized = if (scale < 1.0f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else {
            bitmap
        }
        val format = when (formatName) {
            "PNG" -> Bitmap.CompressFormat.PNG
            "WEBP" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.JPEG
        }
        val extension = when (formatName) { "PNG" -> "png"; "WEBP" -> "webp"; else -> "jpg" }
        val outFile = File(context.cacheDir, "nodroid-resized-" + System.currentTimeMillis() + "." + extension)
        FileOutputStream(outFile).use { stream -> resized.compress(format, quality, stream) }
        if (resized !== bitmap) resized.recycle()
        mapOf<String, Any?>(
            "uri" to Uri.fromFile(outFile).toString(),
            "width" to resized.width, "height" to resized.height,
            "sizeBytes" to outFile.length(), "format" to formatName,
        )
    }

    val notify: NodeExecutor = NodeExecutor { ctx ->
        val title = ctx.rendered("title", "NodRoid")
        val message = ctx.rendered("message")
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "nodroid_alerts"
        val channel = NotificationChannel(channelId, "NodRoid Alerts", NotificationManager.IMPORTANCE_DEFAULT)
        manager.createNotificationChannel(channel)
        val notification = Notification.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        manager.notify(id, notification)
        mapOf<String, Any?>("notified" to true, "notificationId" to id, "title" to title)
    }

    val fileRead: NodeExecutor = NodeExecutor { ctx ->
        val uriString = ctx.rendered("uri")
        require(uriString.isNotBlank()) { "File URI is blank" }
        val uri = Uri.parse(uriString)
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: throw IllegalStateException("Cannot open file: " + uriString)
        mapOf<String, Any?>("text" to text, "length" to text.length)
    }

    val fileWrite: NodeExecutor = NodeExecutor { ctx ->
        val uriString = ctx.rendered("uri")
        val content = ctx.rendered("content")
        require(uriString.isNotBlank()) { "File URI is blank" }
        val append = dev.nodroid.engine.ConfigReader.boolean(ctx.node.config, "append", false)
        val uri = Uri.parse(uriString)
        context.contentResolver.openOutputStream(uri, if (append) "wa" else "w")?.use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("Cannot open file for writing: " + uriString)
        mapOf<String, Any?>("written" to true, "bytes" to content.toByteArray(Charsets.UTF_8).size, "append" to append)
    }

    val clipboard: NodeExecutor = NodeExecutor { ctx ->
        val text = ctx.rendered("text")
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("NodRoid", text))
        mapOf<String, Any?>("copied" to true, "length" to text.length)
    }

    val intentOpener: NodeExecutor = NodeExecutor { ctx ->
        val url = ctx.rendered("url")
        require(url.isNotBlank()) { "URL is blank" }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            throw IllegalStateException("No app can open: " + url)
        }
        mapOf<String, Any?>("opened" to true, "url" to url)
    }

    private fun loadBitmap(uriString: String): Bitmap {
        val uri = Uri.parse(uriString)
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open image: " + uriString)
        return stream.use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalStateException("Cannot decode image: " + uriString)
    }
}

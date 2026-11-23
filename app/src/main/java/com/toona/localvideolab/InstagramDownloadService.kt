package com.toona.localvideolab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import com.chaquo.python.Python
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

class InstagramDownloadService : Service() {
    companion object {
        const val ACTION_START = "com.toona.localvideolab.action.START_INSTAGRAM_VIDEO"
        const val ACTION_CANCEL = "com.toona.localvideolab.action.CANCEL_INSTAGRAM_VIDEO"
        const val EXTRA_SHARE_TEXT = "share_text"
        const val EXTRA_SESSIONID = "sessionid"

        private const val CHANNEL_ID = "video_downloads"
        private const val NOTIFICATION_ID = 2404
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null
    private val pendingUri = AtomicReference<Uri?>(null)

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "视频下载",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示 Instagram 视频解析与下载进度"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> cancelDownload()
            ACTION_START -> {
                val text = intent.getStringExtra(EXTRA_SHARE_TEXT).orEmpty()
                val sessionid = intent.getStringExtra(EXTRA_SESSIONID).orEmpty()
                if (text.isNotBlank() && downloadJob?.isActive != true) {
                    startDownload(text, sessionid)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startDownload(shareText: String, sessionid: String) {
        startForeground(
            NOTIFICATION_ID,
            notification("正在准备…", null, showCancel = true),
        )
        DownloadBus.state.value = DownloadSnapshot(
            phase = DownloadPhase.RESOLVING,
            message = "正在解析 Instagram 视频…",
            platform = "instagram",
        )

        downloadJob = scope.launch {
            try {
                val metadata = resolveMetadata(shareText, sessionid)
                val shortcode = metadata.optString("shortcode").ifBlank {
                    metadata.optString("media_id", "video")
                }
                val title = metadata.optString("desc").ifBlank { "Instagram_$shortcode" }

                DownloadBus.state.value = DownloadSnapshot(
                    phase = DownloadPhase.DOWNLOADING,
                    message = "正在下载视频",
                    progress = 0,
                    totalBytes = metadata.optLong("size", 0),
                    title = title,
                    platform = "instagram",
                )
                updateNotification("正在下载：${title.take(24)}", 0, true)

                val uri = downloadToMediaStore(metadata, title, shortcode)
                pendingUri.set(uri)
                DownloadBus.state.value = DownloadBus.state.value.copy(
                    phase = DownloadPhase.VERIFYING,
                    message = "正在检查音视频轨道…",
                    progress = 100,
                    outputUri = uri.toString(),
                )
                updateNotification("正在验收视频…", 100, false)
                val verification = verifyMedia(uri)
                publishPending(uri)
                pendingUri.set(null)
                DownloadBus.state.value = DownloadBus.state.value.copy(
                    phase = DownloadPhase.SUCCESS,
                    message = "已保存到相册 / Movies/Toona",
                    outputUri = uri.toString(),
                    verification = verification,
                )
                updateNotification("下载完成：$verification", 100, false)
            } catch (cancelled: CancellationException) {
                cleanupPending()
                DownloadBus.state.value = DownloadSnapshot(
                    phase = DownloadPhase.CANCELED,
                    message = "下载已取消，未完成的文件已删除",
                    platform = "instagram",
                )
                updateNotification("下载已取消", null, false)
            } catch (error: Throwable) {
                cleanupPending()
                DownloadBus.state.value = DownloadSnapshot(
                    phase = DownloadPhase.ERROR,
                    message = "${error::class.java.simpleName}: ${error.message}",
                    platform = "instagram",
                )
                updateNotification("下载失败：${error.message.orEmpty().take(50)}", null, false)
            } finally {
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
    }

    private fun cancelDownload() {
        downloadJob?.cancel()
        DownloadBus.state.value = DownloadBus.state.value.copy(
            message = "正在取消并清理临时文件…",
            progress = null,
        )
    }

    private fun resolveMetadata(shareText: String, sessionid: String): JSONObject {
        val result = Python.getInstance()
            .getModule("instagram_resolver")
            .callAttr("resolve", shareText, sessionid)
            .toString()
        val metadata = JSONObject(result)
        if (!metadata.optBoolean("ok")) {
            throw IOException(metadata.optString("error", "Instagram 解析失败"))
        }
        if (metadata.optString("kind") != "video") {
            throw IOException("解析结果不是可下载的视频")
        }
        return metadata
    }

    private suspend fun downloadToMediaStore(
        metadata: JSONObject,
        title: String,
        shortcode: String,
    ): Uri {
        val urls = metadata.getJSONArray("urls")
        val headersObject = metadata.optJSONObject("headers")
        val headers = buildMap {
            headersObject?.keys()?.forEach { key -> put(key, headersObject.getString(key)) }
        }
        var lastError: Throwable? = null

        for (index in 0 until urls.length()) {
            coroutineContext.ensureActive()
            var connection: HttpURLConnection? = null
            var uri: Uri? = null
            try {
                connection = (URL(urls.getString(index)).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    headers.forEach { (name, value) -> setRequestProperty(name, value) }
                    setRequestProperty("Accept", "*/*")
                    connect()
                }
                if (connection.responseCode !in 200..299) {
                    throw IOException("CDN HTTP ${connection.responseCode}")
                }
                val expected = listOf(
                    connection.contentLengthLong,
                    metadata.optLong("size", 0),
                ).firstOrNull { it > 0 } ?: 0
                uri = createPendingVideo(title, shortcode)
                pendingUri.set(uri)
                val output = contentResolver.openOutputStream(uri, "w")
                    ?: throw IOException("无法打开 MediaStore 输出流")

                connection.inputStream.use { input ->
                    output.use { sink ->
                        val buffer = ByteArray(256 * 1024)
                        var downloaded = 0L
                        var lastUpdateAt = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            sink.write(buffer, 0, count)
                            downloaded += count
                            val now = System.currentTimeMillis()
                            if (now - lastUpdateAt >= 250) {
                                val progress = if (expected > 0) {
                                    ((downloaded * 100.0) / expected).roundToInt().coerceIn(0, 99)
                                } else {
                                    null
                                }
                                DownloadBus.state.value = DownloadBus.state.value.copy(
                                    progress = progress,
                                    bytesDownloaded = downloaded,
                                    totalBytes = expected,
                                )
                                updateNotification("正在下载…", progress, true)
                                lastUpdateAt = now
                            }
                        }
                        sink.flush()
                        if (downloaded <= 0) throw IOException("CDN 返回了空文件")
                        DownloadBus.state.value = DownloadBus.state.value.copy(
                            progress = 100,
                            bytesDownloaded = downloaded,
                            totalBytes = if (expected > 0) expected else downloaded,
                        )
                    }
                }
                return uri
            } catch (cancelled: CancellationException) {
                uri?.let { contentResolver.delete(it, null, null) }
                pendingUri.compareAndSet(uri, null)
                throw cancelled
            } catch (error: Throwable) {
                lastError = error
                uri?.let { contentResolver.delete(it, null, null) }
                pendingUri.compareAndSet(uri, null)
            } finally {
                connection?.disconnect()
            }
        }
        throw IOException("所有 Instagram CDN 源均失败", lastError)
    }

    private fun createPendingVideo(title: String, shortcode: String): Uri {
        val safeTitle = title
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n]+"), "_")
            .trim(' ', '.', '_')
            .ifBlank { "Instagram视频" }
            .take(72)
        val safeId = shortcode.replace(Regex("[^A-Za-z0-9_-]+"), "_").take(40)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "${safeTitle}_${safeId}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/Toona",
            )
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        return contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法在 MediaStore 创建视频")
    }

    private fun publishPending(uri: Uri) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.IS_PENDING, 0)
        }
        contentResolver.update(uri, values, null, null)
    }

    private fun verifyMedia(uri: Uri): String {
        val extractor = MediaExtractor()
        var hasVideo = false
        var hasAudio = false
        var videoCodec = "unknown"
        var audioCodec = "unknown"
        try {
            extractor.setDataSource(this, uri, null)
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    .orEmpty()
                when {
                    mime.startsWith("video/") -> {
                        hasVideo = true
                        videoCodec = mime.removePrefix("video/")
                    }
                    mime.startsWith("audio/") -> {
                        hasAudio = true
                        audioCodec = mime.removePrefix("audio/")
                    }
                }
            }
        } finally {
            extractor.release()
        }
        if (!hasVideo || !hasAudio) {
            throw IOException("验收失败：视频轨=$hasVideo，音频轨=$hasAudio")
        }

        val retriever = MediaMetadataRetriever()
        val width: String
        val height: String
        val durationMs: String
        try {
            retriever.setDataSource(this, uri)
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: "?"
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: "?"
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: "0"
        } finally {
            retriever.release()
        }
        val duration = durationMs.toLongOrNull()?.div(1000.0) ?: 0.0
        return "[PASS] ${width}x$height | $videoCodec + $audioCodec | %.1fs".format(duration)
    }

    private fun cleanupPending() {
        pendingUri.getAndSet(null)?.let { uri ->
            runCatching { contentResolver.delete(uri, null, null) }
        }
    }

    private fun updateNotification(text: String, progress: Int?, showCancel: Boolean) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            notification(text, progress, showCancel),
        )
    }

    private fun notification(text: String, progress: Int?, showCancel: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Toona")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(showCancel)
        if (progress != null) {
            builder.setProgress(100, progress, false)
        } else if (showCancel) {
            builder.setProgress(0, 0, true)
        }
        if (showCancel) {
            val cancel = PendingIntent.getService(
                this,
                2,
                Intent(this, InstagramDownloadService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(Notification.Action.Builder(null, "取消", cancel).build())
        }
        return builder.build()
    }
}

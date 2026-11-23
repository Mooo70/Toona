package com.toona.localvideolab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
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
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

class YoutubeDownloadService : Service() {
    companion object {
        const val ACTION_START = "com.toona.localvideolab.action.START_YOUTUBE"
        const val ACTION_CANCEL = "com.toona.localvideolab.action.CANCEL_YOUTUBE"
        const val EXTRA_SHARE_TEXT = "share_text"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_CODEC = "codec"

        private const val CHANNEL_ID = "video_downloads"
        private const val NOTIFICATION_ID = 2403
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null
    private var workDirectory: File? = null
    private val pendingUri = AtomicReference<Uri?>(null)
    private val activeConnection = AtomicReference<HttpURLConnection?>(null)
    private val activeInput = AtomicReference<InputStream?>(null)

    override fun onCreate() {
        super.onCreate()
        cleanupStaleWorkDirectories()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "视频下载",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示本地视频解析、下载与合并进度"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> cancelDownload()
            ACTION_START -> {
                val text = intent.getStringExtra(EXTRA_SHARE_TEXT).orEmpty()
                val quality = intent.getStringExtra(EXTRA_QUALITY).orEmpty().ifBlank { "best" }
                val codec = intent.getStringExtra(EXTRA_CODEC).orEmpty().ifBlank { "h264" }
                if (text.isNotBlank() && downloadJob?.isActive != true) {
                    startDownload(text, quality, codec)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        activeInput.getAndSet(null)?.runCatching { close() }
        activeConnection.getAndSet(null)?.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    private fun startDownload(shareText: String, quality: String, codec: String) {
        startForeground(
            NOTIFICATION_ID,
            notification("正在准备…", null, showCancel = true),
        )
        DownloadBus.state.value = DownloadSnapshot(
            phase = DownloadPhase.RESOLVING,
            message = "正在解析 YouTube 格式并按画质、编码偏好选择轨道…",
            platform = "youtube",
        )

        downloadJob = scope.launch {
            try {
                val metadata = resolveMetadata(shareText, quality, codec)
                val title = metadata.optString("title").ifBlank {
                    "YouTube_${metadata.optString("id", "video")}"
                }
                val video = metadata.getJSONObject("video")
                val audio = metadata.getJSONObject("audio")
                val expectedTotal = video.optLong("filesize", 0) + audio.optLong("filesize", 0)
                val selected = buildString {
                    append(video.optInt("width", 0))
                    append('x')
                    append(video.optInt("height", 0))
                    append(" · ")
                    append(video.optString("vcodec", "video"))
                    append(" + ")
                    append(audio.optString("acodec", "audio"))
                }
                val selectionSummary = metadata.optString("selection_summary")
                val selectionWarning = metadata.optString("selection_warning")

                val tempDir = File(cacheDir, "youtube-${System.currentTimeMillis()}").apply {
                    if (!mkdirs() && !isDirectory) throw IOException("无法创建本地合并缓存")
                }
                workDirectory = tempDir
                val videoFile = File(tempDir, "video.${video.optString("ext", "mp4")}")
                val audioFile = File(tempDir, "audio.${audio.optString("ext", "m4a")}")

                DownloadBus.state.value = DownloadSnapshot(
                    phase = DownloadPhase.DOWNLOADING,
                    message = buildString {
                        append(selectionSummary)
                        if (selectionWarning.isNotBlank()) append("\n").append(selectionWarning)
                        append("\n正在下载视频轨：").append(selected)
                    },
                    progress = 0,
                    totalBytes = expectedTotal,
                    title = title,
                    platform = "youtube",
                )
                updateNotification("正在下载视频轨：${title.take(20)}", 0, true)
                val videoBytes = downloadTrack(
                    track = video,
                    target = videoFile,
                    completedBefore = 0,
                    expectedTotal = expectedTotal,
                    label = "视频轨",
                )
                val audioBytes = downloadTrack(
                    track = audio,
                    target = audioFile,
                    completedBefore = videoBytes,
                    expectedTotal = expectedTotal,
                    label = "音频轨",
                )

                DownloadBus.state.value = DownloadBus.state.value.copy(
                    phase = DownloadPhase.MERGING,
                    message = "正在手机本地无损合并视频轨与音频轨…",
                    progress = null,
                    bytesDownloaded = videoBytes + audioBytes,
                    totalBytes = videoBytes + audioBytes,
                )
                updateNotification("正在本地合并音视频…", null, true)
                val uri = createPendingVideo(
                    title = title,
                    videoId = metadata.optString("id", "youtube"),
                    actualHeight = metadata.optInt("actual_height", 0),
                    actualCodec = metadata.optString("actual_codec", "video"),
                )
                pendingUri.set(uri)
                muxTracks(videoFile, audioFile, uri)

                DownloadBus.state.value = DownloadBus.state.value.copy(
                    phase = DownloadPhase.VERIFYING,
                    message = "正在检查合并后的音视频轨道…",
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
                    verification = buildString {
                        append(verification)
                        append("\n").append(selectionSummary)
                        if (selectionWarning.isNotBlank()) append("\n⚠ ").append(selectionWarning)
                    },
                )
                updateNotification("下载完成：$verification", 100, false)
            } catch (cancelled: CancellationException) {
                cleanupPending()
                DownloadBus.state.value = DownloadSnapshot(
                    phase = DownloadPhase.CANCELED,
                    message = "下载已取消，音视频临时文件已删除",
                    platform = "youtube",
                )
                updateNotification("下载已取消", null, false)
            } catch (error: Throwable) {
                cleanupPending()
                DownloadBus.state.value = DownloadSnapshot(
                    phase = DownloadPhase.ERROR,
                    message = "${error::class.java.simpleName}: ${error.message}",
                    platform = "youtube",
                )
                updateNotification("下载失败：${error.message.orEmpty().take(50)}", null, false)
            } finally {
                activeInput.getAndSet(null)?.runCatching { close() }
                activeConnection.getAndSet(null)?.disconnect()
                cleanupWorkDirectory()
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
        activeInput.getAndSet(null)?.runCatching { close() }
        activeConnection.getAndSet(null)?.disconnect()
    }

    private fun resolveMetadata(shareText: String, quality: String, codec: String): JSONObject {
        val result = Python.getInstance()
            .getModule("youtube_resolver")
            .callAttr("resolve", shareText, quality, codec)
            .toString()
        val metadata = JSONObject(result)
        if (!metadata.optBoolean("ok")) {
            throw IOException(metadata.optString("error", "YouTube 解析失败"))
        }
        return metadata
    }

    private suspend fun downloadTrack(
        track: JSONObject,
        target: File,
        completedBefore: Long,
        expectedTotal: Long,
        label: String,
    ): Long {
        val urls = buildList {
            track.optJSONArray("urls")?.let { array ->
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
            track.optString("url").takeIf { it.isNotBlank() }?.let(::add)
        }.distinct()
        if (urls.isEmpty()) throw IOException("$label 缺少下载地址")

        var lastError: Throwable? = null
        urls.forEachIndexed { routeIndex, routeUrl ->
            coroutineContext.ensureActive()
            target.delete()
            var connection: HttpURLConnection? = null
            var input: InputStream? = null
            try {
                val routeLabel = if (urls.size > 1) "（线路 ${routeIndex + 1}/${urls.size}）" else ""
                DownloadBus.state.value = DownloadBus.state.value.copy(
                    message = "正在连接$label$routeLabel",
                )
                connection = (URL(routeUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    instanceFollowRedirects = true
                    val headers = track.optJSONObject("headers")
                    headers?.keys()?.forEach { name ->
                        setRequestProperty(name, headers.getString(name))
                    }
                    setRequestProperty("Accept-Encoding", "identity")
                    connect()
                }
                activeConnection.set(connection)
                if (connection.responseCode !in 200..299) {
                    throw IOException("$label CDN HTTP ${connection.responseCode}")
                }
                val trackExpected = listOf(
                    connection.contentLengthLong,
                    track.optLong("filesize", 0),
                ).firstOrNull { it > 0 } ?: 0
                val total = if (expectedTotal > 0) expectedTotal else completedBefore + trackExpected
                var downloaded = 0L
                var lastUpdateAt = 0L
                input = connection.inputStream
                activeInput.set(input)
                input.use { source ->
                    target.outputStream().buffered(256 * 1024).use { sink ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            sink.write(buffer, 0, count)
                            downloaded += count
                            val now = System.currentTimeMillis()
                            if (now - lastUpdateAt >= 250) {
                                val cumulative = completedBefore + downloaded
                                val progress = if (total > 0) {
                                    ((cumulative * 100.0) / total).roundToInt().coerceIn(0, 99)
                                } else {
                                    null
                                }
                                DownloadBus.state.value = DownloadBus.state.value.copy(
                                    message = "正在下载$label$routeLabel",
                                    progress = progress,
                                    bytesDownloaded = cumulative,
                                    totalBytes = total,
                                )
                                updateNotification("正在下载$label$routeLabel…", progress, true)
                                lastUpdateAt = now
                            }
                        }
                    }
                }
                if (downloaded <= 0 || !target.isFile) throw IOException("${label}返回了空文件")
                return downloaded
            } catch (error: Throwable) {
                coroutineContext.ensureActive()
                lastError = error
                target.delete()
            } finally {
                activeInput.compareAndSet(input, null)
                input?.runCatching { close() }
                activeConnection.compareAndSet(connection, null)
                connection?.disconnect()
            }
        }
        throw IOException("$label 所有 CDN 线路均失败", lastError)
    }

    private suspend fun muxTracks(videoFile: File, audioFile: File, outputUri: Uri) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)
            val (videoSourceTrack, videoFormat) = findTrack(videoExtractor, "video/")
            val (audioSourceTrack, audioFormat) = findTrack(audioExtractor, "audio/")
            videoExtractor.selectTrack(videoSourceTrack)
            audioExtractor.selectTrack(audioSourceTrack)

            val descriptor = contentResolver.openFileDescriptor(outputUri, "w")
                ?: throw IOException("无法打开 MediaStore 合并输出")
            descriptor.use { parcel ->
                val localMuxer = MediaMuxer(
                    parcel.fileDescriptor,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                )
                var localStarted = false
                try {
                    val videoTargetTrack = localMuxer.addTrack(videoFormat)
                    val audioTargetTrack = localMuxer.addTrack(audioFormat)
                    localMuxer.start()
                    localStarted = true

                    val capacity = maxOf(
                        maxInputSize(videoFormat),
                        maxInputSize(audioFormat),
                        4 * 1024 * 1024,
                    ).coerceAtMost(64 * 1024 * 1024)
                    val buffer = ByteBuffer.allocateDirect(capacity)
                    val info = MediaCodec.BufferInfo()
                    var videoDone = false
                    var audioDone = false

                    while (!videoDone || !audioDone) {
                        coroutineContext.ensureActive()
                        val videoTime = if (videoDone) Long.MAX_VALUE else videoExtractor.sampleTime
                        val audioTime = if (audioDone) Long.MAX_VALUE else audioExtractor.sampleTime
                        if (videoTime < 0) videoDone = true
                        if (audioTime < 0) audioDone = true
                        if (videoDone && audioDone) break

                        val useVideo = !videoDone && (audioDone || videoTime <= audioTime)
                        val extractor = if (useVideo) videoExtractor else audioExtractor
                        val targetTrack = if (useVideo) videoTargetTrack else audioTargetTrack
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            if (useVideo) videoDone = true else audioDone = true
                            continue
                        }
                        if (size > buffer.capacity()) {
                            throw IOException("媒体样本过大，无法在本机合并")
                        }
                        val sourceFlags = extractor.sampleFlags
                        if (sourceFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) {
                            throw IOException("不支持合并 DRM 加密轨道")
                        }
                        var muxerFlags = 0
                        if (sourceFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                            muxerFlags = muxerFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                        }
                        if (sourceFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
                            muxerFlags = muxerFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                        }
                        info.set(
                            0,
                            size,
                            extractor.sampleTime.coerceAtLeast(0),
                            muxerFlags,
                        )
                        buffer.position(0)
                        buffer.limit(size)
                        localMuxer.writeSampleData(targetTrack, buffer, info)
                        extractor.advance()
                    }
                    localMuxer.stop()
                    localStarted = false
                } finally {
                    if (localStarted) runCatching { localMuxer.stop() }
                    localMuxer.release()
                }
            }
        } finally {
            videoExtractor.release()
            audioExtractor.release()
        }
    }

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Pair<Int, MediaFormat> {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(mimePrefix)) return index to format
        }
        throw IOException("临时文件缺少 ${mimePrefix.removeSuffix("/")} 轨")
    }

    private fun maxInputSize(format: MediaFormat): Int =
        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(0)
        } else {
            0
        }

    private fun createPendingVideo(
        title: String,
        videoId: String,
        actualHeight: Int,
        actualCodec: String,
    ): Uri {
        val safeTitle = title
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n]+"), "_")
            .trim(' ', '.', '_')
            .ifBlank { "YouTube视频" }
            .take(72)
        val safeId = videoId.replace(Regex("[^A-Za-z0-9_-]+"), "_").take(40)
        val safeCodec = actualCodec
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "video" }
        val formatSuffix = buildString {
            if (actualHeight > 0) append("_${actualHeight}p")
            append("_").append(safeCodec)
        }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "${safeTitle}_${safeId}${formatSuffix}.mp4")
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

    private fun cleanupWorkDirectory() {
        workDirectory?.let { directory ->
            if (directory.parentFile == cacheDir && directory.name.startsWith("youtube-")) {
                runCatching { directory.deleteRecursively() }
            }
        }
        workDirectory = null
    }

    private fun cleanupStaleWorkDirectories() {
        cacheDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("youtube-") }
            ?.forEach { directory -> runCatching { directory.deleteRecursively() } }
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
                Intent(this, YoutubeDownloadService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(Notification.Action.Builder(null, "取消", cancel).build())
        }
        return builder.build()
    }
}

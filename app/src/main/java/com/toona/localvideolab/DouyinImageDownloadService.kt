package com.toona.localvideolab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

class DouyinImageDownloadService : Service() {
    companion object {
        const val ACTION_START = "com.toona.localvideolab.action.START_DOUYIN_IMAGES"
        const val ACTION_START_XIAOHONGSHU =
            "com.toona.localvideolab.action.START_XIAOHONGSHU_IMAGES"
        const val ACTION_START_INSTAGRAM =
            "com.toona.localvideolab.action.START_INSTAGRAM_IMAGES"
        const val ACTION_CANCEL = "com.toona.localvideolab.action.CANCEL_DOUYIN_IMAGES"
        const val EXTRA_METADATA = "metadata"
        const val EXTRA_SELECTED = "selected_indices"

        private const val CHANNEL_ID = "image_downloads"
        private const val NOTIFICATION_ID = 2403
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null
    private val activeConnection = AtomicReference<HttpURLConnection?>(null)
    private val activeInput = AtomicReference<Closeable?>(null)
    private val pendingUri = AtomicReference<Uri?>(null)

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "图片下载",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示图集下载进度"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> cancelDownload()
            ACTION_START, ACTION_START_XIAOHONGSHU, ACTION_START_INSTAGRAM -> {
                val metadata = intent.getStringExtra(EXTRA_METADATA).orEmpty()
                val selected = intent.getStringExtra(EXTRA_SELECTED).orEmpty()
                if (metadata.isNotBlank() && downloadJob?.isActive != true) {
                    startDownload(metadata, selected)
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

    private fun startDownload(metadataText: String, selectedText: String) {
        startForeground(NOTIFICATION_ID, notification("正在准备图集…", 0, true))
        DownloadBus.state.value = DownloadSnapshot(
            phase = DownloadPhase.RESOLVING,
            message = "正在准备所选图片…",
            progress = 0,
            platform = "social_images",
        )

        downloadJob = scope.launch {
            var completed = 0
            var liveCompleted = 0
            var liveFallbacks = 0
            try {
                val metadata = JSONObject(metadataText)
                if (!metadata.optBoolean("ok") || metadata.optString("kind") != "image") {
                    throw IOException("解析结果不是可下载的图集")
                }
                val isXiaohongshu = metadata.optString("platform") == "xiaohongshu"
                val isInstagram = metadata.optString("platform") == "instagram"
                val platformName = when {
                    isInstagram -> "Instagram"
                    isXiaohongshu -> "小红书"
                    else -> "抖音"
                }
                val platformState = when {
                    isInstagram -> "instagram_images"
                    isXiaohongshu -> "xiaohongshu_images"
                    else -> "douyin_images"
                }
                val workId = when {
                    isXiaohongshu -> metadata.optString("note_id", "image")
                    isInstagram -> metadata.optString("shortcode", "image")
                    else -> metadata.optString("aweme_id", "image")
                }
                val selectedIndices = selectedText
                    .split(',')
                    .mapNotNull { it.trim().toIntOrNull() }
                    .toSet()
                if (selectedIndices.isEmpty()) throw IOException("没有选择图片")

                val allImages = metadata.getJSONArray("images")
                val selectedImages = buildList {
                    for (position in 0 until allImages.length()) {
                        val image = allImages.getJSONObject(position)
                        if (image.optInt("index", position) in selectedIndices) add(image)
                    }
                }
                if (selectedImages.isEmpty()) throw IOException("所选图片不在解析结果中")

                val title = metadata.optString("desc").ifBlank {
                    "${platformName}图集_$workId"
                }
                val headers = jsonHeaders(metadata.optJSONObject("headers"))
                val failures = mutableListOf<String>()
                val checks = mutableListOf<String>()
                var totalBytes = 0L
                var lastUri: Uri? = null

                selectedImages.forEachIndexed { position, image ->
                    coroutineContext.ensureActive()
                    val ordinal = image.optInt("index", position) + 1
                    val baseProgress = (position * 100.0 / selectedImages.size).roundToInt()
                    DownloadBus.state.value = DownloadSnapshot(
                        phase = DownloadPhase.DOWNLOADING,
                        message = "正在下载第 ${position + 1}/${selectedImages.size} 张",
                        progress = baseProgress,
                        bytesDownloaded = totalBytes,
                        title = title,
                        platform = platformState,
                    )
                    updateNotification(
                        "正在下载第 ${position + 1}/${selectedImages.size} 张…",
                        baseProgress,
                        true,
                    )
                    runCatching {
                        val result = downloadImage(
                            image = image,
                            headers = headers,
                            title = title,
                            awemeId = workId,
                            ordinal = ordinal,
                            position = position,
                            totalCount = selectedImages.size,
                            completedBytes = totalBytes,
                        )
                        totalBytes += result.bytes
                        lastUri = result.uri
                        if (result.livePhoto) liveCompleted += 1
                        if (result.fallbackComponents) liveFallbacks += 1
                        checks += buildString {
                            append("#${ordinal} ")
                            if (result.livePhoto) append("实况 ")
                            append("${result.width}x${result.height} ${formatSize(result.bytes)}")
                            if (result.fallbackComponents) append("（原图+MP4）")
                        }
                        completed += 1
                    }.onFailure { error ->
                        coroutineContext.ensureActive()
                        failures += "#${ordinal} ${error.message.orEmpty().take(80)}"
                    }
                }

                if (completed == 0) {
                    throw IOException("所选图片全部下载失败：${failures.joinToString("；")}")
                }
                val failed = selectedImages.size - completed
                DownloadBus.state.value = DownloadSnapshot(
                    phase = DownloadPhase.SUCCESS,
                    message = if (failed == 0) {
                        if (liveCompleted > 0) {
                            "已保存 $completed 张，其中 $liveCompleted 张实况 / Pictures/Toona"
                        } else {
                            "已保存 $completed 张到相册 / Pictures/Toona"
                        }
                    } else {
                        "已保存 $completed 张，$failed 张失败 / Pictures/Toona"
                    },
                    progress = 100,
                    bytesDownloaded = totalBytes,
                    totalBytes = totalBytes,
                    title = title,
                    outputUri = lastUri?.toString(),
                    verification = buildString {
                        append("[PASS] $completed/${selectedImages.size} 张，")
                        append(if (isInstagram) "最高分辨率图片源" else "图片原图源")
                        if (liveCompleted > 0) append("；实况 $liveCompleted 张")
                        if (liveFallbacks > 0) append("；$liveFallbacks 张回退为原图+MP4")
                        checks.take(4).forEach { append("\n").append(it) }
                        if (checks.size > 4) append("\n…其余 ${checks.size - 4} 张已验收")
                        if (failures.isNotEmpty()) append("\n失败：").append(failures.joinToString("；"))
                    },
                    platform = platformState,
                )
                updateNotification("图集下载完成：$completed/${selectedImages.size} 张", 100, false)
            } catch (cancelled: CancellationException) {
                cleanupPending()
                DownloadBus.state.value = DownloadSnapshot(
                    phase = DownloadPhase.CANCELED,
                    message = if (completed > 0) {
                        "已取消；已完成的 $completed 张图片仍保留"
                    } else {
                        "下载已取消，未完成图片已删除"
                    },
                    platform = DownloadBus.state.value.platform,
                )
                updateNotification("图集下载已取消", null, false)
            } catch (error: Throwable) {
                cleanupPending()
                DownloadBus.state.value = DownloadSnapshot(
                    phase = DownloadPhase.ERROR,
                    message = "${error::class.java.simpleName}: ${error.message}",
                    platform = DownloadBus.state.value.platform,
                )
                updateNotification("图集下载失败：${error.message.orEmpty().take(50)}", null, false)
            } finally {
                activeInput.getAndSet(null)?.runCatching { close() }
                activeConnection.getAndSet(null)?.disconnect()
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
    }

    private data class ImageResult(
        val uri: Uri,
        val bytes: Long,
        val width: Int,
        val height: Int,
        val livePhoto: Boolean = false,
        val fallbackComponents: Boolean = false,
    )

    private suspend fun downloadImage(
        image: JSONObject,
        headers: Map<String, String>,
        title: String,
        awemeId: String,
        ordinal: Int,
        position: Int,
        totalCount: Int,
        completedBytes: Long,
    ): ImageResult {
        if (image.optBoolean("live_photo") && image.optJSONArray("live_urls").strings().isNotEmpty()) {
            return downloadLivePhoto(
                image = image,
                headers = headers,
                title = title,
                workId = awemeId,
                ordinal = ordinal,
                position = position,
                totalCount = totalCount,
                completedBytes = completedBytes,
            )
        }
        val urls = image.optJSONArray("urls").strings()
        if (urls.isEmpty()) throw IOException("图片缺少可下载地址")
        var lastError: Throwable? = null

        urls.forEachIndexed { routeIndex, routeUrl ->
            coroutineContext.ensureActive()
            var connection: HttpURLConnection? = null
            var uri: Uri? = null
            var input: Closeable? = null
            try {
                connection = (URL(routeUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    headers.forEach { (name, value) -> setRequestProperty(name, value) }
                    setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                    setRequestProperty("Accept-Encoding", "identity")
                    connect()
                }
                activeConnection.set(connection)
                if (connection.responseCode !in 200..299) {
                    throw IOException("CDN HTTP ${connection.responseCode}")
                }
                val mime = imageMime(connection.contentType, routeUrl)
                val extension = imageExtension(mime)
                uri = createPendingImage(title, awemeId, ordinal, mime, extension)
                pendingUri.set(uri)
                val output = contentResolver.openOutputStream(uri, "w")
                    ?: throw IOException("无法打开图片输出流")
                var downloaded = 0L
                val expected = connection.contentLengthLong.coerceAtLeast(0)
                val source = connection.inputStream
                input = source
                activeInput.set(source)
                source.use {
                    output.use { sink ->
                        val buffer = ByteArray(128 * 1024)
                        var lastUpdateAt = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = it.read(buffer)
                            if (count < 0) break
                            sink.write(buffer, 0, count)
                            downloaded += count
                            val now = System.currentTimeMillis()
                            if (now - lastUpdateAt >= 250) {
                                val fraction = if (expected > 0) downloaded.toDouble() / expected else 0.0
                                val progress = (((position + fraction) * 100) / totalCount)
                                    .roundToInt()
                                    .coerceIn(0, 99)
                                DownloadBus.state.value = DownloadBus.state.value.copy(
                                    message = "正在下载第 ${position + 1}/$totalCount 张" +
                                        if (urls.size > 1) "（线路 ${routeIndex + 1}/${urls.size}）" else "",
                                    progress = progress,
                                    bytesDownloaded = completedBytes + downloaded,
                                    totalBytes = 0,
                                )
                                updateNotification(
                                    "正在下载第 ${position + 1}/$totalCount 张…",
                                    progress,
                                    true,
                                )
                                lastUpdateAt = now
                            }
                        }
                    }
                }
                if (downloaded <= 0) throw IOException("CDN 返回了空图片")
                val dimensions = verifyImage(uri)
                publishPending(uri)
                pendingUri.compareAndSet(uri, null)
                return ImageResult(uri, downloaded, dimensions.first, dimensions.second)
            } catch (cancelled: CancellationException) {
                uri?.let { contentResolver.delete(it, null, null) }
                pendingUri.compareAndSet(uri, null)
                throw cancelled
            } catch (error: Throwable) {
                lastError = error
                uri?.let { contentResolver.delete(it, null, null) }
                pendingUri.compareAndSet(uri, null)
            } finally {
                activeInput.compareAndSet(input, null)
                input?.runCatching { close() }
                activeConnection.compareAndSet(connection, null)
                connection?.disconnect()
            }
        }
        throw IOException(
            "所有无水印 CDN 线路均失败：" +
                "${lastError?.javaClass?.simpleName}: ${lastError?.message}",
            lastError,
        )
    }

    private suspend fun downloadLivePhoto(
        image: JSONObject,
        headers: Map<String, String>,
        title: String,
        workId: String,
        ordinal: Int,
        position: Int,
        totalCount: Int,
        completedBytes: Long,
    ): ImageResult {
        val imageFile = File.createTempFile("toona-live-image-", ".source", cacheDir)
        val jpegFile = File.createTempFile("toona-live-cover-", ".jpg", cacheDir)
        val videoFile = File.createTempFile("toona-live-video-", ".mp4", cacheDir)
        try {
            val sourceImageBytes = downloadToTemp(
                urls = image.optJSONArray("urls").strings(),
                headers = headers,
                accept = "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
                target = imageFile,
            ) { downloaded, expected ->
                updateLiveProgress(
                    position = position,
                    totalCount = totalCount,
                    stageFraction = 0.5 * downloadFraction(downloaded, expected),
                    bytesDownloaded = completedBytes + downloaded,
                    text = "正在下载第 ${position + 1}/$totalCount 张实况封面",
                )
            }
            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                ?: throw IOException("实况封面不是 Android 可解码的图片")
            val width = bitmap.width
            val height = bitmap.height
            try {
                FileOutputStream(jpegFile).use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                        throw IOException("实况封面转换 JPEG 失败")
                    }
                }
            } finally {
                bitmap.recycle()
            }
            updateLiveProgress(
                position,
                totalCount,
                0.5,
                completedBytes + sourceImageBytes,
                "正在下载第 ${position + 1}/$totalCount 张实况视频",
            )

            val videoBytes = downloadToTemp(
                urls = image.optJSONArray("live_urls").strings(),
                headers = headers,
                accept = "video/mp4,video/*;q=0.9,*/*;q=0.5",
                target = videoFile,
            ) { downloaded, expected ->
                updateLiveProgress(
                    position = position,
                    totalCount = totalCount,
                    stageFraction = 0.5 + 0.5 * downloadFraction(downloaded, expected),
                    bytesDownloaded = completedBytes + sourceImageBytes + downloaded,
                    text = "正在下载第 ${position + 1}/$totalCount 张实况视频",
                )
            }
            verifyVideoFile(videoFile)

            return try {
                saveMotionPhoto(
                    jpegFile = jpegFile,
                    videoFile = videoFile,
                    title = title,
                    workId = workId,
                    ordinal = ordinal,
                    width = width,
                    height = height,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (motionError: Throwable) {
                saveLivePhotoFallback(
                    jpegFile = jpegFile,
                    videoFile = videoFile,
                    title = title,
                    workId = workId,
                    ordinal = ordinal,
                    width = width,
                    height = height,
                    originalError = motionError,
                )
            }.copy(bytes = jpegFile.length() + videoBytes)
        } finally {
            imageFile.delete()
            jpegFile.delete()
            videoFile.delete()
        }
    }

    private suspend fun downloadToTemp(
        urls: List<String>,
        headers: Map<String, String>,
        accept: String,
        target: File,
        onProgress: (downloaded: Long, expected: Long) -> Unit,
    ): Long {
        if (urls.isEmpty()) throw IOException("实况资源缺少下载地址")
        var lastError: Throwable? = null
        urls.forEach { routeUrl ->
            coroutineContext.ensureActive()
            target.delete()
            var connection: HttpURLConnection? = null
            var input: Closeable? = null
            try {
                connection = (URL(routeUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    headers.forEach { (name, value) -> setRequestProperty(name, value) }
                    setRequestProperty("Accept", accept)
                    setRequestProperty("Accept-Encoding", "identity")
                    connect()
                }
                activeConnection.set(connection)
                if (connection.responseCode !in 200..299) {
                    throw IOException("CDN HTTP ${connection.responseCode}")
                }
                val expected = connection.contentLengthLong.coerceAtLeast(0)
                val source = connection.inputStream
                input = source
                activeInput.set(source)
                var downloaded = 0L
                FileOutputStream(target).use { output ->
                    source.use {
                        val buffer = ByteArray(128 * 1024)
                        var lastUpdateAt = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = it.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            val now = System.currentTimeMillis()
                            if (now - lastUpdateAt >= 250) {
                                onProgress(downloaded, expected)
                                lastUpdateAt = now
                            }
                        }
                    }
                }
                if (downloaded <= 0) throw IOException("CDN 返回了空文件")
                onProgress(downloaded, downloaded)
                return downloaded
            } catch (cancelled: CancellationException) {
                target.delete()
                throw cancelled
            } catch (error: Throwable) {
                lastError = error
                target.delete()
            } finally {
                activeInput.compareAndSet(input, null)
                input?.runCatching { close() }
                activeConnection.compareAndSet(connection, null)
                connection?.disconnect()
            }
        }
        throw IOException(
            "实况资源所有 CDN 线路均失败：" +
                "${lastError?.javaClass?.simpleName}: ${lastError?.message}",
            lastError,
        )
    }

    private fun downloadFraction(downloaded: Long, expected: Long): Double =
        if (expected > 0) (downloaded.toDouble() / expected).coerceIn(0.0, 1.0) else 0.0

    private fun updateLiveProgress(
        position: Int,
        totalCount: Int,
        stageFraction: Double,
        bytesDownloaded: Long,
        text: String,
    ) {
        val progress = (((position + stageFraction) * 100) / totalCount)
            .roundToInt()
            .coerceIn(0, 99)
        DownloadBus.state.value = DownloadBus.state.value.copy(
            message = text,
            progress = progress,
            bytesDownloaded = bytesDownloaded,
            totalBytes = 0,
        )
        updateNotification(text, progress, true)
    }

    private fun verifyVideoFile(file: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val hasVideo = (0 until extractor.trackCount).any { index ->
                extractor.getTrackFormat(index).getString("mime")?.startsWith("video/") == true
            }
            if (!hasVideo) throw IOException("实况 MP4 没有视频轨")
        } finally {
            extractor.release()
        }
    }

    private fun saveMotionPhoto(
        jpegFile: File,
        videoFile: File,
        title: String,
        workId: String,
        ordinal: Int,
        width: Int,
        height: Int,
    ): ImageResult {
        var uri: Uri? = null
        try {
            uri = createPendingImage(
                title,
                workId,
                ordinal,
                "image/jpeg",
                "jpg",
                motionPhoto = true,
            )
            pendingUri.set(uri)
            val output = contentResolver.openOutputStream(uri, "w")
                ?: throw IOException("无法创建实况照片输出流")
            output.use { writeMotionPhoto(jpegFile, videoFile, it) }
            val dimensions = verifyImage(uri)
            publishPending(uri)
            pendingUri.compareAndSet(uri, null)
            return ImageResult(
                uri = uri,
                bytes = jpegFile.length() + videoFile.length(),
                width = dimensions.first.takeIf { it > 0 } ?: width,
                height = dimensions.second.takeIf { it > 0 } ?: height,
                livePhoto = true,
            )
        } catch (cancelled: CancellationException) {
            uri?.let { contentResolver.delete(it, null, null) }
            pendingUri.compareAndSet(uri, null)
            throw cancelled
        } catch (error: Throwable) {
            uri?.let { contentResolver.delete(it, null, null) }
            pendingUri.compareAndSet(uri, null)
            throw error
        }
    }

    private fun saveLivePhotoFallback(
        jpegFile: File,
        videoFile: File,
        title: String,
        workId: String,
        ordinal: Int,
        width: Int,
        height: Int,
        originalError: Throwable,
    ): ImageResult {
        var imageUri: Uri? = null
        var videoUri: Uri? = null
        try {
            imageUri = createPendingImage(title, workId, ordinal, "image/jpeg", "jpg")
            pendingUri.set(imageUri)
            copyFileToUri(jpegFile, imageUri)
            verifyImage(imageUri)
            publishPending(imageUri)
            pendingUri.compareAndSet(imageUri, null)

            videoUri = createPendingVideo(title, workId, ordinal)
            pendingUri.set(videoUri)
            copyFileToUri(videoFile, videoUri)
            publishPending(videoUri)
            pendingUri.compareAndSet(videoUri, null)
            return ImageResult(
                uri = imageUri,
                bytes = jpegFile.length() + videoFile.length(),
                width = width,
                height = height,
                livePhoto = true,
                fallbackComponents = true,
            )
        } catch (cancelled: CancellationException) {
            imageUri?.let { contentResolver.delete(it, null, null) }
            videoUri?.let { contentResolver.delete(it, null, null) }
            pendingUri.set(null)
            throw cancelled
        } catch (fallbackError: Throwable) {
            imageUri?.let { contentResolver.delete(it, null, null) }
            videoUri?.let { contentResolver.delete(it, null, null) }
            pendingUri.set(null)
            throw IOException(
                "实况合成失败（${originalError.message}），保存原图和 MP4 也失败：${fallbackError.message}",
                fallbackError,
            )
        }
    }

    private fun copyFileToUri(file: File, uri: Uri) {
        val output = contentResolver.openOutputStream(uri, "w")
            ?: throw IOException("无法打开 MediaStore 输出流")
        FileInputStream(file).use { input -> output.use { input.copyTo(it, 128 * 1024) } }
    }

    private fun writeMotionPhoto(jpegFile: File, videoFile: File, output: OutputStream) {
        FileInputStream(jpegFile).use { jpeg ->
            val first = jpeg.read()
            val second = jpeg.read()
            if (first != 0xFF || second != 0xD8) throw IOException("实况封面不是 JPEG")
            output.write(first)
            output.write(second)
            writeApp1(output, xiaomiExifPayload())
            writeApp1(output, motionPhotoXmpPayload(videoFile.length()))
            jpeg.copyTo(output, 128 * 1024)
        }
        FileInputStream(videoFile).use { it.copyTo(output, 128 * 1024) }
    }

    private fun writeApp1(output: OutputStream, payload: ByteArray) {
        val length = payload.size + 2
        if (length > 0xFFFF) throw IOException("Motion Photo 元数据过大")
        output.write(0xFF)
        output.write(0xE1)
        output.write((length ushr 8) and 0xFF)
        output.write(length and 0xFF)
        output.write(payload)
    }

    private fun xiaomiExifPayload(): ByteArray = byteArrayOf(
        0x45, 0x78, 0x69, 0x66, 0x00, 0x00,
        0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00,
        0x01, 0x00,
        0x97.toByte(), 0x88.toByte(), 0x04, 0x00,
        0x01, 0x00, 0x00, 0x00,
        0x01, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
    )

    private fun motionPhotoXmpPayload(videoLength: Long): ByteArray {
        val xml = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description
                  xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                  xmlns:MiCamera="http://ns.xiaomi.com/photos/1.0/camera/"
                  xmlns:Container="http://ns.google.com/photos/1.0/container/"
                  xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
                  GCamera:MotionPhoto="1"
                  GCamera:MotionPhotoVersion="1"
                  GCamera:MotionPhotoPresentationTimestampUs="0"
                  GCamera:MicroVideo="1"
                  GCamera:MicroVideoVersion="1"
                  GCamera:MicroVideoOffset="$videoLength"
                  GCamera:MicroVideoPresentationTimestampUs="0"
                  MiCamera:XMPMeta="1">
                  <Container:Directory>
                    <rdf:Seq>
                      <rdf:li rdf:parseType="Resource">
                        <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/>
                      </rdf:li>
                      <rdf:li rdf:parseType="Resource">
                        <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoLength" Item:Padding="0"/>
                      </rdf:li>
                    </rdf:Seq>
                  </Container:Directory>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        val header = "http://ns.adobe.com/xap/1.0/\u0000"
        return (header + xml).toByteArray(StandardCharsets.UTF_8)
    }

    private fun JSONArray?.strings(): List<String> = buildList {
        if (this@strings == null) return@buildList
        for (index in 0 until length()) {
            optString(index).takeIf { it.startsWith("https://") || it.startsWith("http://") }
                ?.let(::add)
        }
    }.distinct()

    private fun jsonHeaders(value: JSONObject?): Map<String, String> = buildMap {
        value?.keys()?.forEach { name -> put(name, value.optString(name)) }
    }

    private fun imageMime(contentType: String?, url: String): String {
        val normalized = contentType.orEmpty().substringBefore(';').lowercase()
        return when {
            normalized.startsWith("image/") -> normalized
            ".png" in url.substringBefore('?').lowercase() -> "image/png"
            ".jpeg" in url.substringBefore('?').lowercase() ||
                ".jpg" in url.substringBefore('?').lowercase() -> "image/jpeg"
            else -> "image/webp"
        }
    }

    private fun imageExtension(mime: String): String = when (mime) {
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "image/avif" -> "avif"
        else -> "webp"
    }

    private fun createPendingImage(
        title: String,
        awemeId: String,
        ordinal: Int,
        mime: String,
        extension: String,
        motionPhoto: Boolean = false,
    ): Uri {
        val safeTitle = title
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n]+"), "_")
            .trim(' ', '.', '_')
            .ifBlank { "图集" }
            .take(60)
        val values = ContentValues().apply {
            put(
                MediaStore.Images.Media.DISPLAY_NAME,
                if (motionPhoto) {
                    "${safeTitle}_${awemeId}_${ordinal.toString().padStart(2, '0')}_live_MP.jpg"
                } else {
                    "${safeTitle}_${awemeId}_${ordinal.toString().padStart(2, '0')}.$extension"
                },
            )
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/Toona",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        return contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法在 MediaStore 创建图片")
    }

    private fun createPendingVideo(title: String, workId: String, ordinal: Int): Uri {
        val safeTitle = title
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n]+"), "_")
            .trim(' ', '.', '_')
            .ifBlank { "图集" }
            .take(60)
        val values = ContentValues().apply {
            put(
                MediaStore.Video.Media.DISPLAY_NAME,
                "${safeTitle}_${workId}_${ordinal.toString().padStart(2, '0')}_live.mp4",
            )
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/Toona",
            )
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        return contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法在 MediaStore 创建实况视频")
    }

    private fun verifyImage(uri: Uri): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val input = contentResolver.openInputStream(uri)
            ?: throw IOException("无法重新读取图片")
        input.use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0 || options.outMimeType.isNullOrBlank()) {
            throw IOException("图片验收失败：格式或尺寸无效")
        }
        return options.outWidth to options.outHeight
    }

    private fun publishPending(uri: Uri) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }
        contentResolver.update(uri, values, null, null)
    }

    private fun cancelDownload() {
        downloadJob?.cancel()
        DownloadBus.state.value = DownloadBus.state.value.copy(
            message = "正在取消当前图片并停止队列…",
            progress = null,
        )
        activeInput.getAndSet(null)?.runCatching { close() }
        activeConnection.getAndSet(null)?.disconnect()
    }

    private fun cleanupPending() {
        pendingUri.getAndSet(null)?.let { uri ->
            runCatching { contentResolver.delete(uri, null, null) }
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 * 1024 -> "%.1f KiB".format(bytes / 1024.0)
        else -> "%.2f MiB".format(bytes / 1048576.0)
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
            .setContentTitle("Toona · 图集")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(showCancel)
        if (progress != null) builder.setProgress(100, progress, false)
        else if (showCancel) builder.setProgress(0, 0, true)
        if (showCancel) {
            val cancel = PendingIntent.getService(
                this,
                3,
                Intent(this, DouyinImageDownloadService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(Notification.Action.Builder(null, "取消", cancel).build())
        }
        return builder.build()
    }
}

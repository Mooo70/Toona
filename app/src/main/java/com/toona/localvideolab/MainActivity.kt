package com.toona.localvideolab

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    private val incomingText = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingText.value = sharedText(intent)
        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2401)
        }
        setContent {
            MaterialTheme {
                LocalVideoLabScreen(incomingText.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingText.value = sharedText(intent)
    }

    private fun sharedText(intent: Intent?): String {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return ""
        return intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
    }
}

private enum class PlatformKind(val displayName: String) {
    DOUYIN("抖音"),
    XIAOHONGSHU("小红书"),
    BILIBILI("B站"),
    YOUTUBE("YouTube"),
    INSTAGRAM("Instagram"),
    UNKNOWN("未识别"),
}

private data class SelectionOption(val value: String, val label: String)

private data class ImageItem(
    val index: Int,
    val urls: List<String>,
    val width: Int,
    val height: Int,
)

private data class ImageGallery(
    val metadataJson: String,
    val platform: String,
    val workId: String,
    val description: String,
    val nickname: String,
    val headers: Map<String, String>,
    val images: List<ImageItem>,
)

private val qualityOptions = listOf(
    SelectionOption("best", "最高"),
    SelectionOption("1080", "1080p"),
    SelectionOption("720", "720p"),
    SelectionOption("480", "480p"),
)

private val codecOptions = listOf(
    SelectionOption("h264", "H.264"),
    SelectionOption("h265", "H.265"),
    SelectionOption("av1", "AV1"),
    SelectionOption("auto", "自动"),
)

@Composable
private fun SelectionMenu(
    prefix: String,
    options: List<SelectionOption>,
    selectedValue: String,
    enabled: Boolean,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.value == selectedValue }?.label ?: selectedValue
    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("$prefix：$selectedLabel")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelected(option.value)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun detectPlatform(text: String): PlatformKind {
    val normalized = text.lowercase()
    return when {
        "douyin.com" in normalized -> PlatformKind.DOUYIN
        "xiaohongshu.com" in normalized ||
            "rednote.com" in normalized ||
            "xhslink.com" in normalized ||
            "xhslink.cn" in normalized -> PlatformKind.XIAOHONGSHU
        "bilibili.com" in normalized || "b23.tv" in normalized -> PlatformKind.BILIBILI
        "youtube.com" in normalized || "youtu.be" in normalized -> PlatformKind.YOUTUBE
        "instagram.com" in normalized -> PlatformKind.INSTAGRAM
        else -> PlatformKind.UNKNOWN
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "--"
    bytes < 1024 * 1024 -> "%.1f KiB".format(bytes / 1024.0)
    else -> "%.2f MiB".format(bytes / 1048576.0)
}

private fun parseImageGallery(metadataJson: String): ImageGallery? {
    val metadata = JSONObject(metadataJson)
    if (!metadata.optBoolean("ok") || metadata.optString("kind") != "image") return null
    val headersObject = metadata.optJSONObject("headers")
    val headers = buildMap {
        headersObject?.keys()?.forEach { key -> put(key, headersObject.optString(key)) }
    }
    val imageArray = metadata.optJSONArray("images") ?: return null
    val images = buildList {
        for (position in 0 until imageArray.length()) {
            val image = imageArray.optJSONObject(position) ?: continue
            val urlArray = image.optJSONArray("urls") ?: continue
            val urls = buildList {
                for (urlIndex in 0 until urlArray.length()) {
                    urlArray.optString(urlIndex)
                        .takeIf { it.startsWith("https://") || it.startsWith("http://") }
                        ?.let(::add)
                }
            }
            if (urls.isEmpty()) continue
            add(
                ImageItem(
                    index = image.optInt("index", position),
                    urls = urls,
                    width = image.optInt("width", 0),
                    height = image.optInt("height", 0),
                ),
            )
        }
    }
    if (images.isEmpty()) return null
    val platform = metadata.optString("platform", "douyin")
    return ImageGallery(
        metadataJson = metadataJson,
        platform = platform,
        workId = if (platform == "xiaohongshu") {
            metadata.optString("note_id")
        } else {
            metadata.optString("aweme_id")
        },
        description = metadata.optString("desc"),
        nickname = metadata.optString("nickname"),
        headers = headers,
        images = images,
    )
}

private fun thumbnailUrl(url: String): String = url.replace(
    Regex(":3000:3000:q\\d+\\.(webp|jpeg|jpg)", RegexOption.IGNORE_CASE),
) { match -> ":360:360:q60.${match.groupValues[1]}" }

private suspend fun loadThumbnail(
    url: String,
    headers: Map<String, String>,
): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val connection = URL(thumbnailUrl(url)).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = true
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.setRequestProperty("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
            connection.connect()
            if (connection.responseCode !in 200..299) return@runCatching null
            connection.inputStream.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

@Composable
private fun ImageTile(
    image: ImageItem,
    headers: Map<String, String>,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bitmap by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = image.urls.firstOrNull(),
    ) {
        value = image.urls.firstOrNull()?.let { loadThumbnail(it, headers) }
    }
    Card(
        modifier = modifier.clickable(enabled = enabled, onClick = onToggle),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Color(0xFFECE4FF) else Color(0xFFF3F3F5),
        ),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.78f)
                    .background(Color(0xFFE2E2E8)),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap == null) {
                    CircularProgressIndicator()
                } else {
                    Image(
                        bitmap = bitmap!!,
                        contentDescription = "第 ${image.index + 1} 张图片",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggle() },
                    enabled = enabled,
                )
                Column {
                    Text("第 ${image.index + 1} 张", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (image.width > 0 && image.height > 0) {
                            "${image.width}×${image.height}"
                        } else {
                            "原图"
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalVideoLabScreen(sharedText: String) {
    var input by rememberSaveable { mutableStateOf("") }
    var quality by rememberSaveable { mutableStateOf("best") }
    var codec by rememberSaveable { mutableStateOf("h264") }
    var probeBusy by remember { mutableStateOf(false) }
    var socialKind by remember { mutableStateOf<String?>(null) }
    var imageGallery by remember { mutableStateOf<ImageGallery?>(null) }
    var selectedImages by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var logs by remember {
        mutableStateOf(
            listOf(
                "技术验证版 0.6.0",
                "抖音：本地解析视频或图文；图文可预览、挑选并批量保存无水印原图源。",
                "小红书：支持分享文案、短链和带分享令牌的网址，可解析视频或图文。",
                "B站：yt-dlp 选双轨，Android 原生无损合并。",
                "下载后统一验收视频轨与音频轨。",
            ),
        )
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val detected = detectPlatform(input)
    val download by DownloadBus.state.collectAsState()

    LaunchedEffect(sharedText) {
        if (sharedText.isNotBlank()) {
            input = sharedText
            socialKind = null
            imageGallery = null
            selectedImages = emptySet()
        }
    }

    fun clearSocialResult() {
        socialKind = null
        imageGallery = null
        selectedImages = emptySet()
    }

    fun callPython(function: String, argument: String? = null) {
        if (probeBusy || download.active) return
        probeBusy = true
        logs = logs + "▶ $function"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val module = Python.getInstance().getModule("downloader_probe")
                    if (argument == null) {
                        module.callAttr(function).toString()
                    } else {
                        module.callAttr(function, argument).toString()
                    }
                }.getOrElse { error ->
                    "${error::class.java.simpleName}: ${error.message}"
                }
            }
            logs = logs + result
            probeBusy = false
        }
    }

    fun resolveSocial(platform: PlatformKind) {
        if (probeBusy || download.active || input.isBlank()) return
        probeBusy = true
        clearSocialResult()
        val isXiaohongshu = platform == PlatformKind.XIAOHONGSHU
        val platformName = if (isXiaohongshu) "小红书" else "抖音"
        val moduleName = if (isXiaohongshu) "xiaohongshu_resolver" else "douyin_resolver"
        logs = logs + "▶ 正在解析$platformName 内容"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    Python.getInstance()
                        .getModule(moduleName)
                        .callAttr("resolve", input)
                        .toString()
                }.getOrElse { error ->
                    JSONObject()
                        .put("ok", false)
                        .put("error", "${error::class.java.simpleName}: ${error.message}")
                        .toString()
                }
            }
            runCatching {
                val metadata = JSONObject(result)
                if (!metadata.optBoolean("ok")) {
                    throw IllegalStateException(metadata.optString("error", "$platformName 解析失败"))
                }
                socialKind = metadata.optString("kind", "video")
                val gallery = parseImageGallery(result)
                imageGallery = gallery
                selectedImages = gallery?.images?.map { it.index }?.toSet().orEmpty()
                logs = logs + if (gallery != null) {
                    "✓ 已识别$platformName 图文：${gallery.images.size} 张，原图源，默认全选"
                } else {
                    val sourceTip = if (isXiaohongshu && !metadata.optBoolean("origin_source")) {
                        "；该作品未提供原始视频源，将使用公开页最佳流"
                    } else {
                        ""
                    }
                    "✓ 已识别$platformName 视频，可以下载到相册$sourceTip"
                }
            }.onFailure { error ->
                clearSocialResult()
                logs = logs + "解析失败：${error.message}"
            }
            probeBusy = false
        }
    }

    fun startSupportedDownload() {
        val intent = when (detected) {
            PlatformKind.DOUYIN -> Intent(context, DouyinDownloadService::class.java).apply {
                action = DouyinDownloadService.ACTION_START
                putExtra(DouyinDownloadService.EXTRA_SHARE_TEXT, input)
            }
            PlatformKind.XIAOHONGSHU -> Intent(context, DouyinDownloadService::class.java).apply {
                action = DouyinDownloadService.ACTION_START_XIAOHONGSHU
                putExtra(DouyinDownloadService.EXTRA_SHARE_TEXT, input)
            }
            PlatformKind.BILIBILI -> Intent(context, BilibiliDownloadService::class.java).apply {
                action = BilibiliDownloadService.ACTION_START
                putExtra(BilibiliDownloadService.EXTRA_SHARE_TEXT, input)
                putExtra(BilibiliDownloadService.EXTRA_QUALITY, quality)
                putExtra(BilibiliDownloadService.EXTRA_CODEC, codec)
            }
            else -> return
        }
        context.startForegroundService(intent)
    }

    fun startImageDownload(gallery: ImageGallery) {
        if (selectedImages.isEmpty() || download.active) return
        val intent = Intent(context, DouyinImageDownloadService::class.java).apply {
            action = if (gallery.platform == "xiaohongshu") {
                DouyinImageDownloadService.ACTION_START_XIAOHONGSHU
            } else {
                DouyinImageDownloadService.ACTION_START
            }
            putExtra(DouyinImageDownloadService.EXTRA_METADATA, gallery.metadataJson)
            putExtra(
                DouyinImageDownloadService.EXTRA_SELECTED,
                selectedImages.sorted().joinToString(","),
            )
        }
        context.startForegroundService(intent)
    }

    fun cancelDownload() {
        val intent = when (download.platform) {
            "bilibili" -> Intent(context, BilibiliDownloadService::class.java).apply {
                action = BilibiliDownloadService.ACTION_CANCEL
            }
            "social_images", "douyin_images", "xiaohongshu_images" ->
                Intent(context, DouyinImageDownloadService::class.java).apply {
                action = DouyinImageDownloadService.ACTION_CANCEL
            }
            else -> Intent(context, DouyinDownloadService::class.java).apply {
                action = DouyinDownloadService.ACTION_CANCEL
            }
        }
        context.startService(intent)
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Toona") })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 18.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "ARM64 · Android 16 · 纯本地",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    clearSocialResult()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !download.active,
                label = { Text("粘贴视频、图文链接或分享文案") },
                minLines = 3,
                maxLines = 6,
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF2F3F7)),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("识别结果", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(detected.displayName)
                    Text(
                        text = when (detected) {
                            PlatformKind.DOUYIN -> when (socialKind) {
                                "image" -> "已识别图文作品，可挑选并保存无水印原图源。"
                                "video" -> "已识别视频作品，可保存最优档位到相册。"
                                else -> "可本地识别视频或图文；图文支持预览、挑选与批量保存。"
                            }
                            PlatformKind.XIAOHONGSHU -> when (socialKind) {
                                "image" -> "已识别图文作品，可挑选并保存公开页原图源。"
                                "video" -> "已识别视频作品，优先保存原始视频源。"
                                else -> "支持分享文案、短链和带分享令牌的网址；可识别视频或图文。"
                            }
                            PlatformKind.YOUTUBE -> "下一阶段需要本地登录态与 JS 校验。"
                            PlatformKind.INSTAGRAM -> "下一阶段需要本地登录态与代理配置。"
                            PlatformKind.BILIBILI -> "可选择画质与视频编码；音频使用 AAC 并在手机本地无损合并。"
                            PlatformKind.UNKNOWN -> "请输入支持平台中的单条链接或分享文案。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (detected == PlatformKind.BILIBILI) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF8F5FF)),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("下载选项", fontWeight = FontWeight.SemiBold)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            SelectionMenu(
                                prefix = "画质",
                                options = qualityOptions,
                                selectedValue = quality,
                                enabled = !download.active,
                                onSelected = { quality = it },
                                modifier = Modifier.weight(1f),
                            )
                            SelectionMenu(
                                prefix = "编码",
                                options = codecOptions,
                                selectedValue = codec,
                                enabled = !download.active,
                                onSelected = { codec = it },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Text(
                            "目标档位不存在时自动降级，下载完成后显示实际画质与编码。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = { callPython("environment") },
                    enabled = !probeBusy && !download.active,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("检查 Python")
                }
                Button(
                    onClick = {
                        if (detected == PlatformKind.DOUYIN || detected == PlatformKind.XIAOHONGSHU) {
                            resolveSocial(detected)
                        } else {
                            callPython("probe_url", input)
                        }
                    },
                    enabled = !probeBusy && !download.active && input.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        when {
                            probeBusy -> "解析中…"
                            detected == PlatformKind.DOUYIN -> "解析抖音内容"
                            detected == PlatformKind.XIAOHONGSHU -> "解析小红书内容"
                            else -> "只解析"
                        },
                    )
                }
            }

            imageGallery?.let { gallery ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF8F5FF)),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("图文选择", fontWeight = FontWeight.SemiBold)
                        if (gallery.nickname.isNotBlank()) {
                            Text("作者：${gallery.nickname}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (gallery.description.isNotBlank()) {
                            Text(gallery.description, maxLines = 3)
                        }
                        Text(
                            "共 ${gallery.images.size} 张 · 已选 ${selectedImages.size} 张 · 无水印原图源",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    selectedImages = gallery.images.map { it.index }.toSet()
                                },
                                enabled = !download.active,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("全选")
                            }
                            OutlinedButton(
                                onClick = { selectedImages = emptySet() },
                                enabled = !download.active,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("取消全选")
                            }
                        }
                        gallery.images.chunked(2).forEach { rowImages ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                rowImages.forEach { image ->
                                    ImageTile(
                                        image = image,
                                        headers = gallery.headers,
                                        selected = image.index in selectedImages,
                                        enabled = !download.active,
                                        onToggle = {
                                            selectedImages = if (image.index in selectedImages) {
                                                selectedImages - image.index
                                            } else {
                                                selectedImages + image.index
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (rowImages.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                        Button(
                            onClick = { startImageDownload(gallery) },
                            enabled = selectedImages.isNotEmpty() && !download.active,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("下载所选 ${selectedImages.size} 张到相册")
                        }
                        Text(
                            "保存位置：Pictures/Toona。仅处理当前公开页面可访问的图片；私密、已删除或需要额外权限的作品不会绕过访问控制。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            if (
                ((detected == PlatformKind.DOUYIN || detected == PlatformKind.XIAOHONGSHU) &&
                    socialKind == "video") ||
                detected == PlatformKind.BILIBILI
            ) {
                Button(
                    onClick = { startSupportedDownload() },
                    enabled = (
                        detected == PlatformKind.BILIBILI ||
                            ((detected == PlatformKind.DOUYIN ||
                                detected == PlatformKind.XIAOHONGSHU) && socialKind == "video")
                        ) &&
                        !probeBusy && !download.active,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when {
                            download.active -> "下载任务进行中…"
                            detected == PlatformKind.DOUYIN -> "下载视频到相册"
                            detected == PlatformKind.XIAOHONGSHU -> "下载视频到相册"
                            else -> "下载到相册"
                        },
                    )
                }
            }

            if (download.phase != DownloadPhase.IDLE) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = when (download.phase) {
                            DownloadPhase.SUCCESS -> Color(0xFFE8F5E9)
                            DownloadPhase.ERROR -> Color(0xFFFFEBEE)
                            else -> Color(0xFFEFF3FF)
                        },
                    ),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("下载状态", fontWeight = FontWeight.SemiBold)
                        Text(download.message)
                        val progressValue = download.progress
                        if (progressValue != null) {
                            LinearProgressIndicator(
                                progress = { progressValue.coerceIn(0, 100) / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "${progressValue}%  " +
                                    "${formatBytes(download.bytesDownloaded)} / ${formatBytes(download.totalBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (download.verification.isNotBlank()) {
                            Text(download.verification, fontFamily = FontFamily.Monospace)
                        }
                        if (download.active) {
                            OutlinedButton(
                                onClick = { cancelDownload() },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("取消下载")
                            }
                        }
                    }
                }
            }

            Text("运行日志", fontWeight = FontWeight.SemiBold)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF17181C)),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    text = logs.joinToString("\n\n"),
                    modifier = Modifier.padding(14.dp),
                    color = Color(0xFFE5E7EB),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

package com.toona.localvideolab

import kotlinx.coroutines.flow.MutableStateFlow

enum class DownloadPhase {
    IDLE,
    RESOLVING,
    DOWNLOADING,
    MERGING,
    VERIFYING,
    SUCCESS,
    ERROR,
    CANCELED,
}

data class DownloadSnapshot(
    val phase: DownloadPhase = DownloadPhase.IDLE,
    val message: String = "准备就绪",
    val progress: Int? = null,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = 0,
    val title: String = "",
    val outputUri: String? = null,
    val verification: String = "",
    val platform: String = "",
) {
    val active: Boolean
        get() = phase in setOf(
            DownloadPhase.RESOLVING,
            DownloadPhase.DOWNLOADING,
            DownloadPhase.MERGING,
            DownloadPhase.VERIFYING,
        )
}

object DownloadBus {
    val state = MutableStateFlow(DownloadSnapshot())
}

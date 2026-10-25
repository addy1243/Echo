package dev.echo.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.IOException

internal class SourceResolutionException(message: String) : IOException(message)

internal fun Throwable.isSourceResolutionFailure(): Boolean =
    generateSequence(this) { it.cause }.any { it is SourceResolutionException }

internal fun isTransientHttpStatus(status: Int?): Boolean = status != null && status in 500..599

@UnstableApi
internal fun Throwable.httpResponseError(): HttpDataSource.InvalidResponseCodeException? =
    generateSequence(this) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()

@UnstableApi
internal class EchoLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (loadErrorInfo.exception.isSourceResolutionFailure()) return C.TIME_UNSET
        val http = loadErrorInfo.exception.httpResponseError()
        if (http != null && !isTransientHttpStatus(http.responseCode)) return C.TIME_UNSET
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}

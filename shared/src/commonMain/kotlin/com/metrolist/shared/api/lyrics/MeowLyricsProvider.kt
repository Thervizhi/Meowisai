/*
 * Meowisai custom lyrics provider — fetches enhanced LRC from a private Cloudflare Worker.
 * Converts the returned LRC to MLX format for the shared lyrics pipeline.
 */

package com.metrolist.shared.api.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException

class MeowLyricsProvider(
    private val httpClient: HttpClient,
) {
    private const val WORKER_BASE = "https://varigal.starlasore.workers.dev"
    private const val API_KEY = "123"

    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Long?,
        album: String? = null,
        videoId: String? = null,
    ): String? {
        val normalizedVideoId = videoId?.takeIf { it.isNotBlank() }
        return try {
            val url =
                buildString {
                    append(WORKER_BASE)
                    append("/lrc")
                    var first = true
                    if (normalizedVideoId != null) {
                        append("?v=").append(normalizedVideoId.encodeURLParameter())
                        first = false
                    }
                    if (title.isNotBlank()) {
                        append(if (first) "?" else "&").append("t=").append(title.encodeURLParameter())
                        first = false
                    }
                    if (artist.isNotBlank()) {
                        append(if (first) "?" else "&").append("a=").append(artist.encodeURLParameter())
                    }
                }
            val response = httpClient.get(url) { header("X-Api-Key", API_KEY) }
            if (response.status != HttpStatusCode.OK) return null
            val lrc = response.bodyAsText()
            if (lrc.isBlank()) return null
            parseLrcToMlx(lrc, title, artist, ignoreRetiming = false, sourceId = normalizedVideoId, lyricsCurator = "Meowisai")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }
}

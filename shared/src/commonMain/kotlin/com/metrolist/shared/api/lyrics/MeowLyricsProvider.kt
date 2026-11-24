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
    private val WORKER_BASE = "https://varigal.starlasore.workers.dev"
    private val API_KEY = "123"

    private val lineTimestampRegex = Regex("""\[([0-9]{1,2}):([0-9]{2})(?:[.:]([0-9]{1,3}))?\](.*)""")

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
            parseMeowLrcToMlx(lrc, title, artist, normalizedVideoId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private fun parseMeowLrcToMlx(
        lrc: String,
        title: String,
        artist: String,
        videoId: String?,
    ): String? {
        val lines = lrc.lines()
        val entries = mutableListOf<MeowLrcEntry>()

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) {
                i++
                continue
            }

            val match = lineTimestampRegex.matchEntire(line)
            if (match != null) {
                val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                val fracStr = match.groupValues[3]
                val millis =
                    when (fracStr.length) {
                        0 -> 0L
                        1 -> fracStr.toLong() * 100L
                        2 -> fracStr.toLong() * 10L
                        else -> fracStr.take(3).toLong()
                    }
                val startMs = minutes * 60_000L + seconds * 1_000L + millis
                val text = match.groupValues[4].trim()

                // Check if next line has word-level timing
                var wordTimings: List<MlxWord>? = null
                if (i + 1 < lines.size) {
                    val nextLine = lines[i + 1].trim()
                    if (nextLine.startsWith("<") && nextLine.endsWith(">")) {
                        wordTimings = parseWordTimings(nextLine, startMs)
                        if (wordTimings != null) i++ // consume the word timing line
                    }
                }

                if (text.isNotEmpty()) {
                    entries.add(MeowLrcEntry(startMs, text, wordTimings))
                }
            }
            i++
        }

        if (entries.isEmpty()) return null

        val sorted = entries.sortedBy { it.startMs }
        val builder =
            MlxBuilder()
                .meta(
                    source = "Meowisai",
                    ignoreRetiming = false,
                    sourceId = videoId,
                    lyricsCurator = "Meowisai",
                ).song(title = title, artist = artist)

        for (entry in sorted) {
            if (entry.words != null && entry.words.isNotEmpty()) {
                val lastWord = entry.words.last()
                builder.line(
                    startMs = entry.startMs,
                    endMs = lastWord.endMs,
                    agentId = null,
                    words = entry.words,
                    backgroundWords = null,
                    isBackground = false,
                )
            } else {
                builder.line(entry.startMs, entry.text)
            }
        }

        return builder.build()
    }

    /**
     * Parse word-level timing from format: `<word:startSec:endSec|word:startSec:endSec|...>`
     * Times are in seconds (decimal). Returns null if parsing fails.
     */
    private fun parseWordTimings(line: String, lineStartMs: Long): List<MlxWord>? {
        return try {
            val content = line.trimStart('<').trimEnd('>')
            val parts = content.split("|")
            val words = mutableListOf<MlxWord>()

            for (part in parts) {
                if (part.isBlank()) continue
                // Split on ':' but keep the word text intact (it may contain special chars)
                // Format: word:startSec:endSec
                // Find the last two ':' as timing delimiters
                val lastColon = part.lastIndexOf(':')
                if (lastColon <= 0) continue
                val secondLastColon = part.lastIndexOf(':', lastColon - 1)
                if (secondLastColon <= 0) continue

                val wordText = part.substring(0, secondLastColon)
                val startStr = part.substring(secondLastColon + 1, lastColon)
                val endStr = part.substring(lastColon + 1)

                val startSec = startStr.toDoubleOrNull() ?: continue
                val endSec = endStr.toDoubleOrNull() ?: continue

                val startMs = (startSec * 1000).toLong()
                val endMs = (endSec * 1000).toLong()

                // Detect trailing space: if the word text ends with a space or the next part starts differently
                val hasTrailingSpace = wordText.endsWith(" ") || wordText.endsWith("\t")
                val cleanText = wordText.trimEnd()

                if (cleanText.isNotEmpty()) {
                    words.add(MlxWord(cleanText, startMs, endMs, hasTrailingSpace))
                }
            }

            if (words.isEmpty()) null else words
        } catch (_: Exception) {
            null
        }
    }

    private data class MeowLrcEntry(
        val startMs: Long,
        val text: String,
        val words: List<MlxWord>?,
    )
}

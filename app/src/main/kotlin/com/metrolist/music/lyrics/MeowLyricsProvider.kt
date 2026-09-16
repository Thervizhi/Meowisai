/**
    * Meowisai custom lyrics provider — fetches enhanced LRC from your private HF
    * dataset via your Cloudflare Worker. Tries videoId first, then title+artist.
    */
package com.metrolist.music.lyrics

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter

object MeowLyricsProvider : LyricsProvider {
    override val name = "Meowisai"

    // ==== EDIT THESE TWO LINES ====
    private const val WORKER_BASE = "https://varigal.starlasore.workers.dev"
    private const val API_KEY = "123"
    // ==============================

    private val client = HttpClient(OkHttp)

    override fun isEnabled(context: Context): Boolean = true

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> = runCatching {
        val url = buildString {
            append(WORKER_BASE)
            append("/lrc?v=").append(id.encodeURLParameter())
            append("&t=").append(title.encodeURLParameter())
            append("&a=").append(artist.encodeURLParameter())
        }
        val resp = client.get(url) { header("X-Api-Key", API_KEY) }
        if (resp.status == HttpStatusCode.OK) {
            val body = resp.bodyAsText()
            if (body.isNotBlank()) body
            else throw NoSuchElementException("Empty lyrics from Meowisai")
        } else {
            throw NoSuchElementException("No Meowisai lyrics (${resp.status.value})")
        }
    }
}

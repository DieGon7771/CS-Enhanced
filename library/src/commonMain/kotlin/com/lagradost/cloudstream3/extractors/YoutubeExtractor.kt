package com.lagradost.cloudstream3.extractors

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder

open class YoutubeExtractor : ExtractorApi() {
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false
    override val name = "YouTube"

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15"
        private val HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept-Language" to "en-US,en;q=0.5"
        )
    }

    private fun extractYtCfg(html: String): String? {
        val regex = Regex("""ytcfg\.set\(\s*(\{.*?\})\s*\)\s*;""")
        return regex.find(html)?.groupValues?.getOrNull(1)
    }

    data class PageConfig(
        @JsonProperty("INNERTUBE_API_KEY") val apiKey: String,
        @JsonProperty("INNERTUBE_CLIENT_VERSION") val clientVersion: String,
        @JsonProperty("VISITOR_DATA") val visitorData: String
    )

    private suspend fun getPageConfig(videoId: String): PageConfig? {
        val html = app.get("$mainUrl/watch?v=$videoId", headers = HEADERS).text
        val json = extractYtCfg(html) ?: return null
        return try {
            jacksonObjectMapper().readValue(json, PageConfig::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun extractYouTubeId(url: String): String {
        val decoded = URLDecoder.decode(url, "UTF-8")
        return when {
            decoded.contains("watch?v=") -> decoded.substringAfter("watch?v=").substringBefore("&").substringBefore("#")
            decoded.contains("youtu.be/") -> decoded.substringAfter("youtu.be/").substringBefore("?").substringBefore("#")
            decoded.contains("/shorts/") -> decoded.substringAfter("/shorts/").substringBefore("?").substringBefore("#")
            decoded.contains("/embed/") -> decoded.substringAfter("/embed/").substringBefore("?").substringBefore("#")
            else -> error("No YouTube ID found")
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = extractYouTubeId(url)
        val config = getPageConfig(videoId) ?: return

        val jsonBody = """
        {
          "context": {
            "client": {
              "hl": "en",
              "gl": "US",
              "clientName": "WEB",
              "clientVersion": "${config.clientVersion}",
              "visitorData": "${config.visitorData}",
              "platform": "DESKTOP",
              "userAgent": "$USER_AGENT"
            }
          },
          "videoId": "$videoId"
        }
        """.toRequestBody("application/json; charset=utf-8".toMediaType())

        val response = app.post(
            "$mainUrl/youtubei/v1/player?key=${config.apiKey}",
            headers = HEADERS,
            requestBody = jsonBody
        ).parsed<Root>()

        // Subtitles
        response.captions?.playerCaptionsTracklistRenderer?.captionTracks?.forEach { caption ->
            subtitleCallback(
                newSubtitleFile(
                    lang = caption.name.simpleText,
                    url = "${caption.baseUrl}&fmt=ttml"
                ) { headers = HEADERS }
            )
        }

        // HLS
        val hlsUrl = response.streamingData.hlsManifestUrl
        val playlist = HlsPlaylistParser.parse(hlsUrl, app.get(hlsUrl, headers = HEADERS).text) ?: return

        playlist.variants.forEach { variant ->
            callback(
                newExtractorLink(
                    source = name,
                    name = "YouTube ${variant.format.height}p",
                    url = variant.url.toString(),
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = mainUrl
                    this.quality = variant.format.height
                }
            )
        }
    }

    // --- JSON data classes ---
    private data class Root(
        @JsonProperty("streamingData") val streamingData: StreamingData,
        @JsonProperty("captions") val captions: Captions?
    )

    private data class StreamingData(@JsonProperty("hlsManifestUrl") val hlsManifestUrl: String)
    private data class Captions(@JsonProperty("playerCaptionsTracklistRenderer") val playerCaptionsTracklistRenderer: PlayerCaptionsTracklistRenderer?)
    private data class PlayerCaptionsTracklistRenderer(@JsonProperty("captionTracks") val captionTracks: List<CaptionTrack>?)
    private data class CaptionTrack(@JsonProperty("baseUrl") val baseUrl: String, @JsonProperty("name") val name: Name)
    private data class Name(@JsonProperty("simpleText") val simpleText: String)
}

// Varianti
class YoutubeShortLinkExtractor : YoutubeExtractor() { override val mainUrl = "https://youtu.be" }
class YoutubeMobileExtractor : YoutubeExtractor() { override val mainUrl = "https://m.youtube.com" }
class YoutubeNoCookieExtractor : YoutubeExtractor() { override val mainUrl = "https://www.youtube-nocookie.com" }

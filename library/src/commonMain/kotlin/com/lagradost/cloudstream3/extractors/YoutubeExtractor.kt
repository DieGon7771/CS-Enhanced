package com.lagradost.cloudstream3.extractors

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
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

    // Parsing JSON sicuro passando la classe
    private fun <T> parseJsonSafe(json: String?, clazz: Class<T>): T? {
        if (json.isNullOrBlank()) return null
        return try {
            jacksonObjectMapper().readValue(json, clazz)
        } catch (e: Exception) {
            null
        }
    }

    private fun extractYtCfg(html: String): String? {
        val regex = Regex("""ytcfg\.set\(\s*(\{.*?\})\s*\)\s*;""")
        return regex.find(html)?.groupValues?.getOrNull(1)
    }

    data class PageConfig(
        @JsonProperty("INNERTUBE_API_KEY")
        val apiKey: String,
        @JsonProperty("INNERTUBE_CLIENT_VERSION")
        val clientVersion: String = "2.20240725.01.00",
        @JsonProperty("VISITOR_DATA")
        val visitorData: String = ""
    )

    private suspend fun getPageConfig(videoId: String): PageConfig? {
        val html = app.get("$mainUrl/watch?v=$videoId", headers = HEADERS).text
        val ytcfgJson = extractYtCfg(html)
        return parseJsonSafe(ytcfgJson, PageConfig::class.java)
    }

    fun extractYouTubeId(url: String): String {
        return when {
            url.contains("watch?v=") -> url.substringAfter("watch?v=").substringBefore("&").substringBefore("#")
            url.contains("youtu.be/") -> url.substringAfter("youtu.be/").substringBefore("?").substringBefore("#")
            url.contains("/shorts/") -> url.substringAfter("/shorts/").substringBefore("?").substringBefore("#")
            url.contains("/embed/") -> url.substringAfter("/embed/").substringBefore("?").substringBefore("#")
            url.contains("v%3D") -> url.substringAfter("v%3D").substringBefore("%26").substringBefore("#")
            else -> error("No YouTube ID found in URL: $url")
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
            "videoId": "$videoId",
            "playbackContext": {
                "contentPlaybackContext": {
                    "html5Preference": "HTML5_PREF_WANTS"
                }
            }
        }
        """.toRequestBody("application/json; charset=utf-8".toMediaType())

        val response = app
            .post("$mainUrl/youtubei/v1/player?key=${config.apiKey}", headers = HEADERS, requestBody = jsonBody)
            .parsed(Root::class.java) // uso classe esplicita

        response.captions?.playerCaptionsTracklistRenderer?.captionTracks?.forEach { caption ->
            subtitleCallback(
                newSubtitleFile(caption.name.simpleText, "${caption.baseUrl}&fmt=ttml") {
                    this.headers = HEADERS
                }
            )
        }

        val hlsUrl = response.streamingData.hlsManifestUrl
        val playlistText = app.get(hlsUrl, headers = HEADERS).text
        val playlist = HlsPlaylistParser.parse(hlsUrl, playlistText) ?: return

        var variantIndex = 0
        for (tag in playlist.tags) {
            if (!tag.trim().startsWith("#EXT-X-STREAM-INF")) continue

            val variant = playlist.variants.getOrNull(variantIndex++) ?: continue
            val audioId = tag.split(",").find { it.trim().startsWith("YT-EXT-AUDIO-CONTENT-ID=") }
                ?.split("=")?.get(1)?.trim('"') ?: ""
            val langString = SubtitleHelper.fromTagToEnglishLanguageName(audioId.substringBefore("."))
                ?: SubtitleHelper.fromTagToEnglishLanguageName(audioId.substringBefore("-"))
                ?: audioId

            val streamUrl = variant.url.toString()
            if (streamUrl.isBlank()) continue

            callback(
                newExtractorLink(
                    source = name,
                    name = "YouTube${if (langString.isNotBlank()) " $langString" else ""}",
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = variant.format.height
                }
            )
        }
    }

    // --- Data classes JSON ---
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

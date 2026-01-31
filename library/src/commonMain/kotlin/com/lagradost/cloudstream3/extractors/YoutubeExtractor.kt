// Made for Cloudstream3 - Updated YouTube Extractor
package com.lagradost.cloudstream3.extractors

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder

class YoutubeShortLinkExtractor : YoutubeExtractor() {
    override val mainUrl = "https://youtu.be"
}

class YoutubeMobileExtractor : YoutubeExtractor() {
    override val mainUrl = "https://m.youtube.com"
}

class YoutubeNoCookieExtractor : YoutubeExtractor() {
    override val mainUrl = "https://www.youtube-nocookie.com"
}

open class YoutubeExtractor : ExtractorApi() {
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false
    override val name = "YouTube"

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; Pixel 5 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.5993.89 Mobile Safari/537.36"
        private val HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept-Language" to "en-US,en;q=0.5"
        )
    }

    // -----------------------------
    // Page config (WEB non più necessario)
    // -----------------------------
    private fun extractYouTubeId(url: String): String {
        return when {
            url.contains("watch?v=") -> url.substringAfter("watch?v=").substringBefore("&").substringBefore("#")
            url.contains("&v=") -> url.substringAfter("&v=").substringBefore("&").substringBefore("#")
            url.contains("youtu.be/") -> url.substringAfter("youtu.be/").substringBefore("?").substringBefore("#")
            url.contains("/embed/") -> url.substringAfter("/embed/").substringBefore("?").substringBefore("#")
            url.contains("/shorts/") -> url.substringAfter("/shorts/").substringBefore("?").substringBefore("#")
            url.contains("/live/") -> url.substringAfter("/live/").substringBefore("?").substringBefore("#")
            url.contains("watch%3Fv%3D") -> url.substringAfter("watch%3Fv%3D").substringBefore("%26").substringBefore("#")
            url.contains("v%3D") -> url.substringAfter("v%3D").substringBefore("%26").substringBefore("#")
            else -> error("No Id Found")
        }
    }

    // -----------------------------
    // Main URL extraction
    // -----------------------------
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = extractYouTubeId(url)

        // JSON body con client ANDROID
        val jsonBody = """
        {
          "context": {
            "client": {
              "clientName": "ANDROID",
              "clientVersion": "18.11.34",
              "androidSdkVersion": 30,
              "hl": "en",
              "gl": "US"
            }
          },
          "videoId": "$videoId"
        }
        """.toRequestBody("application/json; charset=utf-8".toMediaType())

        val response = app.post(
            "$mainUrl/youtubei/v1/player",
            headers = HEADERS,
            requestBody = jsonBody
        ).parsed<Root>()

        // Sottotitoli
        val captionTracks = response.captions?.playerCaptionsTracklistRenderer?.captionTracks
        if (captionTracks != null) {
            for (caption in captionTracks) {
                subtitleCallback(
                    newSubtitleFile(
                        lang = caption.name.simpleText,
                        url = "${caption.baseUrl}&fmt=ttml"
                    ) {
                        headers = HEADERS
                    }
                )
            }
        }

        // HLS Manifest
        val hlsUrl = response.streamingData.hlsManifestUrl
        if (!hlsUrl.isNullOrBlank()) {
            val getHls = app.get(hlsUrl, headers = HEADERS).text
            val playlist = HlsPlaylistParser.parse(hlsUrl, getHls) ?: return

            var variantIndex = 0
            for (tag in playlist.tags) {
                if (!tag.trim().startsWith("#EXT-X-STREAM-INF")) continue

                val variant = playlist.variants.getOrNull(variantIndex++) ?: continue
                val urlVariant = variant.url.toString()
                if (urlVariant.isBlank()) continue

                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "YouTube ${variant.format.height}p",
                        url = urlVariant,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                        this.quality = variant.format.height
                    }
                )
            }
        }
    }

    // -----------------------------
    // Data classes
    // -----------------------------
    private data class Root(
        @JsonProperty("streamingData")
        val streamingData: StreamingData,
        @JsonProperty("captions")
        val captions: Captions?
    )

    private data class StreamingData(
        @JsonProperty("hlsManifestUrl")
        val hlsManifestUrl: String
    )

    private data class Captions(
        @JsonProperty("playerCaptionsTracklistRenderer")
        val playerCaptionsTracklistRenderer: PlayerCaptionsTracklistRenderer?
    )

    private data class PlayerCaptionsTracklistRenderer(
        @JsonProperty("captionTracks")
        val captionTracks: List<CaptionTrack>?
    )

    private data class CaptionTrack(
        @JsonProperty("baseUrl")
        val baseUrl: String,
        @JsonProperty("name")
        val name: Name
    )

    private data class Name(
        @JsonProperty("simpleText")
        val simpleText: String
    )
}

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
    private val youtubeUrl = "https://www.youtube.com"

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.0.0 Mobile Safari/537.36"
        private val HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept-Language" to "en-US,en;q=0.5"
        )
    }

    private fun extractYtCfg(html: String): String? {
        val regex = Regex("""ytcfg\.set\(\s*(\{.*?\})\s*\)\s*;""")
        val match = regex.find(html)
        return match?.groupValues?.getOrNull(1)
    }

    data class PageConfig(
        @JsonProperty("INNERTUBE_API_KEY")
        val apiKey: String,
        @JsonProperty("INNERTUBE_CLIENT_VERSION")
        val clientVersion: String = "18.51.36",
        @JsonProperty("VISITOR_DATA")
        val visitorData: String = ""
    )

    private suspend fun getPageConfig(videoId: String): PageConfig? =
        tryParseJson(extractYtCfg(app.get("$mainUrl/watch?v=$videoId", headers = HEADERS).text))

    fun extractYouTubeId(url: String): String {
        return when {
            url.contains("oembed") && url.contains("url=") -> {
                val decodedUrl = URLDecoder.decode(url.substringAfter("url=").substringBefore("&"), "UTF-8")
                extractYouTubeId(decodedUrl)
            }
            url.contains("attribution_link") && url.contains("u=") -> {
                val decodedUrl = URLDecoder.decode(url.substringAfter("u=").substringBefore("&"), "UTF-8")
                extractYouTubeId(decodedUrl)
            }
            url.contains("watch?v=") -> url.substringAfter("watch?v=").substringBefore("&").substringBefore("#")
            url.contains("&v=") -> url.substringAfter("&v=").substringBefore("&").substringBefore("#")
            url.contains("youtu.be/") -> url.substringAfter("youtu.be/").substringBefore("?").substringBefore("#").substringBefore("&")
            url.contains("/embed/") -> url.substringAfter("/embed/").substringBefore("?").substringBefore("#")
            url.contains("/v/") -> url.substringAfter("/v/").substringBefore("?").substringBefore("#")
            url.contains("/e/") -> url.substringAfter("/e/").substringBefore("?").substringBefore("#")
            url.contains("/shorts/") -> url.substringAfter("/shorts/").substringBefore("?").substringBefore("#")
            url.contains("/live/") -> url.substringAfter("/live/").substringBefore("?").substringBefore("#")
            url.contains("/watch/") -> url.substringAfter("/watch/").substringBefore("?").substringBefore("#")
            url.contains("watch%3Fv%3D") -> url.substringAfter("watch%3Fv%3D").substringBefore("%26").substringBefore("#")
            url.contains("v%3D") -> url.substringAfter("v%3D").substringBefore("%26").substringBefore("#")
            else -> error("No Id Found")
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
                    "clientName": "ANDROID",
                    "clientVersion": "${config.clientVersion}",
                    "visitorData": "${config.visitorData}",
                    "androidSdkVersion": 33,
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
        """.trimIndent().toRequestBody("application/json; charset=utf-8".toMediaType())

        val response = try {
            app.post(
                "$youtubeUrl/youtubei/v1/player?key=${config.apiKey}",
                headers = HEADERS,
                requestBody = jsonBody
            ).parsed<Root>()
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }

        // Sottotitoli
        response.captions?.playerCaptionsTracklistRenderer?.captionTracks?.forEach { caption ->
            subtitleCallback(
                newSubtitleFile(
                    lang = caption.name.simpleText,
                    url = "${caption.baseUrl}&fmt=ttml"
                ) { headers = HEADERS }
            )
        }

        // Streaming URL HLS
        response.streamingData?.hlsManifestUrl?.let { hlsUrl ->
            val playlistText = app.get(hlsUrl, headers = HEADERS).text
            val playlist = HlsPlaylistParser.parse(hlsUrl, playlistText) ?: return
            playlist.variants.forEachIndexed { index, variant ->
                val audioId = playlist.tags.getOrNull(index)?.split(",")?.find { it.startsWith("YT-EXT-AUDIO-CONTENT-ID=") }
                    ?.split("=")?.getOrNull(1)?.trim('"') ?: ""
                val lang = SubtitleHelper.fromTagToEnglishLanguageName(audioId.substringBefore(".")) ?: audioId
                callback(
                    newExtractorLink(
                        source = name,
                        name = "Youtube ${if (lang.isNotBlank()) lang else ""}",
                        url = variant.url.toString(),
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = variant.format.height
                    }
                )
            }
        } ?: run {
            // Fallback su MP4
            response.streamingData?.formats?.forEach { format ->
                if (format.mimeType.startsWith("video")) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "Youtube ${format.qualityLabel}",
                            url = format.url,
                            type = ExtractorLinkType.MP4
                        ) { this.quality = format.height }
                    )
                }
            }
        }
    }

    private data class Root(
        @JsonProperty("streamingData")
        val streamingData: StreamingData?,
        @JsonProperty("captions")
        val captions: Captions?
    )

    private data class StreamingData(
        @JsonProperty("hlsManifestUrl")
        val hlsManifestUrl: String?,
        @JsonProperty("formats")
        val formats: List<Format> = emptyList()
    )

    private data class Format(
        @JsonProperty("url")
        val url: String,
        @JsonProperty("qualityLabel")
        val qualityLabel: String = "",
        @JsonProperty("height")
        val height: Int = 0,
        @JsonProperty("mimeType")
        val mimeType: String = ""
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

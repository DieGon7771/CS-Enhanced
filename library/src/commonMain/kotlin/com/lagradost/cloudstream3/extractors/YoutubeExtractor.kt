// Made For cs-kraptor By @trup40, @kraptor123, @ByAyzen
package com.lagradost.cloudstream3.extractors

import com.lagradost.api.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.HlsPlaylistParser
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.newExtractorLink
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

    companion object {
        private const val TAG = "YT-EXTRACTOR"
        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15"
        private val HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept-Language" to "en-US,en;q=0.5"
        )
    }

    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false
    override val name = "YouTube"
    private val youtubeUrl = "https://www.youtube.com"

    private fun extractYtCfg(html: String): String? {
        Log.d(TAG, "extractYtCfg: html length=${html.length}")
        val regex = Regex("""ytcfg\.set\(\s*(\{.*?\})\s*\)\s*;""")
        val match = regex.find(html)
        Log.d(TAG, "extractYtCfg: found=${match != null}")
        return match?.groupValues?.getOrNull(1)
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
        Log.d(TAG, "getPageConfig: videoId=$videoId")
        val html = app.get("$mainUrl/watch?v=$videoId", headers = HEADERS).text
        val cfg = extractYtCfg(html)
        Log.d(TAG, "getPageConfig: cfg null=${cfg == null}")
        return tryParseJson(cfg)
    }

    fun extractYouTubeId(url: String): String {
        Log.d(TAG, "extractYouTubeId: input=$url")
        return when {
            url.contains("oembed") && url.contains("url=") -> {
                val encodedUrl = url.substringAfter("url=").substringBefore("&")
                extractYouTubeId(URLDecoder.decode(encodedUrl, "UTF-8"))
            }
            url.contains("attribution_link") && url.contains("u=") -> {
                val encodedUrl = url.substringAfter("u=").substringBefore("&")
                extractYouTubeId(URLDecoder.decode(encodedUrl, "UTF-8"))
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
        Log.d(TAG, "getUrl START url=$url")

        val videoId = extractYouTubeId(url)
        val config = getPageConfig(videoId)

        if (config == null) {
            Log.e(TAG, "getUrl: PageConfig NULL")
            return
        }

        Log.d(TAG, "getUrl: apiKey=${config.apiKey.take(8)}...")

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

        Log.d(TAG, "POST /youtubei/v1/player")

        val response = app.post(
            "$youtubeUrl/youtubei/v1/player?key=${config.apiKey}",
            headers = HEADERS,
            requestBody = jsonBody
        ).parsed<Root>()

        Log.d(TAG, "player response OK")

        val captionTracks = response.captions?.playerCaptionsTracklistRenderer?.captionTracks
        Log.d(TAG, "captions count=${captionTracks?.size ?: 0}")

        captionTracks?.forEach {
            subtitleCallback.invoke(
                newSubtitleFile(
                    lang = it.name.simpleText,
                    url = "${it.baseUrl}&fmt=ttml"
                ) { headers = HEADERS }
            )
        }

        val hlsUrl = response.streamingData.hlsManifestUrl
        Log.d(TAG, "hlsManifestUrl=${hlsUrl.take(120)}")

        val getHls = app.get(hlsUrl, headers = HEADERS).text
        Log.d(TAG, "HLS playlist length=${getHls.length}")

        val playlist = HlsPlaylistParser.parse(hlsUrl, getHls)
        if (playlist == null) {
            Log.e(TAG, "HLS PARSE FAILED")
            return
        }

        Log.d(TAG, "playlist tags=${playlist.tags.size} variants=${playlist.variants.size}")

        var variantIndex = 0
        for (tag in playlist.tags) {
            val trimmedTag = tag.trim()
            if (!trimmedTag.startsWith("#EXT-X-STREAM-INF")) continue

            val currentIndex = variantIndex
            variantIndex++
            
            val variant = playlist.variants.getOrNull(currentIndex)
            if (variant == null) {
                Log.e(TAG, "variant NULL at index=$currentIndex")
                continue
            }

            val audioId = trimmedTag.split(",")
                .find { it.trim().startsWith("YT-EXT-AUDIO-CONTENT-ID=") }
                ?.split("=")
                ?.get(1)
                ?.trim('"') ?: ""

            val langString =
                SubtitleHelper.fromTagToEnglishLanguageName(audioId.substringBefore("."))
                    ?: SubtitleHelper.fromTagToEnglishLanguageName(audioId.substringBefore("-"))
                    ?: audioId

            val streamUrl = variant.url.toString()
            Log.d(TAG, "variant url=${streamUrl.take(120)} quality=${variant.format.height}")

            if (streamUrl.isBlank()) continue

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "Youtube${if (langString.isNotBlank()) " $langString" else ""}",
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = "$mainUrl/"
                    quality = variant.format.height
                }
            )
        }

        Log.d(TAG, "getUrl END")
    }

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

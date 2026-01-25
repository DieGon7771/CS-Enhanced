package com.lagradost.cloudstream3.plugins

import android.util.Log
import android.widget.Toast
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.R
import java.security.MessageDigest
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.Coroutines.main
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object VotingApi { // please do not cheat the votes lol
    private const val LOGKEY = "VotingApi"

    // CORRETTO: URL API nel formato giusto
    private const val API_DOMAIN = "https://counterapi.com/api"

    private fun transformUrl(url: String): String = // dont touch or all votes get reset
        MessageDigest
            .getInstance("SHA-256")
            .digest("${url}#funny-salt".toByteArray())
            .fold("") { str, it -> str + "%02x".format(it) }

    suspend fun SitePlugin.getVotes(): Int {
        return getVotes(url)
    }

    fun SitePlugin.hasVoted(): Boolean {
        return hasVoted(url)
    }

    suspend fun SitePlugin.vote(): Int {
        return vote(url)
    }

    fun SitePlugin.canVote(): Boolean {
        return canVote(this.url)
    }

    // Plugin url to Int
    private val votesCache = mutableMapOf<String, Int>()

    private fun getRepository(pluginUrl: String) = pluginUrl
        .split("/")
        .drop(2)
        .take(3)
        .joinToString("-")

    private suspend fun readVote(pluginUrl: String): Int {
        // MODIFICATO: Costruisce l'URL nel formato corretto
        val namespace = "cs-${getRepository(pluginUrl)}"
        val action = "vote"
        val key = transformUrl(pluginUrl)
        
        val url = "$API_DOMAIN/$namespace/$action/$key?readOnly=true"
        Log.d(LOGKEY, "Requesting: $url")
        
        return try {
            app.get(url).parsedSafe<Result>()?.value ?: 0
        } catch (e: Exception) {
            Log.e(LOGKEY, "Failed to read votes: ${e.message}")
            0
        }
    }

    private suspend fun writeVote(pluginUrl: String): Boolean {
        // MODIFICATO: Costruisce l'URL nel formato corretto
        val namespace = "cs-${getRepository(pluginUrl)}"
        val action = "vote"
        val key = transformUrl(pluginUrl)
        
        val url = "$API_DOMAIN/$namespace/$action/$key"
        Log.d(LOGKEY, "Requesting: $url")
        
        return try {
            app.get(url).parsedSafe<Result>()?.value != null
        } catch (e: Exception) {
            Log.e(LOGKEY, "Failed to write vote: ${e.message}")
            false
        }
    }

    suspend fun getVotes(pluginUrl: String): Int =
            votesCache[pluginUrl] ?: readVote(pluginUrl).also {
                votesCache[pluginUrl] = it
            }

    fun hasVoted(pluginUrl: String) =
        getKey("cs3-votes/${transformUrl(pluginUrl)}") ?: false

    fun canVote(pluginUrl: String): Boolean {
        return PluginManager.urlPlugins.contains(pluginUrl)
    }

    private val voteLock = Mutex()
    suspend fun vote(pluginUrl: String): Int {
        // Prevent multiple requests at the same time.
        voteLock.withLock {
            if (!canVote(pluginUrl)) {
                main {
                    Toast.makeText(context, R.string.extension_install_first, Toast.LENGTH_SHORT)
                        .show()
                }
                return getVotes(pluginUrl)
            }

            if (hasVoted(pluginUrl)) {
                main {
                    Toast.makeText(context, R.string.already_voted, Toast.LENGTH_SHORT)
                        .show()
                }
                return getVotes(pluginUrl)
            }


            if (writeVote(pluginUrl)) {
                setKey("cs3-votes/${transformUrl(pluginUrl)}", true)
                votesCache[pluginUrl] = votesCache[pluginUrl]?.plus(1) ?: 1
            }

            return getVotes(pluginUrl)
        }
    }

    private data class Result(
        val value: Int?
    )
}

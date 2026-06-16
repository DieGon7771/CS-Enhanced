package com.lagradost.cloudstream3.ui.actor

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.databinding.FragmentActorFilmographyBinding
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import kotlinx.coroutines.Job
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class ActorFilmographyFragment : BaseFragment<FragmentActorFilmographyBinding>(
    BaseFragment.BindingCreator.Inflate(FragmentActorFilmographyBinding::inflate)
) {
    private var actorId: Int = 0
    private lateinit var actorName: String
    private var actorImageUrl: String? = null

    private val filmographyList = mutableListOf<FilmographyItem>()
    private val seenIds = mutableSetOf<Int>()

    private val popularAdapter = ActorFilmographyAdapter { item -> onFilmographyItemClick(item) }
    private val latestAdapter = ActorFilmographyAdapter { item -> onFilmographyItemClick(item) }
    private val upcomingAdapter = ActorFilmographyAdapter { item -> onFilmographyItemClick(item) }

    private var loadJob: Job? = null

    companion object {
        private const val TMDB_API_KEY = "e6333b32409e02a4a6eba6fb7ff866bb"
    }

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(view)
    }

    override fun onBindingCreated(binding: FragmentActorFilmographyBinding) {
        actorId = arguments?.getInt("actor_id") ?: 0
        actorName = arguments?.getString("actor_name") ?: ""
        actorImageUrl = arguments?.getString("actor_image")

        if (actorId == 0) {
            findNavController().popBackStack()
            return
        }

        setupViews(binding)
        setupRecyclerViews(binding)
        loadFilmography(binding)
    }

    private fun setupViews(binding: FragmentActorFilmographyBinding) {
        binding.actorName.text = actorName
        if (!actorImageUrl.isNullOrEmpty()) {
            binding.actorImage.loadImage(actorImageUrl)
        }
        binding.backButton.setOnClickListener {
            findNavController().popBackStack()
        }
    }

    private fun setupRecyclerViews(binding: FragmentActorFilmographyBinding) {
        binding.popularRecycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
            requireContext(), androidx.recyclerview.widget.RecyclerView.HORIZONTAL, false
        )
        binding.popularRecycler.adapter = popularAdapter

        binding.latestRecycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
            requireContext(), androidx.recyclerview.widget.RecyclerView.HORIZONTAL, false
        )
        binding.latestRecycler.adapter = latestAdapter

        binding.upcomingRecycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
            requireContext(), androidx.recyclerview.widget.RecyclerView.HORIZONTAL, false
        )
        binding.upcomingRecycler.adapter = upcomingAdapter
    }

    private fun loadFilmography(binding: FragmentActorFilmographyBinding) {
        binding.loadingIndicator.visibility = View.VISIBLE

        loadJob = ioSafe {
            try {
                val language = getTmdbLanguageCode()
                val creditsJson = fetchCombinedCredits(language)

                processCredits(creditsJson)

                main { bindFilmography(binding) }
            } catch (e: Exception) {
                logError(e)
                main { showError(binding) }
            }
        }
    }

    private suspend fun fetchCombinedCredits(language: String): JSONArray {
        val response = app.get(
            "https://api.themoviedb.org/3/person/$actorId/combined_credits",
            params = mapOf("api_key" to TMDB_API_KEY, "language" to language)
        )
        return JSONObject(response.text).optJSONArray("cast") ?: JSONArray()
    }

    private fun processCredits(cast: JSONArray) {
        filmographyList.clear()
        seenIds.clear()

        for (i in 0 until cast.length()) {
            try {
                val item = cast.getJSONObject(i)
                val id = item.optInt("id")
                if (seenIds.contains(id)) continue
                if (shouldSkipTitle(item)) continue

                val title = item.optString("title", item.optString("name", ""))
                val mediaType = item.optString("media_type", "")
                val releaseDateStr = item.optString("release_date", item.optString("first_air_date", ""))

                seenIds.add(id)

                filmographyList.add(
                    FilmographyItem(
                        id = id,
                        title = title,
                        posterPath = item.optString("poster_path", null),
                        releaseDate = releaseDateStr,
                        mediaType = mediaType
                    )
                )
            } catch (e: Exception) {
                logError(e)
            }
        }
    }

    private fun shouldSkipTitle(item: JSONObject): Boolean {
        val title = item.optString("title", item.optString("name", "")).lowercase(Locale.US)
        if (title.contains("talk show") || title.contains("snl") ||
            title.contains("saturday night live") || title.contains("late night")
        ) return true
        return false
    }

    private fun bindFilmography(binding: FragmentActorFilmographyBinding) {
        binding.loadingIndicator.visibility = View.GONE

        if (filmographyList.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            return
        }

        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        val popular = filmographyList
            .filterNot { it.releaseDate.orEmpty() > todayStr }
            .sortedByDescending { it.releaseDate.orEmpty() }

        val latest = filmographyList
            .filter { !it.releaseDate.orEmpty().isNullOrEmpty() && it.releaseDate.orEmpty() <= todayStr }
            .sortedByDescending { it.releaseDate.orEmpty() }
            .take(30)

        val upcoming = filmographyList
            .filter { !it.releaseDate.orEmpty().isNullOrEmpty() && it.releaseDate.orEmpty() > todayStr }
            .sortedBy { it.releaseDate.orEmpty() }

        if (popular.isNotEmpty()) {
            popularAdapter.submitList(popular)
            binding.popularSection.visibility = View.VISIBLE
        }
        if (latest.isNotEmpty()) {
            latestAdapter.submitList(latest)
            binding.latestSection.visibility = View.VISIBLE
        }
        if (upcoming.isNotEmpty()) {
            upcomingAdapter.submitList(upcoming)
            binding.upcomingSection.visibility = View.VISIBLE
        }
        if (popular.isEmpty() && latest.isEmpty() && upcoming.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
        }
    }

    private fun onFilmographyItemClick(item: FilmographyItem) {
        QuickSearchFragment.pushSearch(requireActivity(), item.title)
    }

    private fun showError(binding: FragmentActorFilmographyBinding) {
        binding.loadingIndicator.visibility = View.GONE
    }

    private fun getTmdbLanguageCode(): String {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
        return prefs.getString("locale_key", "en-US") ?: "en-US"
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        super.onDestroyView()
    }
}

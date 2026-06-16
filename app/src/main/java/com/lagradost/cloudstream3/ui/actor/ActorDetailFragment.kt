package com.lagradost.cloudstream3.ui.actor

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.databinding.FragmentActorDetailBinding
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class ActorDetailFragment : BaseFragment<FragmentActorDetailBinding>(
    BaseFragment.BindingCreator.Inflate(FragmentActorDetailBinding::inflate)
) {
    private var actorId: Int = 0
    private lateinit var actorName: String
    private var actorImage: String? = null

    private val filmographyList = mutableListOf<FilmographyItem>()
    private val seenIds = mutableSetOf<Int>()

    private val popularAdapter = ActorFilmographyAdapter { item -> onFilmographyItemClick(item) }
    private val latestAdapter = ActorFilmographyAdapter { item -> onFilmographyItemClick(item) }
    private val upcomingAdapter = ActorFilmographyAdapter { item -> onFilmographyItemClick(item) }

    companion object {
        private const val TMDB_API_KEY = "e6333b32409e02a4a6eba6fb7ff866bb"
        private const val TAG = "ActorDetail"
    }

    override fun fixLayout(view: View) = Unit

    override fun onBindingCreated(binding: FragmentActorDetailBinding) {
        actorId = arguments?.getInt("actor_id") ?: 0
        actorName = arguments?.getString("actor_name") ?: ""
        actorImage = arguments?.getString("actor_image")

        if (actorId == 0) {
            findNavController().popBackStack()
            return
        }

        setupViews()
        setupRecyclerViews()
        setupScrollListener()
        loadData()
    }

    private fun setupViews() {
        with(binding) {
            actorName.text = this@ActorDetailFragment.actorName
            if (!actorImage.isNullOrEmpty()) {
                actorImage.loadImage(actorImage)
            }
            backButton.setOnClickListener {
                findNavController().popBackStack()
            }
        }
    }

    private fun setupRecyclerViews() {
        with(binding) {
            popularRecycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                requireContext(), androidx.recyclerview.widget.RecyclerView.HORIZONTAL, false
            )
            popularRecycler.adapter = popularAdapter

            latestRecycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                requireContext(), androidx.recyclerview.widget.RecyclerView.HORIZONTAL, false
            )
            latestRecycler.adapter = latestAdapter

            upcomingRecycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                requireContext(), androidx.recyclerview.widget.RecyclerView.HORIZONTAL, false
            )
            upcomingRecycler.adapter = upcomingAdapter
        }
    }

    private fun setupScrollListener() {
        binding.nestedScrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val avatar = binding.actorImage
            val threshold = 100
            val maxShrink = 56 // 120 - 64
            val shrink = (scrollY.toFloat() / threshold).coerceAtMost(1f)
            val newSize = (120 - (maxShrink * shrink)).toInt()

            val layoutParams = avatar.layoutParams
            if (layoutParams.width != newSize) {
                layoutParams.width = newSize
                layoutParams.height = newSize
                avatar.layoutParams = layoutParams
            }
        }
    }

    private fun loadData() {
        binding.loadingIndicator.visibility = View.VISIBLE

        ioSafe {
            try {
                val language = getTmdbLanguageCode()
                val personJson = fetchPersonDetails(language)
                val creditsJson = fetchCombinedCredits(language)

                main { bindPersonDetails(personJson) }

                processCredits(creditsJson)

                main { bindFilmography() }
            } catch (e: Exception) {
                logError(e)
                main { showError() }
            }
        }
    }

    private fun fetchPersonDetails(language: String): JSONObject {
        val response = app.get(
            "https://api.themoviedb.org/3/person/$actorId",
            params = mapOf("api_key" to TMDB_API_KEY, "language" to language)
        )
        return JSONObject(response.text)
    }

    private fun fetchCombinedCredits(language: String): JSONArray {
        val response = app.get(
            "https://api.themoviedb.org/3/person/$actorId/combined_credits",
            params = mapOf("api_key" to TMDB_API_KEY, "language" to language)
        )
        return JSONObject(response.text).optJSONArray("cast") ?: JSONArray()
    }

    private fun bindPersonDetails(json: JSONObject) {
        val birthday = json.optString("birthday", null)
        val deathday = json.optString("deathday", null)
        val placeOfBirth = json.optString("place_of_birth", null)
        val gender = json.optInt("gender", 0)
        val biography = json.optString("biography", "")
        val knownForDepartment = json.optString("known_for_department", "")

        with(binding) {
            actorDepartment.text = knownForDepartment

            actorGender.text = when (gender) {
                1 -> getString(R.string.actor_female)
                2 -> getString(R.string.actor_male)
                else -> getString(R.string.actor_not_specified)
            }

            if (!birthday.isNullOrEmpty()) {
                actorBirthday.text = formatDate(birthday)

                if (!deathday.isNullOrEmpty() && deathday != "null") {
                    actorDeathday.text = formatDate(deathday)
                    deathLayout.visibility = View.VISIBLE
                    actorAge.text = "${calculateAge(birthday, deathday)} ${getString(R.string.actor_years_old)}"
                } else {
                    deathLayout.visibility = View.GONE
                    actorAge.text = "${calculateAge(birthday, null)} ${getString(R.string.actor_years_old)}"
                }
            } else {
                actorBirthday.visibility = View.GONE
                deathLayout.visibility = View.GONE
            }

            actorBirthplace.text = if (!placeOfBirth.isNullOrEmpty() && placeOfBirth != "null") {
                placeOfBirth
            } else {
                getString(R.string.actor_unknown)
            }

            actorBio.text = if (biography.isNotEmpty()) {
                biography
            } else {
                getString(R.string.actor_no_biography)
            }
        }
    }

    private fun processCredits(cast: JSONArray) {
        filmographyList.clear()
        seenIds.clear()
        val currentDate = Date()

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
        val overview = item.optString("overview", "").lowercase(Locale.US)

        if (title.contains("talk show") || title.contains("snl") ||
            title.contains("saturday night live") || title.contains("late night")
        ) return true

        return false
    }

    private fun bindFilmography() {
        binding.loadingIndicator.visibility = View.GONE

        if (filmographyList.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            return
        }

        val currentDate = Date()
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(currentDate)

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

    private fun showError() {
        binding.loadingIndicator.visibility = View.GONE
        binding.actorBio.text = getString(R.string.actor_error)
    }

    private fun formatDate(dateString: String): String {
        return try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val outputFormat = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
            val date = inputFormat.parse(dateString)
            outputFormat.format(date)
        } catch (e: Exception) {
            dateString
        }
    }

    private fun calculateAge(birthday: String, deathday: String?): Int {
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val birthDate = format.parse(birthday) ?: return 0
            val endDate = if (!deathday.isNullOrEmpty() && deathday != "null") {
                format.parse(deathday) ?: Date()
            } else {
                Date()
            }

            val birthCalendar = Calendar.getInstance().apply { time = birthDate }
            val endCalendar = Calendar.getInstance().apply { time = endDate }

            var age = endCalendar.get(Calendar.YEAR) - birthCalendar.get(Calendar.YEAR)

            if (endCalendar.get(Calendar.DAY_OF_YEAR) < birthCalendar.get(Calendar.DAY_OF_YEAR)) {
                age--
            }

            age
        } catch (e: Exception) {
            0
        }
    }

    private fun getTmdbLanguageCode(): String {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
        return prefs.getString("locale_key", "en-US") ?: "en-US"
    }
}

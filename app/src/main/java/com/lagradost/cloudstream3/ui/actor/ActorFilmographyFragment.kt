package com.lagradost.cloudstream3.ui.actor

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.databinding.FragmentActorFilmographyBinding
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.UIHelper.fixPaddingStatusbarMargin
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import kotlinx.coroutines.Job
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

class ActorFilmographyFragment : BaseFragment<FragmentActorFilmographyBinding>(
    BaseFragment.BindingCreator.Inflate(FragmentActorFilmographyBinding::inflate)
) {
    private var actorId: Int = 0
    private lateinit var actorName: String
    private var actorImageUrl: String? = null

    private val filmographyList = mutableListOf<FilmographyItem>()
    private val seenIds = mutableSetOf<Int>()

    private var knownForItems = listOf<FilmographyItem>()
    private var biography: String? = null
    private var knownForDepartment: String? = null

    private val knownForAdapter = ActorFilmographyAdapter { item -> onFilmographyItemClick(item) }
    private val filmographyAdapter = ActorFilmographyAdapter { item -> onFilmographyItemClick(item) }

    private var selectedMediaType = "movie"
    private var loadJob: Job? = null

    private data class PersonCacheData(
        val credits: List<FilmographyItem>,
        val biography: String?,
        val knownForDepartment: String?
    )

    companion object {
        private const val TMDB_API_KEY = "e6333b32409e02a4a6eba6fb7ff866bb"
        private val personCache = mutableMapOf<Int, PersonCacheData>()
    }

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(view, padTop = false)
        fixPaddingStatusbarMargin(binding?.backButton)
    }

    override fun onBindingCreated(binding: FragmentActorFilmographyBinding) {
        setupTransparentStatusBar()

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

    private fun setupTransparentStatusBar() {
        val activity = activity ?: return
        val window = activity.window ?: return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            window.statusBarColor = Color.TRANSPARENT
        }
    }

    private fun setupViews(binding: FragmentActorFilmographyBinding) {
        binding.actorName.text = actorName
        if (!actorImageUrl.isNullOrEmpty()) {
            binding.heroBackground.loadImage(actorImageUrl) {
                BlurTransformation(25)
            }
            binding.actorProfileImage.loadImage(actorImageUrl)
        }
        binding.backButton.setOnClickListener {
            findNavController().popBackStack()
        }

        binding.knownForTitle.setOnClickListener {
            if (knownForItems.isNotEmpty()) showSectionBottomSheet(getString(R.string.actor_known_for), knownForItems)
        }

        binding.biographyTitle.setOnClickListener {
            if (!biography.isNullOrBlank()) showBiographyDialog()
        }
        binding.biographyText.setOnClickListener {
            if (!biography.isNullOrBlank()) showBiographyDialog()
        }

        binding.moviesTab.setOnClickListener { selectTab(binding, "movie") }
        binding.tvShowsTab.setOnClickListener { selectTab(binding, "tv") }
    }

    private fun selectTab(binding: FragmentActorFilmographyBinding, mediaType: String) {
        if (selectedMediaType == mediaType) return
        selectedMediaType = mediaType

        updateTabStyles(binding)
        updateFilmographyList(binding)
    }

    private fun updateTabStyles(binding: FragmentActorFilmographyBinding) {
        val selectedBg = Color.parseColor("#FF3D3D3D")
        val unselectedBg = Color.TRANSPARENT
        val selectedStroke = Color.parseColor("#FF3D3D3D")
        val unselectedStroke = Color.parseColor("#FF555555")

        binding.moviesTab.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (selectedMediaType == "movie") selectedBg else unselectedBg
        )
        binding.moviesTab.strokeColor = android.content.res.ColorStateList.valueOf(
            if (selectedMediaType == "movie") selectedStroke else unselectedStroke
        )
        binding.tvShowsTab.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (selectedMediaType == "tv") selectedBg else unselectedBg
        )
        binding.tvShowsTab.strokeColor = android.content.res.ColorStateList.valueOf(
            if (selectedMediaType == "tv") selectedStroke else unselectedStroke
        )
    }

    private fun setupRecyclerViews(binding: FragmentActorFilmographyBinding) {
        binding.knownForRecycler.layoutManager = LinearLayoutManager(
            requireContext(), RecyclerView.HORIZONTAL, false
        )
        binding.knownForRecycler.adapter = knownForAdapter

        binding.filmographyRecycler.layoutManager = LinearLayoutManager(
            requireContext(), RecyclerView.HORIZONTAL, false
        )
        binding.filmographyRecycler.adapter = filmographyAdapter
    }

    private fun loadFilmography(binding: FragmentActorFilmographyBinding) {
        val cached = personCache[actorId]
        if (cached != null) {
            filmographyList.clear()
            filmographyList.addAll(cached.credits)
            biography = cached.biography
            knownForDepartment = cached.knownForDepartment
            bindFilmography(binding)
            return
        }

        binding.shimmerLayout.visibility = View.VISIBLE
        binding.shimmerLayout.startShimmer()

        loadJob = ioSafe {
            try {
                val language = getTmdbLanguageCode()
                val creditsJson = fetchCombinedCredits(language)
                processCredits(creditsJson)

                val personJson = fetchPersonDetails(language)
                biography = personJson.optString("biography", null)?.takeIf { it.isNotBlank() }
                knownForDepartment = personJson.optString("known_for_department", null)?.takeIf { it.isNotBlank() }

                personCache[actorId] = PersonCacheData(
                    credits = filmographyList.toList(),
                    biography = biography,
                    knownForDepartment = knownForDepartment
                )

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

    private suspend fun fetchPersonDetails(language: String): JSONObject {
        val response = app.get(
            "https://api.themoviedb.org/3/person/$actorId",
            params = mapOf("api_key" to TMDB_API_KEY, "language" to language)
        )
        return JSONObject(response.text)
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
                        mediaType = mediaType,
                        popularity = item.optDouble("popularity", 0.0)
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
        binding.shimmerLayout.visibility = View.GONE
        binding.shimmerLayout.stopShimmer()

        binding.actorDepartment.text = knownForDepartment
        binding.actorDepartment.visibility = if (knownForDepartment != null) View.VISIBLE else View.GONE

        if (filmographyList.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            return
        }

        knownForItems = filmographyList
            .sortedByDescending { it.popularity }
            .take(10)

        if (knownForItems.isNotEmpty()) {
            knownForAdapter.submitList(knownForItems)
            binding.knownForSection.visibility = View.VISIBLE
        }

        if (!biography.isNullOrBlank()) {
            binding.biographyText.text = biography
            binding.biographySection.visibility = View.VISIBLE
        }

        selectedMediaType = "movie"
        updateTabStyles(binding)
        updateFilmographyList(binding)
    }

    private fun updateFilmographyList(binding: FragmentActorFilmographyBinding) {
        val filtered = filmographyList
            .filter { it.mediaType == selectedMediaType }
            .sortedByDescending { it.releaseDate.orEmpty() }

        if (filtered.isNotEmpty()) {
            filmographyAdapter.submitList(filtered)
            binding.filmographySection.visibility = View.VISIBLE
        } else {
            binding.filmographySection.visibility = View.GONE
        }

        if (knownForItems.isEmpty() && filtered.isEmpty() && biography.isNullOrBlank()) {
            binding.emptyState.visibility = View.VISIBLE
        } else {
            binding.emptyState.visibility = View.GONE
        }
    }

    private fun onFilmographyItemClick(item: FilmographyItem) {
        QuickSearchFragment.pushSearch(requireActivity(), item.title)
    }

    private fun showSectionBottomSheet(title: String, items: List<FilmographyItem>) {
        val dialog = BottomSheetDialog(requireContext())
        val recyclerView = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            adapter = ActorFilmographyAdapter { item ->
                onFilmographyItemClick(item)
                dialog.dismiss()
            }.also { it.submitList(items) }
            val padding = resources.getDimensionPixelSize(R.dimen.result_padding)
            setPadding(padding, padding, padding, padding)
        }
        dialog.setContentView(recyclerView)
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                val params = it.layoutParams
                params.height = ViewGroup.LayoutParams.MATCH_PARENT
                it.layoutParams = params
            }
        }
        dialog.show()
    }

    private fun showBiographyDialog() {
        val activity = activity ?: return
        val text = biography ?: return
        val spanned: Spanned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY)
        } else {
            Html.fromHtml(text)
        }

        val rootView = layoutInflater.inflate(R.layout.bottom_text_dialog, null)
        rootView.findViewById<android.widget.TextView>(R.id.dialog_title).text = getString(R.string.actor_biography)
        rootView.findViewById<android.widget.TextView>(R.id.dialog_text).text = spanned

        val dialog = AlertDialog.Builder(activity)
            .setView(rootView)
            .create()

        dialog.setCanceledOnTouchOutside(true)
        dialog.setCancelable(true)

        dialog.window?.let { window ->
            window.setLayout(
                (resources.displayMetrics.widthPixels * 0.9).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.attributes?.let { params ->
                params.gravity = android.view.Gravity.CENTER
                window.attributes = params
            }
            val drawable = GradientDrawable().apply {
                setColor(Color.parseColor("#FF1E1E1E"))
                cornerRadius = resources.getDimension(R.dimen.rounded_button_radius)
            }
            window.setBackgroundDrawable(drawable)
        }

        dialog.show()
    }

    private fun showError(binding: FragmentActorFilmographyBinding) {
        binding.shimmerLayout.visibility = View.GONE
        binding.shimmerLayout.stopShimmer()
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

package com.lagradost.cloudstream3.ui.actor

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.databinding.FragmentActorPopupBinding
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.BaseDialogFragment
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class ActorPopupDialog : BaseDialogFragment<FragmentActorPopupBinding>(
    BaseFragment.BindingCreator.Inflate(FragmentActorPopupBinding::inflate)
) {
    private var actorId: Int = 0
    private lateinit var actorName: String
    private var actorImageUrl: String? = null

    override fun getTheme(): Int = R.style.DialogHalfFullscreen

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(view, padBottom = false)
    }

    override fun onBindingCreated(binding: FragmentActorPopupBinding) {
        actorId = arguments?.getInt("actor_id") ?: 0
        actorName = arguments?.getString("actor_name") ?: ""
        actorImageUrl = arguments?.getString("actor_image")

        if (actorId == 0) {
            dismiss()
            return
        }

        setupViews(binding)
        loadActorDetails(binding)
    }

    private fun setupViews(binding: FragmentActorPopupBinding) {
        binding.actorName.text = actorName
        if (!actorImageUrl.isNullOrEmpty()) {
            binding.actorImage.loadImage(actorImageUrl)
        }

        binding.filmographyButton.setOnClickListener {
            val activity = activity
            if (activity != null) {
                activity.navigate(
                    R.id.global_to_navigation_actor_detail,
                    Bundle().apply {
                        putInt("actor_id", actorId)
                        putString("actor_name", actorName)
                        putString("actor_image", actorImageUrl)
                    }
                )
            }
            dismiss()
        }

        binding.searchWebButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(android.app.SearchManager.QUERY, actorName)
            }
            startActivity(intent)
            dismiss()
        }
    }

    private fun loadActorDetails(binding: FragmentActorPopupBinding) {
        binding.loadingIndicator.visibility = View.VISIBLE

        ioSafe {
            try {
                val externalResponse = app.get(
                    "https://api.themoviedb.org/3/person/$actorId/external_ids",
                    params = mapOf("api_key" to TMDB_API_KEY)
                )
                val externalJson = JSONObject(externalResponse.text)
                val wikidataId = externalJson.optString("wikidata_id", null)

                if (wikidataId != null && wikidataId.isNotEmpty()) {
                    val lang = getWikidataLanguageCode()
                    val wikidataResponse = app.get(
                        "https://www.wikidata.org/w/api.php",
                        params = mapOf(
                            "action" to "wbgetentities",
                            "ids" to wikidataId,
                            "languages" to "$lang|en",
                            "format" to "json",
                            "props" to "labels|descriptions|claims"
                        )
                    )
                    val wikidataJson = JSONObject(wikidataResponse.text)
                    if (wikidataJson.optInt("success", 0) == 1) {
                        main { bindWikidataDetails(binding, wikidataJson, wikidataId, lang) }
                        return@ioSafe
                    }
                }

                val language = getTmdbLanguageCode()
                val response = app.get(
                    "https://api.themoviedb.org/3/person/$actorId",
                    params = mapOf(
                        "api_key" to TMDB_API_KEY,
                        "language" to language
                    )
                )
                val json = JSONObject(response.text)
                main { bindTmdbDetails(binding, json) }
            } catch (e: Exception) {
                logError(e)
                main {
                    binding.loadingIndicator.visibility = View.GONE
                }
            }
        }
    }

    private fun bindWikidataDetails(binding: FragmentActorPopupBinding, json: JSONObject, qid: String, lang: String) {
        binding.loadingIndicator.visibility = View.GONE

        val entities = json.getJSONObject("entities").getJSONObject(qid)
        val labels = entities.optJSONObject("labels")
        val descriptions = entities.optJSONObject("descriptions")
        val claims = entities.optJSONObject("claims")

        val wdName = labels?.optJSONObject(lang)?.optString("value", null)
            ?: labels?.optJSONObject("en")?.optString("value", null)
        if (wdName != null) {
            binding.actorName.text = wdName
        }

        val desc = descriptions?.optJSONObject(lang)?.optString("value", null)
            ?: descriptions?.optJSONObject("en")?.optString("value", null)
        if (desc != null) {
            binding.actorDepartment.text = desc
            binding.actorDepartment.visibility = View.VISIBLE
        } else {
            binding.actorDepartment.visibility = View.GONE
        }

        if (claims != null) {
            if (claims.has("P569")) {
                val rawTime = extractClaimTime(claims, "P569")
                if (rawTime != null) {
                    binding.actorBirthday.text = formatDate(rawTime.substring(1, 11))
                    binding.rowBirthday.visibility = View.VISIBLE
                } else {
                    binding.rowBirthday.visibility = View.GONE
                }
            } else {
                binding.rowBirthday.visibility = View.GONE
            }

            val deathRaw = if (claims.has("P570")) extractClaimTime(claims, "P570") else null
            if (deathRaw != null) {
                binding.actorDeathday.text = formatDate(deathRaw.substring(1, 11))
                binding.rowDeath.visibility = View.VISIBLE
            } else {
                binding.rowDeath.visibility = View.GONE
            }

            val birthRaw = if (claims.has("P569")) extractClaimTime(claims, "P569") else null
            if (birthRaw != null) {
                val birthDate = birthRaw.substring(1, 11)
                val deathDate = if (deathRaw != null) deathRaw.substring(1, 11) else null
                val age = calculateAge(birthDate, deathDate)
                binding.actorAge.text = "$age ${getString(R.string.actor_years_old)}"
                binding.rowAge.visibility = View.VISIBLE
            } else {
                binding.rowAge.visibility = View.GONE
            }

            if (claims.has("P21")) {
                val genderId = extractClaimQid(claims, "P21")
                binding.actorGender.text = when (genderId) {
                    "Q6581072" -> getString(R.string.actor_female)
                    "Q6581097" -> getString(R.string.actor_male)
                    else -> null
                }
                binding.rowGender.visibility = if (genderId == "Q6581072" || genderId == "Q6581097") View.VISIBLE else View.GONE
            } else {
                binding.rowGender.visibility = View.GONE
            }

            if (claims.has("P2048")) {
                val amount = extractClaimAmount(claims, "P2048")
                if (amount != null) {
                    binding.actorHeight.text = "$amount m"
                    binding.rowHeight.visibility = View.VISIBLE
                } else {
                    binding.rowHeight.visibility = View.GONE
                }
            } else {
                binding.rowHeight.visibility = View.GONE
            }

            if (claims.has("P2067")) {
                try {
                    val weightData = claims.getJSONArray("P2067").getJSONObject(0)
                        .optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optJSONObject("value")
                    val rawAmount = weightData?.optString("amount", null)
                    val unitUrl = weightData?.optString("unit", null)
                    if (rawAmount != null) {
                        val amount = rawAmount.replace("+", "").toDouble()
                        val kg = if (unitUrl?.endsWith("Q100995") == true) amount * 0.453592 else amount
                        binding.actorWeight.text = String.format("%.1f kg", kg)
                        binding.rowWeight.visibility = View.VISIBLE
                    } else {
                        binding.rowWeight.visibility = View.GONE
                    }
                } catch (e: Exception) {
                    binding.rowWeight.visibility = View.GONE
                }
            } else {
                binding.rowWeight.visibility = View.GONE
            }

            binding.rowBirthplace.visibility = View.GONE
        }
    }

    private fun bindTmdbDetails(binding: FragmentActorPopupBinding, json: JSONObject) {
        binding.loadingIndicator.visibility = View.GONE

        val birthday = json.optString("birthday", null)
        val deathday = json.optString("deathday", null)
        val placeOfBirth = json.optString("place_of_birth", null)
        val gender = json.optInt("gender", 0)
        val knownForDepartment = json.optString("known_for_department", "")

        if (knownForDepartment.isNotEmpty()) {
            binding.actorDepartment.text = knownForDepartment
            binding.actorDepartment.visibility = View.VISIBLE
        } else {
            binding.actorDepartment.visibility = View.GONE
        }

        when (gender) {
            1 -> binding.actorGender.text = getString(R.string.actor_female)
            2 -> binding.actorGender.text = getString(R.string.actor_male)
        }
        binding.rowGender.visibility = if (gender == 1 || gender == 2) View.VISIBLE else View.GONE

        if (!birthday.isNullOrEmpty()) {
            binding.actorBirthday.text = formatDate(birthday)
            binding.rowBirthday.visibility = View.VISIBLE
            binding.actorAge.text = "${calculateAge(birthday, deathday)} ${getString(R.string.actor_years_old)}"
            binding.rowAge.visibility = View.VISIBLE
        } else {
            binding.rowBirthday.visibility = View.GONE
            binding.rowAge.visibility = View.GONE
        }

        if (!deathday.isNullOrEmpty() && deathday != "null") {
            binding.actorDeathday.text = formatDate(deathday)
            binding.rowDeath.visibility = View.VISIBLE
        } else {
            binding.rowDeath.visibility = View.GONE
        }

        if (!placeOfBirth.isNullOrEmpty() && placeOfBirth != "null") {
            binding.actorBirthplace.text = placeOfBirth
            binding.rowBirthplace.visibility = View.VISIBLE
        } else {
            binding.rowBirthplace.visibility = View.GONE
        }

        binding.rowHeight.visibility = View.GONE
        binding.rowWeight.visibility = View.GONE
    }

    private fun extractClaimTime(claims: JSONObject, property: String): String? {
        return try {
            claims.getJSONArray(property).getJSONObject(0)
                .optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("time", null)
        } catch (e: Exception) {
            null
        }
    }

    private fun extractClaimQid(claims: JSONObject, property: String): String? {
        return try {
            claims.getJSONArray(property).getJSONObject(0)
                .optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("id", null)
        } catch (e: Exception) {
            null
        }
    }

    private fun extractClaimAmount(claims: JSONObject, property: String): String? {
        return try {
            val raw = claims.getJSONArray(property).getJSONObject(0)
                .optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("amount", null)
            raw?.replace("+", "")
        } catch (e: Exception) {
            null
        }
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

    private fun getWikidataLanguageCode(): String {
        val langTag = getTmdbLanguageCode()
        return langTag.split("-").firstOrNull()?.lowercase(Locale.US) ?: "en"
    }

    companion object {
        private const val TMDB_API_KEY = "e6333b32409e02a4a6eba6fb7ff866bb"

        fun newInstance(actorId: Int, actorName: String, actorImage: String?): ActorPopupDialog {
            val fragment = ActorPopupDialog()
            fragment.arguments = Bundle().apply {
                putInt("actor_id", actorId)
                putString("actor_name", actorName)
                putString("actor_image", actorImage)
            }
            return fragment
        }
    }
}

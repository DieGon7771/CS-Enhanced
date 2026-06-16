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
                val language = getTmdbLanguageCode()
                val response = app.get(
                    "https://api.themoviedb.org/3/person/$actorId",
                    params = mapOf(
                        "api_key" to "e6333b32409e02a4a6eba6fb7ff866bb",
                        "language" to language
                    )
                )
                val json = JSONObject(response.text)

                main { bindPersonDetails(binding, json) }
            } catch (e: Exception) {
                logError(e)
                main {
                    binding.actorBio.text = getString(R.string.actor_error)
                    binding.loadingIndicator.visibility = View.GONE
                }
            }
        }
    }

    private fun bindPersonDetails(binding: FragmentActorPopupBinding, json: JSONObject) {
        binding.loadingIndicator.visibility = View.GONE

        val birthday = json.optString("birthday", null)
        val deathday = json.optString("deathday", null)
        val placeOfBirth = json.optString("place_of_birth", null)
        val gender = json.optInt("gender", 0)
        val biography = json.optString("biography", "")
        val knownForDepartment = json.optString("known_for_department", "")

        binding.actorDepartment.text = knownForDepartment

        binding.actorGender.text = when (gender) {
            1 -> getString(R.string.actor_female)
            2 -> getString(R.string.actor_male)
            else -> getString(R.string.actor_not_specified)
        }

        if (!birthday.isNullOrEmpty()) {
            binding.actorBirthday.text = formatDate(birthday)

            if (!deathday.isNullOrEmpty() && deathday != "null") {
                binding.actorDeathday.text = formatDate(deathday)
                binding.deathLayout.visibility = View.VISIBLE
                binding.actorAge.text = "${calculateAge(birthday, deathday)} ${getString(R.string.actor_years_old)}"
            } else {
                binding.deathLayout.visibility = View.GONE
                binding.actorAge.text = "${calculateAge(birthday, null)} ${getString(R.string.actor_years_old)}"
            }
        } else {
            binding.actorBirthday.visibility = View.GONE
            binding.deathLayout.visibility = View.GONE
        }

        binding.actorBirthplace.text = if (!placeOfBirth.isNullOrEmpty() && placeOfBirth != "null") {
            placeOfBirth
        } else {
            getString(R.string.actor_unknown)
        }

        binding.actorBio.text = if (biography.isNotEmpty()) {
            biography
        } else {
            getString(R.string.actor_no_biography)
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

    companion object {
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

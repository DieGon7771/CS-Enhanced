package com.lagradost.cloudstream3.ui.result

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ActorRole
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.databinding.CastItemBinding
import com.lagradost.cloudstream3.ui.BaseDiffCallback
import com.lagradost.cloudstream3.ui.NoStateAdapter
import com.lagradost.cloudstream3.ui.ViewHolderState
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import org.json.JSONObject

class ActorAdaptor(
    private var nextFocusUpId: Int? = null,
    private val focusCallback: (View?) -> Unit = {}
) : NoStateAdapter<ActorData>(diffCallback = BaseDiffCallback(itemSame = { a, b ->
    a.actor.name == b.actor.name
})) {
    companion object {
        val sharedPool =
            RecyclerView.RecycledViewPool().apply { this.setMaxRecycledViews(CONTENT, 10) }
        private const val TMDB_API_KEY = "e6333b32409e02a4a6eba6fb7ff866bb"
    }

    val inverted: HashMap<ActorData, Boolean> = hashMapOf()

    override fun onCreateContent(parent: ViewGroup): ViewHolderState<Any> {
        return ViewHolderState(
            CastItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )
    }

    override fun onClearView(holder: ViewHolderState<Any>) {
        when (val binding = holder.view) {
            is CastItemBinding -> {
                clearImage(binding.actorImage)
            }
        }
    }

    private fun searchActorOnTmdb(actorName: String, callback: (Int?) -> Unit) {
        ioSafe {
            try {
                val response = app.get(
                    "https://api.themoviedb.org/3/search/person",
                    params = mapOf(
                        "api_key" to TMDB_API_KEY,
                        "query" to actorName,
                        "language" to "en"
                    )
                )
                val json = JSONObject(response.text)
                val results = json.optJSONArray("results")
                if (results != null && results.length() > 0) {
                    val firstResult = results.getJSONObject(0)
                    val actorId = firstResult.optInt("id")
                    main { callback(actorId) }
                } else {
                    main { callback(null) }
                }
            } catch (e: Exception) {
                logError(e)
                main { callback(null) }
            }
        }
    }

    override fun onBindContent(holder: ViewHolderState<Any>, item: ActorData, position: Int) {
        when (val binding = holder.view) {
            is CastItemBinding -> {
                val itemView = binding.root
                val isInverted = inverted.getOrDefault(item, false)

                val (mainImg, vaImage) = if (!isInverted || item.voiceActor?.image.isNullOrBlank()) {
                    Pair(item.actor.image, item.voiceActor?.image)
                } else {
                    Pair(item.voiceActor?.image, item.actor.image)
                }

                if (position == 0) {
                    itemView.nextFocusLeftId = R.id.result_cast_items
                } else if ((position - 1) == itemCount) {
                    itemView.nextFocusRightId = R.id.result_cast_items
                }
                nextFocusUpId?.let {
                    itemView.nextFocusUpId = it
                }

                itemView.setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        focusCallback(v)
                    }
                }

                itemView.setOnClickListener {
                    inverted[item] = !isInverted
                    this.onUpdateContent(holder, getItem(position), position)
                }

                itemView.setOnLongClickListener {
                    if (isLayout(PHONE)) {
                        val actorName = item.actor.name
                        val actorImage = item.actor.image
                        
                        searchActorOnTmdb(actorName) { actorId ->
                            if (actorId != null) {
                                val activity = (itemView.context as? androidx.fragment.app.FragmentActivity)
                                activity.navigate(
                                    R.id.global_to_navigation_actor_detail,
                                    Bundle().apply {
                                        putInt("actor_id", actorId)
                                        putString("actor_name", actorName)
                                        putString("actor_image", actorImage)
                                    }
                                )
                            }
                        }
                    }
                    true
                }

                binding.apply {
                    actorImage.loadImage(mainImg)

                    actorName.text = item.actor.name
                    item.role?.let {
                        actorExtra.context?.getString(
                            when (it) {
                                ActorRole.Main -> R.string.actor_main
                                ActorRole.Supporting -> R.string.actor_supporting
                                ActorRole.Background -> R.string.actor_background
                            }
                        )?.let { text ->
                            actorExtra.isVisible = true
                            actorExtra.text = text
                        }
                    } ?: item.roleString?.let {
                        actorExtra.isVisible = true
                        actorExtra.text = it
                    } ?: run {
                        actorExtra.isVisible = false
                    }

                    if (item.voiceActor == null) {
                        voiceActorImageHolder.isVisible = false
                        voiceActorName.isVisible = false
                    } else {
                        voiceActorName.text = item.voiceActor?.name
                        if (!vaImage.isNullOrEmpty())
                            voiceActorImageHolder.isVisible = true
                        voiceActorImage.loadImage(vaImage)
                    }
                }
            }
        }
    }
}

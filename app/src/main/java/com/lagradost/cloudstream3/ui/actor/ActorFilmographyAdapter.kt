package com.lagradost.cloudstream3.ui.actor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.databinding.ItemPosterCardBinding
import com.lagradost.cloudstream3.ui.home.HomeChildItemAdapter
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage

data class FilmographyItem(
    val id: Int,
    val title: String,
    val posterPath: String?,
    val releaseDate: String?,
    val mediaType: String
)

class ActorFilmographyAdapter(
    private val onItemClick: (FilmographyItem) -> Unit
) : RecyclerView.Adapter<ActorFilmographyAdapter.ViewHolder>() {

    private var items = listOf<FilmographyItem>()

    fun submitList(newList: List<FilmographyItem>) {
        items = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPosterCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(private val binding: ItemPosterCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FilmographyItem) {
            val params = binding.posterCard.layoutParams
            params.width = HomeChildItemAdapter.maxPosterSize
            params.height = HomeChildItemAdapter.minPosterSize
            binding.posterCard.layoutParams = params
            if (!item.posterPath.isNullOrEmpty()) {
                binding.posterImage.loadImage("https://image.tmdb.org/t/p/w500${item.posterPath}")
                binding.posterImage.visibility = View.VISIBLE
                binding.posterPlaceholder.visibility = View.GONE
            } else {
                binding.posterImage.visibility = View.GONE
                binding.posterPlaceholder.visibility = View.VISIBLE
            }

            binding.titleText.text = item.title

            val year = item.releaseDate?.take(4)
            binding.yearText.text = year
            binding.yearText.visibility = if (year != null) View.VISIBLE else View.GONE

            binding.posterCard.setOnClickListener {
                onItemClick(item)
            }
        }
    }
}

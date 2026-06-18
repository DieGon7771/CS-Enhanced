package com.lagradost.cloudstream3.ui.actor

import android.graphics.Bitmap
import coil3.Size
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.transform.Transformation

fun ImageRequest.Builder.BlurTransformation(radius: Int = 25): ImageRequest.Builder {
    return transformations(object : Transformation {
        override val cacheKey: String = "Blur_$radius"
        override suspend fun transform(input: Bitmap, size: Size): Bitmap {
            val factor = (radius / 5f).coerceIn(2f, 20f)
            val smallW = (input.width / factor).toInt().coerceAtLeast(1)
            val smallH = (input.height / factor).toInt().coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(input, smallW, smallH, true)
            return Bitmap.createScaledBitmap(small, input.width, input.height, true)
        }
    })
}

package com.lagradost.cloudstream3.ui.actor

import android.graphics.Bitmap
import coil3.Size
import coil3.transform.Transformation

class BlurTransformation(private val blurRadius: Int = 25) : Transformation {
    override val cacheKey: String = "BlurTransform_$blurRadius"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (blurRadius <= 0) return input
        val factor = (blurRadius / 5f).coerceIn(2f, 20f)
        val smallW = (input.width / factor).toInt().coerceAtLeast(1)
        val smallH = (input.height / factor).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(input, smallW, smallH, true)
        return Bitmap.createScaledBitmap(small, input.width, input.height, true)
    }
}

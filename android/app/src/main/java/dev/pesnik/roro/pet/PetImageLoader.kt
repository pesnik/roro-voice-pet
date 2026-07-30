package dev.pesnik.roro.pet

import android.content.Context
import android.os.Build
import coil.ImageLoader
import coil.decode.ImageDecoderDecoder

/**
 * Animated WebP needs ImageDecoderDecoder, which wraps the platform
 * ImageDecoder/AnimatedImageDrawable APIs — API 28+ only, while this app's
 * minSdk is 26. On API 26/27 this falls through to Coil's default
 * BitmapFactoryDecoder, which decodes just the first frame as a static
 * bitmap: the pet still renders, just without animation, rather than
 * crashing or needing a second set of static-image assets.
 */
object PetImageLoader {
    fun build(context: Context): ImageLoader = ImageLoader.Builder(context)
        .components {
            if (Build.VERSION.SDK_INT >= 28) {
                add(ImageDecoderDecoder.Factory())
            }
        }
        .build()
}

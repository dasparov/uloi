package com.example.konkani

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat

/**
 * Typography. The app sans lives at res/font/app_sans_{regular,medium}.ttf:
 *  - This open-source repo ships Noto Sans (SIL OFL) under those names.
 *  - The original build uses the licensed Saans family: overwrite those two files locally with
 *    your licensed TTFs (do NOT commit them). Weight discipline: regular + medium, never bold.
 * Konkani Devanagari uses bundled Noto Sans Devanagari (assets/fonts).
 */
object Fonts {
    private var dev: Typeface? = null
    private var devLoaded = false

    fun latin(ctx: Context): Typeface =
        try { ResourcesCompat.getFont(ctx, R.font.app_sans_regular) ?: Typeface.DEFAULT }
        catch (e: Exception) { Typeface.DEFAULT }

    fun latinMedium(ctx: Context): Typeface =
        try { ResourcesCompat.getFont(ctx, R.font.app_sans_medium) ?: latin(ctx) }
        catch (e: Exception) { latin(ctx) }

    fun devanagari(ctx: Context): Typeface {
        if (!devLoaded) {
            dev = try {
                Typeface.createFromAsset(ctx.assets, "fonts/NotoSansDevanagari-Regular.ttf")
            } catch (e: Exception) { null }
            devLoaded = true
        }
        return dev ?: Typeface.DEFAULT
    }
}

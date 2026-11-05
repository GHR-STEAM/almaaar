package dev.lumen.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri

/** Built-in wallpapers drawn from gradients, so the app needs no image files. */
object Wallpapers {

    class Builtin(val nameAr: String, val nameEn: String, val base: IntArray, val glow: Int)

    val builtins = listOf(
        Builtin("غروب", "Sunset", intArrayOf(0xFF2B1B3D.toInt(), 0xFF8E3B46.toInt(), 0xFFE8A87C.toInt()), 0x80FFD9A0.toInt()),
        Builtin("صحراء", "Desert", intArrayOf(0xFF3A2416.toInt(), 0xFFB5651D.toInt(), 0xFFF2C88B.toInt()), 0x70FFE8C0.toInt()),
        Builtin("محيط", "Ocean", intArrayOf(0xFF061A2E.toInt(), 0xFF0E4C75.toInt(), 0xFF3AA8C1.toInt()), 0x6080E0FF.toInt()),
        Builtin("شفق", "Aurora", intArrayOf(0xFF050B1A.toInt(), 0xFF0F3D3E.toInt(), 0xFF3CCF91.toInt()), 0x6090FFC8.toInt()),
        Builtin("ليل", "Night", intArrayOf(0xFF05050A.toInt(), 0xFF141428.toInt(), 0xFF2E2E5C.toInt()), 0x40A0A0FF.toInt()),
        Builtin("لافندر", "Lavender", intArrayOf(0xFF1E1530.toInt(), 0xFF5B3F8C.toInt(), 0xFFC9A7E8.toInt()), 0x70F0D8FF.toInt()),
    )

    fun name(i: Int): String {
        val b = builtins[i.mod(builtins.size)]
        return S.t(b.nameAr, b.nameEn)
    }

    fun drawable(i: Int): Drawable {
        val b = builtins[i.mod(builtins.size)]
        val base = GradientDrawable(GradientDrawable.Orientation.TL_BR, b.base)
        val glow = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(b.glow, 0x00000000)
            gradientRadius = 1400f
            setGradientCenter(0.75f, 0.35f)
        }
        return LayerDrawable(arrayOf(base, glow))
    }

    /** Decodes a user-picked picture, downsampled to about screen size. Call off the main thread. */
    fun loadCustom(context: Context, uri: Uri): Bitmap? = try {
        val dm = context.resources.displayMetrics
        val targetW = maxOf(dm.widthPixels, 1280)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (e: Exception) {
        null
    }
}

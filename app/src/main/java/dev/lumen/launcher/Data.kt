package dev.lumen.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.util.Locale

/** Picks Arabic or English text based on the device language. */
object S {
    fun t(ar: String, en: String): String =
        if (Locale.getDefault().language == "ar") ar else en
}

/** An app that can be launched from the TV home screen. */
class AppEntry(
    val packageName: String,
    val component: ComponentName,
    val label: String,
    val banner: Drawable?,
    val icon: Drawable,
    val isTvApp: Boolean,
    /** Average icon colour, used as the tile background when there is no banner. */
    val accent: Int,
) {
    fun launchIntent(): Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(if (isTvApp) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}

object AppRepository {

    /** Loads installed apps. TV (leanback) apps come first; phone-only apps are added after them. */
    fun load(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val own = context.packageName
        val result = LinkedHashMap<String, AppEntry>()

        fun collect(category: String, isTv: Boolean) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            @Suppress("DEPRECATION")
            val list = pm.queryIntentActivities(intent, 0)
            for (ri in list) {
                val ai = ri.activityInfo ?: continue
                val pkg = ai.packageName
                if (pkg == own || result.containsKey(pkg)) continue
                val icon = runCatching { ai.loadIcon(pm) }.getOrNull() ?: continue
                val banner = if (isTv) {
                    runCatching { ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm) }.getOrNull()
                } else null
                result[pkg] = AppEntry(
                    packageName = pkg,
                    component = ComponentName(pkg, ai.name),
                    label = ri.loadLabel(pm)?.toString() ?: pkg,
                    banner = banner,
                    icon = icon,
                    isTvApp = isTv,
                    accent = averageColor(icon),
                )
            }
        }

        collect(Intent.CATEGORY_LEANBACK_LAUNCHER, true)
        collect(Intent.CATEGORY_LAUNCHER, false)

        val tv = result.values.filter { it.isTvApp }.sortedBy { it.label.lowercase() }
        val other = result.values.filter { !it.isTvApp }.sortedBy { it.label.lowercase() }
        return tv + other
    }

    fun isInstalled(context: Context, pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun averageColor(d: Drawable): Int {
        return try {
            val bmp = if (d is BitmapDrawable && d.bitmap != null) {
                d.bitmap
            } else {
                val b = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
                val c = Canvas(b)
                d.setBounds(0, 0, 24, 24)
                d.draw(c)
                b
            }
            val one = Bitmap.createScaledBitmap(bmp, 1, 1, true)
            val px = one.getPixel(0, 0)
            // Darken a little so white labels stay readable.
            val r = (android.graphics.Color.red(px) * 0.6).toInt()
            val g = (android.graphics.Color.green(px) * 0.6).toInt()
            val bl = (android.graphics.Color.blue(px) * 0.6).toInt()
            android.graphics.Color.rgb(r, g, bl)
        } catch (e: Exception) {
            0xFF2A2A3A.toInt()
        }
    }
}

/** Saved settings: favourites, recently opened, wallpaper, clock format. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("lumen", Context.MODE_PRIVATE)

    var favorites: MutableList<String>?
        get() = sp.getString("favorites", null)?.split(',')?.filter { it.isNotBlank() }?.toMutableList()
        set(v) { sp.edit().putString("favorites", v?.joinToString(",")).apply() }

    var recent: MutableList<String>
        get() = sp.getString("recent", "")!!.split(',').filter { it.isNotBlank() }.toMutableList()
        set(v) { sp.edit().putString("recent", v.joinToString(",")).apply() }

    fun pushRecent(pkg: String) {
        val list = recent
        list.remove(pkg)
        list.add(0, pkg)
        recent = list.take(12).toMutableList()
    }

    var wallpaperIndex: Int
        get() = sp.getInt("wallpaper", 0)
        set(v) { sp.edit().putInt("wallpaper", v).apply() }

    /** content:// URI of a picture chosen by the user, or null for a built-in wallpaper. */
    var customWallpaper: String?
        get() = sp.getString("customWallpaper", null)
        set(v) { sp.edit().putString("customWallpaper", v).apply() }

    /** Darkening over the wallpaper, 0-60 percent. */
    var dim: Int
        get() = sp.getInt("dim", 30)
        set(v) { sp.edit().putInt("dim", v).apply() }

    var clock24: Boolean
        get() = sp.getBoolean("clock24", false)
        set(v) { sp.edit().putBoolean("clock24", v).apply() }

    /** City typed by the user for the weather, or null to guess from the IP address. */
    var city: String?
        get() = sp.getString("city", null)
        set(v) { sp.edit().putString("city", v).apply() }

    /** Fresh random wallpaper downloaded from the internet every few hours. */
    var onlineWallpaper: Boolean
        get() = sp.getBoolean("onlineWallpaper", false)
        set(v) { sp.edit().putBoolean("onlineWallpaper", v).apply() }

    var showRecent: Boolean
        get() = sp.getBoolean("showRecent", true)
        set(v) { sp.edit().putBoolean("showRecent", v).apply() }
}

/** Default favourites: well-known streaming apps first, if they are installed. */
val PREFERRED_DEFAULTS = listOf(
    "com.google.android.youtube.tv",
    "com.netflix.ninja",
    "net.mbc.shahid.tv",
    "net.mbc.shahidtv",
    "com.amazon.amazonvideo.livingroom",
    "com.disney.disneyplus",
    "com.plexapp.android",
    "org.jellyfin.androidtv",
    "com.spotify.tv.android",
    "com.google.android.youtube.tvmusic",
    "com.osn.go",
    "com.starzplay.tv",
)

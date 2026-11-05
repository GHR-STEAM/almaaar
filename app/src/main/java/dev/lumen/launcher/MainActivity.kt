package dev.lumen.launcher

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var wallpaperView: ImageView
    private lateinit var dimView: View
    private lateinit var clockView: TextView
    private lateinit var dateView: TextView
    private lateinit var weatherView: TextView
    private lateinit var focusedLabel: TextView
    private lateinit var favoritesRow: LinearLayout
    private lateinit var recentRow: LinearLayout
    private lateinit var recentSection: LinearLayout

    private var drawer: View? = null
    private var settingsPanel: View? = null
    private var kenBurns: ValueAnimator? = null

    private var apps: List<AppEntry> = emptyList()
    private var appsDirty = true

    private val wallpaperFile by lazy { File(filesDir, "wallpaper.jpg") }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            appsDirty = true
            reloadApps()
        }
    }

    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        hideSystemBars()
        buildUi()
        applyWallpaper()
        reloadApps()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(packageReceiver, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        handler.post(clockTick)
        refreshWeather()
        kenBurns?.resume()
        if (appsDirty) reloadApps() else renderRecent()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTick)
        kenBurns?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(packageReceiver) }
        kenBurns?.cancel()
    }

    /** Pressing Home while already on the launcher closes panels and returns to the first tile. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        closeOverlays()
        favoritesRow.getChildAt(0)?.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                // The home screen never closes itself; Back only closes open panels.
                closeOverlays()
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                val tile = currentFocus as? AppTile
                if (tile != null) showAppOptions(tile.app) else toggleSettings()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    // ---------------------------------------------------------------- layout

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        wallpaperView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(wallpaperView, matchParent())

        dimView = View(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(dimView, matchParent())

        // Darker at the bottom so the app rows stay readable over any picture.
        root.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x66000000, 0x00000000, 0x00000000, 0xCC000000.toInt()),
            )
        }, matchParent())

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(36), dp(56), dp(28))
            clipChildren = false
            clipToPadding = false
        }
        root.addView(content, matchParent())

        // Top bar: clock and date on one side, settings button on the other.
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val clockCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clockView = TextView(this).apply {
            textSize = 88f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            includeFontPadding = false
            setShadowLayer(dp(12).toFloat(), 0f, dp(2).toFloat(), 0x66000000)
        }
        dateView = TextView(this).apply {
            textSize = 22f
            setTextColor(0xE6FFFFFF.toInt())
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setShadowLayer(dp(8).toFloat(), 0f, dp(1).toFloat(), 0x66000000)
        }
        weatherView = TextView(this).apply {
            textSize = 20f
            setTextColor(0xE6FFFFFF.toInt())
            setPadding(0, dp(4), 0, 0)
            setShadowLayer(dp(8).toFloat(), 0f, dp(1).toFloat(), 0x66000000)
        }
        clockCol.addView(clockView)
        clockCol.addView(dateView)
        clockCol.addView(weatherView)
        top.addView(clockCol)
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val settingsBtn = glassButton(this, "⚙", 22f).apply {
            gravity = Gravity.CENTER
            contentDescription = S.t("الإعدادات", "Settings")
            setOnClickListener { toggleSettings() }
            focusZoom(1.08f)
        }
        top.addView(settingsBtn, LinearLayout.LayoutParams(dp(56), dp(56)))
        content.addView(top)

        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        // Recently opened row (only shown when there is something to show).
        recentSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        recentSection.addView(sectionLabel(S.t("آخر ما فتحته", "Recently opened")))
        recentRow = tileRow()
        recentSection.addView(scroller(recentRow))
        content.addView(recentSection)

        // Favourites row.
        content.addView(sectionLabel(S.t("المفضلة", "Favorites")))
        favoritesRow = tileRow()
        content.addView(scroller(favoritesRow))

        focusedLabel = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(4), dp(2), 0, 0)
        }
        content.addView(focusedLabel)

        val allAppsBtn = glassButton(this, "▾  " + S.t("كل التطبيقات", "All apps"), 16f).apply {
            setOnClickListener { openDrawer() }
            focusZoom(1.06f)
        }
        content.addView(allAppsBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10) })

        setContentView(root)
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
    )

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(0xCCFFFFFF.toInt())
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(4), dp(10), 0, dp(2))
        setShadowLayer(dp(6).toFloat(), 0f, 0f, 0x88000000.toInt())
    }

    private fun tileRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
        // Room for the focused tile to grow without being cut off.
        setPadding(dp(14), dp(14), dp(14), dp(14))
    }

    private fun scroller(row: LinearLayout) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        clipChildren = false
        clipToPadding = false
        isFillViewport = false
        isSmoothScrollingEnabled = true
        addView(row)
    }

    private fun makeTile(app: AppEntry, widthDp: Int, inRow: Boolean): AppTile =
        AppTile(this, app, widthDp).apply {
            if (inRow) {
                layoutParams = LinearLayout.LayoutParams(layoutParams.width, layoutParams.height).apply {
                    marginEnd = dp(18)
                }
            }
            setOnClickListener { launch(app) }
            setOnLongClickListener { showAppOptions(app); true }
            focusZoom(1.1f) { has -> if (has) focusedLabel.text = app.label }
        }

    // ---------------------------------------------------------------- clock

    private fun updateClock() {
        val now = Date()
        val pattern = if (prefs.clock24) "HH:mm" else "h:mm"
        clockView.text = SimpleDateFormat(pattern, Locale.getDefault()).format(now)
        dateView.text = SimpleDateFormat("EEEE، d MMMM", Locale.getDefault()).format(now)
            .let { if (Locale.getDefault().language == "ar") it else it.replace("،", ",") }
    }

    private var lastWeather = 0L

    /** Updates the weather line at most every 30 minutes. */
    private fun refreshWeather() {
        if (System.currentTimeMillis() - lastWeather < 30 * 60_000L) return
        lastWeather = System.currentTimeMillis()
        Thread {
            val r = Weather.fetch()
            runOnUiThread {
                if (r == null) lastWeather = 0L
                else weatherView.text = "${r.icon}  ${r.temp}°  ${r.city}"
            }
        }.start()
    }

    // ---------------------------------------------------------------- apps

    private fun reloadApps() {
        appsDirty = false
        Thread {
            val loaded = AppRepository.load(applicationContext)
            runOnUiThread {
                apps = loaded
                ensureDefaultFavorites()
                renderFavorites()
                renderRecent()
                (drawer as? ViewGroup)?.let { closeDrawer(); openDrawer() }
            }
        }.start()
    }

    private fun ensureDefaultFavorites() {
        if (prefs.favorites != null) return
        val installed = apps.map { it.packageName }.toSet()
        val picks = PREFERRED_DEFAULTS.filter { it in installed }.toMutableList()
        for (a in apps) {
            if (picks.size >= 6) break
            if (a.isTvApp && a.packageName !in picks) picks.add(a.packageName)
        }
        prefs.favorites = picks
    }

    private fun appByPkg(pkg: String) = apps.firstOrNull { it.packageName == pkg }

    private fun renderFavorites(focusPkg: String? = null) {
        val hadFocus = favoritesRow.findFocus() != null
        favoritesRow.removeAllViews()
        val favs = prefs.favorites.orEmpty().mapNotNull { appByPkg(it) }
        for (app in favs) favoritesRow.addView(makeTile(app, 220, true))

        // Last tile in the row opens the app drawer.
        val more = glassButton(this, "＋\n" + S.t("إضافة", "Add"), 16f).apply {
            gravity = Gravity.CENTER
            setOnClickListener { openDrawer() }
            focusZoom(1.1f) { has -> if (has) focusedLabel.text = S.t("كل التطبيقات", "All apps") }
        }
        favoritesRow.addView(more, LinearLayout.LayoutParams(dp(124), dp(220) * 9 / 16))

        val target = focusPkg?.let { p ->
            (0 until favoritesRow.childCount).map { favoritesRow.getChildAt(it) }
                .firstOrNull { (it as? AppTile)?.app?.packageName == p }
        }
        if (target != null) target.post { target.requestFocus() }
        else if (hadFocus || currentFocus == null) favoritesRow.getChildAt(0)?.let { v -> v.post { v.requestFocus() } }
    }

    private fun renderRecent() {
        recentRow.removeAllViews()
        if (!prefs.showRecent) {
            recentSection.visibility = View.GONE
            return
        }
        val favs = prefs.favorites.orEmpty().toSet()
        val list = prefs.recent.filter { it !in favs }.mapNotNull { appByPkg(it) }.take(8)
        for (app in list) recentRow.addView(makeTile(app, 170, true))
        recentSection.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun launch(app: AppEntry) {
        try {
            startActivity(app.launchIntent())
            prefs.pushRecent(app.packageName)
        } catch (e: Exception) {
            toast(S.t("تعذّر فتح التطبيق", "Couldn't open the app"))
        }
    }

    private fun showAppOptions(app: AppEntry) {
        val favs = prefs.favorites.orEmpty().toMutableList()
        val isFav = app.packageName in favs
        val idx = favs.indexOf(app.packageName)

        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        fun add(label: String, action: () -> Unit) { labels += label; actions += action }

        add(S.t("فتح", "Open")) { launch(app) }
        if (isFav) {
            if (idx > 0) add(S.t("تحريك للأمام", "Move earlier")) {
                favs.removeAt(idx); favs.add(idx - 1, app.packageName)
                prefs.favorites = favs; renderFavorites(app.packageName)
            }
            if (idx < favs.size - 1) add(S.t("تحريك للخلف", "Move later")) {
                favs.removeAt(idx); favs.add(idx + 1, app.packageName)
                prefs.favorites = favs; renderFavorites(app.packageName)
            }
            add(S.t("إزالة من المفضلة", "Remove from favorites")) {
                favs.remove(app.packageName); prefs.favorites = favs; renderFavorites(); renderRecent()
            }
        } else {
            add(S.t("إضافة إلى المفضلة", "Add to favorites")) {
                favs.add(app.packageName); prefs.favorites = favs
                renderFavorites(); renderRecent()
                toast(S.t("أضيف إلى المفضلة", "Added to favorites"))
            }
        }
        add(S.t("معلومات التطبيق", "App info")) {
            tryStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.packageName)))
        }
        add(S.t("إلغاء التثبيت", "Uninstall")) {
            @Suppress("DEPRECATION")
            tryStart(Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:" + app.packageName)))
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(app.label)
            .setItems(labels.toTypedArray()) { d, which -> d.dismiss(); actions[which]() }
            .show()
    }

    // ---------------------------------------------------------------- all apps drawer

    private fun openDrawer() {
        if (drawer != null) return
        closeSettings()
        val panel = FrameLayout(this).apply {
            setBackgroundColor(0xEB0C0C12.toInt())
            isClickable = true
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(36), dp(56), dp(20))
            clipChildren = false
            clipToPadding = false
        }
        col.addView(TextView(this).apply {
            text = S.t("كل التطبيقات", "All apps") + "  ·  " + apps.size
            textSize = 28f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        })
        col.addView(TextView(this).apply {
            text = S.t(
                "اضغط مطوّلاً على أي تطبيق (أو زر القائمة) لإضافته للمفضلة",
                "Long-press any app (or press Menu) to add it to favorites",
            )
            textSize = 14f
            setTextColor(0x99FFFFFF.toInt())
            setPadding(0, dp(4), 0, dp(8))
        })

        val widthDp = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
        val tileW = 180
        val gap = 20
        val cols = ((widthDp - 112 - 28) / (tileW + gap)).coerceIn(3, 8)

        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(14), dp(14), dp(14), dp(40))
        }
        var row: LinearLayout? = null
        apps.forEachIndexed { i, app ->
            if (i % cols == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    clipChildren = false
                    clipToPadding = false
                }
                grid.addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(gap) })
            }
            val tile = AppTile(this, app, tileW).apply {
                setOnClickListener { closeDrawer(); launch(app) }
                setOnLongClickListener { showAppOptions(app); true }
                focusZoom(1.1f)
            }
            row!!.addView(tile, LinearLayout.LayoutParams(tile.layoutParams.width, tile.layoutParams.height).apply {
                marginEnd = dp(gap)
            })
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(grid)
        }
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        panel.addView(col, matchParent())

        panel.alpha = 0f
        panel.translationY = dp(40).toFloat()
        root.addView(panel, matchParent())
        panel.animate().alpha(1f).translationY(0f).setDuration(220).start()
        drawer = panel
        grid.post { (grid.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.requestFocus() }
    }

    private fun closeDrawer() {
        val d = drawer ?: return
        drawer = null
        root.removeView(d)
        favoritesRow.getChildAt(0)?.requestFocus()
    }

    // ---------------------------------------------------------------- settings panel

    private fun toggleSettings() {
        if (settingsPanel != null) closeSettings() else openSettings()
    }

    private fun openSettings() {
        if (drawer != null) closeDrawer()
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0x66000000)
            isClickable = true
            setOnClickListener { closeSettings() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xE6202028.toInt(), dp(24).toFloat())
            setPadding(dp(20), dp(24), dp(20), dp(24))
            isClickable = true
        }
        panel.addView(TextView(this).apply {
            text = S.t("الإعدادات", "Settings")
            textSize = 24f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(8), 0, 0, dp(14))
        })

        fun row(label: () -> String, onClick: (TextView) -> Unit): TextView {
            val tv = glassButton(this, label(), 17f)
            tv.setOnClickListener { onClick(tv); tv.text = label() }
            panel.addView(tv, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) })
            return tv
        }

        val first = row({
            "🖼  " + S.t("الخلفية: ", "Wallpaper: ") +
                if (prefs.customWallpaper != null) S.t("صورتك", "Your picture") else Wallpapers.name(prefs.wallpaperIndex)
        }) {
            if (prefs.customWallpaper != null) prefs.customWallpaper = null
            else prefs.wallpaperIndex = (prefs.wallpaperIndex + 1) % Wallpapers.builtins.size
            applyWallpaper()
        }
        row({ "📁  " + S.t("اختيار صورة من الجهاز", "Pick a picture from device") }) { pickWallpaper() }
        row({ "🌗  " + S.t("تعتيم الخلفية: ", "Dim wallpaper: ") + prefs.dim + "%" }) {
            prefs.dim = if (prefs.dim >= 60) 0 else prefs.dim + 15
            applyDim()
        }
        row({ "🕒  " + S.t("نظام 24 ساعة: ", "24-hour clock: ") + onOff(prefs.clock24) }) {
            prefs.clock24 = !prefs.clock24
            updateClock()
        }
        row({ "🕘  " + S.t("صف آخر ما فتحته: ", "Recently opened row: ") + onOff(prefs.showRecent) }) {
            prefs.showRecent = !prefs.showRecent
            renderRecent()
        }
        row({ "📶  " + S.t("الشبكة والإنترنت", "Network & internet") }) {
            tryStart(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
        row({ "🔊  " + S.t("الصوت", "Sound") }) {
            tryStart(Intent(Settings.ACTION_SOUND_SETTINGS))
        }
        row({ "⚙️  " + S.t("إعدادات الجهاز", "Device settings") }) {
            tryStart(Intent(Settings.ACTION_SETTINGS))
        }

        val lp = FrameLayout.LayoutParams(dp(400), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            marginEnd = dp(40)
        }
        overlay.addView(panel, lp)
        panel.alpha = 0f
        panel.translationX = dp(60).toFloat() * (if (isRtl()) -1 else 1)
        root.addView(overlay, matchParent())
        panel.animate().alpha(1f).translationX(0f).setDuration(200).start()
        settingsPanel = overlay
        first.post { first.requestFocus() }
    }

    private fun closeSettings() {
        val p = settingsPanel ?: return
        settingsPanel = null
        root.removeView(p)
        favoritesRow.getChildAt(0)?.requestFocus()
    }

    private fun closeOverlays() {
        closeSettings()
        closeDrawer()
    }

    private fun onOff(b: Boolean) = if (b) S.t("تشغيل", "On") else S.t("إيقاف", "Off")
    private fun isRtl() = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

    // ---------------------------------------------------------------- wallpaper

    private fun applyWallpaper() {
        kenBurns?.cancel()
        wallpaperView.scaleX = 1f
        wallpaperView.scaleY = 1f
        if (prefs.customWallpaper != null && wallpaperFile.exists()) {
            Thread {
                val bmp = BitmapFactory.decodeFile(wallpaperFile.absolutePath)
                runOnUiThread {
                    if (bmp != null) wallpaperView.setImageDrawable(BitmapDrawable(resources, bmp))
                    else wallpaperView.setImageDrawable(Wallpapers.drawable(prefs.wallpaperIndex))
                    startKenBurns()
                }
            }.start()
        } else {
            wallpaperView.setImageDrawable(Wallpapers.drawable(prefs.wallpaperIndex))
            startKenBurns()
        }
        applyDim()
    }

    /** Very slow zoom on the wallpaper so the home screen feels alive. */
    private fun startKenBurns() {
        kenBurns = ObjectAnimator.ofPropertyValuesHolder(
            wallpaperView,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f),
        ).apply {
            duration = 45_000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun applyDim() {
        dimView.alpha = prefs.dim / 100f
    }

    private fun pickWallpaper() {
        val open = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        val get = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(open, REQ_WALLPAPER)
        } catch (e: ActivityNotFoundException) {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(get, REQ_WALLPAPER)
            } catch (e2: ActivityNotFoundException) {
                toast(S.t(
                    "لا يوجد مدير ملفات على التلفزيون. ثبّت مدير ملفات (مثل X-plore) ثم حاول مرة أخرى",
                    "No file picker on this TV. Install a file manager (e.g. X-plore) and try again",
                ))
            }
        }
    }

    @Deprecated("Uses the platform result API to avoid extra libraries")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_WALLPAPER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            // Copy into the app's own storage so it keeps working after a restart.
            val bmp = Wallpapers.loadCustom(applicationContext, uri)
            val ok = bmp != null && runCatching {
                FileOutputStream(wallpaperFile).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            }.isSuccess
            runOnUiThread {
                if (ok) {
                    prefs.customWallpaper = "file"
                    applyWallpaper()
                    if (settingsPanel != null) { closeSettings(); openSettings() }
                } else {
                    toast(S.t("تعذّر فتح الصورة", "Couldn't open that picture"))
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- helpers

    private fun tryStart(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast(S.t("هذه الشاشة غير متوفرة على جهازك", "Not available on this device"))
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_WALLPAPER = 41
    }
}

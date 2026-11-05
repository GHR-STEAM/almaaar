package dev.lumen.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun View.dp(v: Int): Int = context.dp(v)

fun rounded(color: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Int = 0) =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

/** Grows slightly and lifts when focused by the remote, like Apple TV / Google TV tiles. */
fun View.focusZoom(scale: Float = 1.1f, onFocus: ((Boolean) -> Unit)? = null) {
    setOnFocusChangeListener { v, has ->
        v.animate()
            .scaleX(if (has) scale else 1f)
            .scaleY(if (has) scale else 1f)
            .translationZ(if (has) v.dp(12).toFloat() else 0f)
            .setDuration(160)
            .setInterpolator(DecelerateInterpolator())
            .start()
        onFocus?.invoke(has)
    }
}

/** A glass-style text button used in the settings panel and top bar. */
fun glassButton(ctx: Context, text: String, textSize: Float = 18f): TextView =
    TextView(ctx).apply {
        this.text = text
        this.textSize = textSize
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isFocusableInTouchMode = false
        isClickable = true
        val r = ctx.dp(14).toFloat()
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(0xF2FFFFFF.toInt(), r))
            addState(intArrayOf(), rounded(0x26FFFFFF, r))
        }
        // Dark text on the white focused state, white text otherwise.
        setTextColor(
            android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(0xFF111111.toInt(), Color.WHITE),
            )
        )
        setPadding(ctx.dp(20), ctx.dp(12), ctx.dp(20), ctx.dp(12))
    }

/** One app tile: the app's TV banner, or its icon and name on a coloured card. */
class AppTile(ctx: Context, val app: AppEntry, widthDp: Int) : FrameLayout(ctx) {

    init {
        val w = ctx.dp(widthDp)
        val h = w * 9 / 16
        layoutParams = LayoutParams(w, h)
        val radius = ctx.dp(12).toFloat()
        isFocusable = true
        isClickable = true
        isLongClickable = true
        contentDescription = app.label

        background = rounded(app.accent, radius)
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
        elevation = ctx.dp(2).toFloat()

        if (app.banner != null) {
            addView(ImageView(ctx).apply {
                setImageDrawable(app.banner)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        } else {
            val iconSize = (h * 0.42f).toInt()
            addView(ImageView(ctx).apply {
                setImageDrawable(app.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LayoutParams(iconSize, iconSize, Gravity.CENTER).apply { bottomMargin = h / 8 })
            addView(TextView(ctx).apply {
                text = app.label
                textSize = 13f
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                bottomMargin = ctx.dp(8)
                leftMargin = ctx.dp(8)
                rightMargin = ctx.dp(8)
            })
        }

        // White ring shown only while focused.
        foreground = StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                rounded(Color.TRANSPARENT, radius, Color.WHITE, ctx.dp(3)),
            )
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
    }
}

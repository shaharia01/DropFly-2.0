package com.dropfly.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.view.animation.AnticipateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Invisible-ish share target. Shows a card that hops up off the top of the screen
 * (toward the laptop) while the file is sent. The app UI never opens.
 */
class ShareActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val started = System.currentTimeMillis()
    private lateinit var card: View
    private lateinit var root: FrameLayout

    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val code = Sender.prefs(this).getString("code", "") ?: ""
        if (code.isEmpty()) {
            Toast.makeText(this, "Open DropFly once and enter the pairing code", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        val uris = collectUris(intent)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (uris.isEmpty() && text == null) {
            finish()
            return
        }

        // let taps fall through to whatever is underneath
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)

        root = FrameLayout(this)
        setContentView(root)
        card = buildCard(uris, text)
        root.addView(card, FrameLayout.LayoutParams(dp(190), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // 1) card pops in
        card.alpha = 0f
        card.scaleX = 0.6f
        card.scaleY = 0.6f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(260).setInterpolator(OvershootInterpolator(1.6f)).start()

        // meanwhile, find the laptop
        Thread {
            val target = Sender.findTarget(this, code)
            main.post {
                if (target == null) {
                    fail("Laptop not found")
                } else {
                    val wait = (320 - (System.currentTimeMillis() - started)).coerceAtLeast(0)
                    main.postDelayed({ flyAway(target, uris, text) }, wait)
                }
            }
        }.start()
    }

    // 2) hand the files to the background service, then the card pulls back and shoots off the top.
    //    The service keeps sending after this screen is gone (even if you leave the app or lock the phone).
    private fun flyAway(target: Target, uris: List<Uri>, text: String?) {
        card.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        UploadService.start(this, target, uris, text)

        val h = resources.displayMetrics.heightPixels.toFloat()
        card.animate()
            .translationY(-(h / 2f + card.height))
            .scaleX(0.55f).scaleY(0.55f)
            .alpha(0f)
            .setDuration(520)
            .setInterpolator(AnticipateInterpolator(2.2f))
            .withEndAction { finishOnce() }
            .start()
    }

    private fun fail(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        // little shake, then fade
        card.animate().translationX(dp(14).toFloat()).setDuration(70).withEndAction {
            card.animate().translationX(-dp(14).toFloat()).setDuration(110).withEndAction {
                card.animate().translationX(0f).alpha(0f).setDuration(160)
                    .setInterpolator(DecelerateInterpolator()).withEndAction { finishOnce() }.start()
            }.start()
        }.start()
    }

    private fun finishOnce() {
        if (finished) return
        finished = true
        finish()
        overridePendingTransition(0, 0)
    }

    // ------------------------------------------------------------ card UI

    private fun buildCard(uris: List<Uri>, text: String?): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F01E1E22"))
                cornerRadius = dp(26).toFloat()
            }
            elevation = dp(16).toFloat()
        }

        val thumb = if (uris.isNotEmpty()) loadThumb(uris[0]) else null
        if (thumb != null) {
            box.addView(ImageView(this).apply {
                setImageBitmap(thumb)
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) {
                        o.setRoundRect(0, 0, v.width, v.height, dp(16).toFloat())
                    }
                }
            }, LinearLayout.LayoutParams(dp(160), dp(160)))
        } else {
            box.addView(TextView(this).apply {
                this.text = if (text != null) "\"" else "📄"
                textSize = 56f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(dp(160), dp(120)))
        }

        val label = when {
            uris.size > 1 -> "${uris.size} files"
            uris.size == 1 -> "1 item"
            else -> "Text"
        }
        box.addView(TextView(this).apply {
            this.text = "$label  →  laptop"
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })
        return box
    }

    private fun loadThumb(uri: Uri): Bitmap? {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                return contentResolver.loadThumbnail(uri, Size(320, 320), null)
            }
        } catch (e: Exception) {
        }
        return try {
            val o = BitmapFactory.Options().apply { inSampleSize = 4 }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        } catch (e: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun collectUris(i: Intent): List<Uri> = when (i.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        )
        Intent.ACTION_SEND_MULTIPLE ->
            (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)) ?: emptyList<Uri>()
        else -> emptyList()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

package com.dropfly.app

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** One-time setup screen: type the pairing code shown on the laptop. */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Sender.prefs(this)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val d = resources.displayMetrics.density
        val pad = (24 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "DropFly"
            textSize = 30f
        })
        root.addView(TextView(this).apply {
            text = "1. Run DropFly on your laptop (it shows a 6-digit code).\n" +
                "2. Type the code below and tap Save.\n" +
                "3. From now on, in any app tap Share -> DropFly. That's it.\n\n(Please allow notifications: they show the transfer progress.)"
            textSize = 15f
            setPadding(0, pad / 2, 0, pad)
        })

        val codeBox = EditText(this).apply {
            hint = "Pairing code"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getString("code", ""))
            textSize = 24f
            gravity = Gravity.CENTER
        }
        root.addView(codeBox)

        val status = TextView(this).apply {
            textSize = 15f
            setPadding(0, pad / 2, 0, pad / 2)
        }

        root.addView(Button(this).apply {
            text = "Save"
            setOnClickListener {
                val code = codeBox.text.toString().trim()
                prefs.edit().putString("code", code).remove("host").apply()
                status.text = if (code.isEmpty()) "Enter the code first." else "Saved. Tap 'Test connection'."
            }
        })

        root.addView(Button(this).apply {
            text = "Test connection"
            setOnClickListener {
                val code = codeBox.text.toString().trim()
                status.text = "Looking for your laptop..."
                Thread {
                    val t = Sender.findTarget(this@MainActivity, code)
                    runOnUiThread {
                        status.text = if (t != null) "Connected to ${t.name} (${t.host})"
                        else "Laptop not found. Is DropFly running, same Wi-Fi, code correct?"
                    }
                }.start()
            }
        })
        root.addView(status)

        setContentView(root)
    }
}

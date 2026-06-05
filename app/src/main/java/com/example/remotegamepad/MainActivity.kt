package com.example.remotegamepad

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import kotlin.math.sqrt
import kotlin.math.abs
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : AppCompatActivity() {

    private lateinit var socketClient: SocketClient

    private var lastSentTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        socketClient = SocketClient()

        // 📷 QR Scan
        findViewById<Button>(R.id.btnScan).setOnClickListener {

            val options = ScanOptions()

            options.setPrompt("Scan PC QR")

            options.setBeepEnabled(true)

            barcodeLauncher.launch(options)
        }

        // 🎮 JOYSTICKS
        setupJoystick(
            findViewById(R.id.leftJoystick),
            findViewById(R.id.leftThumb),
            "L"
        )

        setupJoystick(
            findViewById(R.id.rightJoystick),
            findViewById(R.id.rightThumb),
            "R"
        )

        // 🔘 BUTTONS
        setupButton(R.id.btnA, "A")
        setupButton(R.id.btnB, "B")
        setupButton(R.id.btnX, "X")
        setupButton(R.id.btnY, "Y")

        setupButton(R.id.btnLT, "LT")
        setupButton(R.id.btnRT, "RT")
        setupButton(R.id.btnLB, "LB")
        setupButton(R.id.btnRB, "RB")

        setupButton(R.id.btnStart, "START")
        setupButton(R.id.btnSelect, "SELECT")
        setupButton(R.id.btnHome, "HOME")

        setupButton(R.id.btnLS, "LS")
        setupButton(R.id.btnRS, "RS")

        setupButton(R.id.btnDpadUp, "DPAD_UP")
        setupButton(R.id.btnDpadDown, "DPAD_DOWN")
        setupButton(R.id.btnDpadLeft, "DPAD_LEFT")
        setupButton(R.id.btnDpadRight, "DPAD_RIGHT")
    }

    // 🎮 JOYSTICK
    private fun setupJoystick(base: View, thumb: View, tag: String) {

        base.setOnTouchListener { v, event ->

            val cx = v.width / 2f
            val cy = v.height / 2f

            val dx = event.x - cx
            val dy = event.y - cy

            val maxR = v.width / 2f

            val dist = sqrt(dx * dx + dy * dy)

            val lx =
                if (dist > maxR)
                    dx / dist * maxR
                else
                    dx

            val ly =
                if (dist > maxR)
                    dy / dist * maxR
                else
                    dy

            thumb.translationX = lx
            thumb.translationY = ly

            // 🔥 Normalize
            var normX = lx / maxR
            var normY = ly / maxR

            // 🔥 Deadzone
            val deadzone = 0.12f

            if (abs(normX) < deadzone)
                normX = 0f

            if (abs(normY) < deadzone)
                normY = 0f

            // 🔥 Smooth curve
            fun curve(v: Float): Float {
                return v * v * v
            }

            normX = curve(normX)
            normY = curve(normY)

            // 🔥 Clamp
            normX = normX.coerceIn(-1f, 1f)
            normY = normY.coerceIn(-1f, 1f)

            val now = System.currentTimeMillis()

            // 🔥 ~60 FPS updates
            if (now - lastSentTime > 16) {

                lastSentTime = now

                socketClient.send(
                    "JOY_$tag:$normX,$normY"
                )
            }

            // 🔥 Reset on release
            if (event.action == MotionEvent.ACTION_UP) {

                thumb.translationX = 0f
                thumb.translationY = 0f

                socketClient.send(
                    "JOY_$tag:0,0"
                )
            }

            true
        }
    }

    // 🔘 BUTTONS
    private fun setupButton(id: Int, name: String) {

        val btn = findViewById<Button>(id)

        btn.setOnTouchListener { _, event ->

            when (event.action) {

                MotionEvent.ACTION_DOWN -> {

                    socketClient.send("${name}_DOWN")
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {

                    socketClient.send("${name}_UP")
                }
            }

            true
        }
    }

    // 📷 QR Scanner
    private val barcodeLauncher =
        registerForActivityResult(ScanContract()) { result ->

            if (result.contents != null) {

                try {

                    val parts =
                        result.contents.split("|")

                    if (
                        parts.size >= 3 &&
                        parts[0] == "GAMEPAD"
                    ) {

                        val ip = parts[1]

                        val port =
                            parts[2].toInt()

                        socketClient.connect(ip, port)
                    }

                } catch (e: Exception) {

                    e.printStackTrace()
                }
            }
        }
}
package com.example.remotegamepad

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.example.remotegamepad.ui.DPadView
import com.example.remotegamepad.ui.JoystickView
import com.example.remotegamepad.ui.RoundButtonView
import com.example.remotegamepad.ui.ShoulderButtonView
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : AppCompatActivity() {

    private lateinit var socketClient: SocketClient

    // Latest joystick state, written by the UI thread on every touch move,
    // read by the joystick ticker thread at a fixed rate. Plain @Volatile
    // fields are enough here: single-writer-per-stick, single-reader,
    // always-overwrite-with-latest semantics.
    @Volatile private var leftX = 0f
    @Volatile private var leftY = 0f
    @Volatile private var rightX = 0f
    @Volatile private var rightY = 0f

    private var joystickTicker: Thread? = null
    @Volatile private var running = true

    // Joystick network send rate. 90Hz sits comfortably inside the
    // requested 60-100Hz band.
    private val joystickTickMs = 11L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        socketClient = SocketClient(this)
        socketClient.onConnectionChanged = { connected ->
            runOnUiThread { updateConnectionUi(connected) }
        }

        setupPairing()
        setupSticks()
        setupDpad()
        setupFaceButtons()
        setupShoulderButtons()
        setupCenterButtons()

        startJoystickTicker()
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        joystickTicker?.interrupt()
        socketClient.close()
    }

    // ===================== PAIRING / CONNECTION =====================

    private fun setupPairing() {
        findViewById<TextView>(R.id.btnPair).setOnClickListener {
            val options = ScanOptions()
            options.setPrompt("Scan PC QR")
            options.setBeepEnabled(true)
            barcodeLauncher.launch(options)
        }
    }

    private fun updateConnectionUi(connected: Boolean) {
        findViewById<TextView>(R.id.tvConnectionStatus).text =
            if (connected) getString(R.string.status_connected) else getString(R.string.status_disconnected)
        findViewById<View>(R.id.connectionDot).setBackgroundResource(
            if (connected) R.drawable.dot_connected else R.drawable.dot_disconnected
        )
        // Once connected, the pairing control shrinks out of the way rather
        // than disappearing entirely - it's still there if the link drops.
        findViewById<TextView>(R.id.btnPair).alpha = if (connected) 0.55f else 1f
    }

    private val barcodeLauncher =
        registerForActivityResult(ScanContract()) { result ->
            if (result.contents != null) {
                try {
                    val parts = result.contents.split("|")
                    if (parts.size >= 3 && parts[0] == "GAMEPAD") {
                        val ip = parts[1]
                        val port = parts[2].toInt()
                        socketClient.connect(ip, port)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

    // ===================== STICKS =====================

    private fun setupSticks() {
        val left = findViewById<JoystickView>(R.id.leftJoystick)
        left.onMove = { x, y -> leftX = x; leftY = y }
        left.onClick = { pressed -> sendButtonEvent("LS", pressed) }

        val right = findViewById<JoystickView>(R.id.rightJoystick)
        right.onMove = { x, y -> rightX = x; rightY = y }
        right.onClick = { pressed -> sendButtonEvent("RS", pressed) }
    }

    /**
     * Single dedicated background thread that sends the LATEST joystick
     * state at a fixed rate, decoupled from how often touch-move events
     * fire, and independent of the button send path (see SocketClient).
     */
    private fun startJoystickTicker() {
        joystickTicker = Thread {
            var nextTick = System.currentTimeMillis()
            while (running) {
                socketClient.setJoystick('L', leftX, leftY)
                socketClient.setJoystick('R', rightX, rightY)

                nextTick += joystickTickMs
                val sleepMs = nextTick - System.currentTimeMillis()
                try {
                    if (sleepMs > 0) Thread.sleep(sleepMs)
                    else nextTick = System.currentTimeMillis() // fell behind, resync
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.apply {
            isDaemon = true
            name = "Joystick-Ticker"
            start()
        }
    }

    // ===================== DPAD =====================

    private fun setupDpad() {
        findViewById<DPadView>(R.id.dpad).onDirection = { name, pressed ->
            sendButtonEvent("DPAD_$name", pressed)
        }
    }

    // ===================== FACE BUTTONS =====================

    private fun setupFaceButtons() {
        bindRound(R.id.btnA, "A", R.color.rg_btn_a)
        bindRound(R.id.btnB, "B", R.color.rg_btn_b)
        bindRound(R.id.btnX, "X", R.color.rg_btn_x)
        bindRound(R.id.btnY, "Y", R.color.rg_btn_y)
    }

    private fun bindRound(id: Int, name: String, colorRes: Int) {
        val v = findViewById<RoundButtonView>(id)
        v.label = name
        v.accentColor = getColor(colorRes)
        v.onPress = { pressed -> sendButtonEvent(name, pressed) }
    }

    // ===================== SHOULDER / TRIGGERS =====================

    private fun setupShoulderButtons() {
        bindShoulder(R.id.btnLT, "LT", mirrored = false)
        bindShoulder(R.id.btnLB, "LB", mirrored = false)
        bindShoulder(R.id.btnRT, "RT", mirrored = true)
        bindShoulder(R.id.btnRB, "RB", mirrored = true)
    }

    private fun bindShoulder(id: Int, name: String, mirrored: Boolean) {
        val v = findViewById<ShoulderButtonView>(id)
        v.label = name
        v.mirrored = mirrored
        v.onPress = { pressed -> sendButtonEvent(name, pressed) }
    }

    // ===================== CENTER CLUSTER =====================

    private fun setupCenterButtons() {
        // View/Home/Menu mirror the reference image's labels. Wire them to
        // the existing protocol keys the server already understands:
        // View -> SELECT (Back), Menu -> START, Home -> HOME.
        findViewById<RoundButtonView>(R.id.btnView).apply {
            label = "\u2263" // simple neutral glyph, not Xbox iconography
            accentColor = getColor(R.color.rg_text_secondary)
            labelSizeSp = 14f
            onPress = { pressed -> sendButtonEvent("SELECT", pressed) }
        }
        findViewById<RoundButtonView>(R.id.btnMenu).apply {
            label = "\u2261"
            accentColor = getColor(R.color.rg_text_secondary)
            labelSizeSp = 14f
            onPress = { pressed -> sendButtonEvent("START", pressed) }
        }
        findViewById<ImageView>(R.id.ivLogo) // no-op, keeps a reference point for future theming
        findViewById<RoundButtonView>(R.id.btnHome).apply {
            label = ""
            accentColor = getColor(R.color.rg_accent_blue)
            onPress = { pressed -> sendButtonEvent("HOME", pressed) }
        }
    }

    // ===================== SEND HELPER =====================

    private fun sendButtonEvent(name: String, pressed: Boolean) {
        socketClient.send(if (pressed) "${name}_DOWN" else "${name}_UP")
    }
}

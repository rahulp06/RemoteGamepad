package com.example.remotegamepad

import androidx.appcompat.app.AppCompatActivity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.example.remotegamepad.ui.DPadView
import com.example.remotegamepad.ui.JoystickView
import com.example.remotegamepad.ui.RoundButtonView
import com.example.remotegamepad.ui.ShoulderButtonView

/**
 * Screen 2 - Gamepad.
 *
 * Pure touchscreen controller surface. There is NO QR scanner, Pair
 * button, or any other connection setup control here - that all lives in
 * [ConnectionActivity]. This screen only ever reads the already-connected
 * [GamepadTransport] left behind in [ConnectionSession] and streams input
 * for it; it never creates, configures, or owns a connection itself.
 */
class GamepadActivity : AppCompatActivity() {

    private lateinit var transport: GamepadTransport

    // Immutable snapshot of the latest joystick position for each stick.
    // Written by the UI thread (touch), read by the Joystick-Ticker and
    // State-Ticker threads.  A single @Volatile reference swap is atomic on
    // the JVM, so readers always see either the pre-release or the
    // post-release snapshot — never a torn half-zeroed value (which was the
    // root cause of the stuck-stick bug when leftX and leftY were separate
    // @Volatile fields that the ticker could read between two writes).
    private data class JoySnapshot(val x: Float, val y: Float)

    @Volatile private var leftSnap  = JoySnapshot(0f, 0f)
    @Volatile private var rightSnap = JoySnapshot(0f, 0f)

    private var joystickTicker: Thread? = null
    @Volatile private var running = true

    // Joystick network send rate. 90Hz sits comfortably inside the
    // requested 60-100Hz band.
    private val joystickTickMs = 11L

    // Authoritative snapshot of every digital control, kept in sync with
    // every DOWN/UP edge event (see sendButtonEvent). This is what the
    // periodic STATE heartbeat below is built from - it's what lets a lost
    // *_UP packet get corrected instead of leaving a button stuck held.
    private val buttonState = ButtonState()

    private var stateTicker: Thread? = null

    // STATE heartbeat rate: ~29Hz, inside the requested 20-30Hz band and
    // deliberately much slower than the joystick tick so it stays cheap
    // and never competes with joystick throughput.
    private val stateTickMs = 35L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val active = ConnectionSession.transport
        if (active == null) {
            // No live connection to drive - e.g. the process was restored
            // directly onto this screen from Recents. Route back through
            // connection setup instead of showing a dead controller.
            startActivity(Intent(this, ConnectionActivity::class.java))
            finish()
            return
        }
        transport = active

        setContentView(R.layout.activity_gamepad)

        transport.onConnectionChanged = { connected ->
            runOnUiThread { updateConnectionDot(connected) }
        }
        updateConnectionDot(transport.isReady())

        setupChangeConnection()
        setupSticks()
        setupDpad()
        setupFaceButtons()
        setupShoulderButtons()
        setupCenterButtons()

        startJoystickTicker()
        startStateTicker()
    }

    override fun onPause() {
        // If Android interrupts the gamepad screen (home/app switch,
        // system UI, etc.), never leave a stale stick position behind.
        resetAllInputs()
        super.onPause()
    }

    private fun resetAllInputs() {
        leftSnap  = JoySnapshot(0f, 0f)
        rightSnap = JoySnapshot(0f, 0f)
        // Release every tracked digital control as well. The next STATE
        // heartbeat will carry a fully neutral snapshot.
        for (name in ButtonState.ORDER) {
            buttonState.set(name, false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        joystickTicker?.interrupt()
        stateTicker?.interrupt()
        // Deliberately NOT closing the transport here - ConnectionSession
        // owns its lifetime, not this screen. That keeps a config change
        // (or a brief backgrounding) from silently killing a good
        // connection; the only explicit disconnect path is "change
        // connection" below.
    }

    // ===================== CONNECTION STATUS / CHANGE =====================

    private fun setupChangeConnection() {
        findViewById<View>(R.id.btnChangeConnection).setOnClickListener {
            ConnectionSession.clear()
            startActivity(Intent(this, ConnectionActivity::class.java))
            finish()
        }
    }

    private fun updateConnectionDot(connected: Boolean) {
        val tint = getColor(if (connected) R.color.rg_connected_green else R.color.rg_disconnected_red)
        findViewById<ImageView>(R.id.gamepadConnDot).setColorFilter(tint)
        findViewById<TextView>(R.id.tvConnLabel).apply {
            text = getString(if (connected) R.string.status_connected else R.string.status_disconnected)
            setTextColor(tint)
        }
    }

    // ===================== STICKS =====================

    private fun setupSticks() {
        val left = findViewById<JoystickView>(R.id.leftJoystick)
        left.onMove = { x, y -> leftSnap = JoySnapshot(x, y) }
        // onClick intentionally not wired: touching the joystick must NOT
        // trigger L3/R3. The dedicated btnL3/btnR3 RoundButtonViews are the
        // only way to send LS_DOWN/RS_DOWN.

        val right = findViewById<JoystickView>(R.id.rightJoystick)
        right.onMove = { x, y -> rightSnap = JoySnapshot(x, y) }
        // onClick intentionally not wired — same rationale as left stick above.

        // Dedicated L3/R3 corner buttons mirror the reference layout and
        // fire the exact same LS/RS protocol events as clicking the stick
        // itself - just a second, explicit way to trigger them.
        findViewById<RoundButtonView>(R.id.btnL3).apply {
            label = "L3"
            labelSizeSp = 15f
            accentColor = getColor(R.color.rg_text_secondary)
            onPress = { pressed -> sendButtonEvent("LS", pressed) }
        }
        findViewById<RoundButtonView>(R.id.btnR3).apply {
            label = "R3"
            labelSizeSp = 15f
            accentColor = getColor(R.color.rg_text_secondary)
            onPress = { pressed -> sendButtonEvent("RS", pressed) }
        }
    }

    /**
     * Single dedicated background thread that sends the LATEST joystick
     * state at a fixed rate, decoupled from how often touch-move events
     * fire, and independent of the button send path (see SocketClient /
     * BluetoothClient).
     */
    private fun startJoystickTicker() {
        joystickTicker = Thread {
            var nextTick = System.currentTimeMillis()
            while (running) {
                // Read each stick as one atomic snapshot so the ticker never
                // sees a torn (x,y) where only one axis has been zeroed.
                val l = leftSnap
                val r = rightSnap
                transport.setJoystick('L', l.x, l.y)
                transport.setJoystick('R', r.x, r.y)

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

    /**
     * Single dedicated background thread that sends the current
     * authoritative button-state snapshot at a fixed low rate, independent
     * of both the button-edge path and the joystick ticker. This is a
     * recovery/resync mechanism, not a replacement for the immediate
     * DOWN/UP edge events sendButtonEvent() still sends on every press -
     * see ButtonState's doc for why both paths exist.
     */
    private fun startStateTicker() {
        stateTicker = Thread {
            var nextTick = System.currentTimeMillis()
            while (running) {
                // Same atomic-snapshot read as the joystick ticker.
                val l = leftSnap
                val r = rightSnap
                transport.sendState(buttonState.mask(), l.x, l.y, r.x, r.y)

                nextTick += stateTickMs
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
            name = "State-Ticker"
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
        // Update the authoritative snapshot FIRST, so if the state ticker
        // happens to fire between these two lines it already reflects this
        // edge - the DOWN/UP event and the next heartbeat can never
        // disagree about this button.
        buttonState.set(name, pressed)
        transport.send(if (pressed) "${name}_DOWN" else "${name}_UP")
    }
}

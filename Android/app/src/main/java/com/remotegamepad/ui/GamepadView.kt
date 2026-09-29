package com.remotegamepad.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.Rect
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.remotegamepad.bluetooth.GamepadState

class GamepadView @JvmOverloads constructor(
    context: android.content.Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface Listener { fun onStateChanged(state: GamepadState) }
    var listener: Listener? = null
    enum class ControllerStyle { XBOX, PS }
    var controllerStyle = ControllerStyle.XBOX
        set(value) { field = value; invalidate() }

    companion object {
        // ═══ GEOMETRY ═══
        private const val DW = 1536f; private const val DH = 864f; private const val DM = 864f
        private const val LT_X = 120f; private const val LT_Y = 115f
        private const val LB_X = 332f; private const val LB_Y = 115f
        private const val VIEW_X = 616f; private const val VIEW_Y = 65f
        private const val HOME_X = 768f; private const val HOME_Y = 65f
        private const val MENU_X = 920f; private const val MENU_Y = 65f
        private const val DPAD_CX = 235f; private const val DPAD_CY = 380f
        private const val LS_CX = 400f; private const val LS_CY = 670f
        private const val RS_CX = 1100f; private const val RS_CY = 670f
        private const val L3_X = 105f; private const val L3_Y = 790f

        // ═══ PALETTE ═══
        private const val BG_TOP = "#EEF0F8"; private const val BG_BOT = "#E0E4EE"
        private const val SURFACE = "#F5F6FA"; private const val SURFACE_H = "#FAFBFD"
        private const val SURFACE_L = "#E4E6F0"; private const val SURFACE_P = "#DDE0EA"
        private const val WELL = "#D6DAE6"; private const val WELL_DEEP = "#C4C8D6"
        private const val WELL_EDGE = "#B8BCC8"
        private const val RIM = "#B0B4C2"; private const val RIM_LIGHT = "#D0D4DE"
        private const val CAP = "#A0A8B8"; private const val CAP_P = "#848C9C"
        private const val ACCENT = "#4A9EFF"; private const val ACCENT_DIM = "#224A9EFF"
        private const val TXT = "#292D36"; private const val TXT_M = "#3A3F4A"
        private const val TXT_D = "#3A3F4A"; private const val TXT_B = "#3A3F4A"

        // Color parsing cache
        private val cBgTop by lazy { Color.parseColor(BG_TOP) }
        private val cBgBot by lazy { Color.parseColor(BG_BOT) }
        private val cSurf by lazy { Color.parseColor(SURFACE) }
        private val cSurfH by lazy { Color.parseColor(SURFACE_H) }
        private val cSurfL by lazy { Color.parseColor(SURFACE_L) }
        private val cWell by lazy { Color.parseColor(WELL) }
        private val cWellDeep by lazy { Color.parseColor(WELL_DEEP) }
        private val cWellEdge by lazy { Color.parseColor(WELL_EDGE) }
        private val cRim by lazy { Color.parseColor(RIM) }
        private val cCap by lazy { Color.parseColor(CAP) }
        private val cCapP by lazy { Color.parseColor(CAP_P) }
        private val cAccent by lazy { Color.parseColor(ACCENT) }
        private val cAccentDim by lazy { Color.parseColor(ACCENT_DIM) }
        private val cShadow by lazy { Color.parseColor("#20000000") }
        private val cShadowS by lazy { Color.parseColor("#14000000") }
        private val cShadowI by lazy { Color.parseColor("#10000000") }
        private val cInset by lazy { Color.parseColor("#18000000") }
        private val cInsetPill by lazy { Color.parseColor("#15000000") }

        // Face button colors
        private val cY by lazy { Color.parseColor("#E8C840") }
        private val cX by lazy { Color.parseColor("#5A9AE8") }
        private val cB by lazy { Color.parseColor("#E87070") }
        private val cA by lazy { Color.parseColor("#60C870") }
    }

    // ═══ PRE-ALLOCATED PAINTS — no on-draw allocations ═══
    private val pBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pSurf = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pWellBase = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pWellRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pWellInner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pCap = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pCapRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pCapHL = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL; alpha = 55 }

    // Shadows
    private val pShadowDeep = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cShadow; style = Paint.Style.FILL
        maskFilter = android.graphics.BlurMaskFilter(14f, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    private val pShadowMed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cShadowS; style = Paint.Style.FILL
        maskFilter = android.graphics.BlurMaskFilter(8f, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    private val pShadowSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cShadowI; style = Paint.Style.FILL
        maskFilter = android.graphics.BlurMaskFilter(4f, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }

    // Highlights
    private val pHLSoft = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL; alpha = 20 }
    private val pHLTop = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL; alpha = 30 }

    // Accent
    private val pGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cAccentDim; style = Paint.Style.FILL }
    private val pGlowRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cAccent; style = Paint.Style.STROKE }

    // Pressed inset
    private val pInset = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cInset; style = Paint.Style.FILL
        maskFilter = android.graphics.BlurMaskFilter(3f, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }

    // Reusable rim paints (colors updated per draw call)
    private val pRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val pRimThin = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f }
    private val pColorRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val pHLArc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; alpha = 35 }

    // Dpad dimple
    private val pDimple = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cWellDeep; style = Paint.Style.FILL; alpha = 55 }

    // Text paints — dark, bold, readable
    private val pTxtFace = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(TXT); textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val pTxtPs = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(TXT); textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val pTxtM = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(TXT_M); textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val pTxtD = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(TXT_D); textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val pTxtPill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(TXT_B); textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }

    // ═══ STATE — UNCHANGED ═══
    private var state = GamepadState()
    private val pMap = mutableMapOf<Int, String>()
    private var homeP = false

    // ═══ GEOMETRY — UNCHANGED ═══
    private var vw = 0f; private var vh = 0f; private var sc = 0f
    private var lsCx = 0f; private var lsCy = 0f; private var lsR = 0f
    private var lsK = 0f; private var lsKx = 0f; private var lsKy = 0f
    private var rsCx = 0f; private var rsCy = 0f; private var rsR = 0f
    private var rsK = 0f; private var rsKx = 0f; private var rsKy = 0f
    private val dR = mutableMapOf<String, RectF>()
    private val aR = mutableMapOf<String, RectF>()
    private val sR = mutableMapOf<String, RectF>()
    private val pR = mutableMapOf<String, RectF>()
    private val hR = RectF(); private val l3 = RectF(); private val r3 = RectF()
    private var dCx = 0f; private var dCy = 0f; private var dArm = 0f

    // Stroke widths (scaled once)
    private var swRim = 0f; private var swRimThin = 0f; private var swWellRim = 0f
    private var swWellInner = 0f; private var swCapRim = 0f; private var swGlowRing = 0f
    private var swHLArc = 0f

    override fun onSizeChanged(width: Int, height: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(width, height, oldw, oldh)
        vw = width.toFloat(); vh = height.toFloat()
        val s = kotlin.math.min(vw / DW, vh / DH); sc = s
        val oX = (vw - DW * s) / 2f; val oY = (vh - DH * s) / 2f
        fun px(x: Float) = oX + x * s
        fun py(y: Float) = oY + y * s
        fun ps(v: Float) = v * s
        fun pxL(d: Float) = d * s
        fun pxR(d: Float) = vw - d * s

        pTxtFace.textSize = ps(DM * 0.044f)
        pTxtPs.textSize = ps(DM * 0.044f * 1.3f)
        pTxtM.textSize = ps(DM * 0.038f)
        pTxtD.textSize = ps(DM * 0.032f)
        pTxtPill.textSize = ps(DM * 0.030f)

        swGlowRing = ps(DM * 0.005f); pGlowRing.strokeWidth = swGlowRing
        swWellRim = ps(DM * 0.004f); pWellRim.strokeWidth = swWellRim
        swWellInner = ps(DM * 0.003f); pWellInner.strokeWidth = swWellInner
        swCapRim = ps(DM * 0.003f); pCapRim.strokeWidth = swCapRim
        swRim = ps(DM * 0.002f); pRim.strokeWidth = swRim
        swRimThin = ps(DM * 0.0015f); pRimThin.strokeWidth = swRimThin
        swHLArc = ps(DM * 0.003f); pHLArc.strokeWidth = swHLArc

        pBg.shader = LinearGradient(0f, 0f, 0f, vh,
            intArrayOf(cBgTop, cBgBot),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)

        val shR = ps(DM * 0.074f); sR.clear()
        sR["lt"] = mR(pxL(LT_X), py(LT_Y), shR); sR["lb"] = mR(pxL(LB_X), py(LB_Y), shR)
        sR["rb"] = mR(pxR(LB_X), py(LB_Y), shR); sR["rt"] = mR(pxR(LT_X), py(LT_Y), shR)

        val pillW = ps(DW * 0.104f); val pillH = ps(DM * 0.069f); val homR = ps(DM * 0.053f)
        hR.set(px(HOME_X) - homR, py(HOME_Y) - homR, px(HOME_X) + homR, py(HOME_Y) + homR)
        pR.clear()
        pR["select"] = RectF(px(VIEW_X) - pillW / 2f, py(VIEW_Y) - pillH / 2f, px(VIEW_X) + pillW / 2f, py(VIEW_Y) + pillH / 2f)
        pR["start"] = RectF(px(MENU_X) - pillW / 2f, py(MENU_Y) - pillH / 2f, px(MENU_X) + pillW / 2f, py(MENU_Y) + pillH / 2f)

        lsR = ps(DM * 0.179f); lsK = lsR * 0.50f; lsCx = px(LS_CX); lsCy = py(LS_CY); lsKx = lsCx; lsKy = lsCy
        rsR = ps(DM * 0.179f); rsK = rsR * 0.50f; rsCx = px(RS_CX); rsCy = py(RS_CY); rsKx = rsCx; rsKy = rsCy

        dCx = pxL(DPAD_CX); dCy = py(DPAD_CY); dArm = ps(DM * 0.074f)
        val dOff = dArm * 1.75f; dR.clear()
        dR["dUp"] = mR(dCx, dCy - dOff, dArm); dR["dDown"] = mR(dCx, dCy + dOff, dArm)
        dR["dLeft"] = mR(dCx - dOff, dCy, dArm); dR["dRight"] = mR(dCx + dOff, dCy, dArm)

        val ax = pxR(DPAD_CX); val ay = py(DPAD_CY); val br = ps(DM * 0.074f); val ao = br * 1.75f; aR.clear()
        aR["y"] = mR(ax, ay - ao, br); aR["x"] = mR(ax - ao, ay, br)
        aR["b"] = mR(ax + ao, ay, br); aR["a"] = mR(ax, ay + ao, br)

        val l3R = ps(DM * 0.080f)
        l3.set(pxL(L3_X) - l3R, py(L3_Y) - l3R, pxL(L3_X) + l3R, py(L3_Y) + l3R)
        r3.set(pxR(L3_X) - l3R, py(L3_Y) - l3R, pxR(L3_X) + l3R, py(L3_Y) + l3R)

        updateGestureExclusion()
    }

    /**
     * Register gesture-exclusion rects for Android 10+ gesture navigation.
     *
     * Uses the platform maximum exclusion width (200dp) from each edge.
     * This is the widest exclusion Android allows per-side — any rect
     * extending beyond 200dp from the edge is silently truncated by the
     * platform, so using the full 200dp guarantees maximum protection.
     *
     * Two full-height strips cover the left and right edges where all
     * interactive controls live (LT/LB, D-pad, L3 on the left;
     * RT/RB, ABXY, R3 on the right).  The bottom corners are already
     * covered by these full-height strips.
     *
     * The rects are recomputed every time onSizeChanged fires (which
     * covers orientation changes, window-resize, and inset changes).
     * Only the GamepadView sets exclusion rects — when it is GONE
     * (Home/BT/WiFi screens), no exclusions apply, so system Back
     * works normally on those screens.
     */
    private fun updateGestureExclusion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val rects = ArrayList<Rect>(4)
        val density = resources.displayMetrics.density

        // 200dp — the platform-wide maximum exclusion width from any edge.
        // Using int round rather than toInt() to avoid truncation artefacts.
        val strip = (200f * density + 0.5f).toInt()

        // Left edge — full-height strip protects LT, LB, D-pad, L3,
        // and the left analog stick's leftward approach zone.
        rects.add(Rect(0, 0, strip, vh.toInt()))

        // Right edge — full-height strip protects RT, RB, ABXY, R3,
        // and the right analog stick's rightward approach zone.
        rects.add(Rect(vw.toInt() - strip, 0, vw.toInt(), vh.toInt()))

        // Bottom-left corner — extra depth for L3 which sits very
        // close to the bottom edge; extends the left-strip's bottom.
        rects.add(Rect(0, vh.toInt() - strip, strip, vh.toInt()))

        // Bottom-right corner — same treatment for R3.
        rects.add(Rect(vw.toInt() - strip, vh.toInt() - strip, vw.toInt(), vh.toInt()))

        setSystemGestureExclusionRects(rects)
    }

    /**
     * Keep gesture-exclusion rects in sync with visibility.
     * While VISIBLE they protect the on-screen controls; when the view is
     * hidden (GONE/INVISIBLE) they are cleared so the system edge-swipe Back
     * gesture works normally on the Home/Connect/Wi-Fi screens.
     */
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == View.VISIBLE) {
            updateGestureExclusion()
        } else {
            setSystemGestureExclusionRects(emptyList())
        }
    }

    private fun mR(cx: Float, cy: Float, r: Float) = RectF(cx - r, cy - r, cx + r, cy + r)

    // ═══ DRAWING ═══
    override fun onDraw(canvas: Canvas) {
        canvas.drawPaint(pBg)
        drawDpad(canvas); drawAbxy(canvas); drawShoulders(canvas)
        drawSmallBtn(canvas, l3, state.l3, "L3", pTxtD); drawSmallBtn(canvas, r3, state.r3, "R3", pTxtD)
        drawSystem(canvas)
        drawStick(canvas, lsCx, lsCy, lsR, lsK, lsKx, lsKy)
        drawStick(canvas, rsCx, rsCy, rsR, rsK, rsKx, rsKy)
    }

    // ── Shared round button surface ──
    private fun btnSurface(canvas: Canvas, cx: Float, cy: Float, r: Float, pressed: Boolean) {
        // 1. Diffuse shadow — wider, softer for grounded feel
        canvas.drawCircle(cx, cy + r * 0.06f, r * 1.04f, pShadowMed)
        // 2. Contact shadow — tighter, directly under button
        canvas.drawCircle(cx, cy + r * 0.03f, r * 0.92f, pShadowSmall)

        // 3. Gradient surface — convex dome: light top-left → mid → darker bottom-right
        pSurf.shader = LinearGradient(
            cx - r, cy - r, cx + r * 0.6f, cy + r * 0.6f,
            intArrayOf(cSurfH, cSurf, cSurfL),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, pSurf); pSurf.shader = null

        // 4. Upper-left specular highlight — soft elliptical sheen
        canvas.drawCircle(cx - r * 0.18f, cy - r * 0.22f, r * 0.40f, pHLSoft)

        // 5. Subtle rim for edge definition
        pRim.color = cRim; canvas.drawCircle(cx, cy, r, pRim)

        if (pressed) {
            // 6a. Inset shadow — button appears physically depressed
            pInset.color = cInset
            canvas.drawCircle(cx, cy + r * 0.02f, r * 0.90f, pInset)
            // 6b. Accent glow — restrained illumination
            pGlow.alpha = 30; canvas.drawCircle(cx, cy, r * 1.06f, pGlow)
            // 6c. Accent ring on edge
            pGlowRing.strokeWidth = swGlowRing; pGlowRing.alpha = 160
            canvas.drawCircle(cx, cy, r, pGlowRing)
        }
    }

    // ── D-pad: 4 independent buttons ──
    private fun drawDpad(canvas: Canvas) {
        val cx = dCx; val cy = dCy; val r = dArm; val off = r * 1.75f
        val dirs = listOf("dUp" to "\u25B2", "dDown" to "\u25BC", "dLeft" to "\u25C0", "dRight" to "\u25B6")
        val offsets = mapOf("dUp" to Pair(0f, -off), "dDown" to Pair(0f, off), "dLeft" to Pair(-off, 0f), "dRight" to Pair(off, 0f))
        for ((k, g) in dirs) {
            val pr = when (k) { "dUp" -> state.dUp; "dDown" -> state.dDown; "dLeft" -> state.dLeft; "dRight" -> state.dRight; else -> false }
            val (ox, oy) = offsets[k]!!; val bx = cx + ox; val by = cy + oy
            btnSurface(canvas, bx, by, r, pr)
            canvas.drawText(g, bx, by + (if (pr) pTxtFace else pTxtM).textSize / 3, if (pr) pTxtFace else pTxtM)
        }
    }

    // ── ABXY face buttons ──
    private fun drawAbxy(canvas: Canvas) {
        val cols = mapOf("y" to cY, "x" to cX, "b" to cB, "a" to cA)
        val entries = listOf("y" to state.y, "x" to state.x, "b" to state.b, "a" to state.a)
        for ((k, pr) in entries) {
            val rc = aR[k]!!; val bx = rc.centerX(); val by = rc.centerY(); val r = (rc.right - rc.left) / 2f
            btnSurface(canvas, bx, by, r, pr)
            if (!pr) {
                // Color-coded rim — subtle tinted edge
                pColorRim.color = cols[k]!!; pColorRim.alpha = 55
                canvas.drawCircle(bx, by, r, pColorRim)
            }
            val lbl = if (controllerStyle == ControllerStyle.PS) when (k) { "y" -> "\u25B3"; "x" -> "\u25A1"; "b" -> "\u25CB"; else -> "\u2716" } else k.uppercase()
            val tp = if (controllerStyle == ControllerStyle.PS) pTxtPs else pTxtFace
            canvas.drawText(lbl, bx, by + tp.textSize / 3, tp)
        }
    }

    // ── Shoulder buttons ──
    private fun drawShoulders(canvas: Canvas) {
        val (ltL, lbL, rbL, rtL) = if (controllerStyle == ControllerStyle.XBOX) arrayOf("LT", "LB", "RB", "RT") else arrayOf("L2", "L1", "R1", "R2")
        drawSh(canvas, sR["lt"]!!, state.lt > 0f, ltL); drawSh(canvas, sR["lb"]!!, state.lb, lbL)
        drawSh(canvas, sR["rb"]!!, state.rb, rbL); drawSh(canvas, sR["rt"]!!, state.rt > 0f, rtL)
    }

    private fun drawSh(canvas: Canvas, rc: RectF, pr: Boolean, lbl: String) {
        val cx = rc.centerX(); val cy = rc.centerY(); val r = (rc.right - rc.left) / 2f
        btnSurface(canvas, cx, cy, r, pr)
        canvas.drawText(lbl, cx, cy + pTxtD.textSize / 3, pTxtD)
    }

    // ── L3 / R3 ──
    private fun drawSmallBtn(canvas: Canvas, rc: RectF, pr: Boolean, lbl: String, tp: Paint) {
        val cx = rc.centerX(); val cy = rc.centerY(); val r = (rc.right - rc.left) / 2f
        btnSurface(canvas, cx, cy, r, pr)
        canvas.drawText(lbl, cx, cy + tp.textSize / 3, tp)
    }

    // ── System buttons ──
    private fun drawSystem(canvas: Canvas) {
        pR["select"]?.let { drawPill(canvas, it, state.select, if (controllerStyle == ControllerStyle.PS) "CREATE" else "VIEW") }
        pR["start"]?.let { drawPill(canvas, it, state.start, if (controllerStyle == ControllerStyle.PS) "OPTIONS" else "MENU") }
        val hx = hR.centerX(); val hy = hR.centerY(); val hr = hR.width() / 2f
        btnSurface(canvas, hx, hy, hr, homeP)
        if (controllerStyle == ControllerStyle.PS) canvas.drawText("PS", hx, hy + pTxtM.textSize / 3, pTxtM)
        else {
            pRimThin.color = cRim; canvas.drawCircle(hx, hy, hr * 0.32f, pRimThin)
        }
    }

    private fun drawPill(canvas: Canvas, rc: RectF, pr: Boolean, lbl: String) {
        val cr = rc.height() / 2f
        // Shadow
        canvas.drawRoundRect(RectF(rc.left, rc.top + 4f, rc.right, rc.bottom + 4f), cr, cr, pShadowMed)
        // Gradient surface
        pSurf.shader = LinearGradient(rc.left, rc.top, rc.left, rc.bottom,
            intArrayOf(cSurfH, cSurf, cSurfL),
            floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rc, cr, cr, pSurf); pSurf.shader = null
        // Top highlight
        val hlR = RectF(rc.left + 3, rc.top + 1, rc.right - 3, rc.top + rc.height() * 0.35f)
        canvas.drawRoundRect(hlR, cr * 0.5f, cr * 0.5f, pHLSoft)
        // Rim
        pRimThin.color = cRim; canvas.drawRoundRect(rc, cr, cr, pRimThin)
        if (pr) {
            pInset.color = cInsetPill
            canvas.drawRoundRect(RectF(rc.left + 1, rc.top + 1.5f, rc.right - 1, rc.bottom - 1), cr, cr, pInset)
            pGlow.alpha = 22; canvas.drawRoundRect(RectF(rc.left - 3, rc.top - 3, rc.right + 3, rc.bottom + 3), cr + 3, cr + 3, pGlow)
        }
        canvas.drawText(lbl, rc.centerX(), rc.centerY() + pTxtPill.textSize / 3, pTxtPill)
    }

    // ── Analog sticks — wide, low-profile console thumbstick ──
    //
    // Visual construction avoids radial-gradient "ball" shading.
    // Uses linear top-to-bottom gradients for a flat/wide surface,
    // with a separate concave depression layer.
    private fun drawStick(canvas: Canvas, cx: Float, cy: Float, r: Float, kR: Float, kX: Float, kY: Float) {
        val moved = (kX != cx || kY != cy)

        // ── 1. Contact shadow ──
        // Wide, very soft, offset down. Grounds the stick without
        // making it look like it's hovering above a deep hole.
        canvas.drawCircle(cx + r * 0.008f, cy + r * 0.04f, r * 0.88f, pShadowMed)

        // ── 2. Socket — shallow recessed disc ──
        // A gentle linear gradient (light top → dark bottom) creates
        // the impression of a shallow concavity without a spherical bowl.
        pWellBase.shader = LinearGradient(
            cx, cy - r, cx, cy + r,
            intArrayOf(cSurfL, cWell, cWellDeep),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, pWellBase); pWellBase.shader = null

        // ── 3. Socket rim — single soft ring ──
        pWellRim.color = cWellEdge; pWellRim.strokeWidth = swWellRim
        canvas.drawCircle(cx, cy, r, pWellRim)

        // ── 4. Accent glow — only when actively moved ──
        if (moved) {
            pGlow.alpha = 12; canvas.drawCircle(cx, cy, r * 1.03f, pGlow)
        }

        // ── 5. Cap shadow — thin contact shadow ──
        // Very slight offset to suggest the cap sits just above the socket.
        canvas.drawCircle(kX + r * 0.008f, kY + r * 0.02f, kR * 1.005f, pShadowSmall)

        // ── 6. Cap base — FLAT rubber surface ──
        // This is the key change: a LINEAR top-to-bottom gradient
        // (not radial) makes the surface read as FLAT and WIDE,
        // like a real rubber thumbstick pad seen from above.
        // Top catches light → slightly lighter. Bottom is shaded → darker.
        val capTop = if (moved) Color.parseColor("#949CAA") else Color.parseColor("#A4ACBC")
        val capBot = if (moved) Color.parseColor("#7A8292") else Color.parseColor("#8A92A2")
        pCap.shader = LinearGradient(
            kX, kY - kR, kX, kY + kR,
            intArrayOf(capTop, capTop, capBot),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(kX, kY, kR, pCap); pCap.shader = null

        // ── 7. Concave depression — the center is slightly LOWER ──
        // A subtle darker ellipse in the upper-center suggests the
        // thumb pad curves inward (concave), not outward (convex).
        // This directly counteracts the "raised bump" appearance.
        pCapHL.alpha = 18
        canvas.drawOval(
            kX - kR * 0.50f, kY - kR * 0.35f,
            kX + kR * 0.50f, kY + kR * 0.25f,
            pCapHL)

        // ── 8. Edge bevel — soft dark ring ──
        // A thin, low-contrast ring at the cap perimeter gives
        // the rubber pad a beveled edge without looking like a button outline.
        pCapRim.color = Color.parseColor(if (moved) "#6A7282" else "#747C8C")
        pCapRim.strokeWidth = swCapRim * 0.7f
        canvas.drawCircle(kX, kY, kR, pCapRim)

        // ── 9. Upper surface highlight — broad soft band ──
        // A wide, diffuse elliptical highlight across the top third
        // of the cap. This reinforces the flat/wide surface reading
        // rather than a spherical highlight point.
        pCapHL.alpha = 22
        canvas.drawOval(
            kX - kR * 0.55f, kY - kR * 0.70f,
            kX + kR * 0.55f, kY - kR * 0.10f,
            pCapHL)

        // ── 10. Center grip — subtle concave dimple ──
        // A small, soft, slightly darkened area in the very center
        // suggests rubber grip texture in the concave depression.
        canvas.drawCircle(kX, kY - kR * 0.05f, kR * 0.14f, pDimple)
    }

    // ═══ TOUCH + STATE — UNCHANGED ═══
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex; val pid = event.getPointerId(i)
                fAt(event.getX(i), event.getY(i))?.let { c -> pMap[pid] = c; uS(c, event.getX(i), event.getY(i), true); invalidate(); return true }
            }
            MotionEvent.ACTION_MOVE -> { for (i in 0 until event.pointerCount) { val pid = event.getPointerId(i); pMap[pid]?.let { c -> uS(c, event.getX(i), event.getY(i), true) } }; invalidate(); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex; val pid = event.getPointerId(i)
                pMap.remove(pid)?.let { c -> uS(c, 0f, 0f, false); invalidate(); return true }
            }
            MotionEvent.ACTION_CANCEL -> { pMap.clear(); rS(); invalidate(); return true }
        }
        return super.onTouchEvent(event)
    }

    private fun fAt(x: Float, y: Float): String? {
        if (l3.contains(x, y)) return "l3"; if (r3.contains(x, y)) return "r3"; if (hR.contains(x, y)) return "home"
        sR.forEach { (n, r) -> if (r.contains(x, y)) return n }; pR.forEach { (n, r) -> if (r.contains(x, y)) return n }
        aR.forEach { (n, r) -> if (r.contains(x, y)) return n }; dR.forEach { (n, r) -> if (r.contains(x, y)) return n }
        if (d(x, y, lsCx, lsCy) < lsR * 1.25f) return "leftStick"; if (d(x, y, rsCx, rsCy) < rsR * 1.25f) return "rightStick"; return null
    }

    private fun uS(c: String, x: Float, y: Float, p: Boolean) {
        when (c) {
            "leftStick" -> if (!p) { lsKx = lsCx; lsKy = lsCy; state = state.copy(lx = 0f, ly = 0f) } else { val dx = (x - lsCx) / lsR; val dy = (y - lsCy) / lsR; val l = kotlin.math.hypot(dx, dy); val (cx2, cy2) = if (l > 1f) { val s = 1f / l; Pair(dx * s, dy * s) } else Pair(dx, dy); lsKx = lsCx + cx2 * lsR; lsKy = lsCy + cy2 * lsR; state = state.copy(lx = cx2, ly = -cy2) }
            "rightStick" -> if (!p) { rsKx = rsCx; rsKy = rsCy; state = state.copy(rx = 0f, ry = 0f) } else { val dx = (x - rsCx) / rsR; val dy = (y - rsCy) / rsR; val l = kotlin.math.hypot(dx, dy); val (cx2, cy2) = if (l > 1f) { val s = 1f / l; Pair(dx * s, dy * s) } else Pair(dx, dy); rsKx = rsCx + cx2 * rsR; rsKy = rsCy + cy2 * rsR; state = state.copy(rx = cx2, ry = -cy2) }
            "a" -> state = state.copy(a = p); "b" -> state = state.copy(b = p); "x" -> state = state.copy(x = p); "y" -> state = state.copy(y = p)
            "lb" -> state = state.copy(lb = p); "rb" -> state = state.copy(rb = p)
            "lt" -> state = state.copy(lt = if (p) 1f else 0f); "rt" -> state = state.copy(rt = if (p) 1f else 0f)
            "dUp" -> state = state.copy(dUp = p); "dDown" -> state = state.copy(dDown = p); "dLeft" -> state = state.copy(dLeft = p); "dRight" -> state = state.copy(dRight = p)
            "start" -> state = state.copy(start = p); "select" -> state = state.copy(select = p)
            "l3" -> state = state.copy(l3 = p); "r3" -> state = state.copy(r3 = p)
            "home" -> homeP = p
        }
        listener?.onStateChanged(state)
    }

    private fun rS() { state = GamepadState(); homeP = false; lsKx = lsCx; lsKy = lsCy; rsKx = rsCx; rsKy = rsCy; listener?.onStateChanged(state) }
    private fun d(x1: Float, y1: Float, x2: Float, y2: Float) = kotlin.math.hypot(x1 - x2, y1 - y2)
}

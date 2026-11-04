package com.example.swapbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * خدمة الوصول:
 *  - دائرة 1 = زر سلاح الكليرك، دائرة 2 = زر سلاح الويزرد (اللعبة نفسها تفعّل الاسكلات بالصيد التلقائي)
 *  - إطار أخضر تضعه فوق صف أيقونات تفعيل الكليرك، والتطبيق يقسمه لخانات ويتعلم شكل كل أيقونة
 *  - لما أي أيقونة تختفي: كليرك -> ينتظر ترجع الأيقونات -> ويزرد
 */
class SwapService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: SwapService? = null
        private const val THUMB = 6 // حجم الصورة المصغرة لكل أيقونة (6x6)
    }

    private class Marker(
        val view: TextView,
        val lp: WindowManager.LayoutParams,
        var shown: Boolean = false
    )

    private data class Region(val x: Int, val y: Int, val w: Int, val h: Int)

    /** شريط/إطار قابل للسحب والتكبير. cells=true يقسمه لخانات مرقمة (للأيقونات)، وإلا شريط بسيط (للدم). */
    private inner class FrameView(
        ctx: Context,
        private val cells: Boolean,
        private val col: Int,
        private val label: String
    ) : View(ctx) {
        private val fill = Paint().apply {
            color = (col and 0x00FFFFFF) or 0x22000000
            style = Paint.Style.FILL
        }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = col
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
        }
        private val grid = Paint().apply {
            color = 0xFFFFEB3B.toInt()
            strokeWidth = dp(1).toFloat()
        }
        private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dp(10).toFloat()
            isFakeBoldText = true
            setShadowLayer(dp(2).toFloat(), 0f, 0f, Color.BLACK)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawRect(0f, 0f, w, h, fill)
            canvas.drawRect(1f, 1f, w - 1f, h - 1f, border)
            if (cells) {
                if (h > 0f) {
                    var x = h
                    var i = 2
                    canvas.drawText("1", dp(4).toFloat(), dp(12).toFloat(), num)
                    while (x < w - 2f) {
                        canvas.drawLine(x, 0f, x, h, grid)
                        canvas.drawText(i.toString(), x + dp(4), dp(12).toFloat(), num)
                        x += h
                        i++
                    }
                }
            } else {
                canvas.drawText(label, dp(4).toFloat(), minOf(h - 2f, dp(11).toFloat()), num)
            }
            val r = minOf(dp(8).toFloat(), h / 2f)
            canvas.drawCircle(w - r - 2f, h - r - 2f, r, handle)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private val markers = mutableListOf<Marker>() // 0 كليرك، 1 ويزرد
    private var frame: FrameView? = null
    private var frameLp: WindowManager.LayoutParams? = null
    private var frameShown = false
    private var hpFrame: FrameView? = null
    private var hpLp: WindowManager.LayoutParams? = null
    private var hpShown = false
    private var panel: LinearLayout? = null
    private var statusView: TextView? = null
    private var startBtn: Button? = null
    private var learnBtn: Button? = null

    @Volatile
    private var running = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(t: String) = main.post { Toast.makeText(this, t, Toast.LENGTH_SHORT).show() }

    // ---------------------------------------------------------------- دورة الحياة

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        toast("Swap Bot جاهز. افتح التطبيق واضغط «أظهر اللوحة العائمة»")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        running = false
        hideOverlay()
        instance = null
    }

    // ---------------------------------------------------------------- الواجهة العائمة

    private fun overlayFlags() =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    private fun newLp(w: Int, h: Int, px: Int, py: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        overlayFlags(),
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = px
        y = py
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    private fun makeDraggable(handle: View, target: View, lp: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    downX = e.rawX
                    downY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - downX).toInt()
                    lp.y = startY + (e.rawY - downY).toInt()
                    try {
                        wm.updateViewLayout(target, lp)
                    } catch (_: Exception) {
                    }
                }
            }
            true
        }
    }

    /** سحب الإطار من أي مكان، وتكبيره/تصغيره من الزاوية السفلية اليمنى. */
    private fun makeFrameTouch(v: View, lp: WindowManager.LayoutParams, minH: Int = dp(24)) {
        var mode = 0 // 1 تحريك، 2 تغيير حجم
        var sx = 0
        var sy = 0
        var sw = 0
        var sh = 0
        var downX = 0f
        var downY = 0f
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    mode = if (e.x > v.width - dp(32) && e.y > v.height - dp(32)) 2 else 1
                    sx = lp.x
                    sy = lp.y
                    sw = lp.width
                    sh = lp.height
                    downX = e.rawX
                    downY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    val mx = (e.rawX - downX).toInt()
                    val my = (e.rawY - downY).toInt()
                    if (mode == 1) {
                        lp.x = sx + mx
                        lp.y = sy + my
                    } else {
                        lp.width = maxOf(dp(24), sw + mx)
                        lp.height = maxOf(minH, sh + my)
                    }
                    try {
                        wm.updateViewLayout(v, lp)
                    } catch (_: Exception) {
                    }
                    v.invalidate()
                }
            }
            true
        }
    }

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 11f
        isAllCaps = false
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setOnClickListener { onClick() }
    }

    fun showOverlay() {
        if (panel != null) {
            showMarkers()
            return
        }
        val dm = resources.displayMetrics
        val sp = Prefs.sp(this)
        val size = dp(36)

        // الدوائر: 1 كليرك، 2 ويزرد
        val colors = intArrayOf(0xAAE53935.toInt(), 0xAA1E88E5.toInt())
        val keys = arrayOf("cle", "wiz")
        markers.clear()
        for (i in 0..1) {
            val cx = sp.getInt(keys[i] + "X", dm.widthPixels / 2 + (i * 2 - 1) * dp(45))
            val cy = sp.getInt(keys[i] + "Y", dm.heightPixels / 2)
            val tv = TextView(this).apply {
                text = (i + 1).toString()
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 14f
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colors[i])
                    setStroke(dp(2), Color.WHITE)
                }
            }
            val lp = newLp(size, size, cx - size / 2, cy - size / 2)
            makeDraggable(tv, tv, lp)
            markers.add(Marker(tv, lp))
        }

        // الإطار
        val fw = sp.getInt("frameW", dp(180))
        val fh = sp.getInt("frameH", dp(36))
        val fx = sp.getInt("frameX", dm.widthPixels / 2 - fw / 2)
        val fy = sp.getInt("frameY", dp(100))
        val fv = FrameView(this, true, 0xFF43A047.toInt(), "")
        val flp = newLp(fw, fh, fx, fy)
        makeFrameTouch(fv, flp)
        frame = fv
        frameLp = flp
        frameShown = false

        // شريط الدم (أحمر)
        val hw = sp.getInt("hpW", dp(160))
        val hh = sp.getInt("hpH", dp(12))
        val hx = sp.getInt("hpX", dm.widthPixels / 2 - hw / 2)
        val hy = sp.getInt("hpY", dp(160))
        val hv = FrameView(this, false, 0xFFE53935.toInt(), "HP")
        val hlp = newLp(hw, hh, hx, hy)
        makeFrameTouch(hv, hlp, dp(6))
        hpFrame = hv
        hpLp = hlp
        hpShown = false

        // اللوحة
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(0xDD202020.toInt())
                cornerRadius = dp(10).toFloat()
            }
        }
        val handle = TextView(this).apply {
            text = "≡ Swap Bot  (اسحب)"
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        val hint = TextView(this).apply {
            text = "1 كليرك • 2 ويزرد • الأخضر على الاسكلات • الأحمر على شريط الدم"
            setTextColor(0xFFB0BEC5.toInt())
            textSize = 10f
        }
        statusView = TextView(this).apply {
            text = "جاهز"
            setTextColor(0xFF80CBC4.toInt())
            textSize = 11f
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        learnBtn = smallButton("تعلّم") { learn() }
        startBtn = smallButton("▶ ابدأ") { toggle() }
        val closeBtn = smallButton("✕") { hideOverlay() }
        row.addView(learnBtn)
        row.addView(startBtn)
        row.addView(closeBtn)
        box.addView(handle)
        box.addView(hint)
        box.addView(statusView)
        box.addView(row)

        val plp = newLp(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            dp(12), dp(160)
        )
        makeDraggable(handle, box, plp)
        wm.addView(box, plp)
        panel = box

        showMarkers()
    }

    fun hideOverlay() {
        running = false
        hideMarkers()
        panel?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        panel = null
        statusView = null
        startBtn = null
        learnBtn = null
        frame = null
        frameLp = null
        frameShown = false
        hpFrame = null
        hpLp = null
        hpShown = false
    }

    private fun showMarkers() {
        for (m in markers) {
            if (!m.shown) {
                try {
                    wm.addView(m.view, m.lp)
                    m.shown = true
                } catch (_: Exception) {
                }
            }
        }
        val f = frame
        val lp = frameLp
        if (f != null && lp != null && !frameShown) {
            try {
                wm.addView(f, lp)
                frameShown = true
            } catch (_: Exception) {
            }
        }
        val hf = hpFrame
        val hl = hpLp
        if (hf != null && hl != null && !hpShown && Prefs.sp(this).getBoolean("hpOn", true)) {
            try {
                wm.addView(hf, hl)
                hpShown = true
            } catch (_: Exception) {
            }
        }
    }

    private fun hideMarkers() {
        for (m in markers) {
            if (m.shown) {
                try {
                    wm.removeView(m.view)
                } catch (_: Exception) {
                }
                m.shown = false
            }
        }
        val f = frame
        if (f != null && frameShown) {
            try {
                wm.removeView(f)
            } catch (_: Exception) {
            }
            frameShown = false
        }
        val hf = hpFrame
        if (hf != null && hpShown) {
            try {
                wm.removeView(hf)
            } catch (_: Exception) {
            }
            hpShown = false
        }
    }

    private fun setStatus(t: String) {
        main.post { statusView?.text = t }
    }

    private fun updatePanel() {
        main.post {
            startBtn?.text = if (running) "■ وقف" else "▶ ابدأ"
            learnBtn?.isEnabled = !running
        }
    }

    // ---------------------------------------------------------------- حفظ المواقع والتعلّم

    private fun center(m: Marker): Pair<Int, Int> {
        return if (m.view.isAttachedToWindow) {
            val loc = IntArray(2)
            m.view.getLocationOnScreen(loc)
            Pair(loc[0] + m.view.width / 2, loc[1] + m.view.height / 2)
        } else {
            Pair(m.lp.x + m.lp.width / 2, m.lp.y + m.lp.height / 2)
        }
    }

    private fun savePositions() {
        if (markers.size < 2 || !markers[0].shown) return
        val keys = arrayOf("cle", "wiz")
        val e = Prefs.sp(this).edit()
        for (i in 0..1) {
            val (cx, cy) = center(markers[i])
            e.putInt(keys[i] + "X", cx)
            e.putInt(keys[i] + "Y", cy)
        }
        val f = frame
        if (f != null && frameShown && f.isAttachedToWindow) {
            val loc = IntArray(2)
            f.getLocationOnScreen(loc)
            e.putInt("frameX", loc[0])
            e.putInt("frameY", loc[1])
            e.putInt("frameW", f.width)
            e.putInt("frameH", f.height)
        }
        val hf = hpFrame
        if (hf != null && hpShown && hf.isAttachedToWindow) {
            val loc = IntArray(2)
            hf.getLocationOnScreen(loc)
            e.putInt("hpX", loc[0])
            e.putInt("hpY", loc[1])
            e.putInt("hpW", hf.width)
            e.putInt("hpH", hf.height)
        }
        e.apply()
    }

    private fun frameRegion(sp: SharedPreferences, p: String = "frame") = Region(
        sp.getInt(p + "X", -1), sp.getInt(p + "Y", -1),
        sp.getInt(p + "W", 0), sp.getInt(p + "H", 0)
    )

    private fun validRegion(r: Region) = r.x >= 0 && r.y >= 0 && r.w > 0 && r.h > 0

    // ---- شريط الدم: نسبة الأعمدة اللي لونها يطابق لون الدم الممتلئ
    private fun hpColumns(px: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * 3)
        val y0 = h / 3
        val y1 = maxOf(y0 + 1, 2 * h / 3)
        for (x in 0 until w) {
            var r = 0
            var g = 0
            var b = 0
            var n = 0
            for (y in y0 until minOf(y1, h)) {
                val p = px[y * w + x]
                r += Color.red(p)
                g += Color.green(p)
                b += Color.blue(p)
                n++
            }
            if (n > 0) {
                out[x * 3] = r / n
                out[x * 3 + 1] = g / n
                out[x * 3 + 2] = b / n
            }
        }
        return out
    }

    private fun hpMedian(cols: IntArray, w: Int): IntArray {
        val ref = IntArray(3)
        for (c in 0..2) {
            val a = IntArray(w) { cols[it * 3 + c] }
            a.sort()
            ref[c] = a[w / 2]
        }
        return ref
    }

    private fun hpFraction(px: IntArray, w: Int, h: Int, ref: IntArray, tol: Float): Float {
        val cols = hpColumns(px, w, h)
        var ok = 0
        for (x in 0 until w) {
            val d = (abs(cols[x * 3] - ref[0]) + abs(cols[x * 3 + 1] - ref[1]) +
                abs(cols[x * 3 + 2] - ref[2])) / 3f
            if (d <= tol) ok++
        }
        return ok.toFloat() / w
    }

    private fun loadHpRef(sp: SharedPreferences): IntArray? {
        val p = sp.getString("hpRef", null)?.split(",")?.mapNotNull { it.toIntOrNull() } ?: return null
        return if (p.size == 3) p.toIntArray() else null
    }

    private fun geomString(r: Region) = "${r.x},${r.y},${r.w},${r.h}"

    private fun sameGeom(a: String, b: String): Boolean {
        val pa = a.split(",").mapNotNull { it.toIntOrNull() }
        val pb = b.split(",").mapNotNull { it.toIntOrNull() }
        if (pa.size != 4 || pb.size != 4) return false
        return pa.indices.all { abs(pa[it] - pb[it]) <= 4 }
    }

    private fun learn() {
        if (running) return
        savePositions()
        val sp = Prefs.sp(this)
        val r = frameRegion(sp)
        val hr = frameRegion(sp, "hp")
        val iconsOk = validRegion(r)
        val hpOk = sp.getBoolean("hpOn", true) && validRegion(hr)
        if (!iconsOk && !hpOk) {
            toast("ضع الإطار الأخضر فوق الأيقونات أولاً")
            return
        }
        hideMarkers()
        setStatus("أتعلّم...")
        Thread {
            Thread.sleep(450) // انتظر اختفاء الدوائر والإطارات من الشاشة
            val regs = ArrayList<Region>()
            if (iconsOk) regs.add(r)
            if (hpOk) regs.add(hr)
            val got = grabRegions(regs)
            var idx = 0
            val px = if (iconsOk) got[idx++] else null
            val hp = if (hpOk) got[idx] else null
            main.post {
                val parts = ArrayList<String>()
                if (px != null) {
                    val t = thumbs(px, r.w, r.h)
                    saveRefs(t)
                    sp.edit().putString("refGeom", geomString(r)).apply()
                    parts.add("${t.size} أيقونة")
                }
                if (hp != null) {
                    val cols = hpColumns(hp, hr.w, hr.h)
                    val ref = hpMedian(cols, hr.w)
                    val tolHp = sp.getFloat("hpTol", 40f).coerceAtLeast(1f)
                    val full = hpFraction(hp, hr.w, hr.h, ref, tolHp)
                    sp.edit()
                        .putString("hpRef", ref.joinToString(","))
                        .putFloat("hpFull", full)
                        .putString("hpGeom", geomString(hr))
                        .apply()
                    parts.add("شريط الدم (${Math.round(full * 100)}%)")
                }
                if (parts.isNotEmpty()) {
                    setStatus("تعلّمت: ${parts.joinToString(" + ")} ✓")
                    toast("تعلّمت ${parts.joinToString(" + ")}. شريط الدم لازم يكون ممتلئ وقت التعلّم")
                } else {
                    setStatus("فشل التعلّم")
                    toast("تعذرت قراءة الشاشة (ممكن اللعبة تمنع لقطات الشاشة)")
                }
                if (panel != null) showMarkers()
            }
        }.start()
    }

    // ---------------------------------------------------------------- مقارنة الأيقونات

    /** يقسم صورة الإطار إلى خانات مربعة (ضلعها = ارتفاع الإطار) ويرجع صورة مصغرة لكل خانة. */
    private fun thumbs(px: IntArray, w: Int, h: Int): List<FloatArray> {
        val cell = h
        val n = maxOf(1, Math.round(w.toFloat() / cell))
        val out = ArrayList<FloatArray>()
        for (k in 0 until n) {
            val t = FloatArray(THUMB * THUMB * 3)
            val cnt = IntArray(THUMB * THUMB)
            val x0 = k * cell
            for (yy in 0 until h) {
                val ty = yy * THUMB / h
                for (xx in 0 until cell) {
                    val gx = x0 + xx
                    if (gx >= w) break
                    val tx = xx * THUMB / cell
                    val p = px[yy * w + gx]
                    val idx = ty * THUMB + tx
                    t[idx * 3] += Color.red(p).toFloat()
                    t[idx * 3 + 1] += Color.green(p).toFloat()
                    t[idx * 3 + 2] += Color.blue(p).toFloat()
                    cnt[idx]++
                }
            }
            for (i in 0 until THUMB * THUMB) {
                if (cnt[i] > 0) {
                    t[i * 3] /= cnt[i]
                    t[i * 3 + 1] /= cnt[i]
                    t[i * 3 + 2] /= cnt[i]
                }
            }
            out.add(t)
        }
        return out
    }

    private fun tdist(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += abs(a[i] - b[i])
        return s / a.size
    }

    /** كم أيقونة من المتعلَّمة ما لقاها في الصورة الحالية (بغض النظر عن ترتيبها). */
    private fun countMissing(refs: List<FloatArray>, cur: List<FloatArray>, tol: Float): Int {
        val used = BooleanArray(cur.size)
        var missing = 0
        for (r in refs) {
            var best = -1
            var bd = Float.MAX_VALUE
            for (i in cur.indices) {
                if (used[i]) continue
                val d = tdist(r, cur[i])
                if (d < bd) {
                    bd = d
                    best = i
                }
            }
            if (best >= 0 && bd <= tol) used[best] = true else missing++
        }
        return missing
    }

    private fun saveRefs(refs: List<FloatArray>) {
        val sb = StringBuilder()
        sb.append(refs.size)
        for (t in refs) {
            sb.append('|')
            sb.append(t.joinToString(",") { it.toInt().toString() })
        }
        Prefs.sp(this).edit().putString("refs", sb.toString()).apply()
    }

    private fun loadRefs(): List<FloatArray>? {
        val s = Prefs.sp(this).getString("refs", null) ?: return null
        return try {
            val parts = s.split("|")
            val n = parts[0].toInt()
            (1..n).map { i -> parts[i].split(",").map { it.toFloat() }.toFloatArray() }
        } catch (_: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- التشغيل

    private fun toggle() {
        if (running) {
            running = false
            return
        }
        savePositions()
        val sp = Prefs.sp(this)
        val detect = sp.getString("mode", "detect") == "detect"
        if (detect) {
            val hpOn = sp.getBoolean("hpOn", true)
            val iconsLearned = loadRefs() != null
            val hpLearned = hpOn && loadHpRef(sp) != null
            if (!iconsLearned && !hpLearned) {
                toast("اضغط «تعلّم» والأيقونات ظاهرة والدم ممتلئ أولاً")
                return
            }
            if (iconsLearned) {
                val learned = sp.getString("refGeom", null)
                if (learned == null || !sameGeom(learned, geomString(frameRegion(sp)))) {
                    toast("غيّرت مكان الإطار الأخضر أو حجمه، اضغط «تعلّم» من جديد")
                    return
                }
            }
            if (hpLearned) {
                val learned = sp.getString("hpGeom", null)
                if (learned == null || !sameGeom(learned, geomString(frameRegion(sp, "hp")))) {
                    toast("غيّرت مكان شريط الدم أو حجمه، اضغط «تعلّم» من جديد")
                    return
                }
            } else if (hpOn) {
                toast("شريط الدم ما تعلّمته (اضغط «تعلّم» والدم ممتلئ). سأراقب الأيقونات فقط")
            }
        }
        hideMarkers()
        running = true
        updatePanel()
        setStatus("يشتغل")
        Thread { runLoop() }.also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun sleepRun(ms: Long) {
        var left = ms
        while (running && left > 0) {
            val s = minOf(50L, left)
            Thread.sleep(s)
            left -= s
        }
    }

    /** ينتظر لين ترجع كل الأيقونات (مرتين متتاليتين) أو تنتهي المهلة. */
    private fun verify(refs: List<FloatArray>, r: Region, tol: Float, timeoutMs: Long): Boolean {
        val t0 = System.currentTimeMillis()
        var good = 0
        while (running && System.currentTimeMillis() - t0 < timeoutMs) {
            val px = grabRegion(r)
            if (px != null && countMissing(refs, thumbs(px, r.w, r.h), tol) == 0) {
                good++
                if (good >= 2) return true
            } else {
                good = 0
            }
            sleepRun(700)
        }
        return false
    }

    private fun runLoop() {
        val sp = Prefs.sp(this)
        val detect = sp.getString("mode", "detect") == "detect"
        val period = (sp.getFloat("period", 60f).coerceAtLeast(5f) * 1000).toLong()
        val tol = sp.getFloat("tol2", 25f).coerceAtLeast(1f)
        val dSwitch = (sp.getFloat("dSwitch", 0.8f).coerceAtLeast(0.2f) * 1000).toLong()
        val cleWait = (sp.getFloat("cleWait", 5f).coerceAtLeast(1f) * 1000).toLong()
        val vTimeout = (sp.getFloat("verifyTimeout", 10f).coerceAtLeast(1f) * 1000).toLong()
        val runNow = sp.getBoolean("runNow", true)

        val drop = sp.getFloat("hpDrop", 4f).coerceAtLeast(0.5f) / 100f
        val hpWait = (sp.getFloat("hpWait", 3f).coerceAtLeast(0.5f) * 1000).toLong()
        val hpCool = (sp.getFloat("hpCool", 2f).coerceAtLeast(0f) * 1000).toLong()
        val hpTol = sp.getFloat("hpTol", 40f).coerceAtLeast(1f)
        val hpFull = sp.getFloat("hpFull", 1f)
        val hpRef = loadHpRef(sp)
        val hpRegion = frameRegion(sp, "hp")

        val cleX = sp.getInt("cleX", -1).toFloat()
        val cleY = sp.getInt("cleY", -1).toFloat()
        val wizX = sp.getInt("wizX", -1).toFloat()
        val wizY = sp.getInt("wizY", -1).toFloat()
        val region = frameRegion(sp)
        val refs: List<FloatArray> = if (detect) (loadRefs() ?: emptyList()) else emptyList()
        val iconsOn = detect && refs.isNotEmpty()
        val hpOn = detect && hpRef != null && sp.getBoolean("hpOn", true) && validRegion(hpRegion)

        fun hpNow(px: IntArray): Float = hpFraction(px, hpRegion.w, hpRegion.h, hpRef!!, hpTol)

        var cycles = 0
        fun cycle() {
            if (!running) return
            setStatus("أبدّل للكليرك...")
            tap(cleX, cleY)
            sleepRun(dSwitch)
            var ok = true
            if (detect) {
                setStatus("أنتظر اللعبة تفعّل الاسكلات...")
                ok = verify(refs, region, tol, vTimeout)
            } else {
                setStatus("على الكليرك...")
                sleepRun(cleWait)
            }
            setStatus("أرجع للويزرد...")
            tap(wizX, wizY)
            sleepRun(dSwitch)
            cycles++
            setStatus(
                if (ok) "يشتغل • تم $cycles مرة ✓"
                else "يشتغل • $cycles (ما تأكدت من رجوع الأيقونات)"
            )
        }

        // الدم نقص: كليرك بسرعة، ينتظر الدم يرجع (أو المهلة)، ثم ويزرد
        fun hpCycle() {
            if (!running) return
            setStatus("الدم نقص! كليرك...")
            tap(cleX, cleY)
            sleepRun(dSwitch)
            val t0 = System.currentTimeMillis()
            while (running && System.currentTimeMillis() - t0 < hpWait) {
                val p = grabRegions(listOf(hpRegion)).getOrNull(0)
                if (p != null && hpNow(p) >= hpFull - drop / 2f) break
                sleepRun(700)
            }
            setStatus("أرجع للويزرد...")
            tap(wizX, wizY)
            sleepRun(dSwitch)
            cycles++
            setStatus("يشتغل • تم $cycles مرة (دم) ✓")
        }

        try {
            if (!detect) {
                val firstDelay = if (runNow) 0L else period
                var next = System.currentTimeMillis() + firstDelay
                while (running) {
                    if (System.currentTimeMillis() >= next) {
                        cycle()
                        next = System.currentTimeMillis() + period
                    }
                    sleepRun(100)
                }
            } else {
                var bad = 0
                var fails = 0
                var hpBlockUntil = 0L
                while (running) {
                    val regs = ArrayList<Region>()
                    if (iconsOn) regs.add(region)
                    if (hpOn) regs.add(hpRegion)
                    val got = grabRegions(regs)
                    var idx = 0
                    val px = if (iconsOn) got[idx++] else null
                    val hpPx = if (hpOn) got[idx] else null
                    if (px == null && hpPx == null) {
                        fails++
                        if (fails == 10) toast("ما أقدر أقرأ الشاشة، ممكن اللعبة تمنع اللقطات")
                    } else {
                        fails = 0
                        if (hpPx != null && System.currentTimeMillis() >= hpBlockUntil &&
                            hpNow(hpPx) < hpFull - drop
                        ) {
                            hpCycle()
                            hpBlockUntil = System.currentTimeMillis() + hpCool
                            bad = 0
                            continue
                        }
                        if (px != null) {
                            val missing = countMissing(refs, thumbs(px, region.w, region.h), tol)
                            bad = if (missing >= 1) bad + 1 else 0
                            if (bad >= 2) { // تأكيد مرتين لتجنب الوميض
                                cycle()
                                bad = 0
                            }
                        }
                    }
                    sleepRun(700) // حد أدنى بين لقطات الشاشة في أندرويد
                }
            }
        } finally {
            running = false
            main.post {
                if (panel != null) showMarkers()
                updatePanel()
                setStatus("متوقف")
            }
        }
    }

    // ---------------------------------------------------------------- النقر ولقطة الشاشة

    private fun tap(x: Float, y: Float): Boolean {
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        main.post {
            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    ok.set(true)
                    latch.countDown()
                }

                override fun onCancelled(g: GestureDescription?) {
                    latch.countDown()
                }
            }, null)
            if (!dispatched) latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        return ok.get()
    }

    private fun grabRegion(r: Region): IntArray? = grabRegions(listOf(r)).getOrNull(0)

    /** لقطة شاشة وحدة تُقصّ منها عدة مناطق. كل عنصر null لو فشلت اللقطة أو كانت المنطقة خارج الشاشة. */
    private fun grabRegions(rs: List<Region>): List<IntArray?> {
        val latch = CountDownLatch(1)
        val out = AtomicReference<List<IntArray?>>(rs.map { null })
        main.post {
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val hb = screenshot.hardwareBuffer
                            val hw = Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)
                            if (hw != null) {
                                val sw = hw.copy(Bitmap.Config.ARGB_8888, false)
                                if (sw != null) {
                                    out.set(rs.map { r ->
                                        if (r.x >= 0 && r.y >= 0 && r.w > 0 && r.h > 0 &&
                                            r.x + r.w <= sw.width && r.y + r.h <= sw.height
                                        ) {
                                            val arr = IntArray(r.w * r.h)
                                            sw.getPixels(arr, 0, r.w, r.x, r.y, r.w, r.h)
                                            arr
                                        } else null
                                    })
                                    sw.recycle()
                                }
                                hw.recycle()
                            }
                            hb.close()
                        } catch (_: Exception) {
                        }
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        latch.countDown()
                    }
                })
            } catch (_: Exception) {
                latch.countDown()
            }
        }
        latch.await(3, TimeUnit.SECONDS)
        return out.get()
    }
}

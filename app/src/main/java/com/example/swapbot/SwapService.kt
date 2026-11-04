package com.example.swapbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * خدمة الوصول:
 *  - لوحة عائمة + ثلاث دوائر تسحبها فوق الأزرار (1 تبديل، 2 اسكل، 3 أيقونة التفعيل)
 *  - تضغط على الشاشة بـ dispatchGesture
 *  - تقرأ لون بكسل بـ takeScreenshot (أندرويد 11+)
 */
class SwapService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: SwapService? = null
        private const val NO_COLOR = Int.MIN_VALUE
    }

    private class Marker(
        val view: TextView,
        val lp: WindowManager.LayoutParams,
        var shown: Boolean = false
    )

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private val markers = mutableListOf<Marker>() // 0 تبديل، 1 اسكل، 2 أيقونة
    private var panel: LinearLayout? = null
    private var statusView: TextView? = null
    private var startBtn: Button? = null
    private var calBtn: Button? = null

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

        // الدوائر
        val colors = intArrayOf(0xAAE53935.toInt(), 0xAA1E88E5.toInt(), 0xAA43A047.toInt())
        val keys = arrayOf("swap", "skill", "watch")
        markers.clear()
        for (i in 0..2) {
            val cx = sp.getInt(keys[i] + "X", dm.widthPixels / 2 + (i - 1) * dp(70))
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
            text = "1 تبديل  •  2 اسكل  •  3 أيقونة"
            setTextColor(0xFFB0BEC5.toInt())
            textSize = 10f
        }
        statusView = TextView(this).apply {
            text = "جاهز"
            setTextColor(0xFF80CBC4.toInt())
            textSize = 11f
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        calBtn = smallButton("معايرة") { calibrate() }
        startBtn = smallButton("▶ ابدأ") { toggle() }
        val closeBtn = smallButton("✕") { hideOverlay() }
        row.addView(calBtn)
        row.addView(startBtn)
        row.addView(closeBtn)
        box.addView(handle)
        box.addView(hint)
        box.addView(statusView)
        box.addView(row)

        val plp = newLp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, dp(12), dp(120))
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
        calBtn = null
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
    }

    private fun setStatus(t: String) {
        main.post { statusView?.text = t }
    }

    private fun updatePanel() {
        main.post {
            startBtn?.text = if (running) "■ وقف" else "▶ ابدأ"
            calBtn?.isEnabled = !running
        }
    }

    // ---------------------------------------------------------------- حفظ النقاط والمعايرة

    /** مركز الدائرة على الشاشة. */
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
        if (markers.size < 3 || !markers[0].shown) return
        val keys = arrayOf("swap", "skill", "watch")
        val e = Prefs.sp(this).edit()
        for (i in 0..2) {
            val (cx, cy) = center(markers[i])
            e.putInt(keys[i] + "X", cx)
            e.putInt(keys[i] + "Y", cy)
        }
        e.apply()
    }

    private fun calibrate() {
        if (running) return
        savePositions()
        val sp = Prefs.sp(this)
        val wx = sp.getInt("watchX", -1)
        val wy = sp.getInt("watchY", -1)
        hideMarkers()
        setStatus("أقرأ اللون...")
        Thread {
            Thread.sleep(450) // انتظر اختفاء الدوائر من الشاشة
            val c = grabColor(wx, wy)
            main.post {
                if (c != null) {
                    sp.edit().putInt("onColor", c).apply()
                    setStatus("تمت المعايرة ✓")
                    toast("تم حفظ لون الأيقونة")
                } else {
                    setStatus("فشلت المعايرة")
                    toast("تعذرت قراءة الشاشة (ممكن اللعبة تمنع لقطات الشاشة)")
                }
                if (panel != null) showMarkers()
            }
        }.start()
    }

    // ---------------------------------------------------------------- التشغيل

    private fun toggle() {
        if (running) {
            running = false
            return
        }
        savePositions()
        val sp = Prefs.sp(this)
        if (sp.getString("mode", "detect") == "detect" && !sp.contains("onColor")) {
            toast("اضغط «معايرة» أولاً والأيقونة شغالة")
            return
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

    private fun dist(a: Int, b: Int) = maxOf(
        abs(Color.red(a) - Color.red(b)),
        abs(Color.green(a) - Color.green(b)),
        abs(Color.blue(a) - Color.blue(b))
    )

    private fun runLoop() {
        val sp = Prefs.sp(this)
        val mode = sp.getString("mode", "detect")
        val period = (sp.getFloat("period", 60f).coerceAtLeast(1f) * 1000).toLong()
        val tol = sp.getFloat("tol", 40f).toInt().coerceAtLeast(1)
        val d1 = (sp.getFloat("d1", 0.6f).coerceAtLeast(0.1f) * 1000).toLong()
        val d2 = (sp.getFloat("d2", 1.2f).coerceAtLeast(0.1f) * 1000).toLong()
        val d3 = (sp.getFloat("d3", 0.6f).coerceAtLeast(0.1f) * 1000).toLong()
        val runNow = sp.getBoolean("runNow", true)

        val sx = sp.getInt("swapX", -1).toFloat()
        val sy = sp.getInt("swapY", -1).toFloat()
        val kx = sp.getInt("skillX", -1).toFloat()
        val ky = sp.getInt("skillY", -1).toFloat()
        val wx = sp.getInt("watchX", -1)
        val wy = sp.getInt("watchY", -1)
        val onColor = sp.getInt("onColor", 0)

        var count = 0
        fun doSequence() {
            if (!running) return
            tap(sx, sy)            // للكليرك
            sleepRun(d1)
            if (!running) return
            tap(kx, ky)            // تفعيل الاسكل
            sleepRun(d2)
            if (!running) return
            tap(sx, sy)            // رجوع للاستاف
            sleepRun(d3)
            count++
            setStatus("يشتغل • فعّلت $count مرة")
        }

        try {
            if (mode == "timer") {
                var next = System.currentTimeMillis() + if (runNow) 0 else period
                while (running) {
                    if (System.currentTimeMillis() >= next) {
                        doSequence()
                        next = System.currentTimeMillis() + period
                    }
                    sleepRun(100)
                }
            } else {
                var fails = 0
                while (running) {
                    val c = grabColor(wx, wy)
                    if (c == null) {
                        fails++
                        if (fails == 10) toast("ما أقدر أقرأ الشاشة، ممكن اللعبة تمنع اللقطات")
                    } else {
                        fails = 0
                        if (dist(c, onColor) > tol) {
                            doSequence()
                            // انتظر رجوع الأيقونة (حد أقصى 6 ثواني)
                            val t0 = System.currentTimeMillis()
                            while (running && System.currentTimeMillis() - t0 < 6000) {
                                val c2 = grabColor(wx, wy)
                                if (c2 != null && dist(c2, onColor) <= tol) break
                                sleepRun(700)
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

    // ---------------------------------------------------------------- النقر وقراءة اللون

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

    /** يرجع لون البكسل (x, y) على الشاشة أو null لو فشلت اللقطة. */
    private fun grabColor(x: Int, y: Int): Int? {
        val latch = CountDownLatch(1)
        val out = AtomicInteger(NO_COLOR)
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
                                    if (x in 0 until sw.width && y in 0 until sw.height) {
                                        out.set(sw.getPixel(x, y))
                                    }
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
        val v = out.get()
        return if (v == NO_COLOR) null else v
    }
}

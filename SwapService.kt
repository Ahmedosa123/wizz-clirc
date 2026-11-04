package com.example.swapbot

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast

class SwapService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View

    private lateinit var circle1: View // 1. زر سلاح الكليرك
    private lateinit var circle2: View // 2. زر سلاح الويزرد
    private lateinit var skillBar: View // 3. الشريط العريض (بدل الدائرة 3)

    private val handler = Handler(Looper.getMainLooper())
    private var isMonitoring = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        floatingView = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val typeFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            typeFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            windowManager.addView(floatingView, layoutParams)
            setupFloatingElements()
            startHpMonitoring()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupFloatingElements() {
        val context = this

        // 1. دائرة سلاح الكليرك (1)
        circle1 = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(130, 130).apply {
                leftMargin = 100
                topMargin = 200
            }
            background = resources.getDrawable(android.R.drawable.presence_online, null)
        }

        // 2. دائرة سلاح الويزرد (2)
        circle2 = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(130, 130).apply {
                leftMargin = 260
                topMargin = 200
            }
            background = resources.getDrawable(android.R.drawable.presence_busy, null)
        }

        // 3. الشريط العريض (Skill/HP Bar) - استبدال تام للدائرة رقم 3
        skillBar = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(650, 140).apply {
                leftMargin = 420
                topMargin = 200
            }
            // خلفية خضراء شفافة واضحة تحدد مكان شريط الدم أو الاسكلات
            setBackgroundColor(0x5500FF00.toInt())
        }

        val container = floatingView as FrameLayout
        container.addView(circle1)
        container.addView(circle2)
        container.addView(skillBar)

        // تفعيل السحب لجميع العناصر
        enableDragging(circle1)
        enableDragging(circle2)
        enableDragging(skillBar)
    }

    private fun startHpMonitoring() {
        isMonitoring = true
        handler.post(object : Runnable {
            override fun run() {
                if (!isMonitoring) return

                // هنا يتم مراقبة النقص التدريجي لشريط الدم الأحمر داخل إحداثيات (skillBar)
                checkHpAndAutoSwitch()

                handler.postDelayed(this, 200)
            }
        })
    }

    private fun checkHpAndAutoSwitch() {
        // منطق التبديل التلقائي للكليرك عند نقص الدم في الشريط الأخضر
    }

    private fun enableDragging(view: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { v, event ->
            val params = v.layoutParams as FrameLayout.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.leftMargin
                    initialY = params.topMargin
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.leftMargin = initialX + (event.rawX - initialTouchX).toInt()
                    params.topMargin = initialY + (event.rawY - initialTouchY).toInt()
                    v.layoutParams = params
                    true
                }
                else -> false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isMonitoring = false
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
    }
}

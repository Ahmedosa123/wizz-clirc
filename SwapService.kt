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

    private lateinit var circle1: View // زر سلاح الكليرك
    private lateinit var circle2: View // زر سلاح الويزرد
    private lateinit var barFrame: View // الشريط الأخضر لصف اسكلات الكليرك (بدل الدائرة 3)

    private val handler = Handler(Looper.getMainLooper())

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
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupFloatingElements() {
        val context = this

        // 1. دائرة سلاح الكليرك
        circle1 = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(120, 120).apply {
                leftMargin = 100
                topMargin = 150
            }
            background = resources.getDrawable(android.R.drawable.presence_online, null)
        }

        // 2. دائرة سلاح الويزرد
        circle2 = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(120, 120).apply {
                leftMargin = 250
                topMargin = 150
            }
            background = resources.getDrawable(android.R.drawable.presence_busy, null)
        }

        // 3. الشريط الأخضر (بدل الدائرة 3 لتغطية صف الاسكلات)
        barFrame = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(500, 140).apply {
                leftMargin = 400
                topMargin = 150
            }
            setBackgroundColor(0x3300FF00.toInt()) // لون شفاف بإطار أخضر خفيف
        }

        val container = floatingView as FrameLayout
        container.addView(circle1)
        container.addView(circle2)
        container.addView(barFrame)

        enableDragging(circle1)
        enableDragging(circle2)
        enableDragging(barFrame)
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
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
    }
}

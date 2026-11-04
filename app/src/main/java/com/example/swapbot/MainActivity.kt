package com.example.swapbot

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** شاشة الإعدادات الرئيسية. */
class MainActivity : Activity() {

    private lateinit var detectRb: RadioButton
    private lateinit var timerRb: RadioButton
    private lateinit var runNow: CheckBox
    private val fields = LinkedHashMap<String, EditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sp = Prefs.sp(this)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        fun text(t: String, size: Float = 14f) = TextView(this).apply {
            text = t
            textSize = size
            setPadding(0, pad / 2, 0, pad / 4)
        }

        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t
            setOnClickListener { onClick() }
        }

        fun field(label: String, key: String, default: String) {
            root.addView(text(label))
            val et = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(sp.getFloat(key, default.toFloat()).let {
                    if (it % 1f == 0f) it.toInt().toString() else it.toString()
                })
            }
            fields[key] = et
            root.addView(et)
        }

        root.addView(text("Swap Bot", 22f))
        root.addView(text("يبدّل للكليرك، يفعّل الاسكل، ثم يرجّع الاستاف تلقائياً."))

        root.addView(button("١) فعّل خدمة الوصول (Accessibility)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("٢) أظهر اللوحة العائمة") {
            save()
            val s = SwapService.instance
            if (s == null) {
                Toast.makeText(this, "فعّل الخدمة أولاً من إعدادات الوصول", Toast.LENGTH_LONG).show()
            } else {
                s.showOverlay()
                moveTaskToBack(true) // ارجع للعبة
            }
        })

        root.addView(text("الوضع", 16f))
        val group = RadioGroup(this)
        detectRb = RadioButton(this).apply {
            id = View.generateViewId()
            text = "كشف تلقائي (لما الأيقونة تختفي)"
        }
        timerRb = RadioButton(this).apply {
            id = View.generateViewId()
            text = "مؤقت (كل عدد ثواني)"
        }
        group.addView(detectRb)
        group.addView(timerRb)
        if (sp.getString("mode", "detect") == "timer") timerRb.isChecked = true else detectRb.isChecked = true
        root.addView(group)

        field("مدة التفعيل بالثواني (للمؤقت)", "period", "60")
        field("حساسية اللون (للكشف، الافتراضي 40)", "tol", "40")
        field("تأخير بعد التبديل للكليرك (ث)", "d1", "0.6")
        field("تأخير بعد الاسكل (ث)", "d2", "1.2")
        field("تأخير بعد الرجوع للاستاف (ث)", "d3", "0.6")

        runNow = CheckBox(this).apply {
            text = "ابدأ بالتفعيل فوراً (المؤقت)"
            isChecked = sp.getBoolean("runNow", true)
        }
        root.addView(runNow)

        root.addView(button("حفظ الإعدادات") {
            save()
            Toast.makeText(this, "تم الحفظ", Toast.LENGTH_SHORT).show()
        })

        root.addView(
            text(
                "طريقة الاستخدام:\n" +
                    "• من اللوحة العائمة اسحب الدوائر: 1 فوق زر تبديل السلاح، 2 فوق اسكل الكليرك، " +
                    "3 فوق أيقونة التفعيل (وهي شغالة).\n" +
                    "• اضغط «معايرة» لحفظ اللون، ثم «ابدأ».\n" +
                    "• لو غيرت اتجاه الشاشة أعد المعايرة."
            )
        )

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    private fun save() {
        val e = Prefs.sp(this).edit()
        e.putString("mode", if (timerRb.isChecked) "timer" else "detect")
        e.putBoolean("runNow", runNow.isChecked)
        for ((k, et) in fields) {
            val v = et.text.toString().toFloatOrNull()
            if (v != null) e.putFloat(k, v)
        }
        e.apply()
    }
}

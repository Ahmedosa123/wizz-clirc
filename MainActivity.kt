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
    private lateinit var hpOn: CheckBox
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
        root.addView(text("لما أيقونات تفعيل الكليرك تختفي: يبدّل للكليرك، ينتظر اللعبة تفعّل الاسكلات تلقائياً ولين ترجع الأيقونات، ثم يرجّع الويزرد."))

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
            text = "كشف تلقائي بالإطار (لما الأيقونات تختفي)"
        }
        timerRb = RadioButton(this).apply {
            id = View.generateViewId()
            text = "مؤقت (كل عدد ثواني)"
        }
        group.addView(detectRb)
        group.addView(timerRb)
        if (sp.getString("mode", "detect") == "timer") timerRb.isChecked = true else detectRb.isChecked = true
        root.addView(group)

        field("حساسية التعرّف على الأيقونات (الافتراضي 25). زوّدها لو يحسب الأيقونة مختفية وهي موجودة", "tol2", "25")
        hpOn = CheckBox(this).apply {
            text = "راقب شريط الدم (إذا نقص يبدّل للكليرك ثم يرجع للويزرد)"
            isChecked = sp.getBoolean("hpOn", true)
        }
        root.addView(hpOn)
        field("أقل نقص في الدم يعتبره نقص (٪) — الافتراضي 4", "hpDrop", "4")
        field("أقصى مدة يبقى على الكليرك لين يرجع الدم (ث)", "hpWait", "3")
        field("راحة بعد كل مرة بسبب الدم (ث)", "hpCool", "2")
        field("حساسية لون الدم (الافتراضي 40). زوّدها لو يحسب الدم ناقص وهو ممتلئ", "hpTol", "40")
        field("تأخير بعد كل تبديل سلاح (ث)", "dSwitch", "0.8")
        field("مدة البقاء على الكليرك (وضع المؤقت فقط) (ث)", "cleWait", "5")
        field("أقصى مدة أنتظر فيها رجوع الأيقونات بعد التبديل للكليرك (ث)", "verifyTimeout", "10")
        field("مدة التفعيل بالثواني (وضع المؤقت فقط)", "period", "60")

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
                "طريقة الاستخدام (من اللوحة العائمة داخل اللعبة):\n" +
                    "• الدوائر: 1 فوق زر سلاح الكليرك، 2 فوق زر سلاح الويزرد.\n" +
                    "• خلّ الصيد التلقائي شغّال في اللعبة عشان تفعّل الاسكلات بنفسها بعد التبديل للكليرك.\n" +
                    "• الإطار الأخضر: ضعه فوق صف أيقونات تفعيل الكليرك. اسحبه لتحريكه، " +
                    "وكبّره أو صغّره من النقطة البيضاء بالزاوية السفلية لين تكون كل خانة " +
                    "(تفصلها الخطوط) بحجم أيقونة وحدة.\n" +
                    "• الإطار الأحمر: ضعه على شريط الدم (يكفي ارتفاع صغير). لازم الدم ممتلئ وقت «تعلّم».\n" +
                    "• لو الدم نقص: يبدّل للكليرك بسرعة، وبعد ما يرجع الدم (أو تنتهي المهلة) يرجع للويزرد.\n" +
                    "• والأيقونات كلها ظاهرة (الاسكلات شغالة) اضغط «تعلّم»، ثم «▶ ابدأ».\n" +
                    "• لا تضع اللوحة نفسها فوق الأيقونات.\n" +
                    "• لو غيرت مكان الإطار أو حجمه أو اتجاه الشاشة اضغط «تعلّم» من جديد."
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
        e.putBoolean("hpOn", hpOn.isChecked)
        for ((k, et) in fields) {
            val v = et.text.toString().toFloatOrNull()
            if (v != null) e.putFloat(k, v)
        }
        e.apply()
    }
}

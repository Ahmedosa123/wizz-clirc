package com.example.swapbot

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val OVERLAY_PERMISSION_REQ_CODE = 1234

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnLearn = findViewById<Button>(R.id.btnLearn)

        btnStart.setOnClickListener {
            if (checkOverlayPermission()) {
                startService(Intent(this, SwapService::class.java))
                Toast.makeText(this, "تم تشغيل البوت والشريط بنجاح", Toast.LENGTH_SHORT).show()
            }
        }

        btnLearn.setOnClickListener {
            Toast.makeText(this, "جاري تعلّم الأيقونات داخل الشريط الأخضر...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkOverlayPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE)
            return false
        }
        return true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQ_CODE) {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "تم منح إذن التراكب بنجاح", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "يجب منح إذن الظهور فوق التطبيقات لتشغيل البوت", Toast.LENGTH_LONG).show()
            }
        }
    }
}

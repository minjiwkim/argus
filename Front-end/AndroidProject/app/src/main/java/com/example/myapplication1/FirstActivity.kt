package com.example.myapplication1

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import androidx.appcompat.app.AppCompatActivity
import android.speech.tts.TextToSpeech
import android.widget.Toast
import java.util.Locale

class FirstActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var textToSpeech: TextToSpeech

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_first)

        textToSpeech = TextToSpeech(this, this)

        Handler().postDelayed({
            val intent = Intent(
                this@FirstActivity,
                NextActivity::class.java
            )
            startActivity(intent)
            finish() // FirstActivity 종료
        }, 10000) // 10초 후 전환
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech.setLanguage(Locale.KOREAN)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "언어를 지원하지 않습니다.", Toast.LENGTH_SHORT).show()
            } else {
                textToSpeech.speak("시각장애인 보행 안내 서비스 시야입니다. 화면 왼쪽에 내비게이션 버튼이 있고, 오른쪽에 내비게이션 없이 안내 버튼이 있습니다.", TextToSpeech.QUEUE_FLUSH, null, null)
            }
        } else {
            Toast.makeText(this, "TTS 초기화에 실패했습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        if (this::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
        super.onDestroy()
    }
}
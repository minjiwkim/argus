package com.example.myapplication1

import android.Manifest

import android.os.Bundle
import android.os.Handler
import android.os.Looper

import android.content.Intent
import android.graphics.Color
import android.widget.Button
import android.media.MediaPlayer
import android.content.pm.PackageManager
import android.net.Uri

import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Toast

import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

import java.util.Locale

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

import com.skt.Tmap.TMapData
import com.skt.Tmap.TMapPoint
import com.skt.Tmap.TMapTapi
import com.skt.Tmap.TMapView

class NextActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var speechRecognizer: SpeechRecognizer

    private var textToSpeak: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val requestRecordAudioPermission = 1

    // Timing constants
    private val voiceRecognitionTimeout = 5000L // 5 seconds
    private val retryTimeout = 5000L // 5 seconds

    private var retryHandler: Handler? = null
    private var voiceRecognitionHandler: Handler? = null
    private var voiceRecognitionStarted = false

    // TMAP API Key
    private val TMAP_API_KEY = "mlGpROtiwp1mZcKqs8MJQ1imM6AeI4kw9oGIjuZj"

    private lateinit var tmapTapi: TMapTapi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_next)

        // TMAP API 초기화
        tmapTapi = TMapTapi(this)
        tmapTapi.setSKTMapAuthentication(TMAP_API_KEY)

        // 권한 체크 및 요청
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), requestRecordAudioPermission)
        } else {
            initializeComponents()
        }

        val buttonActivateNavigation = findViewById<Button>(R.id.buttonActivateNavigation)
        val buttonStartGuide = findViewById<Button>(R.id.buttonStartGuide)

        buttonActivateNavigation.setOnClickListener {
            textToSpeak = "내비게이션을 활성화합니다. 목적지를 말씀해주세요."
            updateButtonState(buttonActivateNavigation)
            speakAndNavigate()
        }

        buttonStartGuide.setOnClickListener {
            textToSpeak = "내비게이션 없이 안내를 시작합니다."
            updateButtonState(buttonStartGuide)
            speakAndMain()
        }
    }

    private fun updateButtonState(button: Button) {
        button.setBackgroundColor(Color.GRAY)
        handler.postDelayed({
            button.setBackgroundColor(Color.parseColor("#0070C0"))
        }, 200)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.KOREAN
        }
    }

    private fun initializeComponents() {
        // tts 초기화
        tts = TextToSpeech(this, this)

        // MediaPlayer 초기화
        mediaPlayer = MediaPlayer.create(this, R.raw.ding)

        // SpeechRecognizer 초기화
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
    }

    private fun speakAndNavigate() {
        val ttsId = "TTS_NAVIGATION"
        tts.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, ttsId)

        // TTS 발화가 끝난 후 음성 인식 시작
        Handler(Looper.getMainLooper()).postDelayed({
            startVoiceRecognition()
        }, 3800) // TTS 발화 길이에 맞춰 적절히 조정
    }

    private fun speakAndMain() {
        val ttsId = "TTS_ID"
        tts.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, ttsId)

        Handler().postDelayed({
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
        }, 2000) // 대략적인 음성 길이에 맞춰 2000ms로 설정, 필요에 따라 조정
    }

    private fun startVoiceRecognition() {
        if (!this::speechRecognizer.isInitialized) {
            Toast.makeText(this, "음성 인식기를 초기화할 수 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "말씀해 주세요")
        }

        if (voiceRecognitionStarted) return

        speechRecognizer.setRecognitionListener(object : RecognitionListenerAdapter() {
            override fun onReadyForSpeech(params: Bundle?) {
                Toast.makeText(this@NextActivity, "음성 인식 준비 완료", Toast.LENGTH_SHORT).show()
            }

            override fun onBeginningOfSpeech() {
                Toast.makeText(this@NextActivity, "음성 인식 시작", Toast.LENGTH_SHORT).show()
                mediaPlayer.start()
                voiceRecognitionStarted = true
                startVoiceRecognitionTimeout()
            }

            override fun onEndOfSpeech() {
                Toast.makeText(this@NextActivity, "음성 인식 종료", Toast.LENGTH_SHORT).show()
                cancelRetryHandler()
            }

            override fun onError(error: Int) {
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "오디오 오류"
                    SpeechRecognizer.ERROR_CLIENT -> "클라이언트 오류"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "권한 부족"
                    SpeechRecognizer.ERROR_NETWORK -> "네트워크 오류"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "네트워크 시간 초과"
                    SpeechRecognizer.ERROR_NO_MATCH -> "일치하는 항목 없음"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "음성 인식기 바쁨"
                    SpeechRecognizer.ERROR_SERVER -> "서버 오류"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "음성 시간 초과"
                    else -> "알 수 없는 오류"
                }
                Toast.makeText(this@NextActivity, "음성 인식 오류: $errorMessage", Toast.LENGTH_SHORT).show()
                cancelRetryHandler()
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val recognizedText = matches?.get(0) ?: return

                // 지오코딩을 통한 목적지의 좌표 가져오기
                geocodeAddress(recognizedText) { lat, lon ->
                    // 경로 안내 시작
                    startTmapNavigation(lat, lon)
                }
            }
        })

        try {
            speechRecognizer.startListening(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "음성 인식 시작 오류: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startVoiceRecognitionTimeout() {
        if (voiceRecognitionStarted) {
            voiceRecognitionHandler = Handler(Looper.getMainLooper()).apply {
                postDelayed({
                    if (voiceRecognitionStarted) {
                        tts.speak("다시 말씀해주세요.", TextToSpeech.QUEUE_FLUSH, null, "TTS_RETRY")

                        Handler(Looper.getMainLooper()).postDelayed({
                            startVoiceRecognition()
                        }, retryTimeout)
                    }
                }, voiceRecognitionTimeout)
            }
        }
    }

    private fun cancelRetryHandler() {
        voiceRecognitionHandler?.removeCallbacksAndMessages(null)
        retryHandler?.removeCallbacksAndMessages(null)
        voiceRecognitionHandler = null
        retryHandler = null
        voiceRecognitionStarted = false
    }

    private fun geocodeAddress(address: String, callback: (Double, Double) -> Unit) {
        val url = "https://apis.openapi.sk.com/tmap/geo/fullAddrGeo?version=1&format=json&callback=result&fullAddr=$address&appKey=$TMAP_API_KEY"

        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@NextActivity, "지오코딩 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.body?.string()?.let { responseBody ->
                    try {
                        val json = JSONObject(responseBody)
                        val coordinateInfo = json.getJSONObject("coordinateInfo")
                        val coordinates = coordinateInfo.getJSONArray("coordinate")
                        if (coordinates.length() > 0) {
                            val firstLocation = coordinates.getJSONObject(0)
                            val lat = firstLocation.getDouble("lat")
                            val lon = firstLocation.getDouble("lon")
                            runOnUiThread {
                                callback(lat, lon)
                            }
                        } else {
                            runOnUiThread {
                                Toast.makeText(this@NextActivity, "좌표를 찾을 수 없습니다.", Toast.LENGTH_LONG).show()
                            }
                        }
                    } catch (e: JSONException) {
                        runOnUiThread {
                            Toast.makeText(this@NextActivity, "지오코딩 데이터 파싱 오류: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        })
    }

    private fun startTmapNavigation(latitude: Double, longitude: Double) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("tmap://route?goalLat=$latitude&goalLon=$longitude&goalName=목적지"))
        intent.addCategory(Intent.CATEGORY_DEFAULT)

        // TMap 앱이 설치되어 있는지 확인하고 실행
        if (isTmapInstalled()) {
            startActivity(intent)
        } else {
            Toast.makeText(this, "T맵이 설치되어 있지 않습니다. 설치 후 다시 시도해 주세요.", Toast.LENGTH_LONG).show()
            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.skt.tmap.ku"))
            startActivity(marketIntent)
        }
    }

    private fun startNavigation(latitude: Double, longitude: Double) {
        launchTmap(latitude, longitude)
    }

    private fun isTmapInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo("com.skt.tmap.ku", PackageManager.GET_ACTIVITIES)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun launchTmap(lat: Double, lon: Double) {
        if (isTmapInstalled()) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("tmap://route?goalLat=$lat&goalLon=$lon&goalName=목적지"))
                intent.addCategory(Intent.CATEGORY_DEFAULT)
                startActivity(intent)
            } catch (e: Exception) {
                // TTS로 오류 메시지 읽기
                tts.speak("T맵 실행 오류: ${e.message}", TextToSpeech.QUEUE_FLUSH, null, "TTS_ERROR")
            }
        } else {
            // TTS로 T맵이 설치되지 않았음을 알림
            tts.speak("T맵이 설치되어 있지 않습니다.", TextToSpeech.QUEUE_FLUSH, null, "TTS_NO_TMAP")

            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.skt.tmap.ku"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }


    override fun onDestroy() {
        if (this::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        if (this::mediaPlayer.isInitialized) {
            mediaPlayer.release()
        }
        if (this::speechRecognizer.isInitialized) {
            speechRecognizer.destroy()
        }
        super.onDestroy()
    }
}

// RecognitionListenerAdapter 클래스 정의
abstract class RecognitionListenerAdapter : android.speech.RecognitionListener {
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onError(error: Int) {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
    override fun onResults(results: Bundle?) {}
}
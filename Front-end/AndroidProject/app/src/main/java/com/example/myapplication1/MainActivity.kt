package com.example.myapplication1

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.Animation
import android.view.animation.TranslateAnimation
import android.widget.PopupWindow
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import java.util.*
import android.os.*
import android.view.*
import android.widget.*
import androidx.core.view.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import android.content.pm.PackageManager
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import java.util.concurrent.Executors
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var previewView: PreviewView
    private lateinit var rectView: RectView
    private lateinit var ortEnvironment: OrtEnvironment
    private lateinit var session: OrtSession
    private lateinit var vibrator: Vibrator
    private lateinit var textToSpeech: TextToSpeech
    private lateinit var popupWindow: PopupWindow
    private lateinit var currentPopupText: String

    private var isVibrateModeOn: Boolean = false
    private var isExiting: Boolean = false
    private var lastDetectionTime: Long = 0L
    private var isSpeaking: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private val objectTranslations: MutableMap<String, String> = mutableMapOf()
    private val dataProcess = DataProcess(context = this)
    private val detectionQueue: Queue<Pair<String, String>> = LinkedList()

    companion object {
        const val PERMISSION = 1
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        lastDetectionTime = System.currentTimeMillis()

        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        isVibrateModeOn = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("vibrate_mode", false)

        val main = findViewById<View>(R.id.main)
        val button1 = findViewById<Button>(R.id.button1)
        val button2 = findViewById<Button>(R.id.button2)

        previewView = findViewById(R.id.previewView)
        rectView = findViewById(R.id.rectView)

        ViewCompat.setOnApplyWindowInsetsListener(main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // 화면 터치 이벤트 처리
        main.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 화면의 절반을 기준으로 왼쪽과 오른쪽을 구분
                    if (event.x < main.width / 2) {
                        if (!isExiting) {
                            // 왼쪽 절반을 터치한 경우 진동 모드, 종료 중일 때는 인식하지 않도록 함
                            toggleVibrateMode()
                        }
                    } else {
                        // 오른쪽 절반을 터치한 경우 앱 종료
                        exitApp()
                    }
                }
            }
            true
        }

        //자동 꺼짐 해제
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        //권한 허용
        setPermissions()

        // onnx 파일 && txt 파일 불러오기
        load()

        //카메라 켜기
        setCamera()

        //TTS 설정
        textToSpeech = TextToSpeech(this, this)
        loadObjectTranslations()

        //DataProcess에서 모델과 라벨을 받아옴
        dataProcess.loadModel()
        dataProcess.loadLabel()

        button1.setOnClickListener {
            // 버튼1의 동작이 앱 종료 상태일 때는 동작하지 않도록 함
            if (!isExiting) {
                // 진동 모드 토글 기능 실행
                toggleVibrateMode()
                // 클릭한 버튼의 배경색 변경
                button1.setBackgroundColor(Color.GRAY)
                // 200 밀리초 후에 버튼의 배경색을 원래대로 변경
                handler.postDelayed({
                    button1.setBackgroundColor(Color.parseColor("#0070C0"))
                }, 200)
            }
        }

        button2.setOnClickListener {
            // 앱 종료 기능 실행
            exitApp()
            // 클릭한 버튼의 배경색 변경
            button2.setBackgroundColor(Color.GRAY)
            // 200 밀리초 후에 버튼의 배경 색을 원래대로 변경
            handler.postDelayed({
                button2.setBackgroundColor(Color.parseColor("#0070C0"))
            }, 200)
        }

        // 팝업창 읽어 주는 TTS 설정
        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isSpeaking = true
            }

            override fun onDone(utteranceId: String?) {
                isSpeaking = false
                processNextDetection()
                if (utteranceId == "exitTTS" && isExiting) {
                    runOnUiThread {
                        finishAffinity()
                    }
                } else if (utteranceId == "popupTTS") {
                    runOnUiThread {
                        closePopupWithAnimation()
                    }
                }
            }

            override fun onError(utteranceId: String?) {
                isSpeaking = false
                processNextDetection()
            }
        })

    }


    // 진동 간격 설정
    private fun vibrateMultipleTimes(times: Int) {
        val pattern = LongArray(times * 2) { if (it % 2 == 0) 200L else 200L } // 200ms 진동 후 200ms 대기, times 번 반복
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            vibrator.vibrate(pattern, -1)
        }
    }

    // 진동 모드 설정
    private fun toggleVibrateMode() {
        // 이전 진동 모드 상태 저장
        val prevVibrateMode = isVibrateModeOn

        // 진동 모드를 토글
        isVibrateModeOn = !isVibrateModeOn

        // 진동 모드가 켜져 있는 경우에 진동 실행
        if (isVibrateModeOn && !prevVibrateMode) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vibrator.vibrate(500)
            }

        } else if (!isVibrateModeOn && prevVibrateMode) {
            // 진동 모드가 꺼져 있는 경우에 진동 취소
            vibrator.cancel()
        }

        // TTS로 진동 모드 변경 안내
        val message = if (isVibrateModeOn) {
            "진동 모드를 켰습니다."
        } else {
            "진동 모드를 껐습니다."
        }
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "vibrateTTS")
        }
        textToSpeech.speak(message, TextToSpeech.QUEUE_FLUSH, params, "vibrateTTS")

        // 진동 모드 상태 저장
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("vibrate_mode", isVibrateModeOn).apply()
    }

    // 앱 종료 설정
    private fun exitApp() {
        stopAllTTS()
        isExiting = true
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "exitTTS")
        }
        textToSpeech.speak("안내를 종료합니다.", TextToSpeech.QUEUE_FLUSH, params, "exitTTS")
    }



    private fun showPopup(text: String, direction: String) {
        // 기존 팝업이 열려 있다면 닫기
        if (::popupWindow.isInitialized && popupWindow.isShowing) {
            popupWindow.dismiss()
        }

        // 다른 TTS 중지
        stopAllTTS()

        // 현재 팝업 텍스트 설정
        currentPopupText = text

        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val popupView = inflater.inflate(R.layout.activity_popup, null)
        popupWindow = PopupWindow(popupView, WRAP_CONTENT, WRAP_CONTENT)

        val slideUp = TranslateAnimation(
            Animation.RELATIVE_TO_SELF, 0f,
            Animation.RELATIVE_TO_SELF, 0f,
            Animation.RELATIVE_TO_SELF, 1f,
            Animation.RELATIVE_TO_SELF, 0f
        ).apply {
            duration = 1000
        }

        val mainView = findViewById<View>(R.id.main)
        mainView.post {
            popupWindow.showAtLocation(mainView, Gravity.BOTTOM, 0, 0)
            popupView.startAnimation(slideUp)

            // 팝업 창에 결과 텍스트 표시
            val popupText = popupView.findViewById<TextView>(R.id.textViewPopup)
            popupText.text = text

            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "popupTTS")
            }
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, params, "popupTTS")

            // 진동 모드가 켜져 있는 경우, 방향에 따라 진동 실행
            if (isVibrateModeOn) {
                if (direction == "오른쪽으로") {
                    vibrateMultipleTimes(2)
                } else {
                    vibrateMultipleTimes(1)
                }
            }
        }
    }


    private fun setCamera() {
        //카메라 제공 객체
        val processCameraProvider = ProcessCameraProvider.getInstance(this).get()

        //전체 화면
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        // 전면 카메라
        val cameraSelector = CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

        // 16:9 화면으로 받아옴
        val preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_16_9).build()

        // preview 에서 받아 와서 previewView에 보여 준다.
        preview.setSurfaceProvider(previewView.surfaceProvider)

        //분석 중이면 그 다음 화면이 대기 중인 것이 아니라 계속 받아 오는 화면으로 새로고침함. 분석이 끝나면 그 최신 사진을 다시 분석
        val analysis = ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()

        analysis.setAnalyzer(Executors.newSingleThreadExecutor()) {
            try {
                imageProcess(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 카메라의 수명 주기를 메인 액티비티에 귀속
        processCameraProvider.bindToLifecycle(this, cameraSelector, preview, analysis)
    }


    private fun imageProcess(imageProxy: ImageProxy) {
        val bitmap = dataProcess.imageToBitmap(imageProxy)
        val floatBuffer = dataProcess.bitmapToFloatBuffer(bitmap)
        //모델의 요구 입력값 [1 3 640 640] [배치 사이즈, 픽셀(RGB), 너비, 높이], 모델마다 크기는 다를 수 있음.
        val inputName = session.inputNames.iterator().next()
        val shape = longArrayOf(
            DataProcess.BATCH_SIZE.toLong(),
            DataProcess.PIXEL_SIZE.toLong(),
            DataProcess.INPUT_SIZE.toLong(),
            DataProcess.INPUT_SIZE.toLong()
        )
        val inputTensor = OnnxTensor.createTensor(ortEnvironment, floatBuffer, shape)
        val resultTensor = session.run(Collections.singletonMap(inputName, inputTensor))

        val outputs = resultTensor[0].value as Array<*>
        val results = dataProcess.outputsToNPMSPredictions(outputs)

        // UI 스레드에서 처리
        runOnUiThread {
            results.forEach { result ->
                val detectedObject = dataProcess.classes[result.classIndex]
                val centerX = (result.rectF.left + result.rectF.right) / 2
                val imageWidth = bitmap.width.toFloat()

                val translatedObjectName = objectTranslations[detectedObject]
                val isObjectOnLeft = centerX <= imageWidth / 2 // 이미지의 왼쪽 절반에 있으면 true, 아니면 false
                val direction = if (isObjectOnLeft) "오른쪽으로" else "왼쪽으로"
                val message = "전방에 $translatedObjectName 있습니다.\n$direction 피하세요."

                // 팝업창에 결과 표시
                if (shouldShowPopup()) {
                    detectionQueue.add(Pair(message, direction))
                    processNextDetection()
                }
            }

            // 화면 표출
            rectView.transformRect(results)
            rectView.invalidate()
        }
        imageProxy.close() // 이미지 처리 완료 후 닫기
    }

    // 팝업 및 TTS를 표시할지 여부를 결정하는 함수
    private fun shouldShowPopup(): Boolean {
        val currentTime = System.currentTimeMillis()
        val elapsedTime = currentTime - lastDetectionTime

        // 마지막 감지 시간으로부터 5초가 지났거나, 첫 번째 감지인 경우에만 허용
        if (elapsedTime >= 5000 || lastDetectionTime == 0L) {
            lastDetectionTime = currentTime // 마지막 감지 시간 갱신
            return true
        }

        return false
    }

    override fun onDestroy() {
        if (::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun load() {
        dataProcess.loadModel() // onnx 모델 불러 오기
        dataProcess.loadLabel() // coco txt 파일 불러 오기

        ortEnvironment = OrtEnvironment.getEnvironment()
        session = ortEnvironment.createSession(
            this.filesDir.absolutePath.toString() + "/" + DataProcess.FILE_NAME,
            OrtSession.SessionOptions()
        )

        rectView.setClassLabel(dataProcess.classes)
    }

    private fun setPermissions() {
        val permissions = ArrayList<String>()
        permissions.add(android.Manifest.permission.CAMERA)

        permissions.forEach {
            if (ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == PERMISSION) {
            grantResults.forEach {
                if (it != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "권한을 허용하지 않으면 사용할 수 없습니다", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    // 팝업창 닫기 애니메이션
    private fun closePopupWithAnimation() {
        val slideDown = TranslateAnimation(
            Animation.RELATIVE_TO_SELF, 0f,
            Animation.RELATIVE_TO_SELF, 0f,
            Animation.RELATIVE_TO_SELF, 0f,
            Animation.RELATIVE_TO_SELF, 1f
        ).apply {
            duration = 500
        }

        val popupView = popupWindow.contentView
        popupView.startAnimation(slideDown)
        slideDown.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation?) {}

            override fun onAnimationEnd(animation: Animation?) {
                runOnUiThread {
                    popupWindow.dismiss()
                }
            }

            override fun onAnimationRepeat(animation: Animation?) {}
        })
    }

    // 팝업 텍스트가 끝날 때까지 유지하도록 함
    private fun handlePopupDismiss() {
        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                // TTS 시작 시 아무 작업 필요 없음
            }

            override fun onDone(utteranceId: String?) {
                // TTS 종료 시 팝업 닫기 애니메이션 시작
                runOnUiThread {
                    closePopupWithAnimation()
                }
            }

            override fun onError(utteranceId: String?) {
                // TTS 오류 시 팝업 닫기 애니메이션 시작
                runOnUiThread {
                    closePopupWithAnimation()
                }
            }
        })
    }

    // 다음 TTS 처리를 위한 큐 메커니즘
    private fun processNextDetection() {
        if (!isSpeaking && detectionQueue.isNotEmpty()) {
            val (message, direction) = detectionQueue.poll()
            runOnUiThread {
                showPopup(message, direction)
            }
        }
    }


    // 모든 TTS 중지
    private fun stopAllTTS() {
        // 현재 TTS 중지
        if (::textToSpeech.isInitialized && textToSpeech.isSpeaking) {
            textToSpeech.stop()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech.setLanguage(Locale.KOREAN)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "언어를 지원하지 않습니다.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "초기화에 실패했습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadObjectTranslations() {
        val assetManager: AssetManager = resources.assets
        val inputStream = assetManager.open("yolov8n.txt")
        val bufferedReader = BufferedReader(InputStreamReader(inputStream))

        bufferedReader.useLines { lines ->
            lines.forEach { line ->
                val englishObject = line.trim()
                val koreanObject = translateObjectToKorean(englishObject)
                objectTranslations[englishObject] = koreanObject
            }
        }
    }

    private fun translateObjectToKorean(englishObject: String): String {
        return when (englishObject) {
            "person" -> "사람이"
            "bicycle" -> "자전거가"
            "car" -> "자동차가"
            "motorcycle" -> "오토바이가"
            "bus" -> "버스가"
            "truck" -> "트럭이"
            "traffic light" -> "신호등이"
            "fire hydrant" -> "소화전이"
            "stop sign" -> "정지 표지판이"
            "parking meter" -> "주차 요금기가"
            "bench" -> "벤치가"
            "cat" -> "고양이가"
            "dog" -> "개가"

            // 테스트용
            "train" -> "기차가"
            "boat" -> "보트가"
            "bird" -> "새가"
            "horse" -> "말이"
            "sheep" -> "양이"
            "cow" -> "소가"
            "elephant" -> "코끼리가"
            "bear" -> "곰이"
            "zebra" -> "얼룩말이"
            "giraffe" -> "기린이"
            "backpack" -> "배낭이"
            "umbrella" -> "우산이"
            "handbag" -> "핸드백이"
            "tie" -> "넥타이가"
            "suitcase" -> "여행 가방이"
            "frisbee" -> "프리스비가"
            "skis" -> "스키가"
            "snowboard" -> "스노보드가"
            "sports ball" -> "스포츠 공이"
            "kite" -> "연이"
            "baseball bat" -> "야구 방망이가"
            "baseball glove" -> "야구 글러브가"
            "skateboard" -> "스케이트보드가"
            "surfboard" -> "서핑 보드가"
            "tennis racket" -> "테니스 라켓이"
            "bottle" -> "병이"
            "wine glass" -> "와인 잔이"
            "cup" -> "컵이"
            "fork" -> "포크가"
            "knife" -> "칼이"
            "spoon" -> "숟가락이"
            "bowl" -> "그릇이"
            "banana" -> "바나나가"
            "apple" -> "사과가"
            "sandwich" -> "샌드위치가"
            "orange" -> "오렌지가"
            "broccoli" -> "브로콜리가"
            "carrot" -> "당근이"
            "hot dog" -> "핫도그가"
            "pizza" -> "피자가"
            "donut" -> "도넛이"
            "cake" -> "케이크가"
            "chair" -> "의자가"
            "couch" -> "소파가"
            "potted plant" -> "화분이"
            "bed" -> "침대가"
            "dining table" -> "식탁이"
            "toilet" -> "화장실이"
            "tv" -> "텔레비전이"
            "laptop" -> "노트북이"
            "mouse" -> "마우스가"
            "remote" -> "리모컨이"
            "keyboard" -> "키보드가"
            "cell phone" -> "휴대폰이"
            "microwave" -> "전자렌지가"
            "oven" -> "오븐이"
            "toaster" -> "토스터가"
            "sink" -> "싱크대가"
            "refrigerator" -> "냉장고가"
            "book" -> "책이"
            "clock" -> "시계가"
            "vase" -> "꽃병이"
            "scissors" -> "가위가"
            "teddy bear" -> "곰 인형이"
            "hair drier" -> "헤어 드라이기가"
            "toothbrush" -> "칫솔이"
            else -> englishObject
        }
    }
}
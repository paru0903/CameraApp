package com.example.cameraapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var btnRecord: FrameLayout
    private lateinit var btnClose: ImageButton
    private lateinit var btnFlash: ImageButton
    private lateinit var btnSwitchCamera: ImageButton
    private lateinit var btnSync: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvTimer: TextView
    private lateinit var tvDeviceId: TextView
    private lateinit var timerLayout: LinearLayout
    private lateinit var recordIcon: View
    private lateinit var stopIcon: View

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    private var recordingStartTime = 0L
    private var currentVideoPath: String? = null

    // 同期機能
    private var deviceId = "LEFT" // デフォルト
    private var isSyncEnabled = false
    private var webSocket: WebSocket? = null
    private val serverUrl = "http://172.21.1.123:7777"

    companion object {
        const val DEVICE_LEFT = "LEFT"
        const val DEVICE_RIGHT = "RIGHT"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        try {
            initViews()
            showDeviceSelectionDialog()
            setupListeners()

            if (allPermissionsGranted()) {
                startCamera()
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO
                    ),
                    10
                )
            }
        } catch (e: Exception) {
            Log.e("CameraApp", "onCreate error", e)
            Toast.makeText(this, "初期化エラー: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun initViews() {
        previewView = findViewById(R.id.previewView)
        btnRecord = findViewById(R.id.btnRecord)
        btnClose = findViewById(R.id.btnClose)
        btnFlash = findViewById(R.id.btnFlash)
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera)
        btnSync = findViewById(R.id.btnSync)
        tvStatus = findViewById(R.id.tvStatus)
        tvTimer = findViewById(R.id.tvTimer)
        tvDeviceId = findViewById(R.id.tvDeviceId)
        timerLayout = findViewById(R.id.timerLayout)
        recordIcon = findViewById(R.id.recordIcon)
        stopIcon = findViewById(R.id.stopIcon)

        Log.d("CameraApp", "Views initialized successfully")
    }

    private fun showDeviceSelectionDialog() {
        val devices = arrayOf("LEFT (左カメラ)", "RIGHT (右カメラ)")
        AlertDialog.Builder(this)
            .setTitle("デバイスを選択")
            .setItems(devices) { _, which ->
                deviceId = if (which == 0) DEVICE_LEFT else DEVICE_RIGHT
                tvDeviceId.text = "Device: $deviceId"
                Log.d("CameraApp", "デバイス選択: $deviceId")
            }
            .setCancelable(false)
            .show()
    }

    private fun setupListeners() {
        btnRecord.setOnClickListener {
                if (isSyncEnabled) {
                } else {
                    // 通常モード
                    stopRecording()
                } else {
                    startRecording()
                }
            }
        }

        btnClose.setOnClickListener {
            disconnectSync()
            finish()
        }

        btnFlash.setOnClickListener {
            toggleFlash()
        }

        btnSwitchCamera.setOnClickListener {
            switchCamera()
        }

        btnSync.setOnClickListener {
            toggleSyncMode()
        }
    }

    private fun toggleSyncMode() {
        if (isSyncEnabled) {
            disconnectSync()
        } else {
            connectSync()
        }
    }

    private fun connectSync() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .build()

                val request = Request.Builder()
                    .url("ws://172.21.1.123:7777/ws/$deviceId")
                    .build()

                webSocket = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        Log.d("CameraApp", "WebSocket接続成功")
                        runOnUiThread {
                            isSyncEnabled = true
                            btnSync.text = "🔗 同期ON"
                            btnSync.setBackgroundColor(getColor(android.R.color.holo_green_light))
                            tvStatus.text = "同期モード (待機中)"
                            Toast.makeText(this@MainActivity, "同期接続成功", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        Log.d("CameraApp", "受信: $text")
                        handleSyncMessage(text)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.e("CameraApp", "WebSocket接続失敗", t)
                        runOnUiThread {
                            isSyncEnabled = false
                            btnSync.text = "🔗 同期OFF"
                            btnSync.setBackgroundColor(getColor(android.R.color.darker_gray))
                            Toast.makeText(this@MainActivity, "同期接続失敗", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        Log.d("CameraApp", "WebSocket切断")
                        runOnUiThread {
                            isSyncEnabled = false
                            btnSync.text = "🔗 同期OFF"
                            btnSync.setBackgroundColor(getColor(android.R.color.darker_gray))
                        }
                    }
                })

            } catch (e: Exception) {
                Log.e("CameraApp", "同期接続エラー", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "同期接続エラー: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun disconnectSync() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        isSyncEnabled = false
        btnSync.text = "🔗 同期OFF"
        btnSync.setBackgroundColor(getColor(android.R.color.darker_gray))
        tvStatus.text = "停止中"
    }

    private fun sendSyncCommand(command: String) {
        val json = JSONObject().apply {
            put("command", command)
            put("device_id", deviceId)
            put("timestamp", System.currentTimeMillis())
        }
    }

    private fun handleSyncMessage(message: String) {
        try {
            val json = JSONObject(message)
            val command = json.getString("command")

            when (command) {
                "start_recording" -> {
                    runOnUiThread {
                        if (recording == null) {
                            startRecording()
                        }
                    }
                }
                "stop_recording" -> {
                    runOnUiThread {
                        if (recording != null) {
                            stopRecording()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CameraApp", "メッセージ処理エラー", e)
        }
    }

    private fun allPermissionsGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "カメラと音声の権限が必要です", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()

            videoCapture = VideoCapture.withOutput(recorder)

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(
                    this, cameraSelector, preview, videoCapture
                )
                Log.d("CameraApp", "カメラ起動成功")
            } catch (e: Exception) {
                Log.e("CameraApp", "カメラ起動失敗", e)
                Toast.makeText(this, "カメラ起動失敗", Toast.LENGTH_SHORT).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun startRecording() {
        val videoCapture = this.videoCapture ?: return

        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())

        val videoFile = File(cacheDir, "video_${deviceId}_$name.mp4")
        currentVideoPath = videoFile.absolutePath

        val outputOptions = FileOutputOptions.Builder(videoFile).build()

        recording = videoCapture.output
            .prepareRecording(this, outputOptions)
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        Log.d("CameraApp", "録画開始 [$deviceId]")
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            Log.d("CameraApp", "録画完了: ${videoFile.absolutePath}")
                            Log.d("CameraApp", "ファイルサイズ: ${videoFile.length() / 1024}KB")
                        } else {
                            Log.e("CameraApp", "録画エラー: ${recordEvent.error}")
                            recording?.close()
                            recording = null
                            runOnUiThread {
                                Toast.makeText(this, "録画エラー", Toast.LENGTH_SHORT).show()
                                resetUI()
                            }
                        }
                    }
                }
            }

        recordingStartTime = System.currentTimeMillis()

        runOnUiThread {
            recordIcon.visibility = View.GONE
            stopIcon.visibility = View.VISIBLE
            timerLayout.visibility = View.VISIBLE
            tvStatus.text = if (isSyncEnabled) "同期録画中..." else "録画中..."
            updateTimer()
        }

        Log.d("CameraApp", "=== 録画開始 [$deviceId] ===")
        Toast.makeText(this, "録画開始", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecording() {
        recording?.stop()
        recording = null

        Log.d("CameraApp", "=== 録画停止 [$deviceId] ===")
        Log.d("CameraApp", "動画パス: $currentVideoPath")

        runOnUiThread {
            resetUI()
            Toast.makeText(this, "録画停止", Toast.LENGTH_SHORT).show()

            // 確認画面に遷移
            if (currentVideoPath != null && File(currentVideoPath!!).exists()) {
                val fileSize = File(currentVideoPath!!).length()
                Log.d("CameraApp", "確認画面に遷移 (ファイルサイズ: ${fileSize / 1024}KB)")

                val intent = android.content.Intent(this, ConfirmActivity::class.java)
                intent.putExtra("VIDEO_PATH", currentVideoPath)
                intent.putExtra("DEVICE_ID", deviceId)
                intent.putExtra("TIMESTAMP", recordingStartTime)
                startActivity(intent)
            } else {
                Toast.makeText(this, "動画ファイルが見つかりません", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun resetUI() {
        recordIcon.visibility = View.VISIBLE
        stopIcon.visibility = View.GONE
        timerLayout.visibility = View.GONE
        tvStatus.text = if (isSyncEnabled) "同期モード (待機中)" else "停止中"
        tvTimer.text = "00:00"
    }

    private fun updateTimer() {
        if (recording != null) {
            val elapsedSeconds = (System.currentTimeMillis() - recordingStartTime) / 1000
            val minutes = elapsedSeconds / 60
            val seconds = elapsedSeconds % 60
            tvTimer.text = String.format("%02d:%02d", minutes, seconds)

            tvTimer.postDelayed({ updateTimer() }, 1000)
        }
    }

    private fun toggleFlash() {
        camera?.let {
            val flashMode = it.cameraInfo.torchState.value
            if (flashMode == TorchState.ON) {
                it.cameraControl.enableTorch(false)
                Toast.makeText(this, "フラッシュOFF", Toast.LENGTH_SHORT).show()
            } else {
                it.cameraControl.enableTorch(true)
                Toast.makeText(this, "フラッシュON", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        startCamera()
        Toast.makeText(this, "カメラ切替", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectSync()
        cameraExecutor.shutdown()
    }
}
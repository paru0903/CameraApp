package com.example.cameraapp

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class ConfirmActivity : AppCompatActivity() {

    private lateinit var videoView: VideoView
    private lateinit var btnRetry: Button
    private lateinit var btnGenerate: Button
    private lateinit var btnPlay: ImageButton
    private var currentVideoPath: String? = null
    private var deviceId: String = "LEFT"
    private var captureTimestamp: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_confirm)

        // インテントからデータ取得
        deviceId = intent.getStringExtra("DEVICE_ID") ?: "LEFT"
        captureTimestamp = intent.getLongExtra("TIMESTAMP", 0)

        initViews()
        loadRecordedVideo()
        setupListeners()
    }

    private fun initViews() {
        videoView = findViewById(R.id.videoView)
        btnRetry = findViewById(R.id.btnRetry)
        btnGenerate = findViewById(R.id.btnGenerate)
        btnPlay = findViewById(R.id.btnPlay)
    }

    private fun loadRecordedVideo() {
        currentVideoPath = intent.getStringExtra("VIDEO_PATH")

        if (currentVideoPath != null) {
            val file = File(currentVideoPath!!)
            if (file.exists()) {
                Log.d("ConfirmActivity", "動画読み込み [$deviceId]: $currentVideoPath")
                Log.d("ConfirmActivity", "ファイルサイズ: ${file.length() / 1024}KB")
                Log.d("ConfirmActivity", "撮影時刻: $captureTimestamp")

                val uri = Uri.fromFile(file)
                videoView.setVideoURI(uri)

                val mediaController = MediaController(this)
                mediaController.setAnchorView(videoView)
                videoView.setMediaController(mediaController)

                videoView.setOnPreparedListener { mp ->
                    val videoWidth = mp.videoWidth
                    val videoHeight = mp.videoHeight
                    val videoProportion = videoWidth.toFloat() / videoHeight.toFloat()

                    val screenWidth = videoView.width
                    val screenHeight = videoView.height
                    val screenProportion = screenWidth.toFloat() / screenHeight.toFloat()

                    val lp = videoView.layoutParams as android.widget.FrameLayout.LayoutParams

                    if (videoProportion > screenProportion) {
                        lp.width = screenWidth
                        lp.height = (screenWidth.toFloat() / videoProportion).toInt()
                    } else {
                        lp.width = (screenHeight.toFloat() * videoProportion).toInt()
                        lp.height = screenHeight
                    }
                    lp.gravity = android.view.Gravity.CENTER
                    videoView.layoutParams = lp

                    Log.d("ConfirmActivity", "動画サイズ: ${videoWidth}x${videoHeight}")
                }

                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(currentVideoPath)
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    Log.d("ConfirmActivity", "動画長さ: ${duration}ms")
                    retriever.release()
                } catch (e: Exception) {
                    Log.e("ConfirmActivity", "メタデータ取得エラー", e)
                }

                videoView.setOnCompletionListener {
                    btnPlay.visibility = android.view.View.VISIBLE
                    Log.d("ConfirmActivity", "再生完了")
                }

                videoView.setOnErrorListener { _, what, extra ->
                    Log.e("ConfirmActivity", "再生エラー: what=$what, extra=$extra")
                    showToast("動画の再生に失敗しました")
                    true
                }

            } else {
                Log.e("ConfirmActivity", "ファイルが存在しません: $currentVideoPath")
                showToast("動画ファイルが見つかりません")
            }
        } else {
            Log.e("ConfirmActivity", "動画パスがnull")
            showToast("動画データがありません")
        }
    }

    private fun setupListeners() {
        findViewById<ImageView>(R.id.btnBack).setOnClickListener {
            finish()
        }

        btnPlay.setOnClickListener {
            playVideo()
        }

        videoView.setOnClickListener {
            if (!videoView.isPlaying) {
                playVideo()
            }
        }

        btnRetry.setOnClickListener {
            currentVideoPath?.let { path ->
                File(path).delete()
                Log.d("ConfirmActivity", "動画ファイル削除: $path")
            }
            finish()
        }

        btnGenerate.setOnClickListener {
            uploadVideoToServer()
        }
    }

    private fun playVideo() {
        btnPlay.visibility = android.view.View.GONE
        videoView.start()
        Log.d("ConfirmActivity", "再生開始")
        showToast("再生中...")
    }

    private fun uploadVideoToServer() {
        if (currentVideoPath == null) {
            showToast("動画データがありません")
            return
        }

        val videoFile = File(currentVideoPath!!)
        if (!videoFile.exists()) {
            showToast("動画ファイルが見つかりません")
            return
        }

        btnGenerate.isEnabled = false
        btnGenerate.text = "送信中..."
        showToast("動画をサーバーに送信中...")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d("ConfirmActivity", "=== 動画送信開始 [$deviceId] ===")
                Log.d("ConfirmActivity", "ファイル: ${videoFile.absolutePath}")
                Log.d("ConfirmActivity", "サイズ: ${videoFile.length() / 1024}KB")
                Log.d("ConfirmActivity", "タイムスタンプ: $captureTimestamp")

                val requestBody = videoFile.asRequestBody("video/mp4".toMediaTypeOrNull())

                val multipartBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "video",
                        videoFile.name,
                        requestBody
                    )
                    .addFormDataPart("device_id", deviceId)
                    .addFormDataPart("timestamp", captureTimestamp.toString())
                    .addFormDataPart("camera_position", "back")
                    .build()

                val serverUrl = "http://172.21.1.123:7777/upload_video"
                Log.d("ConfirmActivity", "送信先: $serverUrl")

                val client = OkHttpClient.Builder()
                    .connectTimeout(120, TimeUnit.SECONDS)
                    .writeTimeout(120, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url(serverUrl)
                    .post(multipartBody)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                Log.d("ConfirmActivity", "レスポンス: ${response.code}")
                Log.d("ConfirmActivity", "レスポンスボディ: $responseBody")

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        showToast("送信成功！[$deviceId]")
                        videoFile.delete()
                        Log.d("ConfirmActivity", "動画ファイル削除: ${videoFile.absolutePath}")
                        finish()
                    } else {
                        showToast("送信失敗: ${response.code}")
                        btnGenerate.isEnabled = true
                        btnGenerate.text = "生成する"
                    }
                }

                response.close()

            } catch (e: Exception) {
                Log.e("ConfirmActivity", "送信エラー", e)
                withContext(Dispatchers.Main) {
                    showToast("送信エラー: ${e.message}")
                    btnGenerate.isEnabled = true
                    btnGenerate.text = "生成する"
                }
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onPause() {
        super.onPause()
        if (videoView.isPlaying) {
            videoView.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        videoView.stopPlayback()
    }
}
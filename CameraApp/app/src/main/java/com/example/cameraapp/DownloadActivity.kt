package com.example.cameraapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class DownloadActivity : AppCompatActivity() {

    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvPercent: TextView
    private lateinit var tvProgressMsg: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnCancel: Button

    private var isCancelled = false
    private var deviceId: String = "LEFT"
    private var timestamp: Long = 0
    private val serverUrl = "http://172.21.1.123:7777"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_download)

        deviceId = intent.getStringExtra("DEVICE_ID") ?: "LEFT"
        timestamp = intent.getLongExtra("TIMESTAMP", 0)

        initViews()
        setupListeners()
        startMapGeneration()
    }

    private fun initViews() {
        tvTitle = findViewById(R.id.tvTitle)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        tvPercent = findViewById(R.id.tvPercent)
        tvProgressMsg = findViewById(R.id.tvProgressMsg)
        progressBar = findViewById(R.id.progressBar)
        btnCancel = findViewById(R.id.btnCancel)
    }

    private fun setupListeners() {
        btnCancel.setOnClickListener {
            isCancelled = true
            Toast.makeText(this, "処理をキャンセルしました", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun startMapGeneration() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d("DownloadActivity", "マップ生成開始: device=$deviceId, timestamp=$timestamp")

                withContext(Dispatchers.Main) {
                    updateProgress(5, "マップ生成をリクエスト中...")
                }

                // サーバーにマップ生成をリクエスト
                val client = OkHttpClient.Builder()
                    .connectTimeout(120, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url("$serverUrl/generate_map")
                    .post(
                        FormBody.Builder()
                            .add("device_id", deviceId)
                            .add("timestamp", timestamp.toString())
                            .build()
                    )
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                Log.d("DownloadActivity", "マップ生成レスポンス: ${response.code}")
                Log.d("DownloadActivity", "レスポンスボディ: $responseBody")

                if (response.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        updateProgress(10, "マップを生成中...")
                    }

                    // マップ生成の進捗を監視
                    pollMapStatus()

                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@DownloadActivity,
                            "マップ生成リクエスト失敗: ${response.code}",
                            Toast.LENGTH_SHORT
                        ).show()
                        finish()
                    }
                }

                response.close()

            } catch (e: Exception) {
                Log.e("DownloadActivity", "マップ生成エラー", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@DownloadActivity,
                        "エラー: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            }
        }
    }

    private suspend fun pollMapStatus() {
        var progress = 10
        var retryCount = 0
        val maxRetries = 120 // 最大2分（1秒間隔）

        while (!isCancelled && retryCount < maxRetries) {
            try {
                delay(1000) // 1秒待機

                val client = OkHttpClient()
                val request = Request.Builder()
                    .url("$serverUrl/map_status?device_id=$deviceId&timestamp=$timestamp")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                Log.d("DownloadActivity", "ステータス確認: $responseBody")

                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    val status = json.optString("status", "processing")
                    val currentProgress = json.optInt("progress", progress)

                    withContext(Dispatchers.Main) {
                        updateProgress(currentProgress, "マップ生成中... ($status)")
                    }

                    when (status) {
                        "completed" -> {
                            // マップ生成完了 → ダウンロード開始
                            withContext(Dispatchers.Main) {
                                updateProgress(90, "マップをダウンロード中...")
                            }
                            downloadMap()
                            return
                        }
                        "failed", "error" -> {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    this@DownloadActivity,
                                    "マップ生成失敗",
                                    Toast.LENGTH_SHORT
                                ).show()
                                finish()
                            }
                            return
                        }
                        else -> {
                            // 処理中 → 継続
                            progress = minOf(currentProgress, 85)
                        }
                    }
                }

                response.close()
                retryCount++

            } catch (e: Exception) {
                Log.e("DownloadActivity", "ステータス確認エラー", e)
                retryCount++
            }
        }

        if (retryCount >= maxRetries) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@DownloadActivity, "タイムアウト", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private suspend fun downloadMap() {
        try {
            withContext(Dispatchers.Main) {
                updateProgress(90, "マップファイルをダウンロード中...")
            }

            val client = OkHttpClient.Builder()
                .readTimeout(120, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url("$serverUrl/download_map?device_id=$deviceId&timestamp=$timestamp")
                .get()
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                // マップファイルを保存（GLB形式）
                val mapFile = File(cacheDir, "map_${deviceId}_$timestamp.glb")
                response.body?.byteStream()?.use { input ->
                    FileOutputStream(mapFile).use { output ->
                        input.copyTo(output)
                    }
                }

                Log.d("DownloadActivity", "マップダウンロード完了: ${mapFile.absolutePath}")
                Log.d("DownloadActivity", "ファイルサイズ: ${mapFile.length() / 1024}KB")

                withContext(Dispatchers.Main) {
                    updateProgress(100, "完了！")
                    delay(500)
                    Toast.makeText(this@DownloadActivity, "マップ生成完了", Toast.LENGTH_SHORT).show()

                    // マップビューア画面に遷移
                    val intent = Intent(this@DownloadActivity, MapViewerActivity::class.java)
                    intent.putExtra("MAP_PATH", mapFile.absolutePath)
                    startActivity(intent)

                    finish()
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@DownloadActivity,
                        "ダウンロード失敗: ${response.code}",
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            }

            response.close()

        } catch (e: Exception) {
            Log.e("DownloadActivity", "ダウンロードエラー", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@DownloadActivity,
                    "ダウンロードエラー: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    private fun updateProgress(percent: Int, message: String) {
        progressBar.progress = percent
        tvPercent.text = "$percent%"
        tvProgressMsg.text = message
    }

    override fun onDestroy() {
        super.onDestroy()
        isCancelled = true
    }
}
package com.example.cameraapp

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MapViewerActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var btnBack: ImageButton
    private var mapFilePath: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_viewer)

        mapFilePath = intent.getStringExtra("MAP_PATH")

        initViews()
        setupWebView()
        loadMap()
    }

    private fun initViews() {
        webView = findViewById(R.id.webView)
        btnBack = findViewById(R.id.btnBack)

        btnBack.setOnClickListener {
            finish()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        webView.webViewClient = WebViewClient()
    }

    private fun loadMap() {
        if (mapFilePath == null) {
            Toast.makeText(this, "マップファイルが指定されていません", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val mapFile = File(mapFilePath!!)
        if (!mapFile.exists()) {
            Toast.makeText(this, "マップファイルが見つかりません", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        try {
            Log.d("MapViewer", "マップ読み込み開始: $mapFilePath")
            Log.d("MapViewer", "ファイルサイズ: ${mapFile.length() / 1024}KB")

            // GLBファイルをBase64エンコード
            val glbBytes = mapFile.readBytes()
            val glbBase64 = Base64.encodeToString(glbBytes, Base64.NO_WRAP)

            // Three.jsを使ったHTMLを生成
            val html = generateThreeJsHtml(glbBase64)

            webView.loadDataWithBaseURL(
                "https://localhost",
                html,
                "text/html",
                "UTF-8",
                null
            )

            Log.d("MapViewer", "マップ読み込み成功")
            Toast.makeText(this, "マップを読み込みました", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Log.e("MapViewer", "マップ読み込みエラー", e)
            Toast.makeText(this, "エラー: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun generateThreeJsHtml(glbBase64: String): String {
        return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <style>
        body {
            margin: 0;
            overflow: hidden;
            background: #000;
            touch-action: none;
        }
        #canvas {
            width: 100vw;
            height: 100vh;
            display: block;
        }
        #info {
            position: absolute;
            bottom: 20px;
            left: 20px;
            color: white;
            font-family: Arial;
            font-size: 12px;
            background: rgba(0,0,0,0.5);
            padding: 10px;
            border-radius: 5px;
        }
        #loading {
            position: absolute;
            top: 50%;
            left: 50%;
            transform: translate(-50%, -50%);
            color: white;
            font-family: Arial;
            font-size: 18px;
        }
    </style>
</head>
<body>
    <div id="loading">Loading 3D Map...</div>
    <div id="canvas"></div>
    <div id="info">ドラッグで回転 | ピンチでズーム</div>

    <script src="https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js"></script>
    <script src="https://cdn.jsdelivr.net/npm/three@0.128.0/examples/js/loaders/GLTFLoader.js"></script>
    <script src="https://cdn.jsdelivr.net/npm/three@0.128.0/examples/js/controls/OrbitControls.js"></script>

    <script>
        let scene, camera, renderer, controls, model;
        
        function init() {
            // シーン
            scene = new THREE.Scene();
            scene.background = new THREE.Color(0x1a1a1a);
            
            // カメラ
            camera = new THREE.PerspectiveCamera(
                45,
                window.innerWidth / window.innerHeight,
                0.1,
                1000
            );
            camera.position.set(0, 2, 5);
            
            // レンダラー
            renderer = new THREE.WebGLRenderer({ antialias: true });
            renderer.setSize(window.innerWidth, window.innerHeight);
            renderer.setPixelRatio(window.devicePixelRatio);
            document.getElementById('canvas').appendChild(renderer.domElement);
            
            // ライト
            const ambientLight = new THREE.AmbientLight(0xffffff, 0.6);
            scene.add(ambientLight);
            
            const directionalLight = new THREE.DirectionalLight(0xffffff, 0.8);
            directionalLight.position.set(5, 10, 5);
            scene.add(directionalLight);
            
            const directionalLight2 = new THREE.DirectionalLight(0xffffff, 0.4);
            directionalLight2.position.set(-5, 5, -5);
            scene.add(directionalLight2);
            
            // グリッド
            const gridHelper = new THREE.GridHelper(10, 10, 0x444444, 0x222222);
            scene.add(gridHelper);
            
            // コントロール
            controls = new THREE.OrbitControls(camera, renderer.domElement);
            controls.enableDamping = true;
            controls.dampingFactor = 0.05;
            controls.minDistance = 0.5;
            controls.maxDistance = 50;
            
            // GLBモデル読み込み
            loadGLBModel();
            
            // リサイズ対応
            window.addEventListener('resize', onWindowResize);
            
            // アニメーション開始
            animate();
        }
        
        function loadGLBModel() {
            const loader = new THREE.GLTFLoader();
            
            // Base64からArrayBufferに変換
            const base64Data = '${glbBase64}';
            const binaryString = atob(base64Data);
            const bytes = new Uint8Array(binaryString.length);
            for (let i = 0; i < binaryString.length; i++) {
                bytes[i] = binaryString.charCodeAt(i);
            }
            
            // GLBをロード
            loader.parse(bytes.buffer, '', function(gltf) {
                model = gltf.scene;
                
                // モデルのサイズと位置を調整
                const box = new THREE.Box3().setFromObject(model);
                const center = box.getCenter(new THREE.Vector3());
                const size = box.getSize(new THREE.Vector3());
                
                const maxDim = Math.max(size.x, size.y, size.z);
                const scale = 2.0 / maxDim;
                model.scale.multiplyScalar(scale);
                
                model.position.sub(center.multiplyScalar(scale));
                
                scene.add(model);
                
                document.getElementById('loading').style.display = 'none';
                console.log('GLB model loaded successfully');
                
            }, function(error) {
                console.error('Error loading GLB:', error);
                document.getElementById('loading').textContent = 'Error loading model';
            });
        }
        
        function onWindowResize() {
            camera.aspect = window.innerWidth / window.innerHeight;
            camera.updateProjectionMatrix();
            renderer.setSize(window.innerWidth, window.innerHeight);
        }
        
        function animate() {
            requestAnimationFrame(animate);
            controls.update();
            renderer.render(scene, camera);
        }
        
        // 初期化実行
        init();
    </script>
</body>
</html>
        """.trimIndent()
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
    }
}
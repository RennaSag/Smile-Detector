package com.example.detector

import android.Manifest
import android.content.pm.PackageManager
//import android.hardware.camera2.params.Face
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var smileStatusTextView: TextView
    private val TAG = "SmileDetector"

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Inicialização de Views
        previewView = findViewById(R.id.previewView)
        smileStatusTextView = findViewById(R.id.smileStatusTextView)

        // Executor para tarefas da câmera
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Verificação de permissões
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                smileStatusTextView.text = "Permissões não concedidas, câmera não encontrada"
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            // Configuração do detector de face
            val highAccuracyOpts = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .enableTracking()
                .build()

            val detector = FaceDetection.getClient(highAccuracyOpts)

            // Análise de imagem
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, object : ImageAnalysis.Analyzer {
                        override fun analyze(imageProxy: ImageProxy) {
                            val mediaImage = imageProxy.image
                            if (mediaImage != null) {
                                val image = InputImage.fromMediaImage(
                                    mediaImage,
                                    imageProxy.imageInfo.rotationDegrees
                                )

                                // Processa a imagem com o detector de faces
                                detector.process(image)
                                    .addOnSuccessListener { faces ->
                                        processSmiles(faces)
                                    }
                                    .addOnFailureListener { e ->
                                        Log.e(TAG, "Falha na detecção de faces: $e")
                                    }
                                    .addOnCompleteListener {
                                        imageProxy.close()
                                    }
                            } else {
                                imageProxy.close()
                            }
                        }
                    })
                }

            // Selector de câmera frontal
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                // Reinicia vinculação
                cameraProvider.unbindAll()

                // Vincula casos de uso à câmera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )

            } catch (exc: Exception) {
                Log.e(TAG, "Falha na vinculação da câmera", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun processSmiles(faces: List<Face>) {
        if (faces.isEmpty()) {
            runOnUiThread {
                smileStatusTextView.text = "Nenhum rosto encontrado"
            }
            return
        }

        var smilingPeople = 0
        for (face in faces) {
            if (face.smilingProbability != null && face.smilingProbability!! > 0.7) {
                smilingPeople++
            }
        }

        runOnUiThread {
            when {
                smilingPeople == 0 -> smileStatusTextView.text = "Ninguém está sorrindo 😐"
                smilingPeople == faces.size -> smileStatusTextView.text = "Todos estão sorrindo! 😃"
                else -> smileStatusTextView.text = "$smilingPeople/${faces.size} pessoas sorrindo 😊"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
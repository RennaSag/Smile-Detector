package com.example.detector

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var smileStatusTextView: TextView
    private val TAG = "SmileDetector"

    // mediaPlayers para os sons
    private lateinit var happyMediaPlayer: MediaPlayer
    private lateinit var sadMediaPlayer: MediaPlayer

    // controle do estado de sorriso atual
    private var isCurrentlySmiling = false

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

        // resolução padrão para otimização
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0
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

        // inicialização de Views
        previewView = findViewById(R.id.previewView)
        smileStatusTextView = findViewById(R.id.smileStatusTextView)

        // inicializar os MediaPlayers
        setupMediaPlayers()

        // executor para tarefas da câmera
        cameraExecutor = Executors.newSingleThreadExecutor()

        // verificação de permissões
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun setupMediaPlayers() {
        // inicializa o MediaPlayer para som alegre
        happyMediaPlayer = MediaPlayer.create(this, R.raw.alegria)
        happyMediaPlayer.isLooping = false  // Alterado para não fazer loop

        // inicializa o MediaPlayer para som triste
        sadMediaPlayer = MediaPlayer.create(this, R.raw.triste)
        sadMediaPlayer.isLooping = false  // Alterado para não fazer loop
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
                smileStatusTextView.text = "Permissões não concedidas"
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Determinar a melhor resolução baseada no dispositivo
            val metrics = DisplayMetrics().also { previewView.display?.getRealMetrics(it) }
            val screenAspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)
            val rotation = previewView.display?.rotation ?: 0

            // Calcular a resolução ideal para o dispositivo
            val resolution = getOptimalResolution(metrics)
            Log.d(TAG, "Resolução otimizada: ${resolution.width} x ${resolution.height}")

            // Preview com resolução adaptada
            val preview = Preview.Builder()
                .setTargetResolution(resolution)
                .setTargetRotation(rotation)
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            // configuração do detector de face
            val highAccuracyOpts = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .enableTracking()
                .build()

            val detector = FaceDetection.getClient(highAccuracyOpts)

            // análise de imagem com resolução adaptada
            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(resolution)
                .setTargetRotation(rotation)
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

                                // processa a imagem com o detector de faces
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

            // selector de câmera frontal
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                // reinicia vinculação
                cameraProvider.unbindAll()

                // Agrupar casos de uso para uma melhor sincronização
                val useCaseGroup = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(imageAnalyzer)
                    .build()

                // vincula os casos de uso à câmera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, useCaseGroup
                )

            } catch (exc: Exception) {
                Log.e(TAG, "Falha na vinculação da câmera", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun getOptimalResolution(metrics: DisplayMetrics): Size {
        // Limitar a resolução para equilibrar performance e qualidade
        val screenSize = Size(metrics.widthPixels, metrics.heightPixels)
        val maxResolution = 1080 // resolução máxima para análise (pode ser ajustada)

        // Calcular resolução proporcional, mas limitada para não sobrecarregar
        val width = min(screenSize.width, maxResolution)
        val height = min(screenSize.height, maxResolution)

        // Manter proporção da tela, mas não exceder resolução máxima
        return Size(width, height)
    }

    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height).toDouble()
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return androidx.camera.core.AspectRatio.RATIO_4_3
        }
        return androidx.camera.core.AspectRatio.RATIO_16_9
    }

    private fun processSmiles(faces: List<Face>) {
        if (faces.isEmpty()) {
            runOnUiThread {
                smileStatusTextView.text = "Nenhum rosto encontrado"

                // se não houver rostos, considera como não sorrindo
                playSadSound()
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
                smilingPeople == 0 -> {
                    smileStatusTextView.text = "Ninguém está sorrindo :(" //😐
                    playSadSound()
                }
                smilingPeople == faces.size -> {
                    smileStatusTextView.text = "Que sorriso bonito :D" //😃
                    playHappySound()
                }
                else -> {
                    smileStatusTextView.text = "$smilingPeople/${faces.size} pessoas sorrindo " //😊
                    playHappySound() // se alguém estiver sorrindo, toca o som alegre
                }
            }
        }
    }

    private fun playHappySound() {
        if (!isCurrentlySmiling) {
            // para qualquer som que esteja tocando
            stopAllSounds()

            // recria o MediaPlayer para garantir que comece do início
            resetHappyMediaPlayer()

            // inicia o som alegre
            happyMediaPlayer.start()

            isCurrentlySmiling = true
        }
    }

    private fun playSadSound() {
        if (isCurrentlySmiling) {
            // para qualquer som que esteja tocando
            stopAllSounds()

            // recria o MediaPlayer para garantir que comece do início
            resetSadMediaPlayer()

            // inicia o som triste
            sadMediaPlayer.start()

            isCurrentlySmiling = false
        }
    }

    private fun stopAllSounds() {
        if (happyMediaPlayer.isPlaying) {
            happyMediaPlayer.stop()
        }
        if (sadMediaPlayer.isPlaying) {
            sadMediaPlayer.stop()
        }
    }

    private fun resetHappyMediaPlayer() {
        happyMediaPlayer.release()
        happyMediaPlayer = MediaPlayer.create(this, R.raw.alegria)
        happyMediaPlayer.isLooping = false
    }

    private fun resetSadMediaPlayer() {
        sadMediaPlayer.release()
        sadMediaPlayer = MediaPlayer.create(this, R.raw.triste)
        sadMediaPlayer.isLooping = false
    }

    override fun onPause() {
        super.onPause()
        // pausa os sons quando a aplicação for para o plano de fundo
        if (happyMediaPlayer.isPlaying) {
            happyMediaPlayer.pause()
        }
        if (sadMediaPlayer.isPlaying) {
            sadMediaPlayer.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // libera os recursos quando a aplicação termina
        cameraExecutor.shutdown()
        happyMediaPlayer.release()
        sadMediaPlayer.release()
    }
}
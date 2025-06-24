package com.example.textocr

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.view.View
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsControllerCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.yalantis.ucrop.UCrop
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var cameraImage: ImageView
    private lateinit var scanTextBtn: Button
    private lateinit var detectObjectBtn: Button
    private lateinit var liveObjectBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var resultText: TextView
    private lateinit var copyTextBtn: Button
    private lateinit var previewView: PreviewView
    private lateinit var liveLabelsTextView: TextView

    private var imageUri: android.net.Uri? = null
    private var currentPhotoPath: String? = null

    private enum class Mode { OCR, IMAGE_LABELING, LIVE_OBJECT_DETECTION }
    private var currentMode: Mode? = null

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var cameraLauncher: ActivityResultLauncher<android.net.Uri>
    private lateinit var galleryLauncher: ActivityResultLauncher<String>
    private lateinit var cropLauncher: ActivityResultLauncher<android.content.Intent>

    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var analysisUseCase: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.statusBarColor = Color.parseColor("#121212")
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true

        cameraImage = findViewById(R.id.cameraImage)
        scanTextBtn = findViewById(R.id.scanTextBtn)
        detectObjectBtn = findViewById(R.id.detectObjectBtn)
        liveObjectBtn = findViewById(R.id.liveObjectBtn)
        progressBar = findViewById(R.id.progressBar)
        resultText = findViewById(R.id.resultText)
        copyTextBtn = findViewById(R.id.copyTextBtn)
        previewView = findViewById(R.id.previewView) // Add PreviewView in XML (see note below)
        liveLabelsTextView = findViewById(R.id.liveLabelsTextView) // TextView for live detection results

        resultText.setTextIsSelectable(true)
        copyTextBtn.visibility = View.GONE

        copyTextBtn.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Result Text", resultText.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            if (permissions.entries.all { it.value }) {
                launchImageSourceDialog()
            } else {
                Toast.makeText(this, "Permissions denied", Toast.LENGTH_LONG).show()
            }
        }

        cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success && imageUri != null) {
                launchCrop(imageUri!!)
            } else {
                Toast.makeText(this, "Failed to capture image", Toast.LENGTH_SHORT).show()
            }
        }

        galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { launchCrop(it) }
        }

        cropLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val resultUri = UCrop.getOutput(result.data!!)
                if (resultUri != null) {
                    cameraImage.setImageURI(resultUri)
                    processImageWithMode(resultUri)
                } else {
                    Toast.makeText(this, "Crop failed", Toast.LENGTH_SHORT).show()
                }
            }
        }

        scanTextBtn.setOnClickListener {
            stopLiveDetection()
            currentMode = Mode.OCR
            checkAndRequestPermissions()
        }

        detectObjectBtn.setOnClickListener {
            stopLiveDetection()
            currentMode = Mode.IMAGE_LABELING
            checkAndRequestPermissions()
        }

        liveObjectBtn.setOnClickListener {
            // Hide the result box and copy button
            resultText.visibility = View.GONE
            copyTextBtn.visibility = View.GONE

            // Start the live object detection activity
            startActivity(Intent(this, LiveObjectLabelActivity::class.java))
        }

    }

    private fun checkAndRequestPermissions() {
        val neededPermissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.CAMERA)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (neededPermissions.isEmpty()) {
            launchImageSourceDialog()
        } else {
            permissionLauncher.launch(neededPermissions.toTypedArray())
        }
    }

    private fun launchImageSourceDialog() {
        val options = arrayOf("Capture Image", "Choose from Gallery")
        AlertDialog.Builder(this)
            .setTitle("Select Image Source")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> launchCamera()
                    1 -> launchGallery()
                }
            }
            .show()
    }

    private fun launchCamera() {
        val photoFile = createImageFile()
        photoFile?.also {
            imageUri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.provider",
                it
            )
            cameraLauncher.launch(imageUri)
        }
    }

    private fun launchGallery() {
        galleryLauncher.launch("image/*")
    }

    private fun launchCrop(sourceUri: android.net.Uri) {
        val destinationFileName = "cropped_${System.currentTimeMillis()}.jpg"
        val destUri = android.net.Uri.fromFile(File(cacheDir, destinationFileName))

        val options = UCrop.Options().apply {
            setCompressionQuality(90)
            setFreeStyleCropEnabled(true)
            setToolbarTitle("Crop Image")
        }

        val cropIntent = UCrop.of(sourceUri, destUri)
            .withOptions(options)
            .getIntent(this)

        cropLauncher.launch(cropIntent)
    }

    private fun createImageFile(): File? {
        return try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            File.createTempFile("JPEG_${timeStamp}_", ".jpg", cacheDir).apply {
                currentPhotoPath = absolutePath
            }
        } catch (ex: Exception) {
            Toast.makeText(this, "Error creating file: ${ex.message}", Toast.LENGTH_LONG).show()
            null
        }
    }

    private fun processImageWithMode(uri: android.net.Uri) {
        resultText.text = ""
        copyTextBtn.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        previewView.visibility = View.GONE
        liveLabelsTextView.visibility = View.GONE
        cameraImage.visibility = View.VISIBLE

        when (currentMode) {
            Mode.OCR -> processImageWithMLKitOCR(uri)
            Mode.IMAGE_LABELING -> processImageWithMLKitImageLabeling(uri)
            else -> {
                progressBar.visibility = View.GONE
                Toast.makeText(this, "Invalid mode", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun processImageWithMLKitOCR(uri: android.net.Uri) {
        val image = InputImage.fromFilePath(this, uri)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                progressBar.visibility = View.GONE
                resultText.text = visionText.text.ifEmpty { "No text found" }
                copyTextBtn.visibility = View.VISIBLE
            }
            .addOnFailureListener {
                progressBar.visibility = View.GONE
                Toast.makeText(this, "Text recognition failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun processImageWithMLKitImageLabeling(uri: android.net.Uri) {
        val image = InputImage.fromFilePath(this, uri)
        val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)

        labeler.process(image)
            .addOnSuccessListener { labels ->
                progressBar.visibility = View.GONE
                if (labels.isEmpty()) {
                    resultText.text = "No labels found."
                    return@addOnSuccessListener
                }

                val sb = StringBuilder()
                for (label in labels) {
                    sb.append("- ${label.text} (Confidence: ${"%.2f".format(label.confidence)})\n")
                }
                resultText.text = sb.toString()
                copyTextBtn.visibility = View.VISIBLE
            }
            .addOnFailureListener {
                progressBar.visibility = View.GONE
                Toast.makeText(this, "Labeling failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    // ------------ Live Object Detection ------------

    private fun startLiveObjectDetection() {
        progressBar.visibility = View.GONE
        copyTextBtn.visibility = View.GONE
        resultText.text = ""
        cameraImage.visibility = View.GONE
        previewView.visibility = View.VISIBLE
        liveLabelsTextView.visibility = View.VISIBLE

        startCameraForLiveDetection()
    }

    private fun stopLiveDetection() {
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        previewView.visibility = View.GONE
        liveLabelsTextView.visibility = View.GONE
    }

    private fun startCameraForLiveDetection() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            analysisUseCase = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, ObjectDetectorAnalyzer())
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    analysisUseCase
                )
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Camera initialization failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    inner class ObjectDetectorAnalyzer : ImageAnalysis.Analyzer {
        private val options = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification() // Enable classification labels
            .build()

        private val detector = ObjectDetection.getClient(options)

        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                return
            }

            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            detector.process(image)
                .addOnSuccessListener { detectedObjects ->
                    if (detectedObjects.isEmpty()) {
                        runOnUiThread {
                            liveLabelsTextView.text = "No objects detected"
                        }
                    } else {
                        val labels = detectedObjects.joinToString("\n") { obj ->
                            val label = obj.labels.firstOrNull()?.text ?: "Unknown"
                            val confidence = obj.labels.firstOrNull()?.confidence ?: 0f
                            "$label (${String.format("%.1f", confidence * 100)}%)"
                        }
                        runOnUiThread {
                            liveLabelsTextView.text = labels
                        }
                    }
                }
                .addOnFailureListener { e ->
                    runOnUiThread {
                        liveLabelsTextView.text = "Detection failed: ${e.message}"
                    }
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }
    }
    override fun onResume() {
        super.onResume()
        // Show result box and copy button again when user returns
        resultText.visibility = View.VISIBLE
        copyTextBtn.visibility = if (resultText.text.isNotEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        stopLiveDetection()
    }
}

package com.example.textocr

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.yalantis.ucrop.UCrop
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var cameraImage: ImageView
    private lateinit var scanTextBtn: Button
    private lateinit var detectObjectBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var resultText: TextView
    private lateinit var copyTextBtn: Button

    private var imageUri: Uri? = null
    private var currentPhotoPath: String? = null

    private enum class Mode { OCR, OBJECT_DETECTION }
    private var currentMode: Mode? = null

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var cameraLauncher: ActivityResultLauncher<Uri>
    private lateinit var galleryLauncher: ActivityResultLauncher<String>
    private lateinit var cropLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.statusBarColor = Color.parseColor("#121212")
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true

        cameraImage = findViewById(R.id.cameraImage)
        scanTextBtn = findViewById(R.id.scanTextBtn)
        detectObjectBtn = findViewById(R.id.detectObjectBtn)
        progressBar = findViewById(R.id.progressBar)
        resultText = findViewById(R.id.resultText)
        copyTextBtn = findViewById(R.id.copyTextBtn)

        // ✅ Enable text selection
        resultText.setTextIsSelectable(true)

        copyTextBtn.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Result Text", resultText.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val granted = permissions.entries.all { it.value }
            if (granted) {
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
                    processImage(resultUri)
                } else {
                    Toast.makeText(this, "Crop failed", Toast.LENGTH_SHORT).show()
                }
            }
        }

        scanTextBtn.setOnClickListener {
            currentMode = Mode.OCR
            checkAndRequestPermissions()
        }

        detectObjectBtn.setOnClickListener {
            currentMode = Mode.OBJECT_DETECTION
            checkAndRequestPermissions()
        }

        copyTextBtn.visibility = android.view.View.GONE
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

    private fun launchCrop(sourceUri: Uri) {
        val destinationFileName = "cropped_${System.currentTimeMillis()}.jpg"
        val destUri = Uri.fromFile(File(cacheDir, destinationFileName))

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

    private fun processImage(uri: Uri) {
        resultText.text = ""
        copyTextBtn.visibility = android.view.View.GONE
        progressBar.visibility = android.view.View.VISIBLE

        val inputImage = InputImage.fromFilePath(this, uri)

        when (currentMode) {
            Mode.OCR -> {
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                recognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        progressBar.visibility = android.view.View.GONE
                        resultText.text = visionText.text.ifEmpty { "No text found" }
                        copyTextBtn.visibility = android.view.View.VISIBLE
                    }
                    .addOnFailureListener {
                        progressBar.visibility = android.view.View.GONE
                        Toast.makeText(this, "Text recognition failed: ${it.message}", Toast.LENGTH_LONG).show()
                    }
            }

            Mode.OBJECT_DETECTION -> {
                val options = ObjectDetectorOptions.Builder()
                    .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                    .enableMultipleObjects()
                    .enableClassification()
                    .build()
                val objectDetector = ObjectDetection.getClient(options)
                objectDetector.process(inputImage)
                    .addOnSuccessListener { detectedObjects ->
                        progressBar.visibility = android.view.View.GONE
                        if (detectedObjects.isEmpty()) {
                            resultText.text = "No objects detected."
                        } else {
                            val sb = StringBuilder()
                            detectedObjects.forEachIndexed { i, obj ->
                                sb.append("Object ${i + 1}:\n")
                                sb.append("Tracking ID: ${obj.trackingId ?: "N/A"}\n")
                                sb.append("Bounding Box: ${obj.boundingBox}\n")
                                if (obj.labels.isNotEmpty()) {
                                    sb.append("Labels:\n")
                                    obj.labels.forEach { label ->
                                        sb.append(" - ${label.text} (Confidence: ${"%.2f".format(label.confidence)})\n")
                                    }
                                } else {
                                    sb.append("No labels detected.\n")
                                }
                                sb.append("\n")
                            }
                            resultText.text = sb.toString()
                        }
                        copyTextBtn.visibility = android.view.View.VISIBLE
                    }
                    .addOnFailureListener {
                        progressBar.visibility = android.view.View.GONE
                        Toast.makeText(this, "Object detection failed: ${it.message}", Toast.LENGTH_LONG).show()
                    }
            }

            else -> {
                progressBar.visibility = android.view.View.GONE
                Toast.makeText(this, "Invalid mode", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun createImageFile(): File? {
        return try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val storageDir = cacheDir
            File.createTempFile("JPEG_${timeStamp}_", ".jpg", storageDir).apply {
                currentPhotoPath = absolutePath
            }
        } catch (ex: Exception) {
            Toast.makeText(this, "Error creating file: ${ex.message}", Toast.LENGTH_LONG).show()
            null
        }
    }
}

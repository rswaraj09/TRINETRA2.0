package com.example.assistantapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.speech.tts.TextToSpeech
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.util.Log
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign

@Composable
fun CurrencyDetectionScreen(navController: NavHostController = rememberNavController()) {
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    var capturedImage by remember { mutableStateOf<Bitmap?>(null) }
    var currencyResult by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    val tts = remember { mutableStateOf<TextToSpeech?>(null) }
    var noCurrencyDetected by remember { mutableStateOf(false) }
    
    // Track detected currency notes
    var detectedNotes by remember { mutableStateOf<List<Int>>(emptyList()) }
    var totalAmount by remember { mutableStateOf(0) }
    var lastDetectionTimestamp = remember { mutableStateOf(0L) }
    var lastGuidanceTimestamp = remember { mutableStateOf(0L) }
    var lastFeedbackTimestamp = remember { mutableStateOf(0L) }
    
    val currencyDetector = remember { 
        try {
            CurrencyDetector(context)
        } catch (e: Exception) {
            Log.e("CurrencyDetection", "Error creating CurrencyDetector", e)
            errorMessage = "Error initializing currency detection: ${e.message}"
            null
        }
    }
    
    // Create imageCapture reference at this level so it can be accessed by the capture button
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    
    // Create a variable to store the callback function for processing captured images
    val processCapturedImage: (Bitmap) -> Unit = remember {
        { bitmap: Bitmap ->
            if (currencyDetector == null) {
                errorMessage = "Currency detection is not initialized properly"
            } else {
                capturedImage = bitmap
                coroutineScope.launch {
                    // Don't clear previous results when doing auto-detection
                    // Only clear error messages
                    errorMessage = ""
                    isProcessing = true
                    
                    // Process image with CurrencyDetector
                    currencyDetector.let { detector ->
                        detector.detectCurrency(
                            bitmap,
                            { result ->
                                currencyResult = result
                                isProcessing = false
                                
                                // Check if no currency is detected
                                noCurrencyDetected = result.contains("No currency detected", ignoreCase = true)
                                
                                // Extract all detected denominations
                                val notesRegex = "(\\d+)\\s+Rupee".toRegex()
                                val matchResults = notesRegex.findAll(result)
                                
                                // Parse the detected denominations
                                val detectedDenominations = matchResults.mapNotNull { 
                                    it.groupValues[1].toIntOrNull() 
                                }.filter { it > 0 }.toList()
                                
                                // Get total value if mentioned in the result
                                val totalRegex = "Total value: (\\d+) Rupees".toRegex()
                                val totalMatch = totalRegex.find(result)
                                val resultTotal = totalMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                                
                                // Log all detected notes in this frame for debugging
                                Log.d("CurrencyDetection", "Detected in frame: $detectedDenominations")
                                
                                if (detectedDenominations.isNotEmpty()) {
                                    // Always consider it a new detection for auto-detection
                                    // to provide immediate feedback
                                    val currentTime = System.currentTimeMillis()
                                    
                                    // Track newly detected notes that we haven't seen before
                                    val newNotes = mutableListOf<Int>()
                                    
                                    // Group notes by denomination in this frame for better analysis
                                    val currentDenominationCounts = detectedDenominations.groupBy { it }
                                        .mapValues { it.value.size }
                                    
                                    // Log counts by denomination
                                    Log.d("CurrencyDetection", "Counts in frame: $currentDenominationCounts")
                                    
                                    // Get existing notes by denomination for smart detection
                                    val existingNotesByDenomination = detectedNotes.groupBy { it }
                                    
                                    // For each denomination in the current frame
                                    for ((denomination, count) in currentDenominationCounts) {
                                        // How many of this denomination do we already have?
                                        val existingCount = existingNotesByDenomination[denomination]?.size ?: 0
                                        
                                        // Only add if we're seeing MORE notes of this denomination than before
                                        if (count > existingCount) {
                                            // Add only the newly detected notes (difference)
                                            val numNewNotes = count - existingCount
                                            
                                            // Add each new note of this denomination
                                            repeat(numNewNotes) {
                                                newNotes.add(denomination)
                                            }
                                        }
                                    }
                                    
                                    // Only update if we found new notes
                                    if (newNotes.isNotEmpty()) {
                                        // Update tracked notes with new ones
                                        detectedNotes = detectedNotes + newNotes
                                        
                                        // Update total amount from the sum of all detected notes
                                        totalAmount = detectedNotes.sum()
                                        
                                        // Group the newly detected notes by denomination for better reporting
                                        val newNotesByDenomination = newNotes.groupBy { it }
                                        
                                        // Prepare message about the new notes
                                        val newNotesMessage = if (newNotesByDenomination.size == 1 && newNotes.size == 1) {
                                            // Single new note of one denomination
                                            "Detected new ${newNotes[0]} Rupee note"
                                        } else {
                                            // Multiple new notes, possibly of different denominations
                                            val notesDescription = newNotesByDenomination.entries
                                                .sortedByDescending { it.key } // Sort by denomination value (highest first)
                                                .map { entry -> 
                                                    if (entry.value.size > 1) {
                                                        "${entry.value.size} ${entry.key} Rupee notes"
                                                    } else {
                                                        "one ${entry.key} Rupee note"
                                                    }
                                                }
                                                .joinToString(", ")
                                            
                                            "Detected new notes: $notesDescription"
                                        }
                                        
                                        // Speak the current notes and total
                                        val message = "$newNotesMessage. " +
                                                "You now have ${detectedNotes.size} notes worth a total of $totalAmount Rupees."
                                        
                                        tts.value?.speak(message, TextToSpeech.QUEUE_FLUSH, null, null)
                                    } else if (!result.contains("No currency detected", ignoreCase = true)) {
                                        // No need to tell user about already detected notes
                                        // Silent operation when seeing already counted notes
                                    }
                                } else if (result.contains("No currency detected", ignoreCase = true)) {
                                    // Check if we need to provide guidance
                                    val currentTime = System.currentTimeMillis()
                                    val timeSinceLastGuidance = currentTime - lastGuidanceTimestamp.value
                                    
                                    // Provide guidance every 3 seconds to match the capture interval
                                    if (timeSinceLastGuidance > 3000) {
                                        tts.value?.speak(
                                            "No currency detected. Please point your camera at a currency note and hold steady.",
                                            TextToSpeech.QUEUE_FLUSH,
                                            null,
                                            null
                                        )
                                        lastGuidanceTimestamp.value = currentTime
                                    }
                                } else {
                                    // Only speak other results if it's not "no currency detected"
                                    tts.value?.speak(result, TextToSpeech.QUEUE_FLUSH, null, null)
                                }
                            },
                            { error ->
                                errorMessage = error
                                isProcessing = false
                                // Don't speak errors during continuous detection to avoid too much talking
                            }
                        )
                    }
                }
            }
        }
    }
    
    // Function to capture a photo and analyze it
    val captureAndAnalyze: () -> Unit = {
        // Reset state for a new capture
        currencyResult = ""
        errorMessage = ""
        isProcessing = true
        
        // Trigger image capture for currency detection
        tts.value?.speak(
            "Manually capturing image for currency detection. Please hold the camera steady.",
            TextToSpeech.QUEUE_FLUSH,
            null,
            null
        )
        
        // Take a new picture for analysis
        try {
            val outputOptions = ImageCapture.OutputFileOptions.Builder(createTempFile(context)).build()
            imageCapture.takePicture(
                outputOptions,
                cameraExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        try {
                            val savedUri = outputFileResults.savedUri ?: return
                            val bitmap = BitmapFactory.decodeFile(savedUri.path)
                            if (bitmap != null) {
                                lastDetectionTimestamp.value = 0 // Reset timestamp to force this to be treated as a new detection
                                processCapturedImage(bitmap)
                            } else {
                                errorMessage = "Failed to process image"
                                isProcessing = false
                                tts.value?.speak(
                                    "Failed to process image. Please try again with better lighting.",
                                    TextToSpeech.QUEUE_FLUSH,
                                    null,
                                    null
                                )
                            }
                        } catch (e: Exception) {
                            Log.e("CurrencyDetectionCamera", "Error processing saved image: ${e.message}")
                            errorMessage = "Error processing image: ${e.message}"
                            isProcessing = false
                            tts.value?.speak(
                                "Error processing the image. Please try again with better lighting and ensure the currency note is clearly visible.",
                                TextToSpeech.QUEUE_FLUSH,
                                null,
                                null
                            )
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e("CurrencyDetectionCamera", "Error taking picture: ${exception.message}")
                        errorMessage = "Error capturing image: ${exception.message}"
                        isProcessing = false
                        tts.value?.speak(
                            "Error capturing image. Please make sure the camera is not obstructed and try again.",
                            TextToSpeech.QUEUE_FLUSH,
                            null,
                            null
                        )
                    }
                }
            )
        } catch (e: Exception) {
            Log.e("CurrencyDetectionCamera", "Error taking picture: ${e.message}")
            errorMessage = "Error capturing image: ${e.message}"
            isProcessing = false
            tts.value?.speak(
                "Error capturing image. Please make sure the app has camera permissions and try again.",
                TextToSpeech.QUEUE_FLUSH,
                null,
                null
            )
        }
    }
    
    // Function to report total currency amount
    val reportTotal = {
        if (detectedNotes.isEmpty()) {
            tts.value?.speak(
                "No currency notes have been detected yet.",
                TextToSpeech.QUEUE_FLUSH,
                null,
                null
            )
        } else {
            // Group by denomination and sort in descending order (highest value first)
            val notesByDenomination = detectedNotes.groupBy { it }
                .toList()
                .sortedByDescending { it.first } // Sort by denomination value
                .map { "${it.second.size} × ${it.first}" }
                .joinToString(", ")
            
            // Prepare a detailed breakdown message
            val message = if (detectedNotes.size == 1) {
                "You have one ${detectedNotes[0]} Rupee note. Total is ${detectedNotes[0]} Rupees."
            } else {
                "You have $notesByDenomination. " +
                "That's ${detectedNotes.size} notes with a total value of $totalAmount Rupees."
            }
            
            tts.value?.speak(
                message,
                TextToSpeech.QUEUE_FLUSH,
                null,
                null
            )
        }
    }
    
    // Function to reset tracked currency
    val resetTracking = {
        detectedNotes = emptyList()
        totalAmount = 0
        tts.value?.speak(
            "Currency tracking reset. Ready to detect new notes.",
            TextToSpeech.QUEUE_FLUSH,
            null,
            null
        )
    }
    
    // Check for permissions
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    
    // Initialize TTS
    LaunchedEffect(context) {
        tts.value = TextToSpeech(context) { status ->
            if (status != TextToSpeech.ERROR) {
                tts.value?.language = Locale.US
                tts.value?.setSpeechRate(0.9f)
                tts.value?.setPitch(1.0f)
                
                // Welcome message with clear explanation of 3-second interval
                tts.value?.speak(
                    "Currency detection active. Your phone will automatically take pictures every 3 seconds to detect currency. " +
                    "Point your camera at any currency note and hold steady. The app can detect multiple notes of different values in a single frame. " +
                    "You will hear the value when a new note is detected. " +
                    "Double tap to hear total amount, long press to reset.",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    null
                )
            }
        }
    }
    
    // Clean up resources
    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            tts.value?.stop()
            tts.value?.shutdown()
            currencyDetector?.close()
        }
    }
    
    // Handle back button press
    BackHandler {
        tts.value?.stop()
        navController.popBackStack()
    }
    
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        if (hasPermission) {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                // Show camera preview
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = { offset ->
                                    // Report total on double tap
                                    reportTotal()
                                },
                                onLongPress = { offset ->
                                    // Reset on long press
                                    resetTracking()
                                }
                            )
                        }
                ) {
                    CurrencyDetectionCamera(
                        onImageCaptured = processCapturedImage,
                        cameraExecutor = cameraExecutor,
                        imageCapture = imageCapture,
                        noCurrencyDetected = noCurrencyDetected
                    )
                }
                
                // Currency tracking display
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.MonetizationOn,
                        contentDescription = "Currency Detection Active",
                        modifier = Modifier.size(64.dp),
                        tint = Color(0xFFFF9800) // Orange color
                    )
                    
                    Text(
                        text = if (isProcessing) "Analyzing" else "Ready",
                        color = Color.White,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    
                    // Display count of notes and total amount
                    if (detectedNotes.isNotEmpty()) {
                        // Group notes by denomination for better display
                        val notesByDenomination = detectedNotes.groupBy { it }
                            .map { "${it.value.size} × ${it.key}" }
                            .joinToString(", ")
                            
                        Text(
                            text = notesByDenomination,
                            color = Color.White,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        
                        Text(
                            text = "₹$totalAmount total",
                            color = Color(0xFF4CAF50), // Green color for total amount
                            fontSize = 22.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                
                // Help text for gestures
                Text(
                    text = "Double tap: Hear total | Long press: Reset",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 16.dp, end = 16.dp)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(8.dp)
                )
                
                // Back button
                IconButton(
                    onClick = {
                        tts.value?.stop()
                        navController.popBackStack()
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
                
                // Button to capture a photo and analyze it
                Button(
                    onClick = captureAndAnalyze,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp)
                        .size(width = 200.dp, height = 60.dp) // Make button larger
                ) {
                    Text("Detect Currency", fontSize = 18.sp)
                }
                
                // Additional button for total reporting
                Button(
                    onClick = {
                        reportTotal()
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 32.dp, end = 16.dp)
                ) {
                    Text("Report Total", fontSize = 16.sp)
                }
                
                // Button to reset tracking
                Button(
                    onClick = {
                        resetTracking()
                    },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(bottom = 32.dp, start = 16.dp)
                ) {
                    Text("Reset", fontSize = 16.sp)
                }
                
                // Result display area
                if (currencyResult.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 80.dp, start = 16.dp, end = 16.dp)
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(16.dp)
                    ) {
                        Text(
                            text = currencyResult,
                            color = Color.White,
                            fontSize = 20.sp
                        )
                    }
                }

                // Error message display
                if (errorMessage.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 80.dp, start = 16.dp, end = 16.dp)
                            .background(Color.Red.copy(alpha = 0.7f))
                            .padding(16.dp)
                    ) {
                        Text(
                            text = errorMessage,
                            color = Color.White,
                            fontSize = 16.sp
                        )
                    }
                }
                
                // No need for visual "No Currency Detected" guidance for visually impaired users
                // Audio feedback is provided in the processCapturedImage function instead
            }
        } else {
            // No camera permission UI
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Camera permission is required for currency detection",
                    color = Color.White,
                    modifier = Modifier.padding(16.dp)
                )
                
                Button(
                    onClick = {
                        // Request permission logic would go here
                    },
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text("Grant Permission")
                }
            }
        }
    }
}

@Composable
fun CurrencyDetectionCamera(
    onImageCaptured: (Bitmap) -> Unit,
    cameraExecutor: ExecutorService,
    imageCapture: ImageCapture,
    noCurrencyDetected: Boolean
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    var lastAnalysisTimestamp = remember { 0L }
    val analysisCooldown = 3000L // 3 seconds between automatic analyses for visually impaired users
    var isDetecting by remember { mutableStateOf(false) }

    val preview = Preview.Builder().build()
    val previewView = remember { PreviewView(context) }
    
    // Function to capture a photo automatically for analysis
    fun takeAutoAnalysisPicture() {
        try {
            lastAnalysisTimestamp = System.currentTimeMillis()
            isDetecting = true
            val outputOptions = ImageCapture.OutputFileOptions.Builder(createTempFile(context)).build()
            imageCapture.takePicture(
                outputOptions,
                cameraExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        try {
                            val savedUri = outputFileResults.savedUri ?: return
                            val bitmap = BitmapFactory.decodeFile(savedUri.path)
                            if (bitmap != null) {
                                onImageCaptured(bitmap)
                            }
                        } catch (e: Exception) {
                            Log.e("CurrencyDetectionCamera", "Error processing auto analysis image: ${e.message}")
                        } finally {
                            isDetecting = false
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e("CurrencyDetectionCamera", "Error taking auto analysis picture: ${exception.message}")
                        isDetecting = false
                    }
                }
            )
        } catch (e: Exception) {
            Log.e("CurrencyDetectionCamera", "Error taking auto analysis picture: ${e.message}")
            isDetecting = false
        }
    }

    LaunchedEffect(Unit) {
        try {
            val cameraProvider = ProcessCameraProvider.getInstance(context).get()
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture
            )
            preview.setSurfaceProvider(previewView.surfaceProvider)
            
            // Take initial picture immediately when the screen loads
            kotlinx.coroutines.delay(500) // Short delay to ensure camera is ready
            takeAutoAnalysisPicture()
            
            // Set up automatic analysis timer - exactly every 3 seconds
            coroutineScope.launch {
                while (true) {
                    // Wait until the full 3 seconds have passed
                    val timeToWait = analysisCooldown - (System.currentTimeMillis() - lastAnalysisTimestamp)
                    if (timeToWait > 0) {
                        kotlinx.coroutines.delay(timeToWait)
                    }
                    
                    // Only take picture if not currently detecting
                    if (!isDetecting) {
                        takeAutoAnalysisPicture()
                    } else {
                        // If still detecting, wait a short time and try again
                        kotlinx.coroutines.delay(500)
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e("CurrencyDetectionCamera", "Error setting up camera: ${e.message}")
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Camera preview
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        
        // Status indicator (no visual alert elements)
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = if (isDetecting) "Detecting Currency..." else "Auto-Detection Active",
                color = Color.White,
                fontSize = 14.sp
            )
        }
    }
}

private fun createTempFile(context: android.content.Context): File {
    val tempDir = context.cacheDir
    return File.createTempFile("currency_detection_", ".jpg", tempDir).apply {
        deleteOnExit()
    }
} 
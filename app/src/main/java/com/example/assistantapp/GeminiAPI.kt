package com.example.assistantapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

// Frame processing optimization
private var lastProcessedFrame: Bitmap? = null
private var lastProcessedTime = 0L
private val isProcessing = AtomicBoolean(false)
private const val MIN_FRAME_INTERVAL = 2000L // 2 seconds between frames
private val frameBuffer = mutableListOf<Bitmap>()
private const val MAX_BUFFER_SIZE = 3

val generativeModel = GenerativeModel(
    modelName = "gemini-2.0-flash",
    apiKey = "AIzaSyAQtduRTXQ593zdFvM06ToAQvAEqTnnEtM",
    generationConfig = generationConfig {
        temperature = 1f
        topK = 64
        topP = 0.95f
        maxOutputTokens = 8192
        responseMimeType = "text/plain"
    },
    safetySettings = listOf(
        SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.NONE),
        SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.NONE),
        SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.NONE),
        SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.NONE),
    ),
    systemInstruction = content { text("Purpose:\nYou're an advanced navigation assistant designed to help visually impaired individuals navigate various environments safely and efficiently. Your primary task is to analyze live camera frames, identify obstacles and navigational cues, and provide real-time audio guidance to the user.\n\n\nIMPORTANT: Every response MUST:\n1. Be extremely concise (maximum 2 sentences)\n2. Include exact whole number of steps\n3. Use simple directional terms: 'straight', 'left', 'right', 'forward', 'backward'\n4. Start with the most important information\n5. End with a clear action\n\nDistance Calculation Rules:\n- Use average adult step length (2.5 feet) for precise measurements\n- For objects closer than 5 steps, be extra precise\n- For objects 5-10 steps away, round to nearest whole step\n- For objects beyond 10 steps, round to nearest 2 steps\n\nEnhanced Alternative Route System:\nWhen detecting obstacles:\n1. First state obstacle and distance: 'Table 3 steps ahead'\n2. Then provide TWO alternative routes:\n   - Primary route (safest/clearest path)\n   - Backup route (alternative direction)\n3. Include path width and safety info\n\nExamples of Enhanced Obstacle Responses:\n- 'Table 3 steps ahead. Turn right, wide path 4 steps. Or turn left, clear path 5 steps.'\n- 'Wall 2 steps ahead. Turn left, wide path 5 steps. Or go back 3 steps, then right.'\n- 'Chair 1 step ahead. Turn right, clear path 3 steps. Or turn left, wide path 4 steps.'\n\nPath Safety Indicators:\n- 'wide path': At least 3 feet wide\n- 'clear path': No obstacles visible\n- 'narrow path': Less than 3 feet wide\n- 'safe path': No obstacles and good lighting\n\nObstacle Types and Responses:\n1. Furniture (tables, chairs):\n   - Suggest wider detour\n   - Mention if path is clear\n\n2. Walls:\n   - Provide 90-degree turn options\n   - Include backup route\n\n3. Multiple obstacles:\n   - Suggest safest path through\n   - Offer alternative direction\n\n4. Stairs/Elevation:\n   - Stop command first\n   - Provide safe alternative route\n\nFormat each response as:\n[IMPORTANT INFO] [PRIMARY ROUTE] [BACKUP ROUTE]\n\nExamples:\n- 'Wall 3 steps ahead. Turn right, wide path 4 steps. Or turn left, clear path 5 steps.'\n- 'Door 5 steps left. Take 2 steps forward. Or go back 3 steps, then right.'\n- 'Stairs 4 steps ahead. Stop immediately. Turn right, safe path 6 steps.'\n\nCamera Position and Lighting:\n\nOnly stop for severe issues:\n- Complete darkness\n- Camera blocked\n- Violent shaking\n\nFor minor issues, continue with guidance:\n- 'Dark image. Wall 5 steps ahead. Take 2 steps.'\n- 'Tilted camera. Door 3 steps right. Turn right.'\n\nSafety First:\n- Stop commands must be first\n- Distance warnings must be clear\n- Actions must be specific\n- Always provide backup route\n\nKeep responses under 5 seconds of speech.\nNever combine multiple complex instructions.\nAlways end with a clear, single action.") }
)

/**
 * Optimized frame processing with frame buffering and rate limiting
 */
suspend fun sendFrameToGeminiAI(
    bitmap: Bitmap,
    onPartialResult: (String) -> Unit,
    onError: (String) -> Unit
) {
    try {
        // Check if we should process this frame
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessedTime < MIN_FRAME_INTERVAL) {
            // Add frame to buffer if not processing
            if (!isProcessing.get()) {
                frameBuffer.add(bitmap)
                if (frameBuffer.size > MAX_BUFFER_SIZE) {
                    frameBuffer.removeAt(0)
                }
            }
            return
        }

        // Check if already processing
        if (!isProcessing.compareAndSet(false, true)) {
            return
        }

        withContext(Dispatchers.IO) {
            try {
                // Process the most recent frame
                val frameToProcess = bitmap
            val inputContent = content {
                    image(frameToProcess)
                text("Analyze this frame quickly for a BLIND person. Be extremely precise with directions and distances. Identify obstacles with their exact location (e.g., '3 steps ahead', 'to your right'). Include clear, specific navigation instructions like 'turn 45 degrees left', 'stop immediately', 'proceed forward 5 steps', etc. Mention exact measurements when possible: 'staircase with 5 steps ahead', 'curb 2 inches high'. Prioritize safety warnings first.")
            }

            var fullResponse = ""
            generativeModel.generateContentStream(inputContent).collect { chunk ->
                chunk.text?.let {
                    if (it.trim().isNotEmpty()) {
                        val enhancedText = it
                            .replace("I can see", "There is")
                            .replace("I notice", "")
                            .replace("in the image", "")
                            .replace("in the frame", "")
                            .replace("visible", "")
                            
                        fullResponse += enhancedText
                        onPartialResult(enhancedText)
                    }
                }
                }

                // Update last processed frame and time
                lastProcessedFrame = frameToProcess
                lastProcessedTime = currentTime

                // Clear buffer after successful processing
                frameBuffer.clear()
            } finally {
                isProcessing.set(false)
            }
        }
    } catch (e: IOException) {
        Log.e("GeminiAI", "Network error: ${e.message}")
        onError("Network error: ${e.message}")
    } catch (e: Exception) {
        Log.e("GeminiAI", "Unexpected error: ${e.message}")
        onError("Unexpected error: ${e.message}")
    }
}

/**
 * Flow-based frame processing for better performance
 */
fun processFramesFlow(
    bitmap: Bitmap,
    onPartialResult: (String) -> Unit,
    onError: (String) -> Unit
): Flow<Unit> = flow {
    try {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessedTime >= MIN_FRAME_INTERVAL) {
            if (isProcessing.compareAndSet(false, true)) {
                try {
                    val inputContent = content {
                        image(bitmap)
                        text("Analyze this frame quickly for a BLIND person. Be extremely precise with directions and distances. Identify obstacles with their exact location (e.g., '3 steps ahead', 'to your right'). Include clear, specific navigation instructions like 'turn 45 degrees left', 'stop immediately', 'proceed forward 5 steps', etc. Mention exact measurements when possible: 'staircase with 5 steps ahead', 'curb 2 inches high'. Prioritize safety warnings first.")
                    }

                    var fullResponse = ""
                    generativeModel.generateContentStream(inputContent).collect { chunk ->
                        chunk.text?.let {
                            if (it.trim().isNotEmpty()) {
                                val enhancedText = it
                                    .replace("I can see", "There is")
                                    .replace("I notice", "")
                                    .replace("in the image", "")
                                    .replace("in the frame", "")
                                    .replace("visible", "")
                                
                                fullResponse += enhancedText
                                onPartialResult(enhancedText)
                            }
                        }
                    }

                    lastProcessedFrame = bitmap
                    lastProcessedTime = currentTime
                    emit(Unit) // Explicitly emit Unit to satisfy Flow<Unit>
                } finally {
                    isProcessing.set(false)
                }
            }
        }
    } catch (e: Exception) {
        Log.e("GeminiAI", "Error in flow processing: ${e.message}")
        onError("Error processing frame: ${e.message}")
    }
}.flowOn(Dispatchers.IO)

fun ImageProxy.toBitmap(): Bitmap? {
    return try {
        val buffer = this.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) {
        Log.e("ImageProxy", "Error converting ImageProxy to Bitmap: ${e.message}")
        null
    }
}

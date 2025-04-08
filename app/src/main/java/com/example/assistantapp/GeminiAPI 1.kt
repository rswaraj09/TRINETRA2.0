package com.example.assistantapp

import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Cache for storing recent frame data
private var recentFrameData = mutableListOf<String>()
private const val MAX_FRAME_HISTORY = 3

/**
 * QuestionAnswerModel handles general questions about the user's surroundings
 * and provides detailed descriptions based on navigation data.
 */
val QuestionAnswerModel = GenerativeModel(
    modelName = "gemini-2.0-flash",
    apiKey = "AIzaSyAQtduRTXQ593zdFvM06ToAQvAEqTnnEtM",
    generationConfig = generationConfig {
        temperature = 0.5f  // Balanced temperature for faster, focused responses
        topK = 32  // Reduced for faster responses
        topP = 0.8f
        maxOutputTokens = 1024  // Reduced for faster responses
        responseMimeType = "text/plain"
    },
    safetySettings = listOf(
        SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.NONE),
        SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.NONE),
        SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.NONE),
        SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.NONE),
    ),
    systemInstruction = content { text("""
        You are a fast, efficient AI assistant helping visually impaired users understand their surroundings.
        Your role is to:
        1. Provide quick, concise answers based on the current and recent environment data
        2. Focus on immediate surroundings and recent changes
        3. Prioritize safety-critical information
        4. Use simple, direct language
        5. Include specific measurements and directions
        
        Response Guidelines:
        - Keep responses under 2-3 sentences
        - Start with the most important information
        - Include exact distances and directions
        - Mention any recent changes in the environment
        - Prioritize safety warnings
        
        Format your response as:
        [Immediate Action/Status] [Location/Context] [Additional Details]
        
        Example: "Stop. There is a chair 2 steps ahead. The chair is brown and facing you."
    """) }
)

/**
 * Updates the recent frame history
 */
fun updateFrameHistory(frameData: String) {
    recentFrameData.add(frameData)
    if (recentFrameData.size > MAX_FRAME_HISTORY) {
        recentFrameData.removeAt(0)
    }
}

/**
 * Sends a message to the AI and gets a response
 * @param message The user's question or query
 * @param frameData Optional navigation data from the camera
 * @param onResponse Callback for successful responses
 * @param onError Callback for error handling
 */
suspend fun sendQuestionToAI(
    message: String,
    frameData: String? = null,
    onResponse: (String) -> Unit,
    onError: (String) -> Unit
) {
    try {
        withContext(Dispatchers.IO) {
            // Update frame history if new data is available
            frameData?.let { updateFrameHistory(it) }
            
            // Build context from recent frames
            val contextBuilder = StringBuilder()
            if (recentFrameData.isNotEmpty()) {
                contextBuilder.append("Recent environment data:\n")
                recentFrameData.forEachIndexed { index, data ->
                    contextBuilder.append("Frame ${index + 1}: $data\n")
                }
            }
            
            // Add current frame data if available
            frameData?.let {
                contextBuilder.append("\nCurrent environment data: $it\n")
            }
            
            val inputContent = content {
                text(buildString {
                    append(contextBuilder.toString())
                    append("\nUser question: $message\n")
                    append("Provide a quick, specific answer focusing on the user's immediate surroundings.")
                })
            }

            var fullResponse = ""
            QuestionAnswerModel.generateContentStream(inputContent).collect { chunk ->
                chunk.text?.let {
                    if (it.trim().isNotEmpty()) {
                        fullResponse += it
                        onResponse(it)
                    }
                }
            }

            // If no response was generated
            if (fullResponse.trim().isEmpty()) {
                onError("I couldn't generate a response. Please try rephrasing your question.")
            }
        }
    } catch (e: Exception) {
        Log.e("QuestionAnswerModel", "Error: ${e.message}")
        onError("An error occurred: ${e.message}")
    }
}

/**
 * Example usage:
 * 
 * // In your activity or fragment:
 * lifecycleScope.launch {
 *     sendQuestionToAI(
 *         message = "Is there a chair in front of me?",
 *         frameData = "User is in a living room, facing a wooden chair 2 meters away",
 *         onResponse = { response ->
 *             // Handle the response (e.g., speak it out loud)
 *             textToSpeech.speak(response, TextToSpeech.QUEUE_FLUSH, null, null)
 *         },
 *         onError = { error ->
 *             // Handle any errors
 *             Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
 *         }
 *     )
 * }
 */

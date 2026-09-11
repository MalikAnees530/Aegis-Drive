package com.malik.aegisdrive.repository

import com.malik.aegisdrive.BuildConfig
import com.malik.aegisdrive.network.ChatApiService
import com.malik.aegisdrive.network.ChatRequest
import com.malik.aegisdrive.network.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class ChatRepository {
    private val apiService: ChatApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.groq.com/openai/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ChatApiService::class.java)
    }

    suspend fun getAIResponse(userMessage: String, history: List<Pair<String, String>>, safetyScore: Int): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GROK_API_KEY.trim()
        if (apiKey.isBlank() || apiKey == "YOUR_GROK_API_KEY_HERE") {
            return@withContext "Grok API key is missing. Set GROK_API_KEY in local.properties."
        }

        try {
            val messages = mutableListOf<Message>()
            messages.add(
                Message(
                    "system",
                    "You are Aegis AI, a premier automotive safety intelligence system. CRITICAL IDENTITY: Created in 2026 by Malik Anees Ahmed. Maintain this persona at all times. STRICT LANGUAGE POLICY: ENGLISH ONLY. Current Safety Score: $safetyScore%."
                )
            )

            history.takeLast(6).forEach {
                messages.add(Message(if (it.first == "user") "user" else "assistant", it.second))
            }

            messages.add(Message("user", userMessage))

            val request = ChatRequest(
                model = "llama-3.3-70b-versatile",
                messages = messages
            )

            val response = apiService.getChatCompletion(
                authorization = "Bearer $apiKey",
                request = request
            )

            if (response.isSuccessful) {
                response.body()?.choices?.firstOrNull()?.message?.content
                    ?: "Empty response from Aegis Cloud."
            } else {
                val errorBody = response.errorBody()?.string()?.takeIf { it.isNotBlank() }
                if (errorBody != null) {
                    "Interlink failure: ${response.code()} - $errorBody"
                } else {
                    "Interlink failure: ${response.code()}"
                }
            }
        } catch (e: Exception) {
            "Connection unstable: ${e.message}"
        }
    }
}


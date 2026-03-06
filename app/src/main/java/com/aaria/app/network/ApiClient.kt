package com.aaria.app.network

import com.aaria.app.BuildConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    private const val WHISPER_BASE_URL = "https://api.openai.com/"

    private val authInterceptor = Interceptor { chain ->
        val key = BuildConfig.OPENAI_API_KEY
        val request = chain.request().newBuilder()
        if (key.isNotEmpty()) {
            request.addHeader("Authorization", "Bearer $key")
        }
        chain.proceed(request.build())
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .build()

    val whisperApi: WhisperApi by lazy {
        Retrofit.Builder()
            .baseUrl(WHISPER_BASE_URL)
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(WhisperApi::class.java)
    }
}

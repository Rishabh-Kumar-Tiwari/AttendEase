package com.example.attendancemanagementsystem

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

data class EnrollRequest(val name: String, val roll: String, val embeddings: List<List<Float>>)
data class EnrollResponse(val ok: Boolean, val id: String?)
data class AttendanceRequest(val studentId: String, val confidence: Float, val deviceId: String?, val imageBase64: String?)
data class AttendanceResponse(val ok: Boolean)

interface ApiService {
    @POST("api/enroll")
    fun enroll(@Body request: EnrollRequest): Call<EnrollResponse>

    @POST("api/attendance")
    fun attendance(@Body request: AttendanceRequest): Call<AttendanceResponse>
}

object NetworkClient {
    private const val BASE_URL = "http://10.0.2.2:3000/"

    private val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }
    private val client = OkHttpClient.Builder()
        .addInterceptor(logging)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val api: ApiService = retrofit.create(ApiService::class.java)
}

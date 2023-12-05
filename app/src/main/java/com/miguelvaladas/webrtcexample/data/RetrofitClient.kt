package com.miguelvaladas.webrtcexample.data

import com.miguelvaladas.webrtcexample.data.stream.service.StreamService
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    private val okHttpClient = OkHttpClient.Builder()
        .hostnameVerifier { _, _ -> true }
        .readTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://rclive-microservice-loadbalancer-1371406789.eu-central-1.elb.amazonaws.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .client(okHttpClient)
        .build()

    val streamService: StreamService = retrofit.create(StreamService::class.java)
}

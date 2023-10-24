package com.miguelvaladas.webrtcexample.data.stream.service

import com.miguelvaladas.webrtcexample.data.stream.model.Credentials
import com.miguelvaladas.webrtcexample.data.stream.model.Stream
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.POST

interface StreamService {


    @POST("api/v1/stream/3121d12w")
    suspend fun fetchStream(): Response<Stream>

    @GET("api/v1/aws/credentials?role=MASTER")
    suspend fun fetchCredentials(): Response<Credentials>

}

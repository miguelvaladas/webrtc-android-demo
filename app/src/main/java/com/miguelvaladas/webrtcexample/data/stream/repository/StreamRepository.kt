package com.miguelvaladas.webrtcexample.data.stream.repository

import com.miguelvaladas.webrtcexample.data.RetrofitClient
import com.miguelvaladas.webrtcexample.data.stream.model.Credentials
import com.miguelvaladas.webrtcexample.data.stream.model.Stream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class StreamRepository {

    private val streamService = RetrofitClient.streamService

    fun getStream(): Flow<Stream> {
        return flow {
            val response = streamService.fetchStream()
            if (response.isSuccessful && response.body() != null) {
                emit(response.body()!!)
            } else {
                throw Exception(response.errorBody()?.string() ?: "Unknown error")
            }
        }
    }

    fun getCredentials(): Flow<Credentials> {
        return flow {
            val response = streamService.fetchCredentials()
            if (response.isSuccessful && response.body() != null) {
                emit(response.body()!!)
            } else {
                throw Exception(response.errorBody()?.string() ?: "Unknown error")
            }
        }
    }
}

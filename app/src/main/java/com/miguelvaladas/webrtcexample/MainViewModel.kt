package com.miguelvaladas.webrtcexample

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miguelvaladas.webrtcexample.data.stream.model.Credentials
import com.miguelvaladas.webrtcexample.data.stream.model.Stream
import com.miguelvaladas.webrtcexample.data.stream.repository.StreamRepository
import com.miguelvaladas.webrtcexample.signer.AwsV4Signer
import com.miguelvaladas.webrtcexample.stream.WebRtcClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.net.URI

class MainViewModel(
    private val repository: StreamRepository,
    private val webRtcClient: WebRtcClient
) : ViewModel() {

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming

    private val _stream = MutableStateFlow<Stream?>(null)
    val stream: StateFlow<Stream?> = _stream

    private val _credentials = MutableStateFlow<Credentials?>(null)
    val credentials: StateFlow<Credentials?> = _credentials

    init {
        viewModelScope.launch {
            fetchCredentials()
            fetchStreamData()
        }

    }

    private fun fetchStreamData() {
        viewModelScope.launch {
            repository.getStream().catch { e ->
                Log.e(TAG, e.stackTraceToString())
            }.collect { stream ->
                _stream.value = stream
                Log.i(TAG, stream.toString())
            }
        }
    }

    private fun fetchCredentials() {
        viewModelScope.launch {
            repository.getCredentials().catch { e ->
                Log.e(TAG, e.stackTraceToString())
            }.collect { credentials ->
                _credentials.value = credentials
                Log.i(TAG, credentials.toString())
            }
        }
    }

    fun startStopStream() {
        if (!_isStreaming.value) {
            val signedUrl = signWssEndpoint(
                stream.value?.signalingChannel?.masterEndpoint!!,
                stream.value?.signalingChannel?.arn!!
            )
            Log.i(TAG, "uri: ${signedUrl.toASCIIString()}")

            webRtcClient.setSignalingChannel(stream.value!!.signalingChannel)
            webRtcClient.setSignalingUrl(signedUrl)
            webRtcClient.startConnection()
        } else {
            webRtcClient.closeConnection()
        }
        _isStreaming.value = !_isStreaming.value
    }

    private fun signWssEndpoint(endpoint: String, channelArn: String): URI {
        val mEndpoint = "$endpoint?X-Amz-ChannelARN=$channelArn"

        return AwsV4Signer.sign(
            URI.create(mEndpoint),
            credentials.value?.accessKey,
            credentials.value?.secretKey,
            credentials.value?.token,
            URI.create(endpoint),
            "eu-central-1"
        )
    }

    companion object {
        const val TAG = "MainViewModel"
    }
}
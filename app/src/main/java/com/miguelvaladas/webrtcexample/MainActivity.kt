package com.miguelvaladas.webrtcexample

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.miguelvaladas.webrtcexample.data.stream.repository.StreamRepository
import com.miguelvaladas.webrtcexample.stream.WebRtcClient
import com.miguelvaladas.webrtcexample.ui.theme.WebRTCExampleTheme

class MainActivity : ComponentActivity() {

    private lateinit var streamRepository: StreamRepository
    private lateinit var webRtcClient: WebRtcClient
    private lateinit var mainViewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkPermission(this)

        streamRepository = StreamRepository()
        webRtcClient = WebRtcClient(this)
        mainViewModel = MainViewModel(streamRepository, webRtcClient)

        setContent {
            WebRTCExampleTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    StartStreamButton(mainViewModel)
                }
            }
        }
    }

    private fun checkPermission(activity: Activity) {
        val permissionsToCheck = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.CHANGE_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val permissionsRequired = permissionsToCheck.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsRequired.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, permissionsRequired, 9393)
        }
    }


}

@Composable
fun StartStreamButton(viewModel: MainViewModel) {
    val isStreaming by viewModel.isStreaming.collectAsState()

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Button(
            onClick = {
                viewModel.startStopStream()
            },
            modifier = Modifier.defaultMinSize(minWidth = 150.dp, minHeight = 50.dp)
        ) {
            Text(text = if (isStreaming) "Stop" else "Start")
        }
    }
}

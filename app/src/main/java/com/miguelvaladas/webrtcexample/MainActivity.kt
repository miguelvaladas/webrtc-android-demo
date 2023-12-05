package com.miguelvaladas.webrtcexample

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.miguelvaladas.webrtcexample.data.stream.repository.StreamRepository
import com.miguelvaladas.webrtcexample.stream.WebRtcClient
import com.miguelvaladas.webrtcexample.ui.theme.WebRTCExampleTheme
import org.webrtc.SurfaceViewRenderer

class MainActivity : ComponentActivity() {

    private lateinit var streamRepository: StreamRepository
    private lateinit var webRtcClient: WebRtcClient
    private lateinit var mainViewModel: MainViewModel
    private lateinit var viewRenderer: SurfaceViewRenderer

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allPermissionsGranted = permissions.entries.all { it.value }
            if (allPermissionsGranted)
                initializeAfterPermissionsGranted()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()
    }

    private fun initializeAfterPermissionsGranted() {
        streamRepository = StreamRepository()
        viewRenderer = SurfaceViewRenderer(this)
        webRtcClient = WebRtcClient(this, viewRenderer)
        mainViewModel = MainViewModel(streamRepository, webRtcClient)

        setContent {
            WebRTCExampleTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CameraPreview(viewRenderer)
                    StartStreamButton(mainViewModel)
                }
            }
        }
    }


    private fun checkAndRequestPermissions() {
        val permissionsToCheck = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )

        val permissionsNotGranted = permissionsToCheck.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsNotGranted.isEmpty()) {
            initializeAfterPermissionsGranted()
        } else {
            requestPermissionLauncher.launch(permissionsNotGranted)
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

@Composable
fun CameraPreview(viewRenderer: SurfaceViewRenderer) {
    AndroidView({ viewRenderer }) { view ->
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }
}


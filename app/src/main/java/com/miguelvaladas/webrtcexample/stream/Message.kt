package com.miguelvaladas.webrtcexample.stream

import android.util.Base64
import org.webrtc.SessionDescription


data class Message(
    var action: String,
    var recipientClientId: String,
    var senderClientId: String,
    var messagePayload: String
)

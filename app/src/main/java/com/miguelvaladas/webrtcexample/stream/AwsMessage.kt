package com.miguelvaladas.webrtcexample.stream

import android.util.Base64
import com.google.gson.Gson
import org.webrtc.SessionDescription

data class AwsMessage(
    var action: String,
    var recipientClientId: String,
    var senderClientId: String,
    var messagePayload: String
)

private data class MessagePayLoad(val type: String, val sdp: String)

/**
 * @param sessionDescription SDP description to be converted & sent to signaling service
 * @param recipientClientId - has to be set to null if this is set as viewer
 * @return SDP Answer message to be sent to signaling service
 */
fun createAnswerMessage(
    sessionDescription: SessionDescription,
    recipientClientId: String
): AwsMessage {
    val description = sessionDescription.description
    val encodedString = String(
        Base64.encode(
            Gson().toJson(MessagePayLoad("answer", description!!)).toByteArray(),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
    )

    return AwsMessage(
        "SDP_ANSWER",
        recipientClientId,
        String(),
        encodedString
    )
}
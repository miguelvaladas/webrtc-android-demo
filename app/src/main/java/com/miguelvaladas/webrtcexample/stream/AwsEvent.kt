package com.miguelvaladas.webrtcexample.stream

import android.util.Base64
import android.util.Log
import com.google.gson.JsonParser
import org.webrtc.IceCandidate

private val TAG = "Event"

data class AwsEvent(val senderClientId: String, val messageType: String, val messagePayload: String)

fun parseIceCandidate(event: AwsEvent): IceCandidate? {
    val decode = Base64.decode(event.messagePayload, Base64.DEFAULT)
    val candidateString = String(decode)
    val jsonObject = JsonParser.parseString(candidateString).asJsonObject
    var sdpMid = jsonObject["sdpMid"].toString()
    if (sdpMid.length > 2) {
        sdpMid = sdpMid.substring(1, sdpMid.length - 1)
    }
    val sdpMLineIndex = try {
        jsonObject["sdpMLineIndex"].toString().toInt()
    } catch (e: NumberFormatException) {
        Log.e(TAG, "Invalid sdpMLineIndex")
        return null
    }
    var candidate = jsonObject["candidate"].toString()
    if (candidate.length > 2) {
        candidate = candidate.substring(1, candidate.length - 1)
    }
    return IceCandidate(sdpMid, sdpMLineIndex, candidate)
}

fun parseOfferEvent(offerEvent: AwsEvent): String {
    val s = String(Base64.decode(offerEvent.messagePayload, Base64.DEFAULT))
    val jsonObject = JsonParser.parseString(s).asJsonObject
    return jsonObject["sdp"].asString
}

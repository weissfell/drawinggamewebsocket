package de.weissbeb.data

import de.weissbeb.data.models.Ping
import de.weissbeb.gson
import de.weissbeb.other.Constants
import de.weissbeb.server
import io.ktor.http.cio.websocket.*
import kotlinx.coroutines.*

data class Player(
    val username: String,
    var socket: WebSocketSession,
    val clientId: String,
    var isDrawing: Boolean = false,
    var score: Int = 0,
    var rank: Int = 0
) {

    private var pingJob: Job? = null
    private var pingTime = 0L
    private var pongTime = 0L

    var isOnline = true

    fun startPinging() {
        pingJob?.cancel()
        pingJob = GlobalScope.launch {
            while(true){
                sendPing()
                delay(Constants.PING_FREQUENCY)
            }
        }
    }

    private suspend fun sendPing(){
        pingTime = System.currentTimeMillis()
        socket.send(Frame.Text(gson.toJson(Ping())))
        delay(Constants.PING_FREQUENCY) //give client PING_FREQUENCY ms to respond
        if(pingTime - pongTime > Constants.PING_FREQUENCY) { //not fast enough or no answer at all - dc
            isOnline = false
            server.playerLeft(clientId)
            pingJob?.cancel()
        }
    }

    fun receivedPong(){
        pongTime = System.currentTimeMillis()
        isOnline = true
    }

    fun disconnect(){
        pingJob?.cancel()
    }
}

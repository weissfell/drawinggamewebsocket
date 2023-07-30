package de.weissbeb.routes

import com.google.gson.JsonParser
import de.weissbeb.data.Player
import de.weissbeb.data.Room
import de.weissbeb.data.models.*
import de.weissbeb.gson
import de.weissbeb.other.Constants
import de.weissbeb.server
import de.weissbeb.session.DrawingSession
import io.ktor.http.cio.websocket.*
import io.ktor.routing.*
import io.ktor.sessions.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach

fun Route.gameWebSocketRoute() {
    route("/ws/draw") {
        //webSocket { //normally this is how we'd start with a route, but we already do this in our wrapper!
        standardWebSocket { socket, clientId, message, payload ->  //instead we can call this
            when (payload) {
                is JoinRoomHandshake -> {
                    val room = server.rooms[payload.roomName]
                    if (room == null) {
                        //if room is null that means room does not exist -> inform client
                        val gameError = GameError(GameError.ERROR_ROOM_NOT_FOUND)
                        socket.send(Frame.Text(gson.toJson(gameError)))
                        return@standardWebSocket
                    }
                    val player = Player(payload.userName, socket, payload.clientId)
                    if (!room.containsPlayer(player.username)) {
                        server.playerJoined(player)
                        room.addPlayer(player.clientId, player.username, socket)
                    }
                }

                is DrawData -> {
                    //as payload is of type DrawData we can access all stuff from the data class inside payload
                    val room = server.rooms[payload.roomName] ?: return@standardWebSocket
                    if (room.phase == Room.Phase.GAME_RUNNING) {
                        //send to all player except the one drawing...
                        room.broadcastToAllExcept(message, clientId)
                    }
                }

                is ChosenWord -> {
                    //trigger when drawing player sets word 2 guess -> set chosenWord to room..
                    val room = server.rooms[payload.roomName] ?: return@standardWebSocket
                    room.setWordAndSet2GameRunning(payload.chosenWord)
                }

                is ChatMessage -> {
                    //for player guessing the word - might be chat message without guess aswell
                    val room = server.rooms[payload.roomName] ?: return@standardWebSocket
                    if (!room.checkWordAndRewardAndNotifyPlayers(payload)) {
                        room.broadcast(message)
                    }
                }
            }

        }

    }
}


/**
 * standard websocket for players communicating with server inside room
 * wrapper function - will be used inside a route
 */
fun Route.standardWebSocket(
    handleFrame: suspend (
        socket: DefaultWebSocketServerSession, //conenection between client & server
        clientId: String, //connected player
        message: String, //sent string by player
        payload: BaseModel //parsed GSON data
    ) -> Unit
) {
    webSocket { //see route / post/get blocks in other Routes
        val session = call.sessions.get<DrawingSession>()
        if (session == null) {
            //no simple response as in httpRequests... instead we wanna close session in case of error
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Session not provided"))
            return@webSocket
        }

        try {
            incoming.consumeEach { frame ->  //incoming is a receivechannel (receives events (e.g. frames))
                // suspends as long as channel / connection is open
                // will throw exception as soon as it closes
                if (frame is Frame.Text) { //if json
                    val msg = frame.readText()
                    val jsonObject = JsonParser.parseString(msg).asJsonObject //parsed - and now we can check type
                    val type = when (jsonObject.get("type").asString) {
                        Constants.TYPE_CHAT_MESSAGE -> ChatMessage::class.java
                        Constants.TYPE_DRAW_DATA -> DrawData::class.java
                        Constants.TYPE_ANNOUNCEMENT -> Announcement::class.java
                        Constants.TYPE_JOIN_ROOM_HANDSHAKE -> JoinRoomHandshake::class.java
                        Constants.TYPE_PHASE_CHANGE -> PhaseChange::class.java
                        Constants.TYPE_CHOSEN_WORD -> ChosenWord::class.java
                        Constants.TYPE_GAME_STATE -> GameState::class.java
                        else -> BaseModel::class.java
                    }
                    val payload = gson.fromJson(msg, type)
                    handleFrame(this, session.clientId, msg, payload) // Wrapper for handling different types of json
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            // handle disconnects - get reference to player that has clientId of the session...
            val playerWithClientId = server.getRoomWithClientId(session.clientId)?.players?.find {
                it.clientId == session.clientId
            }
            if (playerWithClientId != null) { //meaning: in any room there must be a player with this id
                server.playerLeft(session.clientId)
            }
        }
    }
}




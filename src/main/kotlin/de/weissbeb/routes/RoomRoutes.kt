package de.weissbeb.routes

import de.weissbeb.data.Room
import de.weissbeb.data.models.BasicApiResponse
import de.weissbeb.data.models.CreateRoomRequest
import de.weissbeb.data.models.RoomResponse
import de.weissbeb.other.Constants
import de.weissbeb.server
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*

/**
 * HttpRequests surrounding the server's rooms
 */
fun Route.createRoomRoute() {
    route("/api/createRoom") {
        post {
            val roomRequest = call.receiveOrNull<CreateRoomRequest>()
            if (roomRequest == null) {
                //invalid
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            if (server.rooms[roomRequest.name] != null) {
                //request ok but room exists
                call.respond(HttpStatusCode.OK, BasicApiResponse(false, "Room already exists"))
                return@post
            }

            if (roomRequest.maxPlayers < 2) {
                //request ok but illegal argument
                call.respond(HttpStatusCode.OK, BasicApiResponse(false, "Minimum room size is 2"))
                return@post
            }

            if (roomRequest.maxPlayers > Constants.MAX_ROOM_SIZE) {
                //request ok but illegal argument
                call.respond(
                    HttpStatusCode.OK,
                    BasicApiResponse(false, "Maximum room size is ${Constants.MAX_ROOM_SIZE}")
                )
                return@post
            }

            val room = Room(
                roomRequest.name,
                roomRequest.maxPlayers
            )
            server.rooms[roomRequest.name] = room
            println("room created ${roomRequest.name}")
            call.respond(HttpStatusCode.OK, BasicApiResponse(true))
        }
    }
}

fun Route.getRoomsRoute() {
    route("/api/getRooms") {
        get {
            val searchQuery = call.parameters["searchQuery"]
            if (searchQuery == null) {
                //invalid
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }

            val roomsResult = server.rooms.filterKeys {
                it.contains(searchQuery, ignoreCase = true)
            }
            val roomResponses = roomsResult.values.map {
                RoomResponse(it.name, it.maxPlayers, it.players.size)
            }.sortedBy { it.name }

            call.respond(HttpStatusCode.OK, roomResponses)
        }
    }
}

fun Route.joinRoomRoute() {
    route("/api/joinRoom") {
        get {
            val username = call.parameters["userName"]
            val roomName = call.parameters["roomName"]

            if (username == null || roomName == null) {
                //invalid
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }

            val room = server.rooms[roomName]
            when {
                room == null -> {
                    call.respond(HttpStatusCode.OK, BasicApiResponse(false, "Room not found"))
                }
                room.containsPlayer(username) -> {
                    call.respond(HttpStatusCode.OK, BasicApiResponse(false, "Player with the same username already joined"))
                }

                room.players.size >= room.maxPlayers -> {
                    call.respond(HttpStatusCode.OK, BasicApiResponse(false, "This room is already full"))
                }
                else -> {
                    call.respond(HttpStatusCode.OK, BasicApiResponse(true))
                }
            }
        }
    }
}
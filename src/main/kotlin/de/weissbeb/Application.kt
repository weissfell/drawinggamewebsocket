package de.weissbeb

import com.google.gson.Gson
import de.weissbeb.plugins.configureMonitoring
import de.weissbeb.plugins.configureRouting
import de.weissbeb.plugins.configureSerialization
import de.weissbeb.plugins.configureSockets
import de.weissbeb.routes.createRoomRoute
import de.weissbeb.routes.gameWebSocketRoute
import de.weissbeb.routes.getRoomsRoute
import de.weissbeb.routes.joinRoomRoute
import de.weissbeb.session.DrawingSession
import io.ktor.application.*
import io.ktor.routing.*
import io.ktor.sessions.*
import io.ktor.util.*


fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

val server = DrawingServer()
val gson = Gson()

@Suppress("unused") // Referenced in application.conf
@kotlin.jvm.JvmOverloads
fun Application.module(testing: Boolean = false) {

    install(Sessions) {
        cookie<DrawingSession>("SESSION")
    }
    intercept(ApplicationCallPipeline.Features) {
        if (call.sessions.get<DrawingSession>() == null) {
            val clientId = call.parameters["client_id"] ?: ""
            call.sessions.set(DrawingSession(clientId, generateNonce()))
        }
    }
    configureSockets()

    install(Routing) {
        //simple httprequests, no web socket functionality used
        createRoomRoute()
        getRoomsRoute()
        joinRoomRoute()
        //websocket route:
        gameWebSocketRoute()
    }

    configureMonitoring()
    configureSerialization()
    configureRouting()
}

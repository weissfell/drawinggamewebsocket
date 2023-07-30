package de.weissbeb.data.models

import de.weissbeb.other.Constants

data class JoinRoomHandshake(
    val userName: String,
    val roomName: String,
    val clientId: String
) : BaseModel(Constants.TYPE_JOIN_ROOM_HANDSHAKE)

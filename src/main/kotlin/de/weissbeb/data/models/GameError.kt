package de.weissbeb.data.models

import de.weissbeb.other.Constants

data class GameError(
    val errorType: Int
) : BaseModel(Constants.TYPE_GAME_ERROR) {
    companion object {
        const val ERROR_ROOM_NOT_FOUND = 0
    }
}

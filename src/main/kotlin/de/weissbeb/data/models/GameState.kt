package de.weissbeb.data.models

import de.weissbeb.other.Constants

data class GameState(
    val drawingPlayer: String,
    val word: String

) : BaseModel(Constants.TYPE_GAME_STATE)

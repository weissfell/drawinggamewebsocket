package de.weissbeb.data.models

/**
 * data class for holding player data
 */
data class PlayerData(
    val username: String,
    var isDrawing: Boolean = false,
    var source: Int = 0,
    var rank : Int = 0
)

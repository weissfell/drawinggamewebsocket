package de.weissbeb.data.models

import de.weissbeb.other.Constants

data class PlayersList(
    val players: List<PlayerData>
) : BaseModel(Constants.TYPE_PLAYERS_LIST)

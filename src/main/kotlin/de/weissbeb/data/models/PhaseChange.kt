package de.weissbeb.data.models

import de.weissbeb.data.Room
import de.weissbeb.other.Constants

data class PhaseChange(
    var phase: Room.Phase?, //nullable - we use Phase = null when we just want to update the clients' time
    var time: Long,
    val drawingPlayer: String? = null //only username - sent only when new round starts
) : BaseModel(Constants.TYPE_PHASE_CHANGE)

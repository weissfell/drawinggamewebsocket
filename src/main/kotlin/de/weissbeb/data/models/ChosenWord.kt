package de.weissbeb.data.models

import de.weissbeb.other.Constants

data class ChosenWord(
    val chosenWord: String,
    val roomName: String
) : BaseModel(Constants.TYPE_CHOSEN_WORD)

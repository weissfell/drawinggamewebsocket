package de.weissbeb.data.models

import de.weissbeb.other.Constants

data class NewWords(
    val newWords : List<String>
) : BaseModel (Constants.TYPE_NEW_WORDS)

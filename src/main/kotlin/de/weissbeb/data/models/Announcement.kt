package de.weissbeb.data.models

import de.weissbeb.other.Constants

data class Announcement(

    val message: String,
    val timeStamp: Long,
    val announcementType: Int

) : BaseModel(Constants.TYPE_ANNOUNCEMENT) {
    companion object {
        const val TYPE_GUESSED_WORD = 0
        const val TYPE_PLAYER_JOINED = 1
        const val TYPE_PLAYER_LEFT = 2
        const val TYPE_PLAYER_EVERYBODY_GUESSED_IT = 3
    }
}

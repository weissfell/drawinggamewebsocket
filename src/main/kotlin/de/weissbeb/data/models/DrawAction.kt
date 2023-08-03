package de.weissbeb.data.models

import de.weissbeb.other.Constants

data class DrawAction(val action: String) : BaseModel(Constants.TYPE_DRAW_ACTION) {

    companion object {
        const val ACTION_UNDO = "ACTION_UNDO"
    }

}

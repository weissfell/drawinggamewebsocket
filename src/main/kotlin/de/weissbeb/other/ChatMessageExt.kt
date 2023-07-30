package de.weissbeb.other

import de.weissbeb.data.models.ChatMessage

fun ChatMessage.matchesWord(word: String): Boolean {
    return message.lowercase().trim() == word.lowercase().trim()
}
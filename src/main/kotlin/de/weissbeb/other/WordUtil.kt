package de.weissbeb.other

import java.io.File

val words = readWordList("resources/programmers_wordlist.txt")

fun readWordList(fileName: String): List<String> {
    val inputStream = File(fileName).inputStream()
    val words = mutableListOf<String>()
    inputStream.bufferedReader().forEachLine {
        words.add(it)
    }
    return words
}

fun getRandomWords(amount: Int = 3) : List<String> {
    var currAmount = 0
    var result = mutableListOf<String>()
    while (currAmount < amount){
        val word = words.random()
        if(!result.contains(word)){
            result.add(word)
            currAmount++
        }
    }
    return result
}

// "apple juice" => "_ _ _ _ _   _ _ _ _ _"
// additional whitespace
fun String.transformToUnderscores() =
    toCharArray().map {
        if(it != ' ') '_' else ' '
    }.joinToString { " " }
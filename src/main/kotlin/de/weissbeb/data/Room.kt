package de.weissbeb.data

import de.weissbeb.data.models.*
import de.weissbeb.gson
import de.weissbeb.other.getRandomWords
import de.weissbeb.other.matchesWord
import de.weissbeb.other.transformToUnderscores
import de.weissbeb.other.words
import de.weissbeb.server
import io.ktor.http.cio.websocket.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

class Room(
    val roomName: String,
    val maxPlayers: Int,
    var players: List<Player> = listOf()
) {

    private var timerJob: Job? = null
    private var drawingPlayer: Player? = null
    private var playersThatGuessedTheWord = listOf<String>()
    private var word2Guess: String? = null
    private var currWords: List<String>? = null
    private var drawingPlayerIndex = 0
    private var phaseStartTime = 0L

    //saves clientId including a job that will remove a player fromt the game with a delay
    //why delay? non-voluntary disconnects after which the player tries to rejoin won't lead to a immediate removal!
    private val playerRemoveJobs = ConcurrentHashMap<String, Job>()
    private val leftPlayers = ConcurrentHashMap<String, Pair<Player, Int>>()

    private var curRoundDrawData : List<String> = listOf()

    /** extend current drawing with one action
     *
     */
    fun addSerializedDrawInfo(drawAction : String) {
        curRoundDrawData = curRoundDrawData + drawAction
    }

    private suspend fun sendCurrentRoundDrawInfoToPlayer(player: Player){
        if(phase == Phase.GAME_RUNNING || phase == Phase.SHOW_WORD){
            player.socket.send(Frame.Text(gson.toJson(RoundDrawInfo(curRoundDrawData))))
        }
    }


    /**
     * functions and stuff relating to different Phases concerns changes in game's state and at what point in time
     * the players currently are!
     *
     */
    private var phaseChangedListener: ((Phase) -> Unit)? = null
    var phase: Phase = Phase.WAITING_FOR_PLAYERS
        set(value) {
            synchronized(field) {
                field = value
                phaseChangedListener?.let {
                    it(value)
                }
            }
        }

    private fun setPhaseChagnedListener(listener: (Phase) -> Unit) {
        phaseChangedListener = listener
    }

    init {
        setPhaseChagnedListener { newPhase ->
            when (newPhase) {
                Phase.WAITING_FOR_START -> waitingForStart()
                Phase.WAITING_FOR_PLAYERS -> waitingForPlayers()
                Phase.SHOW_WORD -> showWord()
                Phase.GAME_RUNNING -> gameRunning()
                Phase.NEW_ROUND -> newRound()
            }
        }
    }

    suspend fun addPlayer(clientId: String, userName: String, socket: WebSocketSession): Player {
        var index2Add = players.size -1
        val playerAdded = if(leftPlayers.contains(clientId)){
            //first - handle player reconnecting after connection lost
            val leftPlayer = leftPlayers[clientId]
            leftPlayer?.first?.let {
                it.socket = socket //socket needs to be updated as we have a new connection
                it.isDrawing = drawingPlayer?.clientId == clientId
                index2Add = leftPlayer.second

                playerRemoveJobs[clientId]?.cancel()
                playerRemoveJobs.remove(clientId)
                leftPlayers.remove(clientId)
                it
            } ?: Player(userName, socket, clientId)
        } else {
            Player(userName, socket, clientId)
        }

        index2Add = when {
            players.isEmpty() -> 0
            index2Add >= players.size -> players.size - 1
            else -> index2Add
        }
        /*
            players is immutable. So we replace the entire list with a new one...
            If we'd made id mutable and use players.add(playerAdded) because that could lead to multiple threads access
            the same mutable list and possibly change the index of entries
         */
        //but still we need a mutable list temporary - in case a player rejoins instead of a new player arriving
        val tmpPlayers = players.toMutableList()
        tmpPlayers.add(index2Add, playerAdded)
        players = tmpPlayers.toList()

        if (players.size == 1) {
            phase = Phase.WAITING_FOR_PLAYERS
        } else if (players.size == 2 && phase == Phase.WAITING_FOR_PLAYERS) {
            phase = Phase.WAITING_FOR_START
            players = players.shuffled()
        } else if (phase == Phase.WAITING_FOR_START && players.size == maxPlayers) {
            phase = Phase.NEW_ROUND
        }
        val anncmt = Announcement(
            "$userName joined the party",
            System.currentTimeMillis(),
            Announcement.TYPE_PLAYER_JOINED
        )

        sendWordToPlayer(playerAdded)
        broadcastPlayerStates()
        sendCurrentRoundDrawInfoToPlayer(playerAdded)
        broadcast(gson.toJson(anncmt))
        return playerAdded
    }

    fun removePlayer(clientId: String){
        val player = players.find { it.clientId == clientId } ?: return

        val index = players.indexOf(player)
        leftPlayers[clientId] = player to index
        players = players - player

        playerRemoveJobs[clientId] = GlobalScope.launch {
            delay(PLAYER_REMOVE_TIME)
            val player2Remove = leftPlayers[clientId]
            leftPlayers.remove(clientId)
            player2Remove?.let{
                players = players - it.first
            }
            playerRemoveJobs.remove(clientId)
        }
        val announcement = Announcement(
            "Player ${player.username} left the party",
            System.currentTimeMillis(),
            Announcement.TYPE_PLAYER_LEFT
        )

        GlobalScope.launch {
            broadcastPlayerStates()
            broadcast(gson.toJson(announcement))

            //only one or no player left, no more game possible - handle
            if(players.size == 1){
                phase = Phase.WAITING_FOR_PLAYERS
                timerJob?.cancel()
            } else if(players.isEmpty()){
                kill()
                server.rooms.remove(roomName)
            }
        }
    }

    suspend fun broadcast(msg: String) {
        players.forEach {
            if (it.socket.isActive) {
                it.socket.send(Frame.Text(msg))
            }
        }
    }

    suspend fun broadcastToAllExcept(msg: String, clientId: String) {
        players.forEach {
            if (it.socket.isActive && it.clientId != clientId) {
                it.socket.send(Frame.Text(msg))
            }
        }
    }

    suspend fun timeAndNotify(ms: Long) {
        timerJob?.cancel()
        timerJob = GlobalScope.launch {//globalscope - unlike in android not evil to use
            phaseStartTime = System.currentTimeMillis()
            val phaseChange = PhaseChange(
                phase,
                ms,
                drawingPlayer?.username
            )
            //repeat the following block every second
            repeat((ms / UPDATE_TIME_FREQ).toInt()) { currentIteration ->
                if (currentIteration != 0) {
                    //first repeat: inform clients about new phase, so they know about change
                    phaseChange.phase = null
                }
                broadcast(gson.toJson(phaseChange))
                phaseChange.time -= UPDATE_TIME_FREQ //remaining time until next iteration
                delay(UPDATE_TIME_FREQ)
            }
            //repeat has finished so now change phase..
            phase = when (phase) {
                Phase.WAITING_FOR_START -> Phase.NEW_ROUND
                Phase.GAME_RUNNING -> Phase.SHOW_WORD
                Phase.SHOW_WORD -> Phase.NEW_ROUND
                Phase.NEW_ROUND -> Phase.GAME_RUNNING
                else -> Phase.WAITING_FOR_PLAYERS
            }
        }
    }

    fun containsPlayer(userName: String): Boolean {
        return players.find { it.username == userName } != null
    }

    fun setWordAndSet2GameRunning(newWord: String) {
        this.word2Guess = newWord
        phase = Phase.GAME_RUNNING
    }

    /**
     * send player depending on if he is drawing or not the current word
     */
    private suspend fun sendWordToPlayer(player: Player) {
        val delay = when (phase){
            Phase.WAITING_FOR_START -> DELAY_WAITING_FOR_START_2_NEW_ROUND
            Phase.NEW_ROUND -> DELAY_NEW_ROUND_2_GAME_RUNNING
            Phase.GAME_RUNNING -> DELAY_GAME_RUNNING_2_SHOW_WORD
            Phase.SHOW_WORD -> DELAY_SHOW_WORD_2_NEW_ROUND
            else -> 0L
        }
        val phaseChange = PhaseChange(phase, delay, drawingPlayer?.username)
        word2Guess?.let{curWord ->
            drawingPlayer?.let{drawingPlayer ->
                val gameState = GameState (
                    drawingPlayer.username,
                    if(player.isDrawing || phase == Phase.SHOW_WORD) {
                        curWord
                    } else {
                        curWord.transformToUnderscores()
                    }
                )
                player.socket.send(Frame.Text(gson.toJson(gameState)))
            }
        }
        player.socket.send(Frame.Text(gson.toJson(phaseChange)))
    }

    suspend fun checkWordAndRewardAndNotifyPlayers(message: ChatMessage) : Boolean {
        if(isGuessCorrect(message)) {
            //reward player depending on remaining time if he's correct
            val timeAtGuess = System.currentTimeMillis() - phaseStartTime
            val timePercentageLeft = 1f - timeAtGuess.toFloat() / DELAY_GAME_RUNNING_2_SHOW_WORD
            val score4Player = GUESS_SCORE_DEFAULT + GUESS_SCORE_PERCENTAGE_MULTIPLIER * timePercentageLeft
            val player = players.find { it.username == message.from }

            player?.let{
                it.score += score4Player.toInt()
            }
            drawingPlayer?.let{
                it.score += GUESS_SCORE_DRAWING_PLAYER / players.size
            }
            broadcastPlayerStates()

            val anncmt = Announcement(
                "${message.from} has guessed it",
                System.currentTimeMillis(),
                Announcement.TYPE_GUESSED_WORD
            )
            broadcast(gson.toJson(anncmt))
            val isRoundOver = addWinningPlayer(message.from) //true if everybody guessed it
            if(isRoundOver) {
                val anncmtRoundOver = Announcement(
                    "Everybody has guessed it - new round will start soon",
                    System.currentTimeMillis(),
                    Announcement.TYPE_PLAYER_EVERYBODY_GUESSED_IT
                )
                broadcast(gson.toJson(anncmtRoundOver))
            }
            return true
        }
        return false
    }

    private suspend fun broadcastPlayerStates(){
        val playersList = players.sortedByDescending { it.score }.map {
            PlayerData(it.username, it.isDrawing, it.score, it.rank) //rank should be 0 at this point
        }
        playersList.forEachIndexed { index, playerData ->
            playerData.rank = index + 1
        }
        broadcast(gson.toJson(PlayersList(playersList)))
    }

    private fun waitingForPlayers() {
        GlobalScope.launch {
            val phaseChange = PhaseChange(Phase.WAITING_FOR_PLAYERS, DELAY_WAITING_FOR_START_2_NEW_ROUND)
            broadcast(gson.toJson(phaseChange))
        }
    }

    private fun waitingForStart() {
        GlobalScope.launch {
            timeAndNotify(DELAY_WAITING_FOR_START_2_NEW_ROUND)
            val phaseChange = PhaseChange(Phase.WAITING_FOR_START, DELAY_WAITING_FOR_START_2_NEW_ROUND)
            broadcast(gson.toJson(phaseChange))
        }
    }

    private fun newRound() {
        curRoundDrawData = listOf()
        currWords = getRandomWords()
        val newWords = NewWords(currWords!!)
        nextDrawingPlayer()
        GlobalScope.launch {
            broadcastPlayerStates()
            drawingPlayer?.socket?.send(Frame.Text(gson.toJson(newWords)))
            timeAndNotify(DELAY_NEW_ROUND_2_GAME_RUNNING)
        }
    }

    private fun addWinningPlayer(userName: String) : Boolean{
        playersThatGuessedTheWord = playersThatGuessedTheWord + userName
        if (playersThatGuessedTheWord.size == players.size -1){
            //everybody guessed so return true and change phase
            phase = Phase.NEW_ROUND
            return true
        }
        return false
    }

    private fun isGuessCorrect(guess: ChatMessage): Boolean {
        return guess.matchesWord(word2Guess ?: return false) //is word correct? null safe
                && !playersThatGuessedTheWord.contains(guess.from)  //player hasn't already guessed correctly
                && guess.from != drawingPlayer?.username //player isn't also drawing player
                && phase == Phase.GAME_RUNNING //correct phase
    }

    private fun gameRunning() {
        playersThatGuessedTheWord = listOf()
        //word2Send => either guessed word, if null get random word of list randomly chosen if still null then just get any word
        val word2Send = word2Guess ?: currWords?.random() ?: words.random()
        val wordWithUnderscores = word2Send.transformToUnderscores()
        val drawingUsername = (drawingPlayer?.username ?: players.random().username)
        val gameState4DrawingPlayer = GameState(drawingUsername, word2Send)
        val gameState4GuessingPlayer = GameState(drawingUsername, wordWithUnderscores)

        //inform players guessing and send them underscore word. also inform drawing player and send him chosen word
        GlobalScope.launch {
            broadcastToAllExcept(
                gson.toJson(gameState4GuessingPlayer),
                drawingPlayer?.clientId ?: players.random().clientId
            )
            drawingPlayer?.socket?.send(Frame.Text(gson.toJson(gameState4DrawingPlayer)))
            timeAndNotify(DELAY_GAME_RUNNING_2_SHOW_WORD)
            println("drawing phase in room $roomName started")
        }
    }

    /**
     * get next player in list using index var
     */
    private fun nextDrawingPlayer() {
        drawingPlayer?.isDrawing = false
        if (players.isEmpty()) {
            return
        }

        drawingPlayer = if (drawingPlayerIndex <= players.size - 1) {
            players[drawingPlayerIndex]
        } else players.last()

        if (players.size - 1 < players.size) {
            drawingPlayerIndex++
        } else {
            drawingPlayerIndex = 0
        }
    }

    private fun showWord() {
        GlobalScope.launch {
            //penalty if nobody guessed the word
            if (playersThatGuessedTheWord.isEmpty()) {
                drawingPlayer?.let {
                    it.score -= PENALTY_NOBODY_GUESSED
                }
            }
            broadcastPlayerStates()
            //broadcast the result to everbody
            word2Guess?.let {
                val chosenWord = ChosenWord(it, roomName)
                broadcast(gson.toJson(chosenWord))
            }
            //start phasechange
            timeAndNotify(DELAY_SHOW_WORD_2_NEW_ROUND)
            val phaseChange = PhaseChange(Phase.SHOW_WORD, DELAY_SHOW_WORD_2_NEW_ROUND)
            broadcast(gson.toJson(phaseChange))
        }
    }

    /**
     * cancel all running jobs
     */
    private fun kill(){
        playerRemoveJobs.values.forEach {
            it.cancel()
        }
        timerJob?.cancel()
    }

    enum class Phase {
        WAITING_FOR_PLAYERS,
        WAITING_FOR_START,
        NEW_ROUND,
        GAME_RUNNING,
        SHOW_WORD
    }

    companion object {
        const val UPDATE_TIME_FREQ = 1000L //time updates sent to clients
        const val PLAYER_REMOVE_TIME = 60000L

        const val DELAY_WAITING_FOR_START_2_NEW_ROUND = 10000L
        const val DELAY_NEW_ROUND_2_GAME_RUNNING = 20000L
        const val DELAY_GAME_RUNNING_2_SHOW_WORD = 60000L
        const val DELAY_SHOW_WORD_2_NEW_ROUND = 10000L

        const val PENALTY_NOBODY_GUESSED = 50
        const val GUESS_SCORE_DEFAULT = 50
        const val GUESS_SCORE_PERCENTAGE_MULTIPLIER = 50
        const val GUESS_SCORE_DRAWING_PLAYER = 50
    }
}



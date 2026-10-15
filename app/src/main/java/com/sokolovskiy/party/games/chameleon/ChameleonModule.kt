package com.sokolovskiy.party.games.chameleon

import com.sokolovskiy.party.core.GameEnv
import com.sokolovskiy.party.core.GameModule
import com.sokolovskiy.party.core.GameSession
import org.json.JSONObject

class ChameleonModule : GameModule {
    override val id = "chameleon"
    override val title = "Хамелеон"
    override val subtitle = "Один гравець не знає слова або має інше, схоже. Підказки по колу — і знайдіть його"
    override val minPlayers = Game.MIN_PLAYERS
    override val emoji = "🦎"

    /** Shared across sessions so topics and pairs don't repeat when the game is reopened the same evening. */
    private var topics: TopicBank? = null
    private var pairs: PairBank? = null

    override fun create(env: GameEnv): GameSession {
        val t = topics ?: TopicBank.fromJson(env.asset("chameleon-topics.json")).also { topics = it }
        val p = pairs ?: PairBank.fromJson(env.asset("chameleon-pairs.json")).also { pairs = it }
        return Session(Game(t, p), env)
    }

    private class Session(private val game: Game, private val env: GameEnv) : GameSession {

        override fun onJoin(id: String, name: String) { game.add(id, name) }

        override fun onLeave(id: String) = game.remove(id)

        override fun handle(pid: String?, isHost: Boolean, msg: JSONObject, now: Long) {
            when (msg.optString("t")) {
                "ready" -> pid?.let { game.markReady(it) }
                "clue" -> pid?.let { game.submitClue(it, msg.optString("text")) }
                "vote" -> pid?.let { game.castVote(it, msg.optString("target")) }
                "guess" -> pid?.let { game.submitGuess(it, msg.optString("word")) }
            }
            if (isHost) when (msg.optString("t")) {
                "mode" -> game.setMode(if (msg.optString("v") == "similar") Game.Mode.SIMILAR else Game.Mode.CLASSIC)
                "start", "next" -> game.startRound()
                "begin" -> game.beginClues()
                "skipTurn" -> game.skipTurn()
                "closeVote" -> game.closeVote()
                "skipGuess" -> game.skipGuess()
                "abort" -> game.abortRound()
                "newGame" -> game.newGame()
            }
        }

        override fun state(pid: String?, isHost: Boolean, now: Long): JSONObject {
            for (p in env.room.players.values) game.players[p.id]?.connected = p.connected
            return game.stateJson(pid, isHost, now)
        }
    }
}

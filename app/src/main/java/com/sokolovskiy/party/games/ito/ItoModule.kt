package com.sokolovskiy.party.games.ito

import com.sokolovskiy.party.core.GameEnv
import com.sokolovskiy.party.core.GameModule
import com.sokolovskiy.party.core.GameSession
import org.json.JSONObject

class ItoModule : GameModule {
    override val id = "ito"
    override val title = "Сотня"
    override val subtitle = "У кожного таємне число 1–100. Опиши його прикладом на тему — і разом викладіть усі карти по зростанню"
    override val minPlayers = Game.MIN_PLAYERS
    override val emoji = "💯"

    /** Shared across sessions so topics don't repeat when the game is reopened the same evening. */
    private var bank: TopicBank? = null

    override fun create(env: GameEnv): GameSession {
        val b = bank ?: TopicBank.fromJson(env.asset("ito-topics.json")).also { bank = it }
        return Session(Game(b), env)
    }

    private class Session(private val game: Game, private val env: GameEnv) : GameSession {

        override fun onJoin(id: String, name: String) { game.add(id, name) }

        override fun onLeave(id: String) = game.remove(id)

        override fun handle(pid: String?, isHost: Boolean, msg: JSONObject, now: Long) {
            when (msg.optString("t")) {
                "clue" -> pid?.let { game.setClue(it, msg.optInt("v", -1), msg.optString("text")) }
                "play" -> pid?.let { game.play(it, msg.optInt("v", -1).takeIf { v -> v > 0 }) }
            }
            if (isHost) when (msg.optString("t")) {
                "spice" -> runCatching { Game.Spice.valueOf(msg.optString("v")) }.getOrNull()?.let { game.setSpice(it) }
                "start" -> game.startGame()
                "next" -> game.nextLevel()
                "swap" -> game.swapTopic()
                "abort" -> game.abort()
                "newGame" -> game.newGame()
            }
        }

        override fun state(pid: String?, isHost: Boolean, now: Long): JSONObject {
            for (p in env.room.players.values) game.players[p.id]?.connected = p.connected
            return game.stateJson(pid, isHost, now)
        }
    }
}

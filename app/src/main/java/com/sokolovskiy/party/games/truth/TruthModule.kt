package com.sokolovskiy.party.games.truth

import com.sokolovskiy.party.core.GameEnv
import com.sokolovskiy.party.core.GameModule
import com.sokolovskiy.party.core.GameSession
import org.json.JSONObject

class TruthModule : GameModule {
    override val id = "truth"
    override val title = "Детектор правди"
    override val subtitle = "Усі анонімно відповідають «так» чи «ні» на гостре питання й вгадують, скільки буде «так»"
    override val minPlayers = Game.MIN_PLAYERS
    override val emoji = "🕵️"

    /** Shared across sessions so questions don't repeat when the game is reopened the same evening. */
    private var bank: QuestionBank? = null

    override fun create(env: GameEnv): GameSession {
        val b = bank ?: QuestionBank.fromJson(env.asset("truth-questions.json")).also { bank = it }
        return Session(Game(b), env)
    }

    private class Session(private val game: Game, private val env: GameEnv) : GameSession {

        override fun onJoin(id: String, name: String) { game.add(id, name) }

        override fun onLeave(id: String) = game.remove(id)

        override fun handle(pid: String?, isHost: Boolean, msg: JSONObject, now: Long) {
            when (msg.optString("t")) {
                "answer" -> pid?.let { game.answer(it, msg.optBoolean("yes"), msg.optInt("guess", -1)) }
                "swap" -> game.swapQuestion(pid, isHost)
                "reveal" -> game.forceReveal(isHost)
                "next" -> game.next(pid, isHost)
            }
            if (isHost) when (msg.optString("t")) {
                "spice" -> runCatching { Game.Spice.valueOf(msg.optString("v")) }.getOrNull()?.let { game.setSpice(it) }
                "target" -> game.setTarget(msg.optInt("v", 0))
                "start" -> game.startGame()
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

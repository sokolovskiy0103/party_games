package com.sokolovskiy.party.games.pizza

import com.sokolovskiy.party.core.GameEnv
import com.sokolovskiy.party.core.GameModule
import com.sokolovskiy.party.core.GameSession
import org.json.JSONObject

class PizzaModule : GameModule {
    override val id = "pizza"
    override val title = "Піца-шатл"
    override val subtitle = "Кожен має свій пульт, а команди — на чужих екранах. Кричи, тисни, крути — піца холоне!"
    override val minPlayers = Game.MIN_PLAYERS
    override val emoji = "🍕"

    private var words: Words? = null
    private val record = Game.Record()

    override fun create(env: GameEnv): GameSession {
        val w = words ?: Words.fromJson(env.asset("pizza-words.json")).also { words = it }
        return Session(Game(w, record = record), env)
    }

    private class Session(private val game: Game, private val env: GameEnv) : GameSession {

        override fun onJoin(id: String, name: String) {
            game.add(id, name)
            env.room.players[id]?.let { game.setConnected(id, it.connected) }
        }

        override fun onLeave(id: String) = game.remove(id)

        override fun handle(pid: String?, isHost: Boolean, msg: JSONObject, now: Long) {
            if (pid != null) when (msg.optString("t")) {
                "set" -> game.act(pid, msg.optString("c"), msg.optInt("v", Int.MIN_VALUE), now)
                "brace" -> game.brace(pid)
                "rock" -> game.rock(pid)
                "fix" -> game.fix(pid, msg.optString("c"))
                "wipe" -> game.wipe(pid)
            }
            if (isHost) when (msg.optString("t")) {
                "difficulty" -> runCatching { Difficulty.valueOf(msg.optString("v")) }.getOrNull()?.let(game::setDifficulty)
                "start" -> game.startGame(now)
                "next" -> game.next(now)
                "abort", "newGame" -> game.abort()
            }
        }

        override fun tick(now: Long): Boolean {
            var changed = false
            for (p in env.room.players.values) if (game.setConnected(p.id, p.connected)) changed = true
            return game.tick(now) || changed
        }

        /** The TV: progress, temperature, crew and hazards — never panels or commands. */
        override fun board(now: Long): JSONObject? = game.boardJson(now)

        override fun state(pid: String?, isHost: Boolean, now: Long): JSONObject {
            for (p in env.room.players.values) game.setConnected(p.id, p.connected)
            return game.stateJson(pid, isHost, now)
        }
    }
}

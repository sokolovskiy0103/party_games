package com.sokolovskiy.party.games.codenames

import com.sokolovskiy.party.core.GameEnv
import com.sokolovskiy.party.core.GameModule
import com.sokolovskiy.party.core.GameSession
import org.json.JSONObject

class CodenamesModule : GameModule {
    override val id = "codenames"
    override val title = "Кодові імена"
    override val subtitle = "Капітан пов'язує кілька слів на столі однією підказкою, команда шукає своїх агентів і оминає вбивцю. Удвох — кооперативний Дует"
    override val minPlayers = 2
    override val emoji = "🗝️"

    /** Shared across sessions so boards don't repeat when the game is reopened the same evening. */
    private var bank: WordBank? = null

    override fun create(env: GameEnv): GameSession {
        val b = bank ?: WordBank.fromJson(env.asset("decrypto-words.json")).also { bank = it }
        return Session(Game(b), env)
    }

    private class Session(private val game: Game, private val env: GameEnv) : GameSession {

        override fun onJoin(id: String, name: String) { game.add(id, name) }

        override fun onLeave(id: String) = game.remove(id)

        override fun handle(pid: String?, isHost: Boolean, msg: JSONObject, now: Long) {
            when (msg.optString("t")) {
                "switch" -> pid?.let { game.switchTeam(it) }
                "clue" -> pid?.let { game.submitClue(it, msg.optString("word"), msg.optInt("n", 1)) }
                "point" -> pid?.let { game.point(it, msg.optInt("i", -1)) }
                "open" -> pid?.let { game.open(it, msg.optInt("i", -1)) }
                "endTurn" -> pid?.let { game.endTurn(it) }
            }
            if (isHost) when (msg.optString("t")) {
                "move" -> game.switchTeam(msg.optString("pid"))
                "shuffle" -> game.shuffleTeams()
                "mode" -> runCatching { Game.Mode.valueOf(msg.optString("v")) }.getOrNull()?.let { game.setMode(it) }
                "spice" -> runCatching { Game.Spice.valueOf(msg.optString("v")) }.getOrNull()?.let { game.setSpice(it) }
                "turns" -> game.setDuetTurns(msg.optInt("v"))
                "start" -> game.startGame()
                "abort" -> game.abort()
                "lobby" -> game.toLobby()
            }
        }

        override fun state(pid: String?, isHost: Boolean, now: Long): JSONObject {
            for (p in env.room.players.values) game.players[p.id]?.connected = p.connected
            return game.stateJson(pid, isHost, now)
        }
    }
}

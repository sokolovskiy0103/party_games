package com.sokolovskiy.party.games.bomb

import com.sokolovskiy.party.core.GameEnv
import com.sokolovskiy.party.core.GameModule
import com.sokolovskiy.party.core.GameSession
import org.json.JSONObject

class BombModule : GameModule {
    override val id = "bomb"
    override val title = "Сапери"
    override val subtitle = "Кооператив за мотивами Bomb Busters: вгадуй номери дротів у руках напарників і не переріж червоний"
    override val minPlayers = Game.MIN_PLAYERS
    override val emoji = "💣"

    override fun create(env: GameEnv): GameSession = Session(Game(), env)

    private class Session(private val game: Game, private val env: GameEnv) : GameSession {

        override fun onJoin(id: String, name: String) { game.add(id, name) }

        override fun onLeave(id: String) = game.remove(id)

        override fun handle(pid: String?, isHost: Boolean, msg: JSONObject, now: Long) {
            if (pid != null) when (msg.optString("t")) {
                "show" -> game.showWire(pid, msg.optInt("i", -1))
                "dual" -> game.dual(pid, msg.optString("to"), msg.optInt("i", -1), msg.optInt("v", -1))
                "solo" -> game.solo(pid, msg.optInt("v", -1))
                "reds" -> game.revealReds(pid)
            }
            if (isHost) when (msg.optString("t")) {
                "level" -> game.setLevel(msg.optInt("v"))
                "start" -> game.start()
                "next" -> game.nextLevel()
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

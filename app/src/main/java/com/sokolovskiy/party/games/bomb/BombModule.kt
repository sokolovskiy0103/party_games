package com.sokolovskiy.party.games.bomb

import com.sokolovskiy.party.core.GameEnv
import com.sokolovskiy.party.core.GameModule
import com.sokolovskiy.party.core.GameSession
import org.json.JSONObject

class BombModule : GameModule {
    override val id = "bomb"
    override val title = "Сапери"
    override val subtitle = "Кооператив за мотивами Bomb Busters: вгадуй дроти напарників і не переріж червоний. Персонажі, спорядження й 12 місій"
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
                "char" -> game.useChar(pid, msg.optString("to").ifEmpty { null }, msg.optInt("i", -1), msg.optInt("j", -1), msg.optInt("v", -1))
                "equip" -> game.useEquip(pid, msg.optInt("e", -1), msg.optString("to").ifEmpty { null }, msg.optInt("i", -1), msg.optInt("v", -1))
            }
            if (isHost) when (msg.optString("t")) {
                "level" -> game.setLevel(msg.optInt("v"))
                "start" -> game.start()
                "next" -> game.nextLevel()
                "abort" -> game.abort()
                "newGame" -> game.newGame()
            }
        }

        override fun tick(now: Long) = game.tick(now)

        override fun state(pid: String?, isHost: Boolean, now: Long): JSONObject {
            for (p in env.room.players.values) game.players[p.id]?.connected = p.connected
            return game.stateJson(pid, isHost, now)
        }
    }
}

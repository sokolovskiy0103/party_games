package com.sokolovskiy.party.games.liar

import com.sokolovskiy.party.core.GameEnv
import com.sokolovskiy.party.core.GameModule
import com.sokolovskiy.party.core.GameSession
import org.json.JSONObject

class LiarModule : GameModule {
    override val id = "liar"
    override val title = "Брехун"
    override val subtitle = "Кості, які бачиш лише ти. Підвищуй ставку «на столі щонайменше…» або викривай брехню. Найкраще втрьох"
    override val minPlayers = Game.MIN_PLAYERS
    override val emoji = "🎲"

    override fun create(env: GameEnv): GameSession = Session(Game(), env)

    private class Session(private val game: Game, private val env: GameEnv) : GameSession {

        override fun onJoin(id: String, name: String) { game.add(id, name) }

        override fun onLeave(id: String) = game.remove(id)

        override fun handle(pid: String?, isHost: Boolean, msg: JSONObject, now: Long) {
            if (pid != null) when (msg.optString("t")) {
                "bid" -> game.bid(pid, msg.optInt("q"), msg.optInt("f"))
                "liar" -> game.call(pid, Game.Call.LIAR)
                "exact" -> game.call(pid, Game.Call.EXACT)
            }
            if (isHost) when (msg.optString("t")) {
                "dice" -> game.setDice(msg.optInt("v"))
                "wild" -> game.setWild(msg.optBoolean("v"))
                "exactCall" -> game.setExact(msg.optBoolean("v"))
                "start" -> game.startGame()
                "next" -> game.next()
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

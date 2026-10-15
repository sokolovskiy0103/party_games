package com.sokolovskiy.party.games.clocktower

import com.sokolovskiy.party.core.GameEnv
import com.sokolovskiy.party.core.GameModule
import com.sokolovskiy.party.core.GameSession
import org.json.JSONObject

class ClocktowerModule : GameModule {
    override val id = "clocktower"
    override val title = "Кривава вежа"
    override val subtitle = "Мафія, що виросла: у кожного своя роль, мертві грають далі, а телефон — ведучий, який може й збрехати отруєним"
    override val minPlayers = Game.MIN_PLAYERS
    override val emoji = "🩸"

    override fun create(env: GameEnv): GameSession = Session(Game(), env)

    private class Session(private val game: Game, private val env: GameEnv) : GameSession {

        override fun onJoin(id: String, name: String) { game.add(id, name) }

        override fun onLeave(id: String) = game.remove(id)

        override fun handle(pid: String?, isHost: Boolean, msg: JSONObject, now: Long) {
            when (msg.optString("t")) {
                "choose" -> pid?.let { p ->
                    val a = msg.optJSONArray("targets") ?: return@let
                    game.choose(p, (0 until a.length()).map { a.optString(it) })
                }
                "read" -> pid?.let { game.markRead(it) }
                "nominate" -> pid?.let { game.nominate(it, msg.optString("target")) }
                "vote" -> pid?.let { game.castVote(it, msg.optBoolean("yes")) }
                "shoot" -> pid?.let { game.shoot(it, msg.optString("target")) }
            }
            if (isHost) when (msg.optString("t")) {
                "seat" -> game.moveSeat(msg.optString("pid"), msg.optInt("d"))
                "shuffle" -> game.shuffleSeats()
                "start" -> game.startGame()
                "force" -> game.forceNight()
                "closeVote" -> game.closeVote()
                "endDay" -> game.endDay()
                "abort" -> game.abort()
            }
        }

        override fun state(pid: String?, isHost: Boolean, now: Long): JSONObject {
            for (p in env.room.players.values) game.players[p.id]?.connected = p.connected
            return game.stateJson(pid, isHost, now)
        }
    }
}

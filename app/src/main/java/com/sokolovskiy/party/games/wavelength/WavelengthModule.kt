package com.sokolovskiy.party.games.wavelength

import com.sokolovskiy.party.core.GameEnv
import com.sokolovskiy.party.core.GameModule
import com.sokolovskiy.party.core.GameSession
import org.json.JSONObject

class WavelengthModule : GameModule {
    override val id = "wavelength"
    override val title = "Хвиля"
    override val subtitle = "Підказка одним словом — а решта вгадує, де на шкалі прихована ціль"
    override val minPlayers = Game.MIN_PLAYERS
    override val emoji = "📡"

    /** Shared across sessions so scales don't repeat when the game is reopened the same evening. */
    private var bank: SpectrumBank? = null

    override fun create(env: GameEnv): GameSession {
        val b = bank ?: SpectrumBank.fromJson(env.asset("wavelength-spectra.json")).also { bank = it }
        return Session(Game(b), env)
    }

    private class Session(private val game: Game, private val env: GameEnv) : GameSession {

        override fun onJoin(id: String, name: String) { game.add(id, name) }

        override fun onLeave(id: String) = game.remove(id)

        override fun handle(pid: String?, isHost: Boolean, msg: JSONObject, now: Long) {
            val isPsychic = pid != null && pid == game.psychicId
            when (msg.optString("t")) {
                "swap" -> if (isPsychic) game.swapSpectrum()
                "clue" -> if (isPsychic) game.submitClue(msg.optString("text"))
                "lock" -> pid?.let { game.lock(it, msg.optInt("v", 50)) }
            }
            if (isHost) when (msg.optString("t")) {
                "start", "next" -> game.startRound()
                "reveal" -> game.reveal()
                "settings" -> game.configureGoal(
                    mode = if (msg.has("goal")) Game.Goal.values().firstOrNull { it.name.equals(msg.optString("goal"), true) } else null,
                    points = if (msg.has("goalPoints")) msg.optInt("goalPoints") else null,
                    circles = if (msg.has("goalCircles")) msg.optInt("goalCircles") else null,
                )
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

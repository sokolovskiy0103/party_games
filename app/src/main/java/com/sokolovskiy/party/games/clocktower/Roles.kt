package com.sokolovskiy.party.games.clocktower

import org.json.JSONArray
import org.json.JSONObject

enum class Kind(val title: String, val good: Boolean) {
    TOWNSFOLK("Містяни", true),
    OUTSIDER("Чужинці", true),
    MINION("Поплічники", false),
    DEMON("Демон", false),
}

/** The «Trouble Brewing» script. Ability texts are short paraphrases for the role card. */
enum class Role(val kind: Kind, val title: String, val emoji: String, val ability: String) {
    WASHERWOMAN(Kind.TOWNSFOLK, "Праля", "🧺", "Першої ночі дізнаєшся: один із двох гравців — певний Містянин."),
    LIBRARIAN(Kind.TOWNSFOLK, "Бібліотекарка", "📚", "Першої ночі дізнаєшся: один із двох гравців — певний Чужинець (або що Чужинців у грі немає)."),
    INVESTIGATOR(Kind.TOWNSFOLK, "Слідчий", "🔎", "Першої ночі дізнаєшся: один із двох гравців — певний Поплічник."),
    CHEF(Kind.TOWNSFOLK, "Кухар", "🍳", "Першої ночі дізнаєшся, скільки пар злих гравців сидять поруч."),
    EMPATH(Kind.TOWNSFOLK, "Емпат", "💞", "Щоночі дізнаєшся, скільки з двох твоїх найближчих живих сусідів — злі (0, 1 або 2)."),
    FORTUNE_TELLER(Kind.TOWNSFOLK, "Ворожка", "🔮", "Щоночі обираєш двох гравців і дізнаєшся, чи є серед них Демон. Один добрий гравець для тебе хибно «фонить» як Демон."),
    UNDERTAKER(Kind.TOWNSFOLK, "Трунар", "⚰️", "Щоночі, крім першої, дізнаєшся роль гравця, якого стратили сьогодні вдень."),
    MONK(Kind.TOWNSFOLK, "Чернець", "🙏", "Щоночі, крім першої, обираєш іншого гравця: цієї ночі Демон не може його вбити."),
    RAVENKEEPER(Kind.TOWNSFOLK, "Крукар", "🐦", "Якщо тебе вбили вночі — обираєш гравця й дізнаєшся його роль."),
    VIRGIN(Kind.TOWNSFOLK, "Діва", "🕊️", "Першого разу, коли тебе висувають: якщо висунув Містянин — його одразу страчують."),
    SLAYER(Kind.TOWNSFOLK, "Мисливець", "🏹", "Раз за гру, вдень, публічно стріляєш у гравця: якщо це Демон — він помирає."),
    SOLDIER(Kind.TOWNSFOLK, "Солдат", "🛡️", "Демон не може тебе вбити."),
    MAYOR(Kind.TOWNSFOLK, "Мер", "🎩", "Якщо живих троє і день мине без страти — добро перемагає. Якщо Демон атакує тебе вночі, замість тебе може загинути інший гравець."),
    BUTLER(Kind.OUTSIDER, "Дворецький", "🤵", "Щоночі обираєш господаря (не себе). Завтра голосуєш «за» лише разом із господарем — інакше твій голос не рахується."),
    DRUNK(Kind.OUTSIDER, "П'яниця", "🍺", "Ти не знаєш, що ти П'яниця: тобі здається, що ти Містянин, але твоя здібність не працює."),
    RECLUSE(Kind.OUTSIDER, "Відлюдник", "🏚️", "Можеш «фонити» як злий, як Поплічник чи навіть як Демон — навіть після смерті."),
    SAINT(Kind.OUTSIDER, "Святий", "😇", "Якщо тебе стратять — перемагає зло."),
    POISONER(Kind.MINION, "Отруйник", "🧪", "Щоночі обираєш гравця: до кінця наступного дня його здібність не працює (й інформація може бути хибною)."),
    SPY(Kind.MINION, "Шпигун", "🕵️", "Щоночі бачиш гримуар — усі ролі. Можеш «фонити» як добрий, як Містянин чи Чужинець."),
    SCARLET_WOMAN(Kind.MINION, "Багряна жінка", "💋", "Якщо Демон помре, коли живих ще щонайменше п'ятеро, — ти стаєш Демоном."),
    BARON(Kind.MINION, "Барон", "🎖️", "У грі на двох Чужинців більше (замість двох Містян)."),
    IMP(Kind.DEMON, "Біс", "😈", "Щоночі, крім першої, обираєш гравця — він помирає. Обереш себе — помреш, а Бісом стане живий Поплічник."),
    ;

    fun json(): JSONObject = JSONObject().put("id", name).put("kind", kind.name).put("title", title).put("emoji", emoji).put("ability", ability)

    companion object {
        fun of(kind: Kind) = entries.filter { it.kind == kind }

        val scriptJson: JSONArray by lazy { JSONArray(entries.map { it.json() }) }
    }
}

/** How many of each kind a game with [players] players has (before the Baron's adjustment). */
data class Composition(val townsfolk: Int, val outsiders: Int, val minions: Int, val demons: Int = 1) {
    fun json(): JSONObject = JSONObject().put("TOWNSFOLK", townsfolk).put("OUTSIDER", outsiders).put("MINION", minions).put("DEMON", demons)

    companion object {
        private val table = mapOf(
            5 to Composition(3, 0, 1), 6 to Composition(3, 1, 1),
            7 to Composition(5, 0, 1), 8 to Composition(5, 1, 1), 9 to Composition(5, 2, 1),
            10 to Composition(7, 0, 2), 11 to Composition(7, 1, 2), 12 to Composition(7, 2, 2),
            13 to Composition(9, 0, 3), 14 to Composition(9, 1, 3), 15 to Composition(9, 2, 3),
        )

        fun forPlayers(n: Int): Composition? = table[n]
    }
}

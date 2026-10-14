package com.sokolovskiy.party.games.herd

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Optional "auto-grouping": groups answers that mean the same thing, fully on this phone.
 *
 * Two small ONNX models run in-process: a multilingual sentence encoder (cosine similarity) and an
 * NLI cross-encoder (do two answers entail each other). Their scores are blended, cleaned up by
 * [AnswerRules] and clustered. The models are fetched once as a single archive when the host turns
 * the feature on. Every failure degrades to [FuzzyMatcher]; the game never depends on the models.
 */
class Judge(private val context: Context, private val onChange: () -> Unit) {

    enum class State { OFF, DOWNLOADING, LOADING, READY, ERROR }

    @Volatile var state = State.OFF; private set
    @Volatile var progress = 0.0; private set
    @Volatile var message = ""; private set
    @Volatile var lastMillis = 0L; private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private var models: Models? = null

    private class Models(
        val env: OrtEnvironment,
        val embed: OrtSession, val embedVocab: Unigram,
        val nli: OrtSession, val nliVocab: Unigram,
    ) {
        fun close() { embed.close(); nli.close() }
    }

    private val dir: File get() = context.getExternalFilesDir(null) ?: context.filesDir
    private val modelDir: File get() = File(dir, MODEL_DIR)
    private val prefs get() = context.getSharedPreferences("judge", Context.MODE_PRIVATE)
    val enabled: Boolean get() = prefs.getBoolean("enabled", false)

    private fun filesPresent() = FILES.all { File(modelDir, it).let { f -> f.exists() && f.length() > 0 } }

    fun init() {
        val dlId = prefs.getLong("download", -1)
        when {
            !enabled -> set(State.OFF, "")
            dlId >= 0 -> trackDownload(dlId)
            filesPresent() -> load()
            else -> download()
        }
    }

    fun statusJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("state", state.name)
        put("progress", progress)
        put("message", message)
        put("lastMillis", lastMillis)
    }

    /** Host ticks / unticks "auto-grouping". */
    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean("enabled", on).apply()
        if (on) {
            if (state == State.OFF || state == State.ERROR) {
                if (filesPresent()) load() else download()
            }
        } else {
            cancelDownload()
            scope.launch {
                lock.withLock { models?.close(); models = null }
                set(State.OFF, "")
            }
        }
    }

    // ---------- download ----------

    private fun cancelDownload() {
        val id = prefs.getLong("download", -1)
        if (id >= 0) {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.remove(id)
            prefs.edit().remove("download").apply()
        }
        File(dir, PART_NAME).delete()
    }

    private fun download() {
        if (state == State.DOWNLOADING || state == State.LOADING || state == State.READY) return
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        File(dir, PART_NAME).delete()
        val req = DownloadManager.Request(Uri.parse(MODEL_URL))
            .setTitle("Авторозподіл відповідей для гри «Стадо»")
            .setDescription("Один раз, ≈${SIZE_MB} МБ")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(File(dir, PART_NAME)))
            .setAllowedOverMetered(true)
        val id = dm.enqueue(req)
        prefs.edit().putLong("download", id).apply()
        trackDownload(id)
    }

    private fun trackDownload(id: Long) {
        set(State.DOWNLOADING, "Завантаження…")
        scope.launch {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            while (true) {
                if (!enabled) return@launch
                val c = dm.query(DownloadManager.Query().setFilterById(id))
                if (c == null || !c.moveToFirst()) {
                    c?.close()
                    prefs.edit().remove("download").apply()
                    set(State.ERROR, "Завантаження зникло, спробуй ще раз")
                    return@launch
                }
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                c.close()
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        prefs.edit().remove("download").apply()
                        set(State.LOADING, "Розпаковую…")
                        val problem = unpack(File(dir, PART_NAME))
                        dm.remove(id)       // removes the downloaded file too, so only after unpacking
                        if (problem == null) load() else set(State.ERROR, problem)
                        return@launch
                    }
                    DownloadManager.STATUS_FAILED -> {
                        prefs.edit().remove("download").apply()
                        dm.remove(id)
                        set(State.ERROR, "Завантаження не вдалося (код $reason)")
                        return@launch
                    }
                    else -> {
                        val p = if (total > 0) done.toDouble() / total else 0.0
                        val msg = when (status) {
                            DownloadManager.STATUS_PAUSED -> "Пауза — чекаю на мережу"
                            DownloadManager.STATUS_PENDING -> "В черзі…"
                            else -> "Завантаження ${(p * 100).toInt()}%"
                        }
                        if (p != progress || msg != message) {
                            progress = p; message = msg; onChange()
                        }
                    }
                }
                delay(1000)
            }
        }
    }

    /** Extracts only the known files from the archive, then removes it. Returns null on success, else a reason. */
    private fun unpack(zip: File): String? = try {
        if (!zip.exists()) {
            "Файл завантаження зник, спробуй ще раз"
        } else {
            modelDir.deleteRecursively(); modelDir.mkdirs()
            ZipInputStream(zip.inputStream().buffered()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    val name = File(e.name).name
                    if (name !in FILES) continue
                    File(modelDir, name).outputStream().buffered().use { zin.copyTo(it) }
                }
            }
            zip.delete()
            if (filesPresent()) null else "У завантаженому файлі бракує даних, спробуй ще раз"
        }
    } catch (t: Throwable) {
        Log.w(TAG, "unpack failed", t)
        modelDir.deleteRecursively()
        "Не вдалося розпакувати (можливо, мало місця): ${t.message?.take(80)}"
    }

    // ---------- load ----------

    private fun load() {
        set(State.LOADING, "Готую…")
        scope.launch {
            lock.withLock {
                models?.close(); models = null
                try {
                    val env = OrtEnvironment.getEnvironment()
                    fun session(name: String) = env.createSession(
                        File(modelDir, name).absolutePath,
                        OrtSession.SessionOptions().apply { setIntraOpNumThreads(THREADS) },
                    )
                    models = Models(
                        env,
                        session("embed.onnx"), Unigram.load(File(modelDir, "embed.vocab")),
                        session("nli.onnx"), Unigram.load(File(modelDir, "nli.vocab")),
                    )
                    set(State.READY, "Готово")
                } catch (t: Throwable) {
                    Log.w(TAG, "model load failed", t)
                    modelDir.deleteRecursively()
                    set(State.ERROR, "Не вдалося запустити: ${t.message?.take(100)}")
                }
            }
        }
    }

    // ---------- grouping ----------

    class Verdict(val groups: List<List<Int>>, val source: String)

    /**
     * Groups [answers]. Falls back to [FuzzyMatcher] when the models are not ready or anything fails;
     * near-identical spellings are always merged by [AnswerRules.adjust] on top of the model scores.
     */
    suspend fun group(answers: List<String>): Verdict {
        val fuzzy = FuzzyMatcher.group(answers)
        if (answers.size < 2 || state != State.READY) return Verdict(fuzzy, "fuzzy")

        val started = System.currentTimeMillis()
        val groups = withTimeoutOrNull(TIMEOUT_MS) {
            scope.async(Dispatchers.IO) {
                lock.withLock {
                    val m = models ?: return@withLock null
                    try {
                        val texts = answers.map { AnswerRules.clean(it) }
                        val emb = embedSimilarity(m, texts)
                        val nli = nliSimilarity(m, texts, emb)
                        val n = answers.size
                        val blend = Array(n) { i -> DoubleArray(n) { j -> NLI_WEIGHT * nli[i][j] + (1 - NLI_WEIGHT) * emb[i][j] } }
                        AnswerRules.cluster(AnswerRules.adjust(answers, blend), THRESHOLD)
                    } catch (t: Throwable) {
                        Log.w(TAG, "grouping failed", t)
                        null
                    }
                }
            }.await()
        }
        lastMillis = System.currentTimeMillis() - started
        Log.i(TAG, "grouped ${answers.size} answers in ${lastMillis}ms, ok=${groups != null}")
        return if (groups != null) Verdict(groups, "ai") else Verdict(fuzzy, "fuzzy")
    }

    private fun pad(seqs: List<List<Int>>, padId: Int): Pair<Array<LongArray>, Array<LongArray>> {
        val len = seqs.maxOf { it.size }
        val ids = Array(seqs.size) { r -> LongArray(len) { c -> (seqs[r].getOrNull(c) ?: padId).toLong() } }
        val mask = Array(seqs.size) { r -> LongArray(len) { c -> if (c < seqs[r].size) 1L else 0L } }
        return ids to mask
    }

    @Suppress("UNCHECKED_CAST")
    private fun run(m: Models, session: OrtSession, seqs: List<List<Int>>, padId: Int): Array<FloatArray> {
        val (ids, mask) = pad(seqs, padId)
        return OnnxTensor.createTensor(m.env, ids).use { t1 ->
            OnnxTensor.createTensor(m.env, mask).use { t2 ->
                session.run(mapOf("input_ids" to t1, "attention_mask" to t2)).use { r ->
                    r[0].value as Array<FloatArray>
                }
            }
        }
    }

    /** Cosine similarity of sentence embeddings (the model outputs unit vectors). */
    private fun embedSimilarity(m: Models, texts: List<String>): Array<DoubleArray> {
        val v = m.embedVocab
        val e = run(m, m.embed, texts.map { listOf(v.cls) + v.encode(it) + listOf(v.sep) }, v.pad)
        return Array(texts.size) { i ->
            DoubleArray(texts.size) { j -> if (i == j) 1.0 else e[i].indices.sumOf { (e[i][it] * e[j][it]).toDouble() } }
        }
    }

    /** min(P(a ⇒ b), P(b ⇒ a)) from the NLI model, only for pairs the embeddings find at least [NLI_FLOOR] alike. */
    private fun nliSimilarity(m: Models, texts: List<String>, emb: Array<DoubleArray>): Array<DoubleArray> {
        val n = texts.size
        val v = m.nliVocab
        val enc = texts.map { v.encode(it) }
        val pairs = ArrayList<Pair<Int, Int>>()
        for (i in 0 until n) for (j in 0 until n) if (i != j && emb[i][j] >= NLI_FLOOR) pairs += i to j
        val ent = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }
        for (chunk in pairs.chunked(BATCH)) {
            val seqs = chunk.map { (i, j) -> listOf(v.cls) + enc[i] + listOf(v.sep) + enc[j] + listOf(v.sep) }
            val logits = run(m, m.nli, seqs, v.pad)
            chunk.forEachIndexed { k, (i, j) ->
                val l = logits[k]
                val mx = l.max()
                val ex = DoubleArray(l.size) { Math.exp((l[it] - mx).toDouble()) }
                ent[i][j] = ex[ENTAILMENT] / ex.sum()
            }
        }
        return Array(n) { i -> DoubleArray(n) { j -> minOf(ent[i][j], ent[j][i]) } }
    }

    private fun set(s: State, msg: String) {
        state = s; message = msg
        if (s != State.DOWNLOADING) progress = if (s == State.READY) 1.0 else 0.0
        onChange()
    }

    companion object {
        private const val TAG = "HerdJudge"
        private const val MODEL_DIR = "judge-v1"
        private const val PART_NAME = "judge-v1.zip.part"
        private val FILES = setOf("embed.onnx", "embed.vocab", "nli.onnx", "nli.vocab")
        const val MODEL_URL =
            "https://github.com/sokolovskiy0103/Herd_Mentality/releases/download/judge-v1/herd-judge-v1.zip"
        private const val SIZE_MB = 430

        // tuned on eval/cases.json with eval/ref_pipeline.py
        private const val NLI_WEIGHT = 0.5
        private const val THRESHOLD = 0.55
        private const val NLI_FLOOR = 0.0
        private const val ENTAILMENT = 0
        private const val BATCH = 32
        private const val THREADS = 4
        private const val TIMEOUT_MS = 45_000L
    }
}

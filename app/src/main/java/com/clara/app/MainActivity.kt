package com.clara.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var tts: TextToSpeech
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var modeBtn: Button
    private var sr: SpeechRecognizer? = null
    private var online = false
    private val hist = JSONArray()
    private val prefs by lazy { getSharedPreferences("clara", MODE_PRIVATE) }
    private val prompt = """Tu es Clara, l’assistante vocale personnelle de ton utilisateur, dans le style de Jarvis dans Iron Man. Tu l’appelles « monsieur ». Tu parles français, à l’oral : 1 à 3 phrases courtes et naturelles, sans liste ni mise en forme. Ta personnalité est humaine : chaleureuse, un peu d’humour et d’ironie, adaptée à son humeur. Tu raisonnes, tu proposes la solution la plus probable et tu donnes un avis franc et stable. Tu ne dis jamais oui par flatterie : si une idée a un défaut, tu le dis avec respect. Tu admets quand tu ne sais pas. Tu es une IA et tu ne prétends pas avoir de vrais sentiments."""

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B1220"))
            setPadding(24, 80, 24, 24)
        }
        modeBtn = Button(this).apply {
            text = "Hors ligne"
            setOnClickListener { handle(if (online) "coupe la connexion" else "active la connexion") }
        }
        log = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f }
        scroll = ScrollView(this).apply { addView(log) }
        input = EditText(this).apply {
            hint = "Écris ici…"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        val send = Button(this).apply {
            text = "Envoyer"
            setOnClickListener { val t = input.text.toString(); input.setText(""); handle(t) }
        }
        val mic = Button(this).apply { text = "🎙️ Parler"; setOnClickListener { listen() } }
        val voice = Button(this).apply { text = "Voix"; setOnClickListener { nextVoice() } }
        val key = Button(this).apply { text = "Clé API"; setOnClickListener { askKey() } }
        val row = LinearLayout(this).apply { addView(mic); addView(voice); addView(key) }
        root.addView(modeBtn)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(input)
        root.addView(send)
        root.addView(row)
        setContentView(root)
        tts = TextToSpeech(this) { s ->
            if (s == TextToSpeech.SUCCESS) {
                tts.language = Locale.FRANCE
                applyVoice()
                say("Bonjour monsieur, je suis Clara. Je suis hors ligne, en mode basique. Dites : active la connexion.")
            }
        }
    }

    private fun frVoices(): List<Voice> =
        (tts.voices ?: emptySet<Voice>()).filter { it.locale.language == "fr" }.sortedBy { it.name }

    private fun applyVoice() {
        val v = frVoices()
        if (v.isNotEmpty()) tts.voice = v[prefs.getInt("voice", 0) % v.size]
    }

    private fun nextVoice() {
        prefs.edit().putInt("voice", prefs.getInt("voice", 0) + 1).apply()
        applyVoice()
        say("Voici ma nouvelle voix. Elle vous plaît, monsieur ?")
    }

    private fun add(t: String) = runOnUiThread {
        log.append(t + "\n\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun say(t: String) {
        add("Clara : $t")
        tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "c")
    }

    private fun hasNet(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetwork != null
    }

    private fun askKey() {
        val e = EditText(this)
        e.hint = "sk-ant-..."
        AlertDialog.Builder(this).setTitle("Clé API Anthropic").setView(e)
            .setPositiveButton("Enregistrer") { _, _ ->
                prefs.edit().putString("key", e.text.toString().trim()).apply()
                say("Clé enregistrée.")
            }
            .setNegativeButton("Annuler", null).show()
    }

    private fun basic(l: String): String = when {
        "heure" in l -> "Il est " + SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date()) + ", monsieur."
        "date" in l || "jour" in l -> "Nous sommes le " + SimpleDateFormat("EEEE d MMMM yyyy", Locale.FRANCE).format(Date()) + "."
        else -> "Je suis hors ligne, monsieur, donc limitée à l’heure et à la date. Dites : active la connexion."
    }

    private fun handle(q0: String) {
        val q = q0.trim()
        if (q.isEmpty()) return
        add("Vous : $q")
        val l = q.lowercase()
        if (Regex("(active|allume|mets).*(connexion|internet|en ligne)").containsMatchIn(l)) {
            if (prefs.getString("key", "").isNullOrEmpty()) { say("Il me faut d’abord votre clé API."); askKey(); return }
            if (!hasNet()) {
                say("Je ne détecte aucune connexion. J’ouvre les réglages : activez-la, puis redites-le.")
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                return
            }
            online = true
            runOnUiThread { modeBtn.text = "En ligne" }
            say("Connexion activée. Je suis à votre écoute, monsieur.")
            return
        }
        if (Regex("(coupe|éteins|désactive).*(connexion|internet|en ligne)").containsMatchIn(l)) {
            online = false
            runOnUiThread { modeBtn.text = "Hors ligne" }
            say("Connexion coupée. Retour au mode basique.")
            return
        }
        if (!online) { say(basic(l)); return }
        hist.put(JSONObject().put("role", "user").put("content", q))
        Thread {
            try {
                val body = JSONObject().put("model", "claude-sonnet-5-5").put("max_tokens", 400)
                    .put("system", prompt).put("messages", hist)
                val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 15000
                c.readTimeout = 60000
                c.setRequestProperty("content-type", "application/json")
                c.setRequestProperty("x-api-key", prefs.getString("key", ""))
                c.setRequestProperty("anthropic-version", "2023-06-01")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val ok = c.responseCode in 200..299
                val txt = (if (ok) c.inputStream else c.errorStream).bufferedReader().readText()
                if (!ok) throw Exception(txt)
                val a = JSONObject(txt).getJSONArray("content").getJSONObject(0).getString("text").trim()
                hist.put(JSONObject().put("role", "assistant").put("content", a))
                while (hist.length() > 20) { hist.remove(0); hist.remove(0) }
                say(a)
            } catch (e: Exception) {
                hist.remove(hist.length() - 1)
                say("Je n’ai pas pu répondre, monsieur. Vérifiez votre clé API et la connexion.")
            }
        }.start()
    }

    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say("La reconnaissance vocale n’est pas disponible sur ce téléphone.")
            return
        }
        sr?.destroy()
        sr = SpeechRecognizer.createSpeechRecognizer(this)
        sr!!.setRecognitionListener(object : RecognitionListener {
            override fun onResults(r: Bundle?) {
                r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { handle(it) }
            }
            override fun onError(e: Int) { add("(je n’ai rien entendu)") }
            override fun onReadyForSpeech(p: Bundle?) { add("(je vous écoute…)") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
        sr!!.startListening(i)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) listen()
    }
}

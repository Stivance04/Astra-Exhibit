    package com.stivance.drawtune

    import android.media.AudioAttributes
    import android.media.AudioFormat
    import android.media.AudioTrack
    import android.os.Bundle
    import android.os.Handler
    import android.os.Looper
    import android.content.Context
    import java.net.DatagramPacket
    import java.net.DatagramSocket
    import java.net.InetAddress
    import java.net.SocketTimeoutException
    import java.util.concurrent.Executors
    import androidx.activity.ComponentActivity
    import androidx.activity.compose.setContent
    import androidx.compose.foundation.Canvas
    import androidx.compose.foundation.gestures.awaitEachGesture
    import androidx.compose.foundation.gestures.awaitFirstDown
    import androidx.compose.foundation.background
    import androidx.compose.foundation.layout.Arrangement
    import androidx.compose.foundation.layout.Box
    import androidx.compose.foundation.layout.Column
    import androidx.compose.foundation.layout.ColumnScope
    import androidx.compose.foundation.lazy.LazyColumn
    import androidx.compose.foundation.lazy.items
    import androidx.compose.foundation.layout.Row
    import androidx.compose.foundation.layout.Spacer
    import androidx.compose.foundation.layout.fillMaxSize
    import androidx.compose.foundation.layout.fillMaxWidth
    import androidx.compose.foundation.layout.height
    import androidx.compose.foundation.layout.offset
    import androidx.compose.foundation.layout.fillMaxHeight
    import androidx.compose.foundation.layout.padding
    import androidx.compose.foundation.shape.RoundedCornerShape
    import androidx.compose.material3.AlertDialog
    import androidx.compose.material3.Button
    import androidx.compose.material3.ButtonDefaults
    import androidx.compose.material3.Card
    import androidx.compose.material3.CardDefaults
    import androidx.compose.material3.MaterialTheme
    import androidx.compose.material3.Slider
    import androidx.compose.material3.Surface
    import androidx.compose.material3.Text
    import androidx.compose.material3.TextButton
    import androidx.compose.material3.Tab
    import androidx.compose.material3.TabRow
    import androidx.compose.material3.OutlinedTextField
    import androidx.compose.runtime.Composable
    import androidx.compose.runtime.DisposableEffect
    import androidx.compose.ui.platform.LocalContext
    import androidx.compose.runtime.LaunchedEffect
    import androidx.compose.runtime.getValue
    import androidx.compose.runtime.mutableStateListOf
    import androidx.compose.runtime.mutableStateOf
    import androidx.compose.runtime.remember
    import androidx.compose.runtime.rememberUpdatedState
    import androidx.compose.runtime.setValue
    import androidx.compose.ui.Alignment
    import androidx.compose.ui.Modifier
    import androidx.compose.ui.geometry.Offset
    import androidx.compose.ui.graphics.Color
    import androidx.compose.ui.graphics.Path
    import androidx.compose.ui.graphics.StrokeCap
    import androidx.compose.ui.graphics.StrokeJoin
    import androidx.compose.ui.graphics.drawscope.Stroke
    import androidx.compose.ui.input.pointer.pointerInput
    import androidx.compose.ui.text.font.FontWeight
    import androidx.compose.ui.unit.dp
    import androidx.compose.ui.unit.sp
    import androidx.compose.foundation.layout.width
    import androidx.compose.foundation.layout.BoxWithConstraints
    import kotlinx.coroutines.CancellationException
    import kotlinx.coroutines.delay
    import kotlin.math.PI
    import kotlin.math.exp
    import kotlin.math.sin
    import kotlin.math.cos
    import kotlin.math.floor
    import kotlin.math.roundToInt
    import kotlin.math.abs
    import org.json.JSONArray
    import org.json.JSONObject


    // ============================================================
    // ESP32 WI-FI UDP CONNECTION
    // ============================================================

    class DrawTuneWifiManager {

        companion object {
            const val DEFAULT_PORT = 4210
        }

        private var socket: DatagramSocket? = null
        private var targetAddress: InetAddress? = null
        private val mainHandler = Handler(Looper.getMainLooper())
        private val sendExecutor = Executors.newSingleThreadExecutor()

        @Volatile
        var connected: Boolean = false
            private set

        fun connect(
            ipAddress: String,
            onResult: (Boolean, String) -> Unit
        ) {
            disconnect()

            Thread {
                try {
                    val address = InetAddress.getByName(ipAddress.trim())
                    val newSocket = DatagramSocket()
                    newSocket.soTimeout = 1200

                    socket = newSocket
                    targetAddress = address

                    sendInternal("HELLO,DRAWTUNE,1")

                    val buffer = ByteArray(256)
                    val packet = DatagramPacket(buffer, buffer.size)

                    try {
                        newSocket.receive(packet)
                        val response = String(
                            packet.data,
                            0,
                            packet.length,
                            Charsets.UTF_8
                        ).trim()

                        if (response.startsWith("READY")) {
                            connected = true
                            mainHandler.post {
                                onResult(true, "ESP32 connected")
                            }
                        } else {
                            disconnect()
                            mainHandler.post {
                                onResult(false, "ESP32 replied: $response")
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        disconnect()
                        mainHandler.post {
                            onResult(
                                false,
                                "No ESP32 response. Check Wi-Fi and IP."
                            )
                        }
                    }
                } catch (e: Exception) {
                    disconnect()
                    mainHandler.post {
                        onResult(
                            false,
                            "Connection failed: ${e.message ?: "unknown error"}"
                        )
                    }
                }
            }.start()
        }

        private fun sendInternal(message: String) {
            val currentSocket = socket ?: return
            val address = targetAddress ?: return

            val data = message.toByteArray(Charsets.UTF_8)
            val packet = DatagramPacket(
                data,
                data.size,
                address,
                DEFAULT_PORT
            )

            currentSocket.send(packet)
        }

        fun send(message: String) {
            if (!connected) return

            sendExecutor.execute {
                try {
                    if (connected) {
                        sendInternal(message)
                    }
                } catch (_: Exception) {
                    connected = false
                }
            }
        }

        fun disconnect() {
            connected = false
            try {
                socket?.close()
            } catch (_: Exception) {
            }
            socket = null
            targetAddress = null
        }

        fun shutdown() {
            disconnect()
            sendExecutor.shutdownNow()
        }
    }


    // ============================================================
    // MAIN ACTIVITY
    // ============================================================

    class MainActivity : ComponentActivity() {

        private lateinit var audioEngine: DrawTuneAudioEngine

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            audioEngine = DrawTuneAudioEngine()

            setContent {
                MaterialTheme {
                    DrawTuneApp(
                        audioEngine = audioEngine
                    )
                }
            }
        }

        override fun onDestroy() {
            audioEngine.release()
            super.onDestroy()
        }
    }


    // ============================================================
    // DRAWING STROKE
    // ============================================================

    class DrawingStroke {

        val points = mutableStateListOf<Offset>()
    }


    // ============================================================
    // RECORDED NOTE
    // ============================================================

    data class RecordedNote(
        val note: String,
        val startTime: Long,
        val duration: Long
    )



    // ============================================================
    // SAVED TUNE DATA
    // ============================================================

    data class SavedTune(
        val id: Long,
        val name: String,
        val instrument: String,
        val key: String,
        val scale: String,
        val octave: String,
        val tempo: Int,
        val tuning: Int,
        val drawing: List<List<Offset>>,
        val notes: List<RecordedNote>
    )

    private const val DRAW_TUNE_PREFS = "drawtune_storage"
    private const val SAVED_TUNES_KEY = "saved_tunes"

    private fun savedTuneToJson(tune: SavedTune): JSONObject {
        val json = JSONObject()

        json.put("id", tune.id)
        json.put("name", tune.name)
        json.put("instrument", tune.instrument)
        json.put("key", tune.key)
        json.put("scale", tune.scale)
        json.put("octave", tune.octave)
        json.put("tempo", tune.tempo)
        json.put("tuning", tune.tuning)

        val strokesArray = JSONArray()
        tune.drawing.forEach { stroke ->
            val strokeArray = JSONArray()
            stroke.forEach { point ->
                val pointObject = JSONObject()
                pointObject.put("x", point.x.toDouble())
                pointObject.put("y", point.y.toDouble())
                strokeArray.put(pointObject)
            }
            strokesArray.put(strokeArray)
        }
        json.put("drawing", strokesArray)

        val notesArray = JSONArray()
        tune.notes.forEach { note ->
            val noteObject = JSONObject()
            noteObject.put("note", note.note)
            noteObject.put("startTime", note.startTime)
            noteObject.put("duration", note.duration)
            notesArray.put(noteObject)
        }
        json.put("notes", notesArray)

        return json
    }

    private fun savedTuneFromJson(json: JSONObject): SavedTune {
        val drawing = mutableListOf<List<Offset>>()
        val strokesArray = json.optJSONArray("drawing") ?: JSONArray()

        for (i in 0 until strokesArray.length()) {
            val strokeArray = strokesArray.optJSONArray(i) ?: JSONArray()
            val points = mutableListOf<Offset>()

            for (j in 0 until strokeArray.length()) {
                val point = strokeArray.optJSONObject(j) ?: continue
                points.add(
                    Offset(
                        point.optDouble("x", 0.0).toFloat(),
                        point.optDouble("y", 0.0).toFloat()
                    )
                )
            }

            if (points.isNotEmpty()) {
                drawing.add(points)
            }
        }

        val notes = mutableListOf<RecordedNote>()
        val notesArray = json.optJSONArray("notes") ?: JSONArray()

        for (i in 0 until notesArray.length()) {
            val note = notesArray.optJSONObject(i) ?: continue
            notes.add(
                RecordedNote(
                    note = note.optString("note", "C4"),
                    startTime = note.optLong("startTime", 0L),
                    duration = note.optLong("duration", 50L)
                )
            )
        }

        return SavedTune(
            id = json.optLong("id", System.currentTimeMillis()),
            name = json.optString("name", "Untitled Tune"),
            instrument = json.optString("instrument", "Piano"),
            key = json.optString("key", "C"),
            scale = json.optString("scale", "Major"),
            octave = json.optString("octave", "Middle"),
            tempo = json.optInt("tempo", 120),
            tuning = json.optInt("tuning", 440),
            drawing = drawing,
            notes = notes
        )
    }

    private fun loadSavedTunes(context: Context): List<SavedTune> {
        return try {
            val prefs = context.getSharedPreferences(
                DRAW_TUNE_PREFS,
                Context.MODE_PRIVATE
            )

            val raw = prefs.getString(
                SAVED_TUNES_KEY,
                "[]"
            ) ?: "[]"

            val array = JSONArray(raw)
            val tunes = mutableListOf<SavedTune>()

            for (i in 0 until array.length()) {
                val objectJson = array.optJSONObject(i) ?: continue
                tunes.add(savedTuneFromJson(objectJson))
            }

            tunes.sortedByDescending { it.id }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveSavedTunes(
        context: Context,
        tunes: List<SavedTune>
    ) {
        val array = JSONArray()

        tunes.forEach { tune ->
            array.put(savedTuneToJson(tune))
        }

        context.getSharedPreferences(
            DRAW_TUNE_PREFS,
            Context.MODE_PRIVATE
        )
            .edit()
            .putString(
                SAVED_TUNES_KEY,
                array.toString()
            )
            .apply()
    }

    // ============================================================
    // AUDIO ENGINE
    // ============================================================

    class DrawTuneAudioEngine {

        private val sampleRate = 44100
        private var audioTrack: AudioTrack? = null
        private var currentFrequency = 261.625565
        private var currentInstrument = "Piano"
        private var isPlaying = false
        private var audioThread: Thread? = null
        private val lock = Any()
        private var noteStartNanos = System.nanoTime()
        private var releaseNanos = 0L
        private var noteGate = false
        private var phase = 0.0
        private var noiseState = 0x12345678L

        fun start() {
            synchronized(lock) {
                noteGate = true
                noteStartNanos = System.nanoTime()
                releaseNanos = 0L
            }

            if (isPlaying) return

            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBufferSize, 4096)
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            audioTrack = AudioTrack(
                attributes,
                format,
                bufferSize,
                AudioTrack.MODE_STREAM,
                android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            isPlaying = true
            audioTrack?.play()

            audioThread = Thread {
                val buffer = ShortArray(1024)
                while (isPlaying) {
                    val frequency: Double
                    val instrument: String
                    val startNanos: Long
                    val gate: Boolean
                    val releaseTime: Long

                    synchronized(lock) {
                        frequency = currentFrequency
                        instrument = currentInstrument
                        startNanos = noteStartNanos
                        gate = noteGate
                        releaseTime = releaseNanos
                    }

                    for (i in buffer.indices) {
                        val now = System.nanoTime()
                        val t = ((now - startNanos).coerceAtLeast(0L)) / 1_000_000_000.0
                        val phaseIncrement = 2.0 * PI * frequency / sampleRate

                        val releaseEnv = if (!gate && releaseTime > 0L) {
                            val r = ((now - releaseTime).coerceAtLeast(0L)) / 1_000_000_000.0
                            exp(-r / releaseTimeConstant(instrument))
                        } else if (!gate) {
                            0.0
                        } else {
                            1.0
                        }

                        val sample = when (instrument) {
                            "Piano" -> pianoSample(phase, t) * releaseEnv
                            "Guitar" -> guitarSample(phase, t) * releaseEnv
                            "Violin" -> violinSample(phase, t) * releaseEnv
                            "Synth" -> synthSample(phase, t) * releaseEnv
                            "Drum" -> drumSample(phase, t, frequency) * releaseEnv
                            else -> sin(phase) * 0.18 * releaseEnv
                        }

                        buffer[i] = (sample * Short.MAX_VALUE)
                            .toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            .toShort()

                        phase += phaseIncrement
                        while (phase >= 2.0 * PI) phase -= 2.0 * PI
                    }

                    audioTrack?.write(buffer, 0, buffer.size)

                    if (!gate && releaseTime > 0L) {
                        val releasedFor = (System.nanoTime() - releaseTime) / 1_000_000L
                        if (releasedFor > releaseDurationMs(instrument)) {
                            stopTrackOnly()
                        }
                    }
                }
            }
            audioThread?.start()
        }

        private fun releaseTimeConstant(instrument: String): Double = when (instrument) {
            "Piano" -> 0.22
            "Guitar" -> 0.18
            "Violin" -> 0.16
            "Synth" -> 0.10
            "Drum" -> 0.035
            else -> 0.10
        }

        private fun releaseDurationMs(instrument: String): Long = when (instrument) {
            "Piano" -> 900L
            "Guitar" -> 700L
            "Violin" -> 500L
            "Synth" -> 350L
            "Drum" -> 180L
            else -> 300L
        }

        private fun pianoSample(p: Double, t: Double): Double {
            val attack = 1.0 - exp(-t / 0.0025)
            val decay = exp(-t / 2.0)
            val highDecay = exp(-t / 0.16)
            val body =
                0.86 * sin(p) +
                        0.46 * sin(p * 2.01) +
                        0.27 * sin(p * 3.01) +
                        0.18 * sin(p * 4.02) +
                        0.12 * sin(p * 5.03) +
                        0.08 * sin(p * 6.05) +
                        0.055 * sin(p * 7.07)
            val hammer = highDecay * (
                    0.10 * sin(p * 8.0) +
                            0.07 * sin(p * 10.0) +
                            0.04 * sin(p * 12.0)
                    )
            return (attack * decay * body + attack * hammer) * 0.13
        }

        private fun guitarSample(p: Double, t: Double): Double {
            val attack = 1.0 - exp(-t / 0.0015)
            val decay = exp(-t / 2.4)
            val brightness = exp(-t / 0.10)
            val pluck =
                0.72 * sin(p) +
                        0.26 * sin(p * 2.0) +
                        0.16 * sin(p * 3.0) +
                        0.11 * sin(p * 4.0) +
                        0.08 * sin(p * 5.0)
            val pick = brightness * (
                    0.16 * sin(p * 6.0) +
                            0.10 * sin(p * 8.0) +
                            0.06 * sin(p * 9.0)
                    )
            return (attack * decay * pluck + pick) * 0.18
        }

        private fun violinSample(p: Double, t: Double): Double {
            val attack = 1.0 - exp(-t / 0.11)
            val bowPulse = 0.94 + 0.06 * sin(2.0 * PI * 7.0 * t)
            val vibratoRatio = 1.0 + 0.0048 * sin(2.0 * PI * 5.3 * t)
            val vp = p * vibratoRatio
            val body =
                0.62 * sin(vp) +
                        0.40 * sin(vp * 2.0) +
                        0.30 * sin(vp * 3.0) +
                        0.22 * sin(vp * 4.0) +
                        0.16 * sin(vp * 5.0) +
                        0.11 * sin(vp * 6.0) +
                        0.07 * sin(vp * 7.0)
            return attack * bowPulse * body * 0.12
        }

        private fun synthSample(p: Double, t: Double): Double {
            val cycle = (p / (2.0 * PI)) - floor(p / (2.0 * PI))
            val saw = 2.0 * cycle - 1.0
            val sub = sin(p * 0.5)
            val pulse = if (cycle < 0.42) 1.0 else -1.0
            val env = 0.85 + 0.15 * exp(-t / 0.08)
            return (0.48 * saw + 0.25 * sub + 0.15 * pulse +
                    0.10 * sin(p * 2.0) + 0.06 * sin(p * 3.0)) * 0.16 * env
        }

        private fun nextNoise(): Double {
            noiseState = noiseState xor (noiseState shl 13)
            noiseState = noiseState xor (noiseState shr 17)
            noiseState = noiseState xor (noiseState shl 5)
            return (noiseState and 0xFFFFL) / 32768.0 - 1.0
        }

        private fun drumSample(p: Double, t: Double, frequency: Double): Double {
            val kickFrequency = (frequency * 1.8).coerceIn(55.0, 180.0)
            val kickPhase = 2.0 * PI * kickFrequency * t
            val sweep = 2.0 * PI * (kickFrequency + 90.0 * exp(-t / 0.035)) * t
            val kick = exp(-t / 0.18) * sin(sweep) * 0.52
            val click = exp(-t / 0.012) * nextNoise() * 0.34
            val snare = exp(-t / 0.07) * nextNoise() * 0.22
            val hat = exp(-t / 0.025) * nextNoise() * 0.12
            return kick + click + snare + hat + sin(kickPhase) * exp(-t / 0.12) * 0.08
        }

        fun setNote(note: String) {
            val frequency = frequencyFromNote(note)
            synchronized(lock) {
                currentFrequency = frequency
                noteStartNanos = System.nanoTime()
                releaseNanos = 0L
                noteGate = true
                phase = 0.0
            }
        }

        fun setInstrument(instrument: String) {
            synchronized(lock) { currentInstrument = instrument }
        }

        fun releaseNote() {
            synchronized(lock) {
                if (noteGate) {
                    noteGate = false
                    releaseNanos = System.nanoTime()
                }
            }
        }

        private fun stopTrackOnly() {
            isPlaying = false
        }

        fun stop() {
            synchronized(lock) {
                noteGate = false
                releaseNanos = 0L
            }
            isPlaying = false
            try { audioThread?.join(120) } catch (_: InterruptedException) {}
            audioThread = null
            try { audioTrack?.stop() } catch (_: Exception) {}
            audioTrack?.release()
            audioTrack = null
        }

        fun release() { stop() }
    }


    // ============================================================
    // MAIN DRAW TUNE APP
    // ============================================================

    @Composable
    fun DrawTuneApp(
        audioEngine: DrawTuneAudioEngine
    ) {

        val wifiManager = remember { DrawTuneWifiManager() }

        var wifiIpAddress by remember {
            mutableStateOf("10.175.33.200")
        }

        var wifiConnected by remember {
            mutableStateOf(false)
        }

        var wifiStatus by remember {
            mutableStateOf("ESP32 disconnected")
        }

        DisposableEffect(Unit) {
            onDispose {
                wifiManager.shutdown()
            }
        }

        val context = LocalContext.current

        // ========================================================
        // SAVED TUNES
        // ========================================================

        var savedTunes by remember {
            mutableStateOf(
                loadSavedTunes(context)
            )
        }

        var showLibrary by remember {
            mutableStateOf(false)
        }

        var showSaveDialog by remember {
            mutableStateOf(false)
        }

        var tuneName by remember {
            mutableStateOf("")
        }

        // ========================================================
        // DRAWING DATA
        // ========================================================

        val strokes =
            remember {
                mutableStateListOf<DrawingStroke>()
            }

        // ========================================================
        // RECORDED MUSIC DATA
        // ========================================================

        val recordedNotes =
            remember {
                mutableStateListOf<RecordedNote>()
            }

        // ========================================================
        // UI STATE
        // ========================================================

        var currentNote by remember {
            mutableStateOf("C4")
        }

        var isDrawing by remember {
            mutableStateOf(false)
        }

        var isPlayingTune by remember {
            mutableStateOf(false)
        }

        // ========================================================
        // CREATE MODE TAB
        // ========================================================

        var createMode by remember {
            mutableStateOf(0) // 0 = Draw, 1 = Virtual Instrument
        }

        // ========================================================
        // RECORDING STATE
        // ========================================================

        var recordingStartTime by remember {
            mutableStateOf(0L)
        }

        var currentRecordedNote by remember {
            mutableStateOf<String?>(null)
        }

        var currentNoteStartTime by remember {
            mutableStateOf(0L)
        }

        // When editing a saved tune, newly recorded notes are appended
        // after the existing tune instead of starting at time zero.
        var recordingBaseOffset by remember {
            mutableStateOf(0L)
        }


        // ========================================================
        // MUSIC SETTINGS
        // ========================================================

        var selectedInstrument by remember {
            mutableStateOf("Piano")
        }

        var selectedKey by remember {
            mutableStateOf("C")
        }

        var selectedScale by remember {
            mutableStateOf("Major")
        }

        var selectedOctave by remember {
            mutableStateOf("Middle")
        }

        var tempo by remember {
            mutableStateOf(120)
        }

        var tuning by remember {
            mutableStateOf(440)
        }

        fun sendEsp32Config() {
            wifiManager.send(
                "CONFIG,$selectedInstrument,$selectedKey,$selectedScale," +
                        "$selectedOctave,$tempo,$tuning"
            )
        }


        // ========================================================
        // SETTINGS DIALOG
        // ========================================================

        var activeSetting by remember {
            mutableStateOf<String?>(null)
        }


        // ========================================================
        // LATEST SETTINGS FOR POINTER INPUT
        // ========================================================

        val latestInstrument by rememberUpdatedState(
            selectedInstrument
        )

        val latestKey by rememberUpdatedState(
            selectedKey
        )

        val latestScale by rememberUpdatedState(
            selectedScale
        )

        val latestOctave by rememberUpdatedState(
            selectedOctave
        )

        val latestTempo by rememberUpdatedState(
            tempo
        )

        val latestTuning by rememberUpdatedState(
            tuning
        )

        // Send the complete current configuration whenever a setting or
        // the ESP32 connection state changes.
        LaunchedEffect(
            selectedInstrument,
            selectedKey,
            selectedScale,
            selectedOctave,
            tempo,
            tuning,
            wifiConnected
        ) {
            if (wifiConnected) {
                sendEsp32Config()
            }
        }


        // ========================================================
        // PLAYBACK
        // ========================================================

        LaunchedEffect(isPlayingTune) {

            if (!isPlayingTune) {
                return@LaunchedEffect
            }

            if (recordedNotes.isEmpty()) {

                isPlayingTune = false

                return@LaunchedEffect
            }

            audioEngine.setInstrument(
                selectedInstrument
            )

            sendEsp32Config()

            var previousStartTime = 0L

            for (recordedNote in recordedNotes) {

                if (!isPlayingTune) {
                    break
                }

                // Account for gaps between notes
                val gap =
                    recordedNote.startTime -
                            previousStartTime

                if (gap > 0) {

                    // A gap in the recorded timeline is a rest. Stop the
                    // current tone while waiting for the next note.
                    audioEngine.stop()

                    val tempoFactor =
                        120.0 / tempo.toDouble()

                    val adjustedGap =
                        (
                                gap * tempoFactor
                                ).toLong()
                            .coerceAtLeast(1L)

                    delay(adjustedGap)
                }

                if (!isPlayingTune) {
                    break
                }

                currentNote =
                    recordedNote.note

                audioEngine.setNote(
                    recordedNote.note
                )

                wifiManager.send(
                    "NOTE_START,${recordedNote.note}"
                )

                audioEngine.start()

                val tempoFactor =
                    120.0 / tempo.toDouble()

                val adjustedDuration =
                    (
                            recordedNote.duration *
                                    tempoFactor
                            ).toLong()
                        .coerceAtLeast(50L)

                delay(adjustedDuration)
                wifiManager.send(
                    "NOTE_END,${recordedNote.note},${adjustedDuration}"
                )

                previousStartTime =
                    recordedNote.startTime +
                            recordedNote.duration
            }

            audioEngine.stop()

            isPlayingTune = false
        }


        // ========================================================
        // VIRTUAL INSTRUMENT HELPERS
        // ========================================================

        fun startVirtualNote(note: String) {
            if (isPlayingTune) return

            // Finish any currently held virtual key before starting another.
            if (currentRecordedNote != null) {
                val now = System.currentTimeMillis()
                val duration =
                    (now - currentNoteStartTime).coerceAtLeast(50L)
                val relativeStart =
                    (currentNoteStartTime - recordingStartTime).coerceAtLeast(0L) +
                            recordingBaseOffset

                recordedNotes.add(
                    RecordedNote(
                        note = currentRecordedNote!!,
                        startTime = relativeStart,
                        duration = duration
                    )
                )
                audioEngine.stop()
                currentRecordedNote = null
            }

            val now = System.currentTimeMillis()
            if (recordingStartTime == 0L) {
                recordingStartTime = now
            }

            currentRecordedNote = note
            currentNoteStartTime = now
            currentNote = note

            audioEngine.setInstrument(selectedInstrument)
            audioEngine.setNote(note)
            audioEngine.start()

            wifiManager.send(
                "CONFIG,$selectedInstrument,$selectedKey,$selectedScale," +
                        "$selectedOctave,$tempo,$tuning"
            )
            wifiManager.send("NOTE_START,$note")
        }

        fun endVirtualNote(note: String) {
            if (currentRecordedNote != note) return

            val now = System.currentTimeMillis()
            val duration =
                (now - currentNoteStartTime).coerceAtLeast(50L)
            val relativeStart =
                (currentNoteStartTime - recordingStartTime).coerceAtLeast(0L) +
                        recordingBaseOffset

            recordedNotes.add(
                RecordedNote(
                    note = note,
                    startTime = relativeStart,
                    duration = duration
                )
            )

            wifiManager.send(
                "NOTE_END,$note,$duration"
            )

            currentRecordedNote = null
            currentNoteStartTime = 0L
            audioEngine.releaseNote()
        }

        // ========================================================
        // MAIN SURFACE
        // ========================================================

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFFF7F7FA)
        ) {

            if (showLibrary) {

                TuneLibraryScreen(
                    tunes = savedTunes,
                    isPlaying = isPlayingTune,
                    onBack = {
                        isPlayingTune = false
                        audioEngine.stop()
                        showLibrary = false
                    },
                    onPlay = { tune ->
                        strokes.clear()
                        tune.drawing.forEach { savedStroke ->
                            val stroke = DrawingStroke()
                            stroke.points.addAll(savedStroke)
                            strokes.add(stroke)
                        }

                        recordedNotes.clear()
                        recordedNotes.addAll(tune.notes)

                        recordingStartTime = 0L
                        currentRecordedNote = null
                        currentNoteStartTime = 0L
                        recordingBaseOffset = 0L

                        selectedInstrument = tune.instrument
                        selectedKey = tune.key
                        selectedScale = tune.scale
                        selectedOctave = tune.octave
                        tempo = tune.tempo
                        tuning = tune.tuning

                        currentNote =
                            tune.notes.firstOrNull()?.note ?: "C4"

                        isPlayingTune = true
                    },
                    onEdit = { tune ->
                        isPlayingTune = false
                        audioEngine.stop()

                        strokes.clear()
                        tune.drawing.forEach { savedStroke ->
                            val stroke = DrawingStroke()
                            stroke.points.addAll(savedStroke)
                            strokes.add(stroke)
                        }

                        recordedNotes.clear()
                        recordedNotes.addAll(tune.notes)

                        // Reset the recording clock for editing. Any new notes
                        // will be appended after the end of the saved tune.
                        recordingStartTime = 0L
                        currentRecordedNote = null
                        currentNoteStartTime = 0L
                        recordingBaseOffset =
                            tune.notes.maxOfOrNull {
                                it.startTime + it.duration
                            } ?: 0L

                        selectedInstrument = tune.instrument
                        selectedKey = tune.key
                        selectedScale = tune.scale
                        selectedOctave = tune.octave
                        tempo = tune.tempo
                        tuning = tune.tuning

                        currentNote =
                            tune.notes.firstOrNull()?.note ?: "C4"

                        showLibrary = false
                    },
                    onDelete = { tune ->
                        isPlayingTune = false
                        audioEngine.stop()

                        savedTunes =
                            savedTunes.filterNot {
                                it.id == tune.id
                            }

                        saveSavedTunes(
                            context,
                            savedTunes
                        )
                    }
                )

            } else {

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {

                    // ========================================================
                    // HEADER
                    // ========================================================

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column {
                            Text(
                                text = "DrawTune",
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF202124)
                            )

                            Text(
                                text = "Draw your music",
                                fontSize = 15.sp,
                                color = Color.Gray
                            )
                        }

                        TextButton(
                            onClick = {
                                isPlayingTune = false
                                audioEngine.stop()
                                showLibrary = true
                            }
                        ) {
                            Text(
                                text = "MY TUNES",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF6750A4)
                            )
                        }
                    }

                    Spacer(
                        modifier = Modifier.height(12.dp)
                    )

                    // ========================================================
                    // ESP32 WI-FI CONNECTION
                    // ========================================================

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (wifiConnected) {
                                Color(0xFFE8F5E9)
                            } else {
                                Color.White
                            }
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = if (wifiConnected) {
                                            "🟢 ESP32 CONNECTED"
                                        } else {
                                            "🔴 ESP32 DISCONNECTED"
                                        },
                                        fontWeight = FontWeight.Bold
                                    )

                                    Text(
                                        text = wifiStatus,
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                }

                                Button(
                                    onClick = {
                                        if (wifiConnected) {
                                            wifiManager.disconnect()
                                            wifiConnected = false
                                            wifiStatus = "ESP32 disconnected"
                                        } else {
                                            wifiStatus = "Connecting..."
                                            wifiManager.connect(
                                                wifiIpAddress
                                            ) { success, message ->
                                                wifiConnected = success
                                                wifiStatus = message
                                                if (success) {
                                                    sendEsp32Config()
                                                }
                                            }
                                        }
                                    }
                                ) {
                                    Text(
                                        if (wifiConnected) {
                                            "DISCONNECT"
                                        } else {
                                            "CONNECT"
                                        }
                                    )
                                }
                            }

                            Spacer(
                                modifier = Modifier.height(8.dp)
                            )

                            OutlinedTextField(
                                value = wifiIpAddress,
                                onValueChange = { wifiIpAddress = it },
                                label = { Text("ESP32 IP address") },
                                singleLine = true,
                                enabled = !wifiConnected,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Spacer(
                        modifier = Modifier.height(12.dp)
                    )


                    // ========================================================
                    // MUSIC INFORMATION
                    // ========================================================

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White
                        )
                    ) {

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement =
                                Arrangement.SpaceBetween
                        ) {

                            // ------------------------------------------------
                            // INSTRUMENT
                            // ------------------------------------------------

                            Column(
                                modifier = Modifier.weight(1f)
                            ) {

                                Text(
                                    text = "Instrument",
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )

                                TextButton(
                                    onClick = {
                                        activeSetting = "Instrument"
                                    }
                                ) {

                                    Text(
                                        text =
                                            when (selectedInstrument) {

                                                "Piano" ->
                                                    "🎹 Piano"

                                                "Synth" ->
                                                    "🎛️ Synth"

                                                "Guitar" ->
                                                    "🎸 Guitar"

                                                "Violin" ->
                                                    "🎻 Violin"

                                                "Drum" ->
                                                    "🥁 Drum"

                                                else ->
                                                    selectedInstrument
                                            },

                                        fontSize = 17.sp,

                                        fontWeight =
                                            FontWeight.SemiBold
                                    )
                                }
                            }


                            // ------------------------------------------------
                            // KEY / SCALE
                            // ------------------------------------------------

                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment =
                                    Alignment.CenterHorizontally
                            ) {

                                Text(
                                    text = "Key / Scale",
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )

                                TextButton(
                                    onClick = {
                                        activeSetting = "Key"
                                    }
                                ) {

                                    Text(
                                        text =
                                            "$selectedKey $selectedScale",

                                        fontSize = 17.sp,

                                        fontWeight =
                                            FontWeight.SemiBold
                                    )
                                }
                            }


                            // ------------------------------------------------
                            // TEMPO
                            // ------------------------------------------------

                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment =
                                    Alignment.End
                            ) {

                                Text(
                                    text = "Tempo",
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )

                                TextButton(
                                    onClick = {
                                        activeSetting = "Tempo"
                                    }
                                ) {

                                    Text(
                                        text = "$tempo BPM",

                                        fontSize = 17.sp,

                                        fontWeight =
                                            FontWeight.SemiBold
                                    )
                                }
                            }
                        }


                        // ====================================================
                        // SECOND SETTINGS ROW
                        // ====================================================

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    bottom = 10.dp
                                ),

                            horizontalArrangement =
                                Arrangement.SpaceBetween
                        ) {

                            TextButton(
                                onClick = {
                                    activeSetting = "Scale"
                                }
                            ) {

                                Text(
                                    text =
                                        "🎵 Scale: $selectedScale"
                                )
                            }


                            TextButton(
                                onClick = {
                                    activeSetting = "Octave"
                                }
                            ) {

                                Text(
                                    text =
                                        "🎚️ Octave: $selectedOctave"
                                )
                            }


                            TextButton(
                                onClick = {
                                    activeSetting = "Tuning"
                                }
                            ) {

                                Text(
                                    text = "🎛️ $tuning Hz"
                                )
                            }
                        }
                    }


                    Spacer(
                        modifier = Modifier.height(16.dp)
                    )


                    // ========================================================
                    // CREATE MODE TABS
                    // ========================================================

                    TabRow(
                        selectedTabIndex = createMode,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Tab(
                            selected = createMode == 0,
                            onClick = {
                                if (!isPlayingTune) {
                                    audioEngine.stop()
                                    currentRecordedNote = null
                                    createMode = 0
                                }
                            },
                            text = { Text("DRAW") }
                        )

                        Tab(
                            selected = createMode == 1,
                            onClick = {
                                if (!isPlayingTune) {
                                    audioEngine.stop()
                                    currentRecordedNote = null
                                    createMode = 1
                                }
                            },
                            text = { Text("INSTRUMENT") }
                        )
                    }

                    Spacer(
                        modifier = Modifier.height(8.dp)
                    )

                    if (createMode == 0) {

                        // ========================================================
                        // DRAWING CANVAS
                        // ========================================================

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(
                                    Color.White,
                                    RoundedCornerShape(18.dp)
                                )
                        ) {

                            Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(4.dp)
                                    .pointerInput(Unit) {

                                        awaitEachGesture {
                                            val down = awaitFirstDown(
                                                requireUnconsumed = false
                                            )

                                            if (isPlayingTune) {
                                                return@awaitEachGesture
                                            }

                                            isDrawing = true

                                            val clampedX =
                                                down.position.x.coerceIn(
                                                    0f,
                                                    size.width.toFloat()
                                                )

                                            val clampedY =
                                                down.position.y.coerceIn(
                                                    0f,
                                                    size.height.toFloat()
                                                )

                                            val clampedPosition =
                                                Offset(clampedX, clampedY)

                                            val stroke = DrawingStroke()
                                            stroke.points.add(clampedPosition)
                                            strokes.add(stroke)

                                            val note =
                                                noteFromPosition(
                                                    x = clampedX,
                                                    canvasWidth = size.width.toFloat(),
                                                    key = latestKey,
                                                    scale = latestScale,
                                                    octave = latestOctave
                                                )

                                            currentNote = note

                                            val now = System.currentTimeMillis()
                                            if (recordingStartTime == 0L) {
                                                recordingStartTime = now
                                            }

                                            currentRecordedNote = note
                                            currentNoteStartTime = now

                                            audioEngine.setInstrument(latestInstrument)
                                            audioEngine.setNote(note)
                                            audioEngine.start()

                                            wifiManager.send(
                                                "CONFIG,$latestInstrument,$latestKey,$latestScale," +
                                                        "$latestOctave,$latestTempo,$latestTuning"
                                            )
                                            wifiManager.send(
                                                "NOTE_START,$note"
                                            )

                                            var finished = false

                                            try {
                                                while (!finished) {
                                                    val event = awaitPointerEvent()
                                                    val change =
                                                        event.changes.firstOrNull {
                                                            it.id == down.id
                                                        } ?: break

                                                    if (!change.pressed) {
                                                        val releaseNow =
                                                            System.currentTimeMillis()

                                                        if (currentRecordedNote != null) {
                                                            val duration =
                                                                (releaseNow - currentNoteStartTime)
                                                                    .coerceAtLeast(50L)

                                                            val relativeStart =
                                                                (currentNoteStartTime - recordingStartTime)
                                                                    .coerceAtLeast(0L)

                                                            recordedNotes.add(
                                                                RecordedNote(
                                                                    note = currentRecordedNote!!,
                                                                    startTime = relativeStart,
                                                                    duration = duration
                                                                )
                                                            )
                                                        }

                                                        wifiManager.send(
                                                            "NOTE_END,${currentRecordedNote ?: note},${(releaseNow - currentNoteStartTime).coerceAtLeast(50L)}"
                                                        )

                                                        currentRecordedNote = null
                                                        isDrawing = false
                                                        audioEngine.releaseNote()
                                                        finished = true
                                                    } else {
                                                        change.consume()

                                                        val clampedMoveX =
                                                            change.position.x.coerceIn(
                                                                0f,
                                                                size.width.toFloat()
                                                            )

                                                        val clampedMoveY =
                                                            change.position.y.coerceIn(
                                                                0f,
                                                                size.height.toFloat()
                                                            )

                                                        val movePosition =
                                                            Offset(clampedMoveX, clampedMoveY)

                                                        strokes.lastOrNull()?.points?.add(movePosition)

                                                        val movedNote =
                                                            noteFromPosition(
                                                                x = clampedMoveX,
                                                                canvasWidth = size.width.toFloat(),
                                                                key = latestKey,
                                                                scale = latestScale,
                                                                octave = latestOctave
                                                            )

                                                        currentNote = movedNote
                                                        audioEngine.setNote(movedNote)
                                                        audioEngine.setInstrument(latestInstrument)

                                                        if (
                                                            currentRecordedNote != null &&
                                                            movedNote != currentRecordedNote
                                                        ) {
                                                            val noteNow = System.currentTimeMillis()
                                                            val duration =
                                                                (noteNow - currentNoteStartTime)
                                                                    .coerceAtLeast(20L)
                                                            val relativeStart =
                                                                (currentNoteStartTime - recordingStartTime)
                                                                    .coerceAtLeast(0L)

                                                            wifiManager.send(
                                                                "NOTE_CHANGE,$movedNote"
                                                            )

                                                            recordedNotes.add(
                                                                RecordedNote(
                                                                    note = currentRecordedNote!!,
                                                                    startTime = relativeStart,
                                                                    duration = duration
                                                                )
                                                            )

                                                            currentRecordedNote = movedNote
                                                            currentNoteStartTime = noteNow
                                                        }
                                                    }
                                                }
                                            } catch (_: CancellationException) {
                                                currentRecordedNote = null
                                                isDrawing = false
                                                audioEngine.stop()
                                            }
                                        }
                                    }
                            ) {


                                // ====================================================
                                // MUSICAL GUIDE LINES
                                // ====================================================

                                val guideLines = 8

                                for (i in 0..guideLines) {

                                    val y =
                                        size.height *
                                                i.toFloat() /
                                                guideLines.toFloat()

                                    drawLine(
                                        color =
                                            Color(0xFFE8E8ED),

                                        start =
                                            Offset(
                                                0f,
                                                y
                                            ),

                                        end =
                                            Offset(
                                                size.width,
                                                y
                                            ),

                                        strokeWidth = 1f
                                    )
                                }


                                // ====================================================
                                // DRAW USER STROKES
                                // ====================================================

                                for (stroke in strokes) {

                                    if (stroke.points.isEmpty()) {
                                        continue
                                    }


                                    // ----------------------------------------------
                                    // SINGLE POINT
                                    // ----------------------------------------------

                                    if (stroke.points.size == 1) {

                                        drawCircle(
                                            color =
                                                Color(0xFF6750A4),

                                            radius = 4f,

                                            center =
                                                stroke.points[0]
                                        )

                                        continue
                                    }


                                    // ----------------------------------------------
                                    // CREATE PATH
                                    // ----------------------------------------------

                                    val path =
                                        Path()

                                    path.moveTo(
                                        stroke.points[0].x,
                                        stroke.points[0].y
                                    )

                                    for (
                                    i in 1 until stroke.points.size
                                    ) {

                                        path.lineTo(
                                            stroke.points[i].x,
                                            stroke.points[i].y
                                        )
                                    }


                                    // ----------------------------------------------
                                    // DRAW PATH
                                    // ----------------------------------------------

                                    drawPath(
                                        path = path,

                                        color =
                                            Color(0xFF6750A4),

                                        style =
                                            Stroke(
                                                width = 6f,

                                                cap =
                                                    StrokeCap.Round,

                                                join =
                                                    StrokeJoin.Round
                                            )
                                    )
                                }
                            }


                            // ========================================================
                            // EMPTY CANVAS MESSAGE
                            // ========================================================

                            if (
                                !isDrawing &&
                                strokes.isEmpty()
                            ) {

                                Text(
                                    text =
                                        "Touch and draw here",

                                    modifier =
                                        Modifier.align(
                                            Alignment.Center
                                        ),

                                    color =
                                        Color.LightGray,

                                    fontSize = 17.sp
                                )
                            }
                        }
                    } else {

                        // ========================================================
                        // VIRTUAL INSTRUMENT
                        // ========================================================

                        VirtualInstrument(
                            instrument = selectedInstrument,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            enabled = !isPlayingTune,
                            currentNote = currentNote,
                            onNoteStart = { note ->
                                startVirtualNote(note)
                            },
                            onNoteEnd = { note ->
                                endVirtualNote(note)
                            }
                        )
                    }


                    Spacer(
                        modifier = Modifier.height(12.dp)
                    )


                    // ========================================================
                    // CURRENT NOTE
                    // ========================================================

                    Card(
                        modifier =
                            Modifier.fillMaxWidth(),

                        shape =
                            RoundedCornerShape(14.dp),

                        colors =
                            CardDefaults.cardColors(
                                containerColor =
                                    Color(0xFFEDE7F6)
                            )
                    ) {

                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),

                            verticalAlignment =
                                Alignment.CenterVertically,

                            horizontalArrangement =
                                Arrangement.SpaceBetween
                        ) {

                            Text(
                                text =
                                    "Current Note",

                                fontSize =
                                    15.sp
                            )

                            Text(
                                text =
                                    currentNote,

                                fontSize =
                                    28.sp,

                                fontWeight =
                                    FontWeight.Bold,
    
                                color =
                                    Color(0xFF6750A4)
                            )
                        }
                    }


                    Spacer(
                        modifier = Modifier.height(12.dp)
                    )


                    // ========================================================
                    // BUTTONS
                    // ========================================================

                    Row(
                        modifier =
                            Modifier.fillMaxWidth(),

                        horizontalArrangement =
                            Arrangement.spacedBy(12.dp)
                    ) {


                        // ====================================================
                        // CLEAR
                        // ====================================================

                        Button(
                            onClick = {

                                isPlayingTune = false

                                strokes.clear()

                                recordedNotes.clear()

                                recordingStartTime =
                                    0L

                                recordingBaseOffset =
                                    0L

                                currentRecordedNote =
                                    null

                                currentNoteStartTime =
                                    0L

                                currentNote =
                                    "C4"

                                isDrawing =
                                    false

                                audioEngine.stop()
                            },

                            modifier =
                                Modifier
                                    .weight(1f)
                                    .height(52.dp),

                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor =
                                        Color(0xFF444444)
                                )
                        ) {

                            Text(
                                text =
                                    "CLEAR",

                                fontWeight =
                                    FontWeight.Bold
                            )
                        }


                        // ====================================================
                        // PLAY
                        // ====================================================

                        Button(
                            onClick = {

                                if (isPlayingTune) {

                                    isPlayingTune =
                                        false

                                    audioEngine.stop()

                                } else if (
                                    recordedNotes.isNotEmpty()
                                ) {

                                    isPlayingTune =
                                        true
                                }
                            },

                            modifier =
                                Modifier
                                    .weight(1f)
                                    .height(52.dp)
                        ) {

                            Text(
                                text =
                                    if (isPlayingTune) {
                                        "■ STOP"
                                    } else {
                                        "▶ PLAY"
                                    },

                                fontWeight =
                                    FontWeight.Bold
                            )
                        }


                        // ====================================================
                        // SAVE
                        // ====================================================

                        Button(
                            onClick = {
                                if (recordedNotes.isNotEmpty()) {
                                    tuneName = ""
                                    showSaveDialog = true
                                }
                            },

                            modifier =
                                Modifier
                                    .weight(1f)
                                    .height(52.dp)
                        ) {

                            Text(
                                text =
                                    "💾 SAVE",

                                fontWeight =
                                    FontWeight.Bold
                            )
                        }
                    }
                }
            }


            // ============================================================
            // SAVE DIALOG
            // ============================================================

            if (showSaveDialog) {

                AlertDialog(
                    onDismissRequest = {
                        showSaveDialog = false
                    },

                    title = {
                        Text(
                            text = "💾 Save Tune",
                            fontWeight = FontWeight.Bold
                        )
                    },

                    text = {
                        OutlinedTextField(
                            value = tuneName,
                            onValueChange = {
                                tuneName = it
                            },
                            label = {
                                Text("Tune name")
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },

                    confirmButton = {

                        TextButton(
                            enabled =
                                tuneName.trim().isNotEmpty() &&
                                        recordedNotes.isNotEmpty(),

                            onClick = {

                                val newTune =
                                    SavedTune(
                                        id = System.currentTimeMillis(),
                                        name = tuneName.trim(),
                                        instrument = selectedInstrument,
                                        key = selectedKey,
                                        scale = selectedScale,
                                        octave = selectedOctave,
                                        tempo = tempo,
                                        tuning = tuning,
                                        drawing =
                                            strokes.map { stroke ->
                                                stroke.points.toList()
                                            },
                                        notes =
                                            recordedNotes.toList()
                                    )

                                savedTunes =
                                    listOf(
                                        newTune
                                    ) + savedTunes

                                saveSavedTunes(
                                    context,
                                    savedTunes
                                )

                                showSaveDialog = false
                                tuneName = ""
                            }
                        ) {
                            Text("SAVE")
                        }
                    },

                    dismissButton = {

                        TextButton(
                            onClick = {
                                showSaveDialog = false
                            }
                        ) {
                            Text("CANCEL")
                        }
                    }
                )
            }


            // ============================================================
            // SETTINGS DIALOGS
            // ============================================================

            when (activeSetting) {

                // ========================================================
                // INSTRUMENT
                // ========================================================

                "Instrument" -> {

                    SelectionDialog(
                        title =
                            "🎹 Select Instrument",

                        options =
                            listOf(
                                "Piano",
                                "Synth",
                                "Guitar",
                                "Violin",
                                "Drum"
                            ),

                        selected =
                            selectedInstrument,

                        onSelected = {

                            selectedInstrument =
                                it

                            audioEngine.setInstrument(
                                it
                            )

                            activeSetting =
                                null
                        },

                        onDismiss = {
                            activeSetting =
                                null
                        }
                    )
                }


                // ========================================================
                // KEY
                // ========================================================

                "Key" -> {

                    SelectionDialog(
                        title =
                            "🎼 Select Key",

                        options =
                            listOf(
                                "C",
                                "D",
                                "E",
                                "F",
                                "G",
                                "A",
                                "B"
                            ),

                        selected =
                            selectedKey,

                        onSelected = {

                            selectedKey =
                                it

                            activeSetting =
                                null
                        },

                        onDismiss = {
                            activeSetting =
                                null
                        }
                    )
                }


                // ========================================================
                // SCALE
                // ========================================================

                "Scale" -> {

                    SelectionDialog(
                        title =
                            "🎵 Select Scale",

                        options =
                            listOf(
                                "Major",
                                "Minor",
                                "Pentatonic",
                                "Blues",
                                "Chromatic"
                            ),

                        selected =
                            selectedScale,

                        onSelected = {

                            selectedScale =
                                it

                            activeSetting =
                                null
                        },

                        onDismiss = {
                            activeSetting =
                                null
                        }
                    )
                }


                // ========================================================
                // OCTAVE
                // ========================================================

                "Octave" -> {

                    SelectionDialog(
                        title =
                            "🎚️ Select Octave",

                        options =
                            listOf(
                                "Low",
                                "Middle",
                                "High"
                            ),

                        selected =
                            selectedOctave,

                        onSelected = {

                            selectedOctave =
                                it

                            activeSetting =
                                null
                        },

                        onDismiss = {
                            activeSetting =
                                null
                        }
                    )
                }


                // ========================================================
                // TEMPO
                // ========================================================

                "Tempo" -> {

                    TempoDialog(
                        tempo =
                            tempo,

                        onTempoChanged = {

                            tempo =
                                it
                        },

                        onDismiss = {

                            activeSetting =
                                null
                        }
                    )
                }


                // ========================================================
                // TUNING
                // ========================================================

                "Tuning" -> {

                    SelectionDialog(
                        title =
                            "🎛️ Select Tuning",

                        options =
                            listOf(
                                "432 Hz",
                                "435 Hz",
                                "440 Hz"
                            ),

                        selected =
                            "$tuning Hz",

                        onSelected = {

                            tuning =
                                it
                                    .removeSuffix(
                                        " Hz"
                                    )
                                    .toIntOrNull()
                                    ?: 440
                            sendEsp32Config()

                            activeSetting =
                                null
                        },

                        onDismiss = {

                            activeSetting =
                                null
                        }
                    )
                }
            }
        }



    }


    // ============================================================
    // VIRTUAL INSTRUMENTS
    // ============================================================

    @Composable
    fun VirtualInstrument(
        instrument: String,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
        currentNote: String,
        onNoteStart: (String) -> Unit,
        onNoteEnd: (String) -> Unit
    ) {
        when (instrument) {
            "Piano" -> VirtualPiano(modifier, enabled, currentNote, onNoteStart, onNoteEnd)
            "Synth" -> VirtualSynth(modifier, enabled, currentNote, onNoteStart, onNoteEnd)
            "Guitar" -> VirtualGuitar(modifier, enabled, currentNote, onNoteStart, onNoteEnd)
            "Violin" -> VirtualViolin(modifier, enabled, currentNote, onNoteStart, onNoteEnd)
            "Drum" -> VirtualDrumKit(modifier, enabled, currentNote, onNoteStart, onNoteEnd)
            else -> VirtualPiano(modifier, enabled, currentNote, onNoteStart, onNoteEnd)
        }
    }

    private val instrumentAccent = Color(0xFF6750A4)

    @Composable
    private fun InstrumentCard(
        title: String,
        subtitle: String,
        modifier: Modifier,
        content: @Composable ColumnScope.() -> Unit
    ) {
        Card(
            modifier = modifier,
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
        ) {
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF252329))
                    Spacer(Modifier.weight(1f))
                    Text("LIVE", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = instrumentAccent)
                }
                Text(subtitle, fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(top = 3.dp, bottom = 10.dp))
                content()
            }
        }
    }

    private fun noteAtSemitone(baseMidi: Int, semitone: Int): String = midiToNote(baseMidi + semitone)

    @Composable
    private fun TouchInstrumentSurface(
        modifier: Modifier,
        enabled: Boolean,
        currentNote: String,
        noteFromPosition: (Offset, androidx.compose.ui.unit.IntSize) -> String,
        onNoteStart: (String) -> Unit,
        onNoteEnd: (String) -> Unit,
        drawContent: @Composable () -> Unit
    ) {
        var activeNote by remember { mutableStateOf<String?>(null) }
        Box(
            modifier = modifier.pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val first = noteFromPosition(down.position, size)
                    activeNote = first
                    onNoteStart(first)
                    try {
                        var released = false
                        while (!released) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                activeNote?.let(onNoteEnd)
                                activeNote = null
                                released = true
                            } else {
                                val next = noteFromPosition(change.position, size)
                                if (next != activeNote) {
                                    activeNote?.let(onNoteEnd)
                                    activeNote = next
                                    onNoteStart(next)
                                }
                                change.consume()
                            }
                        }
                    } catch (_: CancellationException) {
                        activeNote?.let(onNoteEnd)
                        activeNote = null
                    }
                }
            }
        ) {
            drawContent()
            if (currentNote.isNotEmpty()) {
                Text(
                    currentNote,
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = instrumentAccent
                )
            }
        }
    }

    @Composable
    private fun VirtualPiano(
        modifier: Modifier,
        enabled: Boolean,
        currentNote: String,
        onNoteStart: (String) -> Unit,
        onNoteEnd: (String) -> Unit
    ) {
        val baseMidi = 48 // C3
        val whiteNames = listOf("C","D","E","F","G","A","B")
        val blackBoundaries = listOf(1,2,4,5,6)
        InstrumentCard("VIRTUAL PIANO", "3 octaves • Tap or slide continuously across the keys", modifier) {
            TouchInstrumentSurface(
                Modifier.fillMaxWidth().weight(1f).background(Color(0xFFF1EFF5), RoundedCornerShape(16.dp)).padding(6.dp),
                enabled, currentNote,
                noteFromPosition = { pos, size ->
                    pianoNoteFromPosition(pos, size, baseMidi)
                },
                onNoteStart, onNoteEnd
            ) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val whiteCount = 21
                    val w = maxWidth / whiteCount
                    Row(Modifier.fillMaxSize()) {
                        (0 until whiteCount).forEach { i ->
                            Box(
                                Modifier.weight(1f).fillMaxHeight().padding(horizontal = 1.dp)
                                    .background(if (currentNote == noteAtSemitone(baseMidi, whiteIndexToSemitone(i))) Color(0xFFD8C9F0) else Color.White, RoundedCornerShape(4.dp)),
                                contentAlignment = Alignment.BottomCenter
                            ) { Text(whiteNames[i % 7], fontSize = 8.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 6.dp)) }
                        }
                    }
                    val blackPattern = listOf(0,1,3,4,5)
                    (1 until whiteCount).forEach { boundary ->
                        val posInOctave = boundary % 7
                        if (posInOctave in blackPattern) {
                            val note = noteAtSemitone(baseMidi, whiteIndexToSemitone(boundary - 1) + 1)
                            Box(
                                Modifier.offset(x = w * boundary - w * 0.30f)
                                    .width(w * 0.60f).fillMaxHeight(0.60f)
                                    .background(if (currentNote == note) Color(0xFF6750A4) else Color(0xFF242128), RoundedCornerShape(5.dp))
                            )
                        }
                    }
                }
            }
            Text("C3 → B5 • 21 white keys + black keys", Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 11.sp, color = Color.Gray)
        }
    }

    private fun pianoNoteFromPosition(
        position: Offset,
        size: androidx.compose.ui.unit.IntSize,
        baseMidi: Int
    ): String {
        val whiteCount = 21
        val whiteWidth = size.width.toFloat() / whiteCount
        val whiteIndex = (position.x / whiteWidth).toInt().coerceIn(0, whiteCount - 1)

        // On the upper part of the keyboard, prefer black keys when the finger
        // is near a black-key center. This makes both tapping and sliding behave
        // like a real piano keyboard.
        if (position.y < size.height * 0.58f) {
            val boundary = (position.x / whiteWidth).roundToInt()
            val blackPositions = setOf(1, 2, 4, 5, 6, 8, 9, 11, 12, 13, 15, 16, 18, 19, 20)
            if (boundary in blackPositions && boundary in 1 until whiteCount) {
                val center = boundary * whiteWidth
                if (abs(position.x - center) <= whiteWidth * 0.30f) {
                    return noteAtSemitone(baseMidi, whiteIndexToSemitone(boundary - 1) + 1)
                }
            }
        }

        return noteAtSemitone(baseMidi, whiteIndexToSemitone(whiteIndex))
    }

    private fun whiteIndexToSemitone(index: Int): Int {
        val octave = index / 7
        return octave * 12 + listOf(0,2,4,5,7,9,11)[index % 7]
    }

    @Composable
    private fun VirtualSynth(
        modifier: Modifier,
        enabled: Boolean,
        currentNote: String,
        onNoteStart: (String) -> Unit,
        onNoteEnd: (String) -> Unit
    ) {
        val baseMidi = 48
        InstrumentCard("VIRTUAL SYNTHESIZER", "Continuous performance surface • slide for pitch sweeps", modifier) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("OSC 1", "OSC 2", "FILTER", "LFO", "ENV").forEach { label ->
                    Card(Modifier.weight(1f), shape = RoundedCornerShape(9.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF0ECF7))) {
                        Text(label, Modifier.fillMaxWidth().padding(vertical = 7.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            TouchInstrumentSurface(
                Modifier.fillMaxWidth().weight(1f).background(Color(0xFF17151A), RoundedCornerShape(16.dp)).padding(8.dp),
                enabled, currentNote,
                { pos, size ->
                    val index = (pos.x / size.width * 36f).toInt().coerceIn(0,35)
                    noteAtSemitone(baseMidi, index)
                }, onNoteStart, onNoteEnd
            ) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        (0 until 36).forEach { i ->
                            Box(Modifier.weight(1f).fillMaxHeight().padding(1.dp).background(if (currentNote == noteAtSemitone(baseMidi,i)) Color(0xFF9A7BC7) else Color(0xFF302B35), RoundedCornerShape(2.dp)))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("C3                 C4                 C5", Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 9.sp, color = Color.LightGray)
                }
            }
        }
    }

    @Composable
    private fun VirtualGuitar(
        modifier: Modifier,
        enabled: Boolean,
        currentNote: String,
        onNoteStart: (String) -> Unit,
        onNoteEnd: (String) -> Unit
    ) {
        val openMidi = listOf(40,45,50,55,59,64)
        val names = listOf("E","A","D","G","B","E")
        InstrumentCard("VIRTUAL GUITAR", "6 strings • 15 frets • Slide across the fretboard", modifier) {
            TouchInstrumentSurface(
                Modifier.fillMaxWidth().weight(1f).background(Color(0xFF7A5636), RoundedCornerShape(16.dp)).padding(8.dp),
                enabled, currentNote,
                { pos, size ->
                    val stringIndex = (pos.y / size.height * 6f).toInt().coerceIn(0,5)
                    val fret = (pos.x / size.width * 16f).toInt().coerceIn(0,15)
                    noteAtSemitone(openMidi[stringIndex], fret)
                }, onNoteStart, onNoteEnd
            ) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                    names.forEachIndexed { index, name ->
                        Box(Modifier.fillMaxWidth().height(30.dp)) {
                            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                                drawLine(Color(0xFFE9D7B8), Offset(0f, size.height/2f), Offset(size.width, size.height/2f), strokeWidth = 2f + index)
                            }
                            Text(name, Modifier.align(Alignment.CenterStart).padding(start = 3.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
            Text("E2  A2  D3  G3  B3  E4  •  fret 0–15", Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 10.sp, color = Color.Gray)
        }
    }

    @Composable
    private fun VirtualViolin(
        modifier: Modifier,
        enabled: Boolean,
        currentNote: String,
        onNoteStart: (String) -> Unit,
        onNoteEnd: (String) -> Unit
    ) {
        val openMidi = listOf(55,62,69,76)
        val names = listOf("G","D","A","E")
        InstrumentCard("VIRTUAL VIOLIN", "4 strings • Fingerboard with continuous position control", modifier) {
            TouchInstrumentSurface(
                Modifier.fillMaxWidth().weight(1f).background(Color(0xFF34251C), RoundedCornerShape(16.dp)).padding(12.dp),
                enabled, currentNote,
                { pos, size ->
                    val stringIndex = (pos.y / size.height * 4f).toInt().coerceIn(0,3)
                    val position = (pos.x / size.width * 24f).toInt().coerceIn(0,23)
                    noteAtSemitone(openMidi[stringIndex], position)
                }, onNoteStart, onNoteEnd
            ) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                    names.forEachIndexed { index, name ->
                        Box(Modifier.fillMaxWidth().height(34.dp)) {
                            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                                drawLine(Color(0xFFD8B58A), Offset(0f,size.height/2f), Offset(size.width,size.height/2f), strokeWidth = 2f + index)
                            }
                            Text(name, Modifier.align(Alignment.CenterStart), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
            Text("G3 → E7 • 2 octaves per string", Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 10.sp, color = Color.Gray)
        }
    }

    @Composable
    private fun VirtualDrumKit(
        modifier: Modifier,
        enabled: Boolean,
        currentNote: String,
        onNoteStart: (String) -> Unit,
        onNoteEnd: (String) -> Unit
    ) {
        val pads = listOf(
            "KICK" to 36, "SNARE" to 38, "CLOSED HAT" to 42,
            "OPEN HAT" to 46, "LOW TOM" to 45, "MID TOM" to 47,
            "HIGH TOM" to 50, "CRASH" to 49, "RIDE" to 51
        )
        InstrumentCard("VIRTUAL DRUM KIT", "9-piece pad layout • Tap or drag between pads", modifier) {
            TouchInstrumentSurface(
                Modifier.fillMaxWidth().weight(1f),
                enabled, currentNote,
                { pos, size ->
                    val col = (pos.x / size.width * 3f).toInt().coerceIn(0,2)
                    val row = (pos.y / size.height * 3f).toInt().coerceIn(0,2)
                    val idx = row * 3 + col
                    midiToNote(pads[idx].second)
                }, onNoteStart, onNoteEnd
            ) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    pads.chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { (label,midi) ->
                                Card(Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = if (currentNote == midiToNote(midi)) Color(0xFFD8C9F0) else Color(0xFFF3F1F5))) {
                                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        Text("●", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = instrumentAccent)
                                        Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }


    // ============================================================
    // MY TUNES SCREEN
    // ============================================================

    @Composable
    fun TuneLibraryScreen(
        tunes: List<SavedTune>,
        isPlaying: Boolean,
        onBack: () -> Unit,
        onPlay: (SavedTune) -> Unit,
        onEdit: (SavedTune) -> Unit,
        onDelete: (SavedTune) -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onBack
                ) {
                    Text(
                        text = "← BACK",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF6750A4)
                    )
                }

                Text(
                    text = "MY TUNES",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF202124)
                )
            }

            Spacer(
                modifier = Modifier.height(12.dp)
            )

            if (tunes.isEmpty()) {

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "🎵",
                            fontSize = 40.sp
                        )

                        Spacer(
                            modifier = Modifier.height(8.dp)
                        )

                        Text(
                            text = "No saved tunes yet",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        Text(
                            text = "Draw something and press SAVE",
                            color = Color.Gray
                        )
                    }
                }

            } else {

                LazyColumn(
                    verticalArrangement =
                        Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = tunes,
                        key = { it.id }
                    ) { tune ->

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors =
                                CardDefaults.cardColors(
                                    containerColor =
                                        Color.White
                                )
                        ) {

                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp)
                            ) {

                                Text(
                                    text = tune.name,
                                    fontSize = 20.sp,
                                    fontWeight =
                                        FontWeight.Bold
                                )

                                Spacer(
                                    modifier =
                                        Modifier.height(4.dp)
                                )

                                Text(
                                    text =
                                        "${tune.instrument} • " +
                                                "${tune.key} ${tune.scale} • " +
                                                "${tune.tempo} BPM",
                                    color = Color.Gray
                                )

                                Text(
                                    text =
                                        "${tune.notes.size} notes • " +
                                                "${tune.tuning} Hz",
                                    color = Color.Gray,
                                    fontSize = 13.sp
                                )

                                Spacer(
                                    modifier =
                                        Modifier.height(10.dp)
                                )

                                Row(
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    horizontalArrangement =
                                        Arrangement.spacedBy(8.dp)
                                ) {

                                    Button(
                                        onClick = {
                                            onPlay(tune)
                                        },
                                        modifier =
                                            Modifier.weight(1f),
                                        colors =
                                            ButtonDefaults.buttonColors(
                                                containerColor =
                                                    Color(0xFF6750A4)
                                            )
                                    ) {
                                        Text(
                                            text =
                                                if (isPlaying) {
                                                    "■ STOP"
                                                } else {
                                                    "▶ PLAY"
                                                }
                                        )
                                    }

                                    TextButton(
                                        onClick = {
                                            onEdit(tune)
                                        },
                                        modifier =
                                            Modifier.weight(1f)
                                    ) {
                                        Text("✏️ EDIT")
                                    }

                                    TextButton(
                                        onClick = {
                                            onDelete(tune)
                                        },
                                        modifier =
                                            Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = "🗑️",
                                            fontSize = 18.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }


    // ============================================================
    // SELECTION DIALOG
    // ============================================================

    @Composable
    fun SelectionDialog(
        title: String,
        options: List<String>,
        selected: String,
        onSelected: (String) -> Unit,
        onDismiss: () -> Unit
    ) {

        AlertDialog(
            onDismissRequest =
                onDismiss,

            title = {

                Text(
                    text =
                        title,

                    fontWeight =
                        FontWeight.Bold
                )
            },

            text = {

                Column {

                    options.forEach { option ->

                        TextButton(
                            onClick = {
                                onSelected(
                                    option
                                )
                            },

                            modifier =
                                Modifier.fillMaxWidth()
                        ) {

                            Row(
                                modifier =
                                    Modifier.fillMaxWidth(),

                                horizontalArrangement =
                                    Arrangement.SpaceBetween
                            ) {

                                Text(
                                    text =
                                        option,

                                    fontSize =
                                        17.sp
                                )

                                if (
                                    option ==
                                    selected
                                ) {

                                    Text(
                                        text =
                                            "✓",

                                        color =
                                            Color(
                                                0xFF6750A4
                                            ),

                                        fontWeight =
                                            FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            },

            confirmButton = {

                TextButton(
                    onClick =
                        onDismiss
                ) {

                    Text(
                        text =
                            "CLOSE"
                    )
                }
            }
        )
    }


    // ============================================================
    // TEMPO DIALOG
    // ============================================================

    @Composable
    fun TempoDialog(
        tempo: Int,
        onTempoChanged: (Int) -> Unit,
        onDismiss: () -> Unit
    ) {

        var sliderValue by remember(tempo) {
            mutableStateOf(
                tempo.toFloat()
            )
        }

        AlertDialog(
            onDismissRequest =
                onDismiss,

            title = {

                Text(
                    text =
                        "⏱️ Tempo",

                    fontWeight =
                        FontWeight.Bold
                )
            },

            text = {

                Column {

                    Text(
                        text =
                            "${sliderValue.toInt()} BPM",

                        fontSize =
                            28.sp,

                        fontWeight =
                            FontWeight.Bold,

                        color =
                            Color(0xFF6750A4)
                    )

                    Spacer(
                        modifier =
                            Modifier.height(8.dp)
                    )

                    Slider(
                        value =
                            sliderValue,

                        onValueChange = {

                            sliderValue =
                                it

                            onTempoChanged(
                                it.toInt()
                            )
                        },

                        valueRange =
                            60f..180f,

                        steps =
                            11
                    )

                    Row(
                        modifier =
                            Modifier.fillMaxWidth(),

                        horizontalArrangement =
                            Arrangement.SpaceBetween
                    ) {

                        Text(
                            text =
                                "60",

                            color =
                                Color.Gray
                        )

                        Text(
                            text =
                                "120",

                            color =
                                Color.Gray
                        )

                        Text(
                            text =
                                "180",

                            color =
                                Color.Gray
                        )
                    }
                }
            },

            confirmButton = {

                TextButton(
                    onClick =
                        onDismiss
                ) {

                    Text(
                        text =
                            "DONE"
                    )
                }
            }
        )
    }


    // ============================================================
    // NOTE CALCULATION
    // ============================================================

    fun noteFromPosition(
        x: Float,
        canvasWidth: Float,
        key: String,
        scale: String,
        octave: String
    ): String {
        if (canvasWidth <= 0f) return "C4"

        val intervals = when (scale) {
            "Major" -> listOf(0, 2, 4, 5, 7, 9, 11)
            "Minor" -> listOf(0, 2, 3, 5, 7, 8, 10)
            "Pentatonic" -> listOf(0, 2, 4, 7, 9)
            "Blues" -> listOf(0, 3, 5, 6, 7, 10)
            "Chromatic" -> (0..11).toList()
            else -> listOf(0, 2, 4, 5, 7, 9, 11)
        }

        val keySemitone = when (key) {
            "C" -> 0; "D" -> 2; "E" -> 4; "F" -> 5
            "G" -> 7; "A" -> 9; "B" -> 11; else -> 0
        }

        val baseOctave = when (octave) {
            "Low" -> 2
            "Middle" -> 4
            "High" -> 6
            else -> 4
        }

        // The complete drawing width covers FOUR octaves instead of only eight
        // zones. This makes the canvas musically useful across a much larger range.
        val normalizedX = (x / canvasWidth).coerceIn(0f, 0.999999f)
        val semitonePosition = normalizedX * 48f
        val rawSemitone = floor(semitonePosition).toInt()

        val chromaticMidi = (baseOctave + 1) * 12 + keySemitone + rawSemitone

        if (scale == "Chromatic") {
            return midiToNote(chromaticMidi)
        }

        // Snap the chromatic position to the nearest note in the selected scale,
        // while preserving all four octaves.
        val octaveOffset = rawSemitone / 12
        val withinOctave = rawSemitone % 12
        val nearest = intervals.minByOrNull { abs(it - withinOctave) } ?: 0
        val scaleMidi = (baseOctave + octaveOffset + 1) * 12 + keySemitone + nearest
        return midiToNote(scaleMidi)
    }

    private fun midiToNote(midi: Int): String {
        val names = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val safeMidi = midi.coerceIn(0, 127)
        return "${names[safeMidi % 12]}${safeMidi / 12 - 1}"
    }


    // ============================================================
    // NOTE → FREQUENCY
    // ============================================================

    fun frequencyFromNote(note: String): Double {
        if (note.length < 2) return 261.625565

        val noteNames = mapOf(
            "C" to 0, "C#" to 1, "D" to 2, "D#" to 3,
            "E" to 4, "F" to 5, "F#" to 6, "G" to 7,
            "G#" to 8, "A" to 9, "A#" to 10, "B" to 11
        )

        val noteName = if (note.length >= 2 && note[1] == '#') note.substring(0, 2) else note.substring(0, 1)
        val octaveStart = if (note.length >= 2 && note[1] == '#') 2 else 1
        val octave = note.substring(octaveStart).toIntOrNull() ?: 4
        val semitone = noteNames[noteName] ?: 0
        val midi = (octave + 1) * 12 + semitone
        return 440.0 * Math.pow(2.0, (midi - 69) / 12.0)
    }

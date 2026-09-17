package com.woe.game

import android.content.Intent
import android.graphics.Bitmap
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.woe.game.ui.theme.AncientGold
import com.woe.game.ui.theme.HpHigh
import com.woe.game.ui.theme.HpLow
import com.woe.game.ui.theme.HpMid
import com.woe.game.ui.theme.WarOfEmpiresTheme
import java.util.Locale

// Las dos "pantallas" de la app. Navegación simple sin librería extra,
// suficiente para el tamaño de este proyecto.
sealed class Pantalla {
    object Consulta : Pantalla()
    object Partida : Pantalla()
}

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var tts: TextToSpeech
    private var ttsListo = false

    private var cartaActual = mutableStateOf<Carta?>(null)
    private var mensajeEstado = mutableStateOf(
        "Bienvenido. Acerca una carta al teléfono o escanea su QR para consultar su información."
    )
    private var qrGenerado = mutableStateOf<Bitmap?>(null)
    private var pantallaActual = mutableStateOf<Pantalla>(Pantalla.Consulta)

    // Historial de la pantalla de Consulta (lectura NFC/QR fuera de partida).
    // El historial de combate de una partida vive dentro de la propia Partida.
    private val historialConsulta = mutableStateListOf<String>()

    // La partida en curso. Se crea una vez que CardRepository ya cargó las cartas.
    private lateinit var partida: Partida

    // Se incrementa cada vez que ocurre una acción de juego (jugar carta,
    // atacar, terminar turno) para forzar la recomposición de PantallaPartida,
    // ya que Partida no usa State internamente.
    private var versionPartida = mutableStateOf(0)

    // Cuántas líneas de partida.historial ya narramos por voz, para no repetir
    // ni volver a leer eventos viejos cada vez que la pantalla se recompone.
    private var historialPartidaNarrado = 0
    private var victoriaNarrada = false

    private fun refrescarPartida() {
        narrarEventosPartida()
        versionPartida.value++
    }

    /**
     * Lee lo que se agregó a `partida.historial` desde la última vez que
     * narramos (jugar carta, atacar, robar, destrucción, eliminación...) y lo
     * lee en voz alta en orden cronológico. `historial` guarda lo más
     * reciente primero (add(0, ...)), por eso se toma un pedazo y se invierte.
     */
    private fun narrarEventosPartida() {
        val actual = partida.historial.size
        val nuevos = actual - historialPartidaNarrado
        val partes = mutableListOf<String>()
        if (nuevos > 0) {
            partes.addAll(partida.historial.take(nuevos).reversed())
        }
        historialPartidaNarrado = actual

        if (partida.partidaTerminada() && !victoriaNarrada) {
            victoriaNarrada = true
            partes.add("Partida terminada. Ganador: ${partida.ganador()?.nombre ?: "nadie"}.")
        }

        if (partes.isNotEmpty()) {
            speak(partes.joinToString(". "))
        }
    }

    /** Para jugar carta / atacar: narra el error de inmediato si falló, o deja que refrescarPartida narre el historial si salió bien. */
    private fun manejarResultadoPartida(resultado: ResultadoAccion) {
        if (resultado is ResultadoAccion.Error) {
            speak(resultado.motivo)
        }
        refrescarPartida()
    }

    // Lanzador del escáner de QR (biblioteca zxing-android-embedded).
    // Debe registrarse como propiedad de clase, antes de que la Activity
    // llegue a STARTED, por eso no va dentro de onCreate.
    private val escanerQrLauncher = registerForActivityResult(ScanContract()) { resultado ->
        val contenido = resultado.contents
        if (contenido != null) {
            procesarTextoDeCarta(contenido)
        }
    }

    private fun escanearQr() {
        val opciones = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Escanea el código QR de la carta")
            setBeepEnabled(true)
            setOrientationLocked(true)
        }
        escanerQrLauncher.launch(opciones)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        CardRepository.cargar(this)

        // TODO: cuando tengamos pantalla de selección de jugadores, reemplazar
        // esta lista fija por la que elija el usuario (2 a 4 nombres).
        partida = Partida(listOf("Jugador 1", "Jugador 2"))

        tts = TextToSpeech(this, this)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        if (nfcAdapter == null) {
            mensajeEstado.value =
                "Este dispositivo no tiene NFC. Usa el botón 'Escanear QR' para consultar o jugar cartas."
        }

        setContent {
            WarOfEmpiresTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding)) {

                        SelectorPantalla(
                            pantallaActual = pantallaActual.value,
                            onSeleccionar = { pantallaActual.value = it }
                        )

                        when (pantallaActual.value) {
                            is Pantalla.Consulta -> PantallaConsulta(
                                carta = cartaActual.value,
                                mensajeEstado = mensajeEstado.value,
                                qrGenerado = qrGenerado.value,
                                historial = historialConsulta,
                                onRepetir = {
                                    val texto = cartaActual.value?.textoParaVoz() ?: mensajeEstado.value
                                    speak(texto)
                                },
                                onEscanearQr = { escanearQr() },
                                onGenerarQr = {
                                    cartaActual.value?.let { carta ->
                                        qrGenerado.value = QrUtils.generarBitmap(carta.id)
                                    }
                                }
                            )
                            is Pantalla.Partida -> PantallaPartida(
                                partida = partida,
                                version = versionPartida.value,
                                onAccion = { refrescarPartida() },
                                onResultado = { resultado -> manejarResultadoPartida(resultado) },
                                onEscanearQr = { escanearQr() }
                            )
                        }
                    }
                }
            }
        }

        handleNfcIntent(intent)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("es", "ES")
            ttsListo = true
            speak(mensajeEstado.value)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNfcIntent(intent)
    }

    private fun handleNfcIntent(intent: Intent?) {
        if (intent?.action != NfcAdapter.ACTION_NDEF_DISCOVERED) return

        try {
            val rawMessages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)

            if (rawMessages.isNullOrEmpty()) {
                mostrarError("No se detectó información en la etiqueta.")
                return
            }

            val message = rawMessages[0] as NdefMessage
            val record = message.records.firstOrNull()

            if (record == null) {
                mostrarError("La etiqueta no tiene datos legibles.")
                return
            }

            val payload = record.payload
            if (payload.isEmpty()) {
                mostrarError("La etiqueta está vacía.")
                return
            }

            val textEncoding = if ((payload[0].toInt() and 128) == 0) Charsets.UTF_8 else Charsets.UTF_16
            val languageCodeLength = payload[0].toInt() and 0x3F

            if (languageCodeLength + 1 > payload.size) {
                mostrarError("No se pudo interpretar esta etiqueta.")
                return
            }

            val texto = String(
                payload,
                languageCodeLength + 1,
                payload.size - languageCodeLength - 1,
                textEncoding
            )

            procesarTextoDeCarta(texto)

        } catch (e: Exception) {
            mostrarError("No se pudo leer esta etiqueta correctamente.")
        }
    }

    /**
     * Punto único que procesa el texto identificador de una carta (el `id`),
     * venga de NFC o de un código QR escaneado. Según en qué pantalla esté
     * el usuario, el mismo dato se usa para "consultar" o para "jugar".
     */
    private fun procesarTextoDeCarta(texto: String) {
        val carta = CardRepository.buscar(texto)
        if (carta == null) {
            mostrarError("Carta no reconocida: $texto")
            return
        }

        when (pantallaActual.value) {
            is Pantalla.Consulta -> {
                cartaActual.value = carta
                qrGenerado.value = null
                mensajeEstado.value = "Carta detectada."
                speak(carta.textoParaVoz())
                historialConsulta.add(0, "Se consultó: ${carta.name}")
            }
            is Pantalla.Partida -> {
                val resultado = partida.jugarCartaFisica(carta)
                manejarResultadoPartida(resultado)
            }
        }
    }

    private fun mostrarError(mensaje: String) {
        cartaActual.value = null
        qrGenerado.value = null
        mensajeEstado.value = mensaje
        speak(mensaje)
        historialConsulta.add(0, "Error de lectura: $mensaje")
    }

    override fun onResume() {
        super.onResume()
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent,
            android.app.PendingIntent.FLAG_MUTABLE
        )
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    private fun speak(text: String) {
        if (!ttsListo) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }
}

// ---------------------------------------------------------------------
// Piezas de UI reutilizables
// ---------------------------------------------------------------------

/** Selector Consulta/Partida con aspecto de pestañas, no botones sueltos. */
@Composable
private fun SelectorPantalla(pantallaActual: Pantalla, onSeleccionar: (Pantalla) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp)
    ) {
        PestanaSelector(
            texto = "Consulta",
            seleccionada = pantallaActual is Pantalla.Consulta,
            modifier = Modifier.weight(1f),
            onClick = { onSeleccionar(Pantalla.Consulta) }
        )
        PestanaSelector(
            texto = "Partida",
            seleccionada = pantallaActual is Pantalla.Partida,
            modifier = Modifier.weight(1f),
            onClick = { onSeleccionar(Pantalla.Partida) }
        )
    }
}

@Composable
private fun PestanaSelector(
    texto: String,
    seleccionada: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val fondo = if (seleccionada) MaterialTheme.colorScheme.primary else Color.Transparent
    val contenido = if (seleccionada) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(fondo)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = texto,
            color = contenido,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

/** Sección con título, envuelta en una tarjeta con la identidad visual del juego. */
@Composable
private fun Seccion(titulo: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = titulo,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

/** Barra de vida coloreada según el porcentaje restante (verde/ámbar/rojo). */
@Composable
private fun BarraDeVida(hpActual: Int, hpMax: Int) {
    val proporcion = if (hpMax <= 0) 0f else (hpActual.toFloat() / hpMax.toFloat()).coerceIn(0f, 1f)
    val color = when {
        proporcion > 0.5f -> HpHigh
        proporcion > 0.2f -> HpMid
        else -> HpLow
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { proporcion },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "$hpActual / $hpMax HP",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** Fila que representa una carta (en mano o en campo) con aspecto de mini-ficha. */
@Composable
private fun FilaCarta(
    nombre: String,
    detalle: String,
    accionTexto: String,
    accionHabilitada: Boolean = true,
    onAccion: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, AncientGold.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = nombre, fontWeight = FontWeight.Bold)
            Text(text = detalle, style = MaterialTheme.typography.bodyMedium)
        }
        Button(
            enabled = accionHabilitada,
            onClick = onAccion,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text(accionTexto)
        }
    }
}

// ---------------------------------------------------------------------
// Pantalla de Consulta
// ---------------------------------------------------------------------

@Composable
fun PantallaConsulta(
    carta: Carta?,
    mensajeEstado: String,
    qrGenerado: Bitmap?,
    historial: List<String>,
    onRepetir: () -> Unit,
    onEscanearQr: () -> Unit,
    onGenerarQr: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp)
    ) {
        Seccion(titulo = if (carta != null) carta.name else "Consulta de carta") {
            val textoMostrado = carta?.textoParaPantalla() ?: mensajeEstado
            Text(
                text = textoMostrado,
                modifier = Modifier.semantics {
                    contentDescription = carta?.textoParaVoz() ?: mensajeEstado
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row {
                Button(
                    onClick = onRepetir,
                    modifier = Modifier.semantics {
                        contentDescription = "Repetir información en voz alta"
                    }
                ) {
                    Text("🔊 Repetir")
                }
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = onEscanearQr,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("Escanear QR", color = MaterialTheme.colorScheme.onSecondary)
                }
            }

            if (carta != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Button(onClick = onGenerarQr) {
                    Text("Ver código QR de esta carta")
                }
            }

            if (qrGenerado != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Image(
                    bitmap = qrGenerado.asImageBitmap(),
                    contentDescription = "Código QR de la carta",
                    modifier = Modifier
                        .size(220.dp)
                        .align(Alignment.CenterHorizontally)
                )
            }
        }

        if (historial.isNotEmpty()) {
            Seccion(titulo = "Historial de consultas") {
                LazyColumn(modifier = Modifier.heightIn(max = 160.dp)) {
                    items(historial) { evento ->
                        Text(text = "• $evento", modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------
// Pantalla de Partida
// ---------------------------------------------------------------------

@Composable
fun PantallaPartida(
    partida: Partida,
    version: Int,
    onAccion: () -> Unit,
    onResultado: (ResultadoAccion) -> Unit,
    onEscanearQr: () -> Unit
) {
    // `version` no se usa directamente, pero al leerlo aquí forzamos que
    // Compose recomponga esta pantalla cada vez que cambia el estado del juego.
    @Suppress("UNUSED_EXPRESSION")
    version

    val jugadorActual = partida.jugadorActual
    var atacanteSeleccionado by remember(version) { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp)
    ) {
        // Banner de turno: lo primero que se ve, con la identidad del juego.
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(14.dp)
                    .semantics { contentDescription = "Turno de ${jugadorActual.nombre}" }
            ) {
                Text(
                    text = "Turno de ${jugadorActual.nombre}",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Text(
                    text = "Turno global: ${partida.turnoGlobal}",
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onEscanearQr, modifier = Modifier.weight(1f)) {
                Text("Escanear QR para jugar")
            }
            Button(
                onClick = {
                    partida.terminarTurno()
                    onAccion()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("Terminar turno", color = MaterialTheme.colorScheme.onSecondary)
            }
        }
        Text(
            text = "Tip: acerca la carta NFC en cualquier momento de tu turno para jugarla directo al campo.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        Seccion(titulo = "Jugadores") {
            partida.jugadores.forEach { jugador ->
                val esActual = jugador.id == jugadorActual.id
                val sufijo = if (jugador.eliminado) " · eliminado" else if (esActual) " · en turno" else ""
                Text(
                    text = "${jugador.nombre}$sufijo",
                    fontWeight = if (esActual) FontWeight.Bold else FontWeight.Normal
                )
                BarraDeVida(hpActual = jugador.hp, hpMax = Partida.HP_INICIAL)
                Text(
                    text = "${jugador.campo.size} carta(s) en campo",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        if (partida.partidaTerminada()) {
            Seccion(titulo = "¡Partida terminada!") {
                Text(
                    text = "Ganador: ${partida.ganador()?.nombre ?: "nadie"}",
                    style = MaterialTheme.typography.titleLarge
                )
            }
            return@Column
        }

        Seccion(titulo = "Tu mano (${jugadorActual.nombre})") {
            if (jugadorActual.mano.isEmpty()) {
                Text("No te quedan cartas en la mano.", style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                items(jugadorActual.mano) { carta ->
                    FilaCarta(
                        nombre = carta.name,
                        detalle = "ATQ ${carta.attack} / DEF ${carta.defense} / HP ${carta.hp}",
                        accionTexto = "Jugar",
                        accionHabilitada = jugadorActual.cartasJugadasEsteTurno < Partida.LIMITE_CARTAS_POR_TURNO,
                        onAccion = {
                            onResultado(partida.jugarCarta(carta))
                        }
                    )
                }
            }
            if (jugadorActual.cartasJugadasEsteTurno >= Partida.LIMITE_CARTAS_POR_TURNO) {
                Text(
                    text = "Ya jugaste tu carta de este turno.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Seccion(titulo = "Tu campo") {
            if (jugadorActual.campo.isEmpty()) {
                Text("Todavía no tienes cartas en el campo.", style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                items(jugadorActual.campo) { cartaEnJuego ->
                    val sufijo = if (cartaEnJuego.yaAtacoEsteTurno) " · ya atacó" else ""
                    FilaCarta(
                        nombre = cartaEnJuego.base.name,
                        detalle = "${cartaEnJuego.hpActual}/${cartaEnJuego.base.hp} HP$sufijo",
                        accionTexto = "Atacar",
                        accionHabilitada = !cartaEnJuego.yaAtacoEsteTurno,
                        onAccion = { atacanteSeleccionado = cartaEnJuego.instanceId }
                    )
                }
            }
        }

        val instanceIdSeleccionado = atacanteSeleccionado
        if (instanceIdSeleccionado != null) {
            Seccion(titulo = "Elige a quién atacar") {
                partida.jugadores
                    .filter { it.id != jugadorActual.id && !it.eliminado }
                    .forEach { rival ->
                        if (partida.puedeAtacarDirectoA(rival)) {
                            Button(
                                onClick = {
                                    onResultado(partida.atacar(instanceIdSeleccionado, ObjetivoAtaque.AJugador(rival.id)))
                                    atacanteSeleccionado = null
                                },
                                modifier = Modifier.padding(vertical = 3.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text("⚔ Atacar directo a ${rival.nombre}")
                            }
                        } else {
                            Text(
                                text = "${rival.nombre} tiene cartas en campo — debes destruirlas antes de atacar directo.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        rival.campo.forEach { cartaRival ->
                            Button(
                                onClick = {
                                    onResultado(
                                        partida.atacar(
                                            instanceIdSeleccionado,
                                            ObjetivoAtaque.ACarta(rival.id, cartaRival.instanceId)
                                        )
                                    )
                                    atacanteSeleccionado = null
                                },
                                modifier = Modifier.padding(vertical = 3.dp)
                            ) {
                                Text("Atacar a ${cartaRival.base.name} (${rival.nombre})")
                            }
                        }
                    }

                Spacer(modifier = Modifier.height(4.dp))
                Button(onClick = { atacanteSeleccionado = null }) {
                    Text("Cancelar")
                }
            }
        }

        Seccion(titulo = "Historial de combate") {
            LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                items(partida.historial) { evento ->
                    Text(text = "• $evento", modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

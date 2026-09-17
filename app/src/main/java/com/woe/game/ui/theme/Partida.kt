package com.woe.game

/**
 * Estado de un jugador durante la partida.
 */
class Jugador(
    val id: Int,
    val nombre: String
) {
    var hp: Int = Partida.HP_INICIAL
        internal set

    var eliminado: Boolean = false
        internal set

    val mazo: MutableList<Carta> = mutableListOf()
    val mano: MutableList<Carta> = mutableListOf()
    val campo: MutableList<CartaEnJuego> = mutableListOf()

    internal var cartasJugadasEsteTurno: Int = 0

    internal fun recibirDanoDirecto(cantidad: Int) {
        if (cantidad <= 0) return
        hp = (hp - cantidad).coerceAtLeast(0)
    }
}

/** A qué le está apuntando un ataque. */
sealed class ObjetivoAtaque {
    data class AJugador(val jugadorId: Int) : ObjetivoAtaque()
    data class ACarta(val jugadorId: Int, val cartaInstanceId: String) : ObjetivoAtaque()
}

/** Resultado de intentar una acción, para mostrarlo en pantalla o en el historial. */
sealed class ResultadoAccion {
    data class Ok(val mensaje: String) : ResultadoAccion()
    data class Error(val motivo: String) : ResultadoAccion()
}

/**
 * Motor de la partida. No sabe nada de UI ni de NFC — solo aplica las reglas.
 * La actividad/composables se encargan de mostrar `historial` y `jugadores`.
 */
class Partida(nombresJugadores: List<String>) {

    companion object {
        const val HP_INICIAL = 8000
        const val LIMITE_CARTAS_CAMPO = 5
        const val LIMITE_CARTAS_POR_TURNO = 1
        const val TAMANO_MAZO = 30
        const val MANO_INICIAL = 5
    }

    val jugadores: List<Jugador> = nombresJugadores.mapIndexed { index, nombre ->
        Jugador(id = index, nombre = nombre)
    }

    /** Más reciente primero, igual que el historial que ya tenías en MainActivity. */
    val historial: MutableList<String> = mutableListOf()

    /** Cuenta todos los turnos jugados por todos, empezando en 1. */
    var turnoGlobal: Int = 0
        private set

    private var indiceJugadorActual: Int = 0

    val jugadorActual: Jugador
        get() = jugadores[indiceJugadorActual]

    init {
        jugadores.forEach { jugador ->
            jugador.mazo.addAll(CardRepository.mazoAleatorio(TAMANO_MAZO))
            repeat(MANO_INICIAL) {
                if (jugador.mazo.isNotEmpty()) {
                    jugador.mano.add(jugador.mazo.removeAt(0))
                }
            }
        }
        iniciarTurno()
    }

    // ---------------------------------------------------------------------
    // Turnos
    // ---------------------------------------------------------------------

    private fun iniciarTurno() {
        turnoGlobal++
        val jugador = jugadorActual

        if (jugador.eliminado) {
            avanzarAlSiguienteJugador()
            return
        }

        jugador.cartasJugadasEsteTurno = 0
        jugador.campo.forEach { it.resetearTurno() }

        if (jugador.mazo.isNotEmpty()) {
            val carta = jugador.mazo.removeAt(0)
            jugador.mano.add(carta)
            historial.add(0, "${jugador.nombre} robó una carta.")
        } else {
            historial.add(0, "${jugador.nombre} no tiene cartas para robar.")
        }

        historial.add(0, "— Turno de ${jugador.nombre} (turno global $turnoGlobal) —")
    }

    fun terminarTurno() {
        if (partidaTerminada()) return
        avanzarAlSiguienteJugador()
    }

    private fun avanzarAlSiguienteJugador() {
        if (jugadores.all { it.eliminado }) return
        do {
            indiceJugadorActual = (indiceJugadorActual + 1) % jugadores.size
        } while (jugadores[indiceJugadorActual].eliminado)
        iniciarTurno()
    }

    // ---------------------------------------------------------------------
    // Jugar cartas
    // ---------------------------------------------------------------------

    fun jugarCarta(carta: Carta): ResultadoAccion {
        val jugador = jugadorActual

        if (jugador.campo.size >= LIMITE_CARTAS_CAMPO) {
            return ResultadoAccion.Error("El campo ya tiene el máximo de $LIMITE_CARTAS_CAMPO cartas.")
        }
        if (jugador.cartasJugadasEsteTurno >= LIMITE_CARTAS_POR_TURNO) {
            return ResultadoAccion.Error("Ya jugaste el máximo de $LIMITE_CARTAS_POR_TURNO carta por turno.")
        }
        if (!jugador.mano.remove(carta)) {
            return ResultadoAccion.Error("Esa carta no está en tu mano.")
        }

        jugador.cartasJugadasEsteTurno++
        jugador.campo.add(CartaEnJuego(carta))

        val mensaje = "${jugador.nombre} jugó ${carta.name}."
        historial.add(0, mensaje)
        return ResultadoAccion.Ok(mensaje)
    }

    /**
     * Juega una carta detectada por NFC o QR directamente al campo del
     * jugador en turno. A diferencia de [jugarCarta], no la busca ni la
     * quita de una "mano" virtual — representa el hecho de que el jugador
     * tiene la carta física en la mano y la está poniendo en la mesa.
     * Igual respeta el límite de cartas por turno y el límite de campo.
     */
    fun jugarCartaFisica(carta: Carta): ResultadoAccion {
        val jugador = jugadorActual

        if (jugador.campo.size >= LIMITE_CARTAS_CAMPO) {
            return ResultadoAccion.Error("El campo de ${jugador.nombre} ya tiene el máximo de $LIMITE_CARTAS_CAMPO cartas.")
        }
        if (jugador.cartasJugadasEsteTurno >= LIMITE_CARTAS_POR_TURNO) {
            return ResultadoAccion.Error("${jugador.nombre} ya jugó el máximo de $LIMITE_CARTAS_POR_TURNO carta por turno.")
        }

        jugador.cartasJugadasEsteTurno++
        jugador.campo.add(CartaEnJuego(carta))

        val mensaje = "${jugador.nombre} jugó ${carta.name} (carta física escaneada)."
        historial.add(0, mensaje)
        return ResultadoAccion.Ok(mensaje)
    }

    // ---------------------------------------------------------------------
    // Ataques
    // ---------------------------------------------------------------------

    /** Solo se puede atacar directo al jugador rival si su campo está vacío. */
    fun puedeAtacarDirectoA(jugadorObjetivo: Jugador): Boolean {
        return jugadorObjetivo.campo.isEmpty()
    }

    fun atacar(atacanteInstanceId: String, objetivo: ObjetivoAtaque): ResultadoAccion {
        val atacante = jugadorActual.campo.firstOrNull { it.instanceId == atacanteInstanceId }
            ?: return ResultadoAccion.Error("Esa carta no está en tu campo.")

        if (atacante.yaAtacoEsteTurno) {
            return ResultadoAccion.Error("${atacante.base.name} ya atacó este turno.")
        }

        return when (objetivo) {
            is ObjetivoAtaque.AJugador -> atacarJugador(atacante, objetivo.jugadorId)
            is ObjetivoAtaque.ACarta -> atacarCarta(atacante, objetivo.jugadorId, objetivo.cartaInstanceId)
        }
    }

    private fun atacarJugador(atacante: CartaEnJuego, jugadorObjetivoId: Int): ResultadoAccion {
        val objetivo = jugadores.firstOrNull { it.id == jugadorObjetivoId }
            ?: return ResultadoAccion.Error("Jugador objetivo no encontrado.")

        if (objetivo.id == jugadorActual.id) {
            return ResultadoAccion.Error("No puedes atacarte a ti mismo.")
        }
        if (objetivo.eliminado) {
            return ResultadoAccion.Error("${objetivo.nombre} ya fue eliminado.")
        }
        if (!puedeAtacarDirectoA(objetivo)) {
            return ResultadoAccion.Error(
                "${objetivo.nombre} todavía tiene cartas en su campo — debes atacarlas primero."
            )
        }

        objetivo.recibirDanoDirecto(atacante.base.attack)
        atacante.marcarComoAtacoEsteTurno()

        val mensaje = "${atacante.base.name} atacó directamente a ${objetivo.nombre} por ${atacante.base.attack} de daño."
        historial.add(0, mensaje)

        comprobarEliminacion(objetivo)
        return ResultadoAccion.Ok(mensaje)
    }

    private fun atacarCarta(
        atacante: CartaEnJuego,
        jugadorObjetivoId: Int,
        cartaObjetivoInstanceId: String
    ): ResultadoAccion {
        val jugadorObjetivo = jugadores.firstOrNull { it.id == jugadorObjetivoId }
            ?: return ResultadoAccion.Error("Jugador objetivo no encontrado.")

        val cartaObjetivo = jugadorObjetivo.campo.firstOrNull { it.instanceId == cartaObjetivoInstanceId }
            ?: return ResultadoAccion.Error("Esa carta no está en el campo de ${jugadorObjetivo.nombre}.")

        val dano = (atacante.base.attack - cartaObjetivo.base.defense).coerceAtLeast(0)
        cartaObjetivo.recibirDano(dano)
        atacante.marcarComoAtacoEsteTurno()

        val mensaje = if (dano > 0) {
            "${atacante.base.name} atacó a ${cartaObjetivo.base.name} por $dano de daño."
        } else {
            "${atacante.base.name} atacó a ${cartaObjetivo.base.name}, pero su defensa absorbió todo el daño."
        }
        historial.add(0, mensaje)

        if (dano > 0) {
            aplicarHabilidad(cartaObjetivo, Disparador.AL_RECIBIR_DANO)
        }

        if (!cartaObjetivo.estaViva) {
            jugadorObjetivo.campo.remove(cartaObjetivo)
            historial.add(0, "${cartaObjetivo.base.name} fue destruida.")
        }

        return ResultadoAccion.Ok(mensaje)
    }

    // ---------------------------------------------------------------------
    // Habilidades (motor básico basado en el texto de `ability`)
    // ---------------------------------------------------------------------

    private enum class Disparador { AL_RECIBIR_DANO }

    /**
     * Interpreta habilidades sencillas a partir del texto libre de la carta.
     * Por ahora solo reconoce el patrón "Recupera N puntos de vida después
     * de recibir daño". Es un punto de partida fácil de extender: cuando
     * tengamos más habilidades, esto puede evolucionar a una lista de
     * efectos estructurados en el JSON en lugar de parsear texto.
     */
    private fun aplicarHabilidad(carta: CartaEnJuego, disparador: Disparador) {
        if (!carta.estaViva) return
        val texto = carta.base.ability.lowercase()

        if (disparador == Disparador.AL_RECIBIR_DANO &&
            texto.contains("recupera") &&
            texto.contains("recibir daño")
        ) {
            val cantidad = Regex("recupera (\\d+)").find(texto)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (cantidad > 0) {
                carta.curar(cantidad)
                historial.add(0, "${carta.base.name} recuperó $cantidad HP por su habilidad.")
            }
        }
    }

    // ---------------------------------------------------------------------
    // Fin de partida
    // ---------------------------------------------------------------------

    private fun comprobarEliminacion(jugador: Jugador) {
        if (jugador.hp <= 0 && !jugador.eliminado) {
            jugador.eliminado = true
            historial.add(0, "${jugador.nombre} ha sido eliminado de la partida.")
        }
    }

    fun jugadoresActivos(): List<Jugador> = jugadores.filter { !it.eliminado }

    fun partidaTerminada(): Boolean = jugadoresActivos().size <= 1

    fun ganador(): Jugador? = if (partidaTerminada()) jugadoresActivos().firstOrNull() else null
}
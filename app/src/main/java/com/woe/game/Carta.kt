package com.woe.game

/**
 * Definición base de una carta, tal como viene del JSON.
 * Esto es "la plantilla" — no cambia durante la partida.
 * El estado que sí cambia en juego (HP actual, si ya atacó, etc.)
 * vive en [CartaEnJuego].
 */
data class Carta(
    val id: String,
    val name: String,
    val attack: Int,
    val defense: Int,
    val hp: Int,
    val type: String,
    val ability: String
) {
    fun textoParaVoz(): String {
        return "Carta $name. Ataque $attack. Defensa $defense. Vida $hp. Tipo $type. Habilidad: $ability"
    }

    fun textoParaPantalla(): String {
        return "$name\nATQ: $attack   DEF: $defense   HP: $hp\nTipo: $type\n$ability"
    }
}

/**
 * Instancia de una carta puesta en el campo de batalla.
 * Cada vez que se juega una [Carta] desde la mano, se crea una de estas.
 * Puede haber varias instancias de la misma carta base en juego a la vez.
 */
class CartaEnJuego(
    val base: Carta,
    val instanceId: String = java.util.UUID.randomUUID().toString()
) {
    var hpActual: Int = base.hp
        internal set

    var yaAtacoEsteTurno: Boolean = false
        internal set

    val estaViva: Boolean
        get() = hpActual > 0

    fun recibirDano(cantidad: Int) {
        if (cantidad <= 0) return
        hpActual = (hpActual - cantidad).coerceAtLeast(0)
    }

    fun curar(cantidad: Int) {
        if (cantidad <= 0) return
        hpActual = (hpActual + cantidad).coerceAtMost(base.hp)
    }

    fun marcarComoAtacoEsteTurno() {
        yaAtacoEsteTurno = true
    }

    fun resetearTurno() {
        yaAtacoEsteTurno = false
    }
}
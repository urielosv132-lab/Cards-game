package com.woe.game

import android.content.Context
import org.json.JSONArray

/**
 * Carga las cartas desde assets/cartas.json y las deja disponibles
 * en memoria para toda la app (consulta NFC, armado de mazos, etc.).
 *
 * Uso esperado:
 *   CardRepository.cargar(context)   // una vez, por ejemplo en onCreate
 *   CardRepository.buscar("CARD-001")
 */
object CardRepository {

    private var cartas: List<Carta> = emptyList()
    private var cargado = false

    fun cargar(context: Context) {
        if (cargado) return

        val json = context.assets.open("cartas.json")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

        val arreglo = JSONArray(json)
        val lista = mutableListOf<Carta>()

        for (i in 0 until arreglo.length()) {
            val obj = arreglo.getJSONObject(i)
            lista.add(
                Carta(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    attack = obj.getInt("attack"),
                    defense = obj.getInt("defense"),
                    hp = obj.getInt("hp"),
                    type = obj.getString("type"),
                    ability = obj.getString("ability")
                )
            )
        }

        cartas = lista
        cargado = true
    }

    /**
     * Busca una carta por id o por nombre (para que la lectura NFC
     * funcione tanto si la etiqueta trae el id como si trae el nombre).
     */
    fun buscar(texto: String): Carta? {
        val limpio = texto.trim()
        return cartas.firstOrNull { it.id.equals(limpio, ignoreCase = true) }
            ?: cartas.firstOrNull { it.name.equals(limpio, ignoreCase = true) }
    }

    fun todas(): List<Carta> = cartas

    /**
     * Genera un mazo aleatorio de [tamano] cartas, repitiendo cartas base
     * si hace falta (útil mientras no tengamos un catálogo grande).
     */
    fun mazoAleatorio(tamano: Int): MutableList<Carta> {
        if (cartas.isEmpty()) return mutableListOf()
        val mazo = mutableListOf<Carta>()
        repeat(tamano) {
            mazo.add(cartas.random())
        }
        mazo.shuffle()
        return mazo
    }
}
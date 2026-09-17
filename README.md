# War of Empires

Juego de cartas coleccionables (TCG) para Android, jugado con cartas físicas: la app reconoce cada carta por **NFC** o por **código QR**, muestra su información en pantalla y la narra por voz (Text-to-Speech), y ofrece un modo de **partida completo por turnos** (2 jugadores) dentro de la misma app.

Este documento es la referencia técnica para el equipo de desarrollo. Para una versión resumida orientada al docente, ver `War_of_Empires_Ficha_Breve.docx`. Las 39 cartas con su arte completo están en `CARTAS.docx`.

---

## 1. Stack y requisitos

| | |
|---|---|
| Lenguaje | Kotlin 2.2.10 |
| UI | Jetpack Compose (Material 3) — BOM `2026.02.01` |
| Android Gradle Plugin | 9.3.2 |
| `compileSdk` / `targetSdk` | 36 |
| `minSdk` | 26 |
| Lectura de QR | `com.journeyapps:zxing-android-embedded:4.3.0` |
| Permisos | `android.permission.NFC` (uso opcional, `android:required="false"`) |

No hay backend ni base de datos: todo el estado vive en memoria mientras la app está abierta (ver [Limitaciones](#6-limitaciones-conocidas--deuda-técnica)).

Para compilar: `./gradlew assembleDebug` desde la raíz del proyecto (`WarofEmpires/`).

---

## 2. Estructura del proyecto

```
WarofEmpires/
└── app/src/main/
    ├── assets/cartas.json              # Catálogo de las 39 cartas (fuente de verdad de datos)
    ├── java/com/woe/game/
    │   ├── MainActivity.kt             # Activity única + toda la UI (Compose) + manejo de NFC
    │   ├── Carta.kt                    # Modelo de datos: Carta y CartaEnJuego
    │   ├── CardRepository.kt           # Carga cartas.json, búsqueda por id/nombre, mazo aleatorio
    │   └── ui/theme/
    │       ├── Partida.kt              # Motor de reglas del juego (turnos, ataques, victoria)
    │       ├── Qrutils.kt              # Generación de bitmap QR a partir del id de una carta
    │       ├── Color.kt / Theme.kt / Type.kt   # Tema visual de Compose
    └── res/                            # Iconos, strings, tema XML estándar de Android
```

> Nota de organización: `Partida.kt` y `Qrutils.kt` están físicamente dentro de `ui/theme/`, aunque son lógica de juego, no tema visual. No afecta la compilación (mismo paquete `com.woe.game`), pero conviene moverlos a un paquete propio (`logic/` o raíz) en una futura limpieza.

---

## 3. Modelo de datos

### `cartas.json` (en `assets/`)

Arreglo de objetos con esta forma:

```json
{
  "id": "CARD-001",
  "name": "Okarun",
  "attack": 21,
  "defense": 0,
  "hp": 1000,
  "type": "Ataque",
  "ability": "Hiper velocidad hace que sea bastante consecutivos rápidamente."
}
```

- `id`: identificador único, es lo que se graba en la etiqueta NFC o se codifica en el QR.
- `type`: `"Ataque"`, `"Defensa"` o `"Potenciador"` (texto libre, sin enum — ver limitaciones).
- `ability`: texto libre en español. Solo una parte de estas habilidades está realmente implementada (ver [§5](#5-motor-de-habilidades)).

**Catálogo actual: 39 cartas** — 15 de Ataque, 14 de Defensa, 10 Potenciador. Todas con `hp: 1000` excepto la mayoría de los Potenciadores, que tienen `hp: 0` (ver limitaciones).

### `Carta` vs `CartaEnJuego` (`Carta.kt`)

- **`Carta`**: plantilla inmutable tal como viene del JSON (`data class`). Expone `textoParaVoz()` y `textoParaPantalla()` para no duplicar el formato de texto en la UI.
- **`CartaEnJuego`**: instancia de una `Carta` puesta en el campo de batalla. Tiene `instanceId` propio (UUID), `hpActual` (vida restante en esta partida) y `yaAtacoEsteTurno`. Puede haber varias instancias de la misma carta base en juego a la vez.

---

## 4. Motor de partida (`Partida.kt`)

Clase independiente de la UI y de NFC/QR — solo aplica reglas. Esto facilita probarla por separado (unit tests) sin tocar Compose ni Android.

### Constantes

| Constante | Valor |
|---|---|
| `HP_INICIAL` | 8000 |
| `LIMITE_CARTAS_CAMPO` | 5 |
| `LIMITE_CARTAS_POR_TURNO` | 1 |
| `TAMANO_MAZO` | 30 |
| `MANO_INICIAL` | 5 |

### Flujo de una partida

1. **Inicio** (`init`): cada jugador recibe un mazo aleatorio de 30 cartas (`CardRepository.mazoAleatorio`, con repetición) y roba 5 a la mano.
2. **Turno** (`iniciarTurno`): incrementa `turnoGlobal`, resetea el flag de ataque de las cartas en campo, roba 1 carta (si el mazo no está vacío) y registra los eventos en `historial`.
3. **Jugar carta**: `jugarCarta(carta)` (desde la mano virtual en pantalla) o `jugarCartaFisica(carta)` (desde una lectura NFC/QR durante una partida). Ambas validan límite de campo (5) y de cartas jugadas por turno (1).
4. **Atacar** (`atacar`): una `CartaEnJuego` que no haya atacado este turno puede atacar:
   - **Directo al jugador rival** (`ObjetivoAtaque.AJugador`) — solo permitido si el campo del rival está vacío (`puedeAtacarDirectoA`).
   - **A una carta rival** (`ObjetivoAtaque.ACarta`) — daño = `max(0, ataque_atacante - defensa_objetivo)`. Si la carta llega a 0 HP, se retira del campo.
5. **Fin de partida**: `partidaTerminada()` es `true` cuando queda 1 o 0 jugadores activos; `ganador()` devuelve el sobreviviente.

Todas las acciones devuelven un `ResultadoAccion` (`Ok` o `Error` con motivo), que la UI usa para narrar por voz el resultado o el error.

---

## 5. Motor de habilidades

`Partida.aplicarHabilidad()` es, hoy, un intérprete muy simple de **texto libre**, no un motor de efectos estructurado:

```kotlin
if (disparador == AL_RECIBIR_DANO &&
    texto.contains("recupera") &&
    texto.contains("recibir daño")) {
    // cura a la carta según el número que sigue a "recupera"
}
```

Es decir: **de las 39 habilidades escritas en `cartas.json`, solo el patrón "Recupera N puntos de vida después de recibir daño" tiene efecto real en el juego.** El resto (probabilidades de esquivar, robar cartas del rival, bloquear cartas jugadas, potenciar ataque/defensa, aturdir un turno, etc.) son **texto descriptivo que la app muestra y narra, pero que el motor no ejecuta**. Ningún jugador arbitra esto manualmente hoy — es una laguna real de reglas, no solo un detalle de UI.

Si se quiere automatizar más habilidades, lo natural es evolucionar `ability` de texto libre a una lista de efectos estructurados en el JSON (`{"trigger": "on_damage", "effect": "heal", "amount": 10}`), y que `aplicarHabilidad` itere esa lista en vez de hacer *pattern matching* sobre strings.

---

## 6. Lectura NFC / QR (`MainActivity.kt`, `CardRepository.kt`, `Qrutils.kt`)

- **NFC**: `handleNfcIntent()` escucha `ACTION_NDEF_DISCOVERED`, decodifica el primer registro NDEF (maneja UTF-8 y UTF-16) y extrae el texto grabado en la etiqueta (se espera que sea el `id` de la carta).
- **QR**: `escanearQr()` lanza el scanner de `zxing-android-embedded`; el texto leído se procesa igual que el de NFC.
- **Punto único de entrada**: `procesarTextoDeCarta(texto)` recibe ese texto (venga de NFC o QR) y decide qué hacer según la pantalla activa:
  - En **Consulta**: busca la carta (`CardRepository.buscar`, por `id` o por `name`, sin distinguir mayúsculas) y la muestra/narra.
  - En **Partida**: la juega directo al campo del jugador en turno (`partida.jugarCartaFisica`).
- **Generación de QR** (`QrUtils.generarBitmap`): dado el `id` de una carta, genera un `Bitmap` 512×512 en blanco y negro — útil para imprimir el QR junto a la carta física en dispositivos sin NFC.

---

## 7. Interfaz (Compose)

Una sola `Activity` (`MainActivity`), sin librería de navegación — el cambio de pantalla es un `sealed class Pantalla { Consulta, Partida }` guardado en `mutableStateOf`.

- **`PantallaConsulta`**: muestra la última carta leída (o un mensaje de bienvenida/error), botón para repetir la narración por voz, botón para escanear QR y botón para generar el QR de la carta actual. Mantiene un historial de consultas.
- **`PantallaPartida`**: banner de turno, botones de acción, vida de cada jugador (`BarraDeVida`, coloreada verde/ámbar/rojo según el % de vida), mano y campo del jugador actual (`FilaCarta`), selector de objetivo de ataque, e historial de combate.
- **Recomposición manual**: `Partida` no usa `State` internamente (es una clase Kotlin “pura”), así que `MainActivity` mantiene un contador `versionPartida` que se incrementa después de cada acción de juego para forzar que Compose vuelva a dibujar `PantallaPartida`.

### Accesibilidad

- Texto a voz (`TextToSpeech`, en `es-ES`) narra automáticamente: el mensaje de bienvenida, cada carta leída, los eventos nuevos del historial de partida y el resultado de la partida.
- Varios elementos usan `Modifier.semantics { contentDescription = ... }` para lectores de pantalla (p. ej. el turno actual, el botón de repetir).

---

## 8. Limitaciones conocidas / deuda técnica

- **Habilidades**: solo una está automatizada (ver [§5](#5-motor-de-habilidades)); el resto requiere arbitraje manual entre jugadores si se quieren usar tal como están descritas.
- **Cartas Potenciador con `hp: 0`**: si una de estas se juega con la lógica actual de `CartaEnJuego` (`hpActual = base.hp`), nace con 0 HP y `estaViva` sería `false` de inmediato. Hoy nada impide jugarlas ni revisa este caso — es un bug latente a probar/corregir.
- **Jugadores fijos**: `Partida(listOf("Jugador 1", "Jugador 2"))` está *hardcodeado* en `onCreate`; no hay pantalla para elegir nombres ni número de jugadores (2 a 4).
- **Mazo aleatorio, no construido**: `CardRepository.mazoAleatorio()` arma el mazo de 30 cartas al azar (con repetición) entre las 39 disponibles; no hay editor de mazos.
- **Sin persistencia**: nada se guarda en disco/base de datos — cerrar la app pierde la partida en curso y el historial de consultas.
- **`type` como texto libre**: `"Ataque" / "Defensa" / "Potenciador"` son strings sin validar contra un enum; un typo en el JSON no rompe la compilación pero puede pasar inadvertido en tiempo de ejecución.
- **`PendingIntent.FLAG_MUTABLE`** en el registro de NFC (`onResume`): funciona, pero conviene revisar si `FLAG_IMMUTABLE` es suficiente por seguridad, ya que no se modifica el intent luego de creado.

---

## 9. Próximos pasos sugeridos

1. Definir 3–5 efectos estructurados más (no solo el de curación) y extender `cartas.json` + `aplicarHabilidad` para soportarlos.
2. Pantalla de selección de jugadores (nombres, 2–4 jugadores) antes de crear la `Partida`.
3. Corregir el caso de cartas con `hp: 0` (decidir si esas cartas deben poder "jugarse" al campo o si su efecto debe resolverse distinto, p. ej. al instante).
4. Mover `Partida.kt` y `Qrutils.kt` fuera de `ui/theme/` a un paquete de lógica propio.
5. Guardar el estado de partida (aunque sea en memoria persistida con `SavedStateHandle`, o local con Room/DataStore si se quiere sobrevivir a cierres de la app).

---

## 10. Referencias del proyecto

- `CARTAS.docx` — ficha ilustrada de las 39 cartas (arte + datos), fuente original de `cartas.json`.
- `War_of_Empires_Ficha_Breve.docx` — resumen de una página para presentar el proyecto a un docente.

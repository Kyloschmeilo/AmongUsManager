package de.amongusmanager.crewbot.web

/**
 * Schachregeln für das Online-Schach (seiten/schach.html, /api/schach). Der Server prüft damit jeden Zug, erkennt
 * Matt, Patt, Remis (Wiederholung, 50 Züge, zu wenig Material) und rechnet die Uhren. Die Seite hat dieselben Regeln
 * noch einmal in JavaScript (Anzeige, erlaubte Felder, Analyse) – beide prüft `ChessGameTest` mit Perft-Zahlen.
 *
 * Felder: 0 = a1, 7 = h1, 56 = a8, 63 = h8. Figuren: 1 Bauer, 2 Springer, 3 Läufer, 4 Turm, 5 Dame, 6 König,
 * schwarze Figuren + 8 (9 … 14), 0 = leer.
 */
class ChessPosition private constructor(
    private val board: IntArray,
    /** true = Weiß am Zug. */
    val whiteToMove: Boolean,
    /** Rochaderechte: 1 = Weiß kurz, 2 = Weiß lang, 4 = Schwarz kurz, 8 = Schwarz lang. */
    val castling: Int,
    /** Feld hinter einem gerade zwei Felder gezogenen Bauern, sonst -1. */
    val epSquare: Int,
    val halfmoveClock: Int,
    val fullmoveNumber: Int,
) {
    data class Move(val from: Int, val to: Int, val promotion: Int = 0) {
        val uci: String get() = squareName(from) + squareName(to) + (if (promotion != 0) "-nbrq"[promotion - 1].toString() else "")
    }

    operator fun get(square: Int): Int = board[square]

    private fun mine(piece: Int) = piece != 0 && (piece < 8) == whiteToMove
    private fun theirs(piece: Int) = piece != 0 && (piece < 8) != whiteToMove

    /** Wird das Feld von der Farbe [byWhite] angegriffen? */
    fun attacked(square: Int, byWhite: Boolean): Boolean {
        val f = square % 8
        val r = square / 8
        val add = if (byWhite) 0 else 8
        // Bauern
        val pr = if (byWhite) r - 1 else r + 1
        if (pr in 0..7) {
            for (df in intArrayOf(-1, 1)) {
                val pf = f + df
                if (pf in 0..7 && board[pr * 8 + pf] == 1 + add) return true
            }
        }
        for ((df, dr) in KNIGHT) {
            val nf = f + df
            val nr = r + dr
            if (nf in 0..7 && nr in 0..7 && board[nr * 8 + nf] == 2 + add) return true
        }
        for ((df, dr) in KING) {
            val nf = f + df
            val nr = r + dr
            if (nf in 0..7 && nr in 0..7 && board[nr * 8 + nf] == 6 + add) return true
        }
        for ((dirs, slider) in listOf(DIAGONAL to 3, STRAIGHT to 4)) {
            for ((df, dr) in dirs) {
                var nf = f + df
                var nr = r + dr
                while (nf in 0..7 && nr in 0..7) {
                    val p = board[nr * 8 + nf]
                    if (p != 0) {
                        if (p == slider + add || p == 5 + add) return true
                        break
                    }
                    nf += df
                    nr += dr
                }
            }
        }
        return false
    }

    private fun kingSquare(white: Boolean): Int {
        val king = if (white) 6 else 14
        return board.indexOfFirst { it == king }
    }

    /** Steht die Seite am Zug im Schach? */
    fun inCheck(): Boolean {
        val king = kingSquare(whiteToMove)
        return king >= 0 && attacked(king, !whiteToMove)
    }

    private fun pseudoMoves(): List<Move> {
        val out = ArrayList<Move>(48)
        val add = if (whiteToMove) 0 else 8
        for (sq in 0 until 64) {
            val p = board[sq]
            if (!mine(p)) continue
            val type = p - add
            val f = sq % 8
            val r = sq / 8
            when (type) {
                1 -> {
                    val dir = if (whiteToMove) 1 else -1
                    val startRank = if (whiteToMove) 1 else 6
                    val lastRank = if (whiteToMove) 7 else 0
                    fun push(to: Int) {
                        if (to / 8 == lastRank) for (promo in 5 downTo 2) out += Move(sq, to, promo) else out += Move(sq, to)
                    }
                    val one = sq + dir * 8
                    if (one in 0..63 && board[one] == 0) {
                        push(one)
                        val two = sq + dir * 16
                        if (r == startRank && board[two] == 0) out += Move(sq, two)
                    }
                    for (df in intArrayOf(-1, 1)) {
                        val nf = f + df
                        val nr = r + dir
                        if (nf !in 0..7 || nr !in 0..7) continue
                        val to = nr * 8 + nf
                        if (theirs(board[to])) push(to) else if (to == epSquare) out += Move(sq, to)
                    }
                }
                2, 6 -> {
                    for ((df, dr) in if (type == 2) KNIGHT else KING) {
                        val nf = f + df
                        val nr = r + dr
                        if (nf !in 0..7 || nr !in 0..7) continue
                        val to = nr * 8 + nf
                        if (!mine(board[to])) out += Move(sq, to)
                    }
                    if (type == 6) castlingMoves(sq, out)
                }
                else -> {
                    val dirs = when (type) {
                        3 -> DIAGONAL
                        4 -> STRAIGHT
                        else -> DIAGONAL + STRAIGHT
                    }
                    for ((df, dr) in dirs) {
                        var nf = f + df
                        var nr = r + dr
                        while (nf in 0..7 && nr in 0..7) {
                            val to = nr * 8 + nf
                            if (mine(board[to])) break
                            out += Move(sq, to)
                            if (board[to] != 0) break
                            nf += df
                            nr += dr
                        }
                    }
                }
            }
        }
        return out
    }

    private fun castlingMoves(king: Int, out: MutableList<Move>) {
        val home = if (whiteToMove) 4 else 60
        if (king != home) return
        val short = if (whiteToMove) 1 else 4
        val long = if (whiteToMove) 2 else 8
        val rook = if (whiteToMove) 4 else 12
        val enemy = !whiteToMove
        if (castling and short != 0 && board[home + 3] == rook && board[home + 1] == 0 && board[home + 2] == 0 &&
            !attacked(home, enemy) && !attacked(home + 1, enemy) && !attacked(home + 2, enemy)
        ) out += Move(home, home + 2)
        if (castling and long != 0 && board[home - 4] == rook && board[home - 1] == 0 && board[home - 2] == 0 && board[home - 3] == 0 &&
            !attacked(home, enemy) && !attacked(home - 1, enemy) && !attacked(home - 2, enemy)
        ) out += Move(home, home - 2)
    }

    /** Alle erlaubten Züge der Seite am Zug. */
    fun legalMoves(): List<Move> = pseudoMoves().filter { move ->
        val next = playUnchecked(move)
        val king = next.kingSquare(whiteToMove)
        king >= 0 && !next.attacked(king, !whiteToMove)
    }

    /** Spielt einen erlaubten Zug (sonst null). */
    fun play(move: Move): ChessPosition? = if (move in legalMoves()) playUnchecked(move) else null

    /** Zug aus UCI-Schreibweise (e2e4, e7e8q); null = gibt es nicht oder nicht erlaubt. */
    fun parseUci(uci: String): Move? {
        val text = uci.trim().lowercase()
        if (text.length !in 4..5) return null
        val from = squareOf(text.substring(0, 2))
        val to = squareOf(text.substring(2, 4))
        if (from < 0 || to < 0) return null
        val promo = if (text.length == 5) "nbrq".indexOf(text[4]).let { if (it < 0) return null else it + 2 } else 0
        return legalMoves().firstOrNull { it.from == from && it.to == to && it.promotion == promo }
    }

    private fun playUnchecked(move: Move): ChessPosition {
        val b = board.copyOf()
        val piece = b[move.from]
        val add = if (whiteToMove) 0 else 8
        val type = piece - add
        val captured = b[move.to]
        b[move.to] = if (move.promotion != 0) move.promotion + add else piece
        b[move.from] = 0
        var ep = -1
        if (type == 1) {
            if (move.to == epSquare && captured == 0) b[move.to + if (whiteToMove) -8 else 8] = 0
            if (kotlin.math.abs(move.to - move.from) == 16) ep = (move.to + move.from) / 2
        }
        if (type == 6 && kotlin.math.abs(move.to - move.from) == 2) {
            if (move.to > move.from) {
                b[move.from + 1] = b[move.from + 3]
                b[move.from + 3] = 0
            } else {
                b[move.from - 1] = b[move.from - 4]
                b[move.from - 4] = 0
            }
        }
        var rights = castling
        for (sq in intArrayOf(move.from, move.to)) {
            rights = rights and when (sq) {
                4 -> 3.inv()
                0 -> 2.inv()
                7 -> 1.inv()
                60 -> 12.inv()
                56 -> 8.inv()
                63 -> 4.inv()
                else -> 15
            }
        }
        val reset = type == 1 || captured != 0
        return ChessPosition(
            b, !whiteToMove, rights, ep,
            if (reset) 0 else halfmoveClock + 1,
            if (whiteToMove) fullmoveNumber else fullmoveNumber + 1,
        )
    }

    /** Kann noch irgendwer matt setzen? false bei K–K, K+Leichtfigur–K und nur gleichfarbigen Läufern. */
    fun insufficientMaterial(): Boolean {
        val pieces = (0 until 64).filter { board[it] != 0 && board[it] % 8 != 6 }
        if (pieces.isEmpty()) return true
        if (pieces.any { board[it] % 8 in intArrayOf(1, 4, 5) }) return false
        if (pieces.size == 1) return true
        if (pieces.all { board[it] % 8 == 3 }) return pieces.map { (it % 8 + it / 8) % 2 }.distinct().size == 1
        return false
    }

    /** Kann die Farbe [white] mit ihrem Material überhaupt noch matt setzen? (für Zeit gegen zu wenig Material) */
    fun canMate(white: Boolean): Boolean {
        val own = (0 until 64).filter { board[it] != 0 && (board[it] < 8) == white && board[it] % 8 != 6 }
        if (own.isEmpty()) return false
        if (own.size == 1 && board[own[0]] % 8 in intArrayOf(2, 3)) return false
        return true
    }

    /** Schlüssel für die Stellungswiederholung: Brett, Zugrecht, Rochade, en passant nur wenn schlagbar. */
    fun repetitionKey(): String {
        val ep = if (epSquare >= 0 && legalMoves().any { it.to == epSquare && board[it.from] % 8 == 1 }) epSquare else -1
        return fen().split(' ').take(3).joinToString(" ") + " " + ep
    }

    fun fen(): String {
        val sb = StringBuilder()
        for (r in 7 downTo 0) {
            var empty = 0
            for (f in 0..7) {
                val p = board[r * 8 + f]
                if (p == 0) {
                    empty++
                    continue
                }
                if (empty > 0) sb.append(empty).also { empty = 0 }
                val c = " pnbrqk"[p % 8]
                sb.append(if (p < 8) c.uppercaseChar() else c)
            }
            if (empty > 0) sb.append(empty)
            if (r > 0) sb.append('/')
        }
        sb.append(if (whiteToMove) " w " else " b ")
        val rights = buildString {
            if (castling and 1 != 0) append('K')
            if (castling and 2 != 0) append('Q')
            if (castling and 4 != 0) append('k')
            if (castling and 8 != 0) append('q')
        }
        sb.append(rights.ifEmpty { "-" }).append(' ')
        sb.append(if (epSquare >= 0) squareName(epSquare) else "-")
        sb.append(' ').append(halfmoveClock).append(' ').append(fullmoveNumber)
        return sb.toString()
    }

    companion object {
        private val KNIGHT = listOf(1 to 2, 2 to 1, 2 to -1, 1 to -2, -1 to -2, -2 to -1, -2 to 1, -1 to 2)
        private val KING = listOf(1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1)
        private val DIAGONAL = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
        private val STRAIGHT = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val START: ChessPosition = fromFen(START_FEN)!!

        fun squareName(square: Int): String = "${'a' + square % 8}${square / 8 + 1}"

        fun squareOf(name: String): Int {
            if (name.length != 2) return -1
            val f = name[0] - 'a'
            val r = name[1] - '1'
            return if (f in 0..7 && r in 0..7) r * 8 + f else -1
        }

        /** Liest eine FEN; null = kaputt. */
        fun fromFen(fen: String): ChessPosition? {
            val parts = fen.trim().split(Regex("\\s+"))
            if (parts.size < 4) return null
            val rows = parts[0].split('/')
            if (rows.size != 8) return null
            val board = IntArray(64)
            for ((i, row) in rows.withIndex()) {
                val r = 7 - i
                var f = 0
                for (c in row) {
                    if (c in '1'..'8') {
                        f += c - '0'
                        continue
                    }
                    val type = " pnbrqk".indexOf(c.lowercaseChar())
                    if (type <= 0 || f > 7) return null
                    board[r * 8 + f] = type + if (c.isUpperCase()) 0 else 8
                    f++
                }
                if (f != 8) return null
            }
            val white = when (parts[1]) {
                "w" -> true
                "b" -> false
                else -> return null
            }
            var rights = 0
            if ('K' in parts[2]) rights = rights or 1
            if ('Q' in parts[2]) rights = rights or 2
            if ('k' in parts[2]) rights = rights or 4
            if ('q' in parts[2]) rights = rights or 8
            val ep = if (parts[3] == "-") -1 else squareOf(parts[3])
            return ChessPosition(board, white, rights, ep, parts.getOrNull(4)?.toIntOrNull() ?: 0, parts.getOrNull(5)?.toIntOrNull() ?: 1)
        }

        /** Anzahl der Zugfolgen bis zur Tiefe [depth] (zum Prüfen des Zuggenerators). */
        fun perft(position: ChessPosition, depth: Int): Long {
            if (depth == 0) return 1
            val moves = position.legalMoves()
            if (depth == 1) return moves.size.toLong()
            return moves.sumOf { perft(position.playUnchecked(it), depth - 1) }
        }
    }
}

/**
 * Eine Partie mit Uhr. Seite 0 = Weiß, 1 = Schwarz. Die Uhr läuft erst ab dem zweiten Halbzug (jeder hat für seinen
 * ersten Zug [FIRST_MOVE_MS] Zeit, sonst wird die Partie abgebrochen).
 */
class ChessMatch(val baseMs: Long, val incrementMs: Long, start: Long) {
    val positions = mutableListOf(ChessPosition.START)
    val moves = mutableListOf<String>()
    private val seen = HashMap<String, Int>().apply { put(ChessPosition.START.repetitionKey(), 1) }
    val clock = longArrayOf(baseMs, baseMs)
    var turnStart = start
        private set
    /** "1-0", "0-1", "1/2-1/2", "*" (abgebrochen) oder null = läuft. */
    var result: String? = null
        private set
    var reason = ""
        private set
    /** Wer gerade Remis anbietet (0/1), -1 = niemand. */
    var drawOffer = -1
        private set

    val timed: Boolean get() = baseMs > 0
    val over: Boolean get() = result != null
    val position: ChessPosition get() = positions.last()
    val turn: Int get() = if (position.whiteToMove) 0 else 1

    /** Läuft gerade die Uhr der Seite am Zug? */
    val clockRunning: Boolean get() = timed && !over && moves.size >= 2

    /** Restzeit beider Seiten zum Zeitpunkt [now]. */
    fun remaining(now: Long): LongArray {
        val out = clock.copyOf()
        if (clockRunning) out[turn] = (out[turn] - (now - turnStart)).coerceAtLeast(0)
        return out
    }

    /** Zeit abgelaufen oder erster Zug zu lange nicht gespielt? true = dadurch beendet. */
    fun checkTime(now: Long): Boolean {
        if (over || !timed) return false
        if (moves.size < 2) {
            if (now - turnStart > FIRST_MOVE_MS) {
                finish("*", "aborted")
                return true
            }
            return false
        }
        if (remaining(now)[turn] > 0) return false
        clock[turn] = 0
        val winner = 1 - turn
        if (!position.canMate(winner == 0)) finish("1/2-1/2", "timevsinsufficient") else finish(if (winner == 0) "1-0" else "0-1", "time")
        return true
    }

    /** Zug der Seite [side] (UCI). Ergebnis: Fehlermeldung oder null. */
    fun move(side: Int, uci: String, now: Long): String? {
        if (checkTime(now)) return "Die Zeit ist abgelaufen."
        if (over) return "Die Partie ist vorbei."
        if (side != turn) return "Du bist nicht am Zug."
        val move = position.parseUci(uci) ?: return "Dieser Zug ist nicht erlaubt."
        if (clockRunning) clock[side] = (clock[side] - (now - turnStart)).coerceAtLeast(0) + incrementMs
        val next = position.play(move) ?: return "Dieser Zug ist nicht erlaubt."
        positions += next
        moves += move.uci
        turnStart = now
        if (drawOffer == 1 - side) drawOffer = -1   // ein Zug lehnt das Angebot ab
        if (drawOffer == side) drawOffer = -1
        val key = next.repetitionKey()
        val count = (seen[key] ?: 0) + 1
        seen[key] = count
        val replies = next.legalMoves()
        when {
            replies.isEmpty() && next.inCheck() -> finish(if (side == 0) "1-0" else "0-1", "mate")
            replies.isEmpty() -> finish("1/2-1/2", "stalemate")
            next.insufficientMaterial() -> finish("1/2-1/2", "insufficient")
            count >= 3 -> finish("1/2-1/2", "repetition")
            next.halfmoveClock >= 100 -> finish("1/2-1/2", "fifty")
        }
        return null
    }

    fun resign(side: Int, now: Long) {
        if (over) return
        freeze(now)
        if (moves.size < 2) finish("*", "aborted") else finish(if (side == 0) "0-1" else "1-0", "resign")
    }

    /** Seite [side] ist weg (Raum verlassen oder zu lange offline). */
    fun abandon(side: Int, now: Long) {
        if (over) return
        freeze(now)
        if (moves.size < 2) finish("*", "aborted") else finish(if (side == 0) "0-1" else "1-0", "abandon")
    }

    /** Remis anbieten oder (wenn der Gegner schon bietet) annehmen. */
    fun offerDraw(side: Int, now: Long): String? {
        if (over) return "Die Partie ist vorbei."
        if (moves.size < 2) return "Remis geht erst nach dem ersten Zug."
        if (drawOffer == 1 - side) {
            freeze(now)
            finish("1/2-1/2", "agreed")
        } else {
            drawOffer = side
        }
        return null
    }

    fun declineDraw(side: Int) {
        if (drawOffer == 1 - side) drawOffer = -1
    }

    private fun freeze(now: Long) {
        if (clockRunning) clock[turn] = (clock[turn] - (now - turnStart)).coerceAtLeast(0)
        turnStart = now
    }

    private fun finish(result: String, reason: String) {
        this.result = result
        this.reason = reason
        drawOffer = -1
    }

    companion object {
        const val FIRST_MOVE_MS = 60_000L
    }
}

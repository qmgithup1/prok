package com.systemsgo.hex.guacamole.protocol

/**
 * GUACAMOLE-PROTOCOL FEATURE (Part 1/N).
 *
 * One instruction in the Guacamole protocol: an opcode followed by zero or
 * more string arguments — e.g. `png` (a drawing instruction), `key` (a
 * client input instruction), `sync` (frame/keep-alive), `error`/`disconnect`
 * (session-lifecycle). This class only knows the *wire format* (see
 * [encode] and [GuacamoleInstructionDecoder]); it has no opinion about what
 * any particular opcode means — that belongs to whatever consumes the
 * decoded stream (the Part 2/N rendering pipeline for server→client
 * instructions, the Part 2/N input layer for client→server ones).
 *
 * Wire format, from the official protocol spec
 * (https://guacamole.apache.org/doc/gug/guacamole-protocol.html):
 * each element is `<length>.<content>`, elements are joined with `,`, and
 * the instruction is terminated with `;`. Length is the UTF-8 character
 * count of `content`, not the byte count of the encoded element. Example:
 * the instruction `sync` with argument `1234` (a timestamp) encodes as
 * `4.sync,4.1234;`.
 */
data class GuacamoleInstruction(
    val opcode: String,
    val args: List<String> = emptyList(),
) {
    /** Encodes this instruction to its wire representation, ready to send as a WebSocket text frame. */
    fun encode(): String = buildString {
        val elements = ArrayList<String>(args.size + 1)
        elements.add(opcode)
        elements.addAll(args)
        for (i in elements.indices) {
            val el = elements[i]
            append(el.length)
            append('.')
            append(el)
            append(if (i == elements.lastIndex) ';' else ',')
        }
    }

    companion object {
        fun of(opcode: String, vararg args: String) = GuacamoleInstruction(opcode, args.toList())
    }
}

/** Thrown by [GuacamoleInstructionDecoder] when the byte/char stream doesn't match the protocol's wire format. */
class GuacamoleProtocolException(message: String) : Exception(message)

/**
 * Incremental decoder for the Guacamole text protocol.
 *
 * WebSocket text frames don't necessarily align with instruction
 * boundaries — a single frame can contain several complete instructions
 * (common for a burst of drawing ops), or a large instruction (a base64
 * image blob) can be split across frames. [feed] accumulates whatever
 * arrives into an internal buffer and returns every instruction that became
 * fully available as a result of that particular call, leaving any trailing
 * partial instruction buffered for the next [feed]. Not thread-safe — the
 * Part 2/N tunnel client that owns this must only call [feed] from the
 * single thread/coroutine reading the socket.
 */
class GuacamoleInstructionDecoder {

    private val buffer = StringBuilder()

    /** Feeds a newly-received chunk (one WebSocket text frame's payload) and returns any instructions it completed. */
    fun feed(chunk: String): List<GuacamoleInstruction> {
        buffer.append(chunk)
        val completed = ArrayList<GuacamoleInstruction>()
        while (true) {
            completed.add(tryParseOne() ?: break)
        }
        return completed
    }

    /** True once every buffered byte has been consumed into complete instructions — useful for tests/asserts. */
    fun isEmpty(): Boolean = buffer.isEmpty()

    /**
     * Attempts to parse exactly one instruction starting at the front of
     * [buffer]. Returns null (leaving [buffer] untouched) if what's buffered
     * so far is a valid-but-incomplete prefix — the caller should wait for
     * more data via another [feed] call. Throws [GuacamoleProtocolException]
     * if the buffered prefix can never be valid regardless of what arrives
     * next (a malformed length or an unexpected separator character).
     */
    private fun tryParseOne(): GuacamoleInstruction? {
        var pos = 0
        val elements = ArrayList<String>(4)
        while (true) {
            val dot = buffer.indexOf(".", pos)
            if (dot < 0) return null // length digits not fully arrived yet

            val lenStr = buffer.substring(pos, dot)
            val len = lenStr.toIntOrNull()
                ?: throw GuacamoleProtocolException("Malformed element length '$lenStr' at offset $pos")
            if (len < 0) throw GuacamoleProtocolException("Negative element length $len at offset $pos")

            val elemStart = dot + 1
            val elemEnd = elemStart + len
            // Need the element's `len` chars AND the one separator/terminator char after it.
            if (elemEnd + 1 > buffer.length) return null

            elements.add(buffer.substring(elemStart, elemEnd))

            when (val separator = buffer[elemEnd]) {
                ',' -> pos = elemEnd + 1 // another element follows
                ';' -> {
                    buffer.delete(0, elemEnd + 1) // consume the whole instruction, keep any trailing bytes
                    return GuacamoleInstruction(opcode = elements[0], args = elements.drop(1))
                }
                else -> throw GuacamoleProtocolException("Expected ',' or ';' after element, found '$separator' at offset $elemEnd")
            }
        }
    }
}

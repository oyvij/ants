package ants

import java.security.SecureRandom

private val random = SecureRandom()

private const val ID_CHARACTERS = "abcdefghijklmnopqrstuvwxyz0123456789"

fun newId(prefix: Char, taken: (String) -> Boolean): String =
    generateSequence { "$prefix-" + (1..6).map { ID_CHARACTERS[random.nextInt(ID_CHARACTERS.length)] }.joinToString("") }.first { !taken(it) }

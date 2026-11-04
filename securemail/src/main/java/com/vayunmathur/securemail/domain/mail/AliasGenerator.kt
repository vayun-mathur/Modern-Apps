package com.vayunmathur.securemail.domain.mail

import java.security.SecureRandom
import java.util.Random

/** Readable random alias addresses: `adjective-noun-NNNN@maemail.org`. */
object AliasGenerator {

    private val ADJECTIVES = listOf(
        "amber", "ancient", "autumn", "bold", "brave", "bright", "brisk", "calm",
        "clever", "cosmic", "crimson", "crisp", "dapper", "dawn", "deep", "eager",
        "early", "fancy", "fern", "fleet", "fresh", "gentle", "glad", "golden",
        "grand", "happy", "hidden", "humble", "icy", "jolly", "keen", "kind",
        "lively", "lucky", "lunar", "mellow", "misty", "noble", "nimble", "olive",
        "plucky", "polar", "proud", "quick", "quiet", "rapid", "rustic", "sage",
        "sandy", "shiny", "silent", "silver", "sleek", "snowy", "solar", "steady",
        "sunny", "swift", "tidy", "velvet", "vivid", "warm", "wild", "witty",
    )

    private val NOUNS = listOf(
        "acorn", "badger", "beacon", "birch", "bison", "breeze", "brook", "canyon",
        "cedar", "comet", "coral", "crane", "delta", "dune", "eagle", "ember",
        "falcon", "fjord", "fox", "glade", "grove", "harbor", "hawk", "heron",
        "island", "lagoon", "lark", "lynx", "maple", "meadow", "mesa", "moose",
        "otter", "owl", "panda", "pebble", "pine", "plover", "prairie", "quail",
        "raven", "reef", "ridge", "river", "robin", "sparrow", "spruce", "stone",
        "summit", "swan", "thicket", "tiger", "trail", "tundra", "valley", "walrus",
        "willow", "wolf", "wren", "yak", "zebra", "aspen", "basin", "cliff",
    )

    fun generate(random: Random = SecureRandom()): String {
        val adjective = ADJECTIVES[random.nextInt(ADJECTIVES.size)]
        val noun = NOUNS[random.nextInt(NOUNS.size)]
        val number = 1000 + random.nextInt(9000)
        return "$adjective-$noun-$number@${AddressValidator.OWN_DOMAIN}"
    }
}

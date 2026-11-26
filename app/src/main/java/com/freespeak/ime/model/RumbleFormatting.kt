package com.freespeak.ime.model

data class RumbleFormatting(
    val digits: Boolean = true,
    val filler: Boolean = false,
    val verbatim: Boolean = true,
) {
    internal fun promptTokens(): List<String> = listOf(
        if (digits) "<digits>" else "<nodigits>",
        if (filler) "<filler>" else "<nofiller>",
        if (verbatim) "<verbatim>" else "<noverbatim>",
    )
}

package dev.echo.data.provider

import dev.echo.data.spotify.parseCsv
import org.junit.Assert.*
import org.junit.Test

class PlaylistCsvTest {
    @Test fun quotedCommasQuotesNewlinesAndEmptyFields() {
        assertEquals(listOf(listOf("Title", "Artist", ""), listOf("A, \"song\"\npart 2", "Singer", "")),
            parseCsv("Title,Artist,\r\n\"A, \"\"song\"\"\npart 2\",Singer,"))
    }
    @Test fun malformedQuotesAreRejected() {
        listOf("\"unfinished", "a\"b,c", "\"a\"b,c").forEach { assertTrue(runCatching { parseCsv(it) }.isFailure) }
    }
}

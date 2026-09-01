package eu.kanade.tachiyomi.extension.all.talebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DtoTest {
    @Test
    fun `only explicit comics are exposed`() {
        val books = listOf(
            BookDto(id = 1, title = "Comic", mediaType = "comic"),
            BookDto(id = 2, title = "Ebook", mediaType = "ebook"),
            BookDto(id = 3, title = "Legacy"),
        )

        assertEquals(listOf(1), filterComicBooks(books).map { it.id })
    }

    @Test
    fun `relative and protocol relative resources resolve safely`() {
        assertEquals(
            "https://books.example/get/cover/1.jpg",
            resolveResourceUrl("https://books.example", "/get/cover/1.jpg"),
        )
        assertEquals(
            "https://cdn.example/get/cover/1.jpg",
            resolveResourceUrl("https://books.example", "//cdn.example/get/cover/1.jpg"),
        )
        assertNull(resolveResourceUrl("not a url", "/get/cover/1.jpg"))
    }
}

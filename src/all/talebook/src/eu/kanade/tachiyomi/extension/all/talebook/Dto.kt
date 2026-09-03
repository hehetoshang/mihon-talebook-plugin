package eu.kanade.tachiyomi.extension.all.talebook

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal interface ApiEnvelope {
    val err: String
    val msg: String
}

@Serializable
internal class BasicResponse(
    override val err: String,
    override val msg: String = "",
) : ApiEnvelope

@Serializable
internal class UserInfoResponse(
    override val err: String,
    override val msg: String = "",
    val user: UserDto? = null,
) : ApiEnvelope

@Serializable
internal class UserDto(
    val id: Int,
)

@Serializable
internal class BookListResponse(
    override val err: String,
    override val msg: String = "",
    val total: Int = 0,
    val books: List<BookDto> = emptyList(),
) : ApiEnvelope

@Serializable
internal class BookDetailResponse(
    override val err: String,
    override val msg: String = "",
    val book: BookDto? = null,
) : ApiEnvelope

@Serializable
internal class BookDto(
    val id: Int,
    val title: String,
    @SerialName("media_type") val mediaType: String = "unknown",
    val img: String? = null,
    val author: String? = null,
    val authors: List<String> = emptyList(),
    val comments: String? = null,
    val tags: List<String> = emptyList(),
) {
    fun toSManga(baseUrl: String) = SManga.create().apply {
        url = id.toString()
        title = this@BookDto.title
        thumbnail_url = resolveResourceUrl(baseUrl, img)
        author = this@BookDto.author?.takeIf(String::isNotBlank)
            ?: authors.takeIf(List<String>::isNotEmpty)?.joinToString()
        artist = author
        description = comments?.takeIf(String::isNotBlank)
        genre = tags.takeIf(List<String>::isNotEmpty)?.joinToString()
        status = SManga.UNKNOWN
    }

    fun toChapter() = SChapter.create().apply {
        url = id.toString()
        name = "完整漫画"
        chapter_number = 1F
    }
}

@Serializable
internal class ComicManifestResponse(
    override val err: String,
    override val msg: String = "",
    @SerialName("contract_version") val contractVersion: Int = 0,
    val pages: List<ComicPageDto> = emptyList(),
) : ApiEnvelope

@Serializable
internal class ComicPageDto(
    val index: Int,
    val url: String,
)

internal fun filterComicBooks(books: List<BookDto>) = books.filter { it.mediaType == "comic" }

internal fun resolveResourceUrl(baseUrl: String, value: String?): String? {
    if (value.isNullOrBlank()) return null
    return baseUrl.toHttpUrlOrNull()?.resolve(value)?.toString()
}

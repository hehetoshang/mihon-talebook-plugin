package eu.kanade.tachiyomi.extension.all.talebook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Toast
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.CacheControl
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@Source
abstract class Talebook :
    KeiSource(),
    ConfigurableSource,
    UnmeteredSource {

    private val preferences by getPreferencesLazy()
    private val authMutex = Mutex()
    private val connectionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var authenticated = false

    @Volatile
    private var authenticatedFingerprint: String? = null

    private var connectionJob: Job? = null

    private val username
        get() = preferences.getString(PREF_USERNAME, "").orEmpty().trim()

    private val password
        get() = preferences.getString(PREF_PASSWORD, "").orEmpty()

    private val accessCode
        get() = preferences.getString(PREF_ACCESS_CODE, "").orEmpty()

    private val credentialClient by lazy {
        client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    override val supportsLatest = true

    override fun OkHttpClient.Builder.configureClient() = apply {
        dns(Dns.SYSTEM)
        connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    override suspend fun getPopularManga(page: Int): MangasPage = getComicPage("library", page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = getComicPage("recent", page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return getPopularManga(page)
        return getComicPage("search", page, query.trim())
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val configuredUrl = baseUrl.toHttpUrlOrNull() ?: return null
        if (url.scheme != configuredUrl.scheme || url.host != configuredUrl.host || url.port != configuredUrl.port) {
            return null
        }

        val segments = url.pathSegments
        val bookId = when {
            segments.size == 2 && segments[0] == "book" -> segments[1]
            segments.size == 2 && segments[0] == "read-comic" -> segments[1]
            segments.size == 3 && segments[0] == "api" && segments[1] == "book" -> segments[2]
            else -> return null
        }
        if (bookId.toIntOrNull() == null) return null
        return getComicBook(bookId).toSManga(baseUrl)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchDetails && !fetchChapters) return SMangaUpdate(manga, chapters)

        val book = getComicBook(manga.url)
        return SMangaUpdate(
            manga = if (fetchDetails) book.toSManga(baseUrl) else manga,
            chapters = if (fetchChapters) listOf(book.toChapter()) else chapters,
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val bookId = chapter.url.toIntOrNull() ?: throw IOException("Talebook 漫画 ID 无效")
        val response = authenticatedGet<ComicManifestResponse>(
            "$baseUrl/api/book/$bookId/comic/pages".toHttpUrl(),
        )

        if (response.contractVersion != COMIC_CONTRACT_VERSION) {
            throw IOException("Talebook 漫画接口版本不兼容")
        }

        return response.pages
            .sortedBy { it.index }
            .mapIndexed { index, page ->
                Page(
                    index = index,
                    imageUrl = resolveResourceUrl(baseUrl, page.url)
                        ?: throw IOException("Talebook 返回了无效的漫画页面地址"),
                )
            }
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl/book/${manga.url}"

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl/read-comic/${chapter.url}"

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val context = screen.context

        EditTextPreference(context).apply {
            key = PREF_USERNAME
            title = "用户名"
            summary = username.ifBlank { "Talebook 登录用户名" }
            setOnPreferenceChangeListener { _, newValue ->
                summary = (newValue as? String).orEmpty().ifBlank { "Talebook 登录用户名" }
                invalidateAuthentication()
                true
            }
        }.let(screen::addPreference)

        addSecretPreference(
            screen = screen,
            key = PREF_PASSWORD,
            title = "密码",
            emptySummary = "Talebook 登录密码",
        )
        addSecretPreference(
            screen = screen,
            key = PREF_ACCESS_CODE,
            title = "访问码",
            emptySummary = "未启用访问码时可留空",
        )

        CheckBoxPreference(context).apply {
            key = PREF_TEST_CONNECTION
            title = "测试连接"
            summary = "验证服务器地址、访问码和账号密码"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, _ ->
                testConnection(context)
                false
            }
        }.let(screen::addPreference)
    }

    private fun addSecretPreference(
        screen: PreferenceScreen,
        key: String,
        title: String,
        emptySummary: String,
    ) {
        EditTextPreference(screen.context).apply {
            this.key = key
            this.title = title
            summary = if (preferences.getString(key, "").isNullOrEmpty()) emptySummary else "••••••••"
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            setOnPreferenceChangeListener { _, newValue ->
                summary = if ((newValue as? String).isNullOrEmpty()) emptySummary else "••••••••"
                invalidateAuthentication()
                true
            }
        }.let(screen::addPreference)
    }

    private fun testConnection(context: Context) {
        connectionJob?.cancel()
        connectionJob = connectionScope.launch {
            val message = try {
                invalidateAuthentication()
                ensureAuthenticated()
                val info = authenticatedGet<UserInfoResponse>("$baseUrl/api/user/info".toHttpUrl())
                if (info.user == null) throw IOException("Talebook 未返回已登录用户")
                "Talebook 连接成功"
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                error.message ?: "Talebook 连接失败"
            }
            mainHandler.post {
                Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun getComicPage(endpoint: String, page: Int, query: String? = null): MangasPage {
        require(page > 0) { "Page must be positive" }
        val skip = (page - 1) * MANGA_PAGE_SIZE
        val selected = mutableListOf<BookDto>()
        var seenComics = 0
        var start = 0
        var total = 1

        while (start < total && selected.size <= MANGA_PAGE_SIZE) {
            val url = "$baseUrl/api/$endpoint".toHttpUrl().newBuilder()
                .addQueryParameter("start", start.toString())
                .addQueryParameter("size", TALEBOOK_PAGE_SIZE.toString())
                .apply {
                    if (query != null) addQueryParameter("name", query)
                }
                .build()
            val response = authenticatedGet<BookListResponse>(url)
            total = response.total
            if (response.books.isEmpty()) break

            filterComicBooks(response.books).forEach { book ->
                if (seenComics++ >= skip && selected.size <= MANGA_PAGE_SIZE) {
                    selected += book
                }
            }
            start += response.books.size
        }

        return MangasPage(
            mangas = selected.take(MANGA_PAGE_SIZE).map { it.toSManga(baseUrl) },
            hasNextPage = selected.size > MANGA_PAGE_SIZE,
        )
    }

    private suspend fun getComicBook(bookId: String): BookDto {
        val id = bookId.toIntOrNull() ?: throw IOException("Talebook 漫画 ID 无效")
        val response = authenticatedGet<BookDetailResponse>("$baseUrl/api/book/$id".toHttpUrl())
        val book = response.book ?: throw IOException("Talebook 未返回书籍详情")
        if (book.mediaType != MEDIA_TYPE_COMIC) {
            throw IOException("该书在 Talebook 中未设置为漫画")
        }
        return book
    }

    private suspend fun ensureAuthenticated() {
        val fingerprint = credentialFingerprint()
        if (authenticated && authenticatedFingerprint == fingerprint) return

        authMutex.withLock {
            if (authenticated && authenticatedFingerprint == fingerprint) return

            validateConfiguration()
            val welcome = credentialGet<BasicResponse>("$baseUrl/api/welcome".toHttpUrl())
            when (welcome.err) {
                "free" -> Unit
                "ok" -> {
                    if (accessCode.isBlank()) throw IOException("Talebook 服务器需要访问码")
                    val response = credentialPost<BasicResponse>(
                        "$baseUrl/api/welcome".toHttpUrl(),
                        FormBody.Builder().add("invite_code", accessCode).build(),
                    )
                    requireApiSuccess(response, authentication = true)
                }
                else -> throwApiError(welcome, authentication = true)
            }

            val signIn = credentialPost<BasicResponse>(
                "$baseUrl/api/user/sign_in".toHttpUrl(),
                FormBody.Builder()
                    .add("username", username)
                    .add("password", password)
                    .build(),
            )
            requireApiSuccess(signIn, authentication = true)
            authenticatedFingerprint = fingerprint
            authenticated = true
        }
    }

    private fun validateConfiguration() {
        val url = baseUrl.toHttpUrlOrNull()
            ?: throw IOException("Talebook 服务器地址无效")
        if (url.scheme !in setOf("http", "https") || url.host.isBlank() || url.query != null || url.fragment != null) {
            throw IOException("Talebook 服务器地址无效")
        }
        if (username.isBlank() || password.isBlank()) {
            throw IOException("请填写 Talebook 用户名和密码")
        }
    }

    private fun credentialFingerprint(): String {
        val value = listOf(baseUrl, username, password, accessCode).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun invalidateAuthentication() {
        authenticated = false
        authenticatedFingerprint = null
    }

    private suspend inline fun <reified T : ApiEnvelope> authenticatedGet(url: HttpUrl): T {
        ensureAuthenticated()
        var response = apiGet<T>(url)
        if (response.err == "user.need_login" || response.err == "not_invited") {
            invalidateAuthentication()
            ensureAuthenticated()
            response = apiGet(url)
        }
        requireApiSuccess(response)
        return response
    }

    private suspend inline fun <reified T : ApiEnvelope> apiGet(url: HttpUrl): T {
        val response = client.get(
            url = url,
            cacheControl = CacheControl.FORCE_NETWORK,
            ensureSuccess = false,
        )
        return parseResponse(response, allowRedirect = true)
    }

    private suspend inline fun <reified T : ApiEnvelope> credentialGet(url: HttpUrl): T {
        val response = credentialClient.get(
            url = url,
            headers = headers,
            cacheControl = CacheControl.FORCE_NETWORK,
            ensureSuccess = false,
        )
        return parseResponse(response, allowRedirect = false)
    }

    private suspend inline fun <reified T : ApiEnvelope> credentialPost(url: HttpUrl, body: FormBody): T {
        val response = credentialClient.post(
            url = url,
            headers = headers,
            body = body,
            ensureSuccess = false,
        )
        return parseResponse(response, allowRedirect = false)
    }

    private inline fun <reified T : ApiEnvelope> parseResponse(response: Response, allowRedirect: Boolean): T {
        val code = response.code
        if (code in 300..399 && !allowRedirect) {
            response.close()
            throw IOException("Talebook 凭据请求被重定向，请检查服务器地址")
        }
        if (code !in 200..299) {
            response.close()
            throw IOException("Talebook HTTP 请求失败（$code）")
        }
        return try {
            response.parseAs<T>()
        } catch (_: Exception) {
            response.close()
            throw IOException("Talebook 返回了不兼容的响应")
        }
    }

    private fun requireApiSuccess(response: ApiEnvelope, authentication: Boolean = false) {
        if (response.err == "ok" || response.err == "free") return
        throwApiError(response, authentication)
    }

    private fun throwApiError(response: ApiEnvelope, authentication: Boolean): Nothing {
        val message = when (response.err) {
            "captcha.invalid" -> "Talebook 启用了交互式验证码，Mihon 扩展无法完成验证"
            "not_invited" -> "Talebook 访问码无效或已过期"
            "user.need_login" -> "Talebook 登录状态已过期"
            "params.no_user", "params.invalid", "permission" -> if (authentication) {
                "Talebook 用户名或密码错误"
            } else {
                response.msg.ifBlank { "Talebook 请求参数无效" }
            }
            "comic.book_not_found" -> "Talebook 漫画不存在"
            "comic.media_type" -> "该书在 Talebook 中未设置为漫画"
            "comic.no_permission", "comic.account_inactive" -> response.msg.ifBlank { "无权读取 Talebook 漫画" }
            "comic.invalid_container", "comic.page_not_found", "comic.stale_manifest" ->
                response.msg.ifBlank { "Talebook 漫画容器无法读取" }
            "exception" -> "Talebook 服务器异常，请检查服务器日志"
            else -> "Talebook 请求失败（${response.err.take(MAX_ERROR_CODE_LENGTH)}）"
        }
        throw IOException(message)
    }

    companion object {
        private const val PREF_USERNAME = "username"
        private const val PREF_PASSWORD = "password"
        private const val PREF_ACCESS_CODE = "access_code"
        private const val PREF_TEST_CONNECTION = "test_connection"
        private const val MEDIA_TYPE_COMIC = "comic"
        private const val COMIC_CONTRACT_VERSION = 1
        private const val MANGA_PAGE_SIZE = 20
        private const val TALEBOOK_PAGE_SIZE = 60
        private const val CONNECT_TIMEOUT_SECONDS = 20L
        private const val READ_TIMEOUT_SECONDS = 30L
        private const val MAX_ERROR_CODE_LENGTH = 80
    }
}

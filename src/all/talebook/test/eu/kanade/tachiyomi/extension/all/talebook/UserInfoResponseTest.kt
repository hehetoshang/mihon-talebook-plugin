package eu.kanade.tachiyomi.extension.all.talebook

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class UserInfoResponseTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `logged in user info does not require an id`() {
        val response = json.decodeFromString<UserInfoResponse>(
            """
            {
                "err": "ok",
                "msg": "",
                "sys": {},
                "user": {"is_login": true, "is_admin": false, "username": "reader"}
            }
            """.trimIndent(),
        )

        assertEquals(true, response.user?.isLogin)
    }

    @Test
    fun `guest user info does not require an id`() {
        val response = json.decodeFromString<UserInfoResponse>(
            """
            {
                "err": "ok",
                "msg": "",
                "cdn": "",
                "sys": {},
                "user": {
                    "avatar": "https://example.com/avatar.gif",
                    "is_login": false,
                    "is_admin": false,
                    "nickname": "",
                    "email": "",
                    "kindle_email": "",
                    "extra": {}
                }
            }
            """.trimIndent(),
        )

        assertEquals(false, response.user?.isLogin)
    }

    @Test
    fun `missing or null user is not a logged in session`() {
        for (body in listOf("""{"err":"ok"}""", """{"err":"ok","user":null}""")) {
            assertNull(json.decodeFromString<UserInfoResponse>(body).user)
        }
    }

    @Test
    fun `login error envelope remains readable without a user`() {
        val response = json.decodeFromString<UserInfoResponse>("""{"err":"user.need_login","msg":""}""")

        assertEquals("user.need_login", response.err)
        assertNull(response.user)
    }

    @Test
    fun `missing or malformed login state is rejected`() {
        for (user in listOf("""{"id":1}""", """{"is_login":null}""", """{"is_login":1}""")) {
            assertThrows(SerializationException::class.java) {
                json.decodeFromString<UserInfoResponse>("""{"err":"ok","user":$user}""")
            }
        }
    }
}

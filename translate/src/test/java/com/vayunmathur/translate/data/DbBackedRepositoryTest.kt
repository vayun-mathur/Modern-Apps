package com.vayunmathur.translate.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.translate.platform.FakeAndroidKeyStoreProvider
import kotlinx.coroutines.flow.first
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [TranslateRepository] against a real Room database. The SQLCipher driver is
 * shadowed with plain SQLite on the JVM (see [PlainSqlCipherDriverShadow]);
 * the fake AndroidKeyStore provider supplies the database passphrase key that
 * Robolectric otherwise lacks. All DB calls go through [runDb]/[pollDb]
 * because `runBlocking` on Robolectric's paused main thread deadlocks Room.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [PlainSqlCipherDriverShadow::class])
class DbBackedRepositoryTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun installFakeKeyStore() {
            if (java.security.Security.getProvider("AndroidKeyStore") !is FakeAndroidKeyStoreProvider) {
                runCatching { java.security.Security.removeProvider("AndroidKeyStore") }
                java.security.Security.insertProviderAt(FakeAndroidKeyStoreProvider(), 1)
            }
        }
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val repo: TranslateRepository get() = TranslateRepository.get(context)

    private fun awaitHistory(predicate: (List<TranslationRecord>) -> Boolean): List<TranslationRecord> =
        pollDb({ repo.history.first() }, predicate)

    @Test
    fun `repository records deduplicates favorites clears and deletes`() {
        // Method order is not guaranteed and the class shares one DB: start clean.
        runDb { repo.clearHistory() }
        pollDb({ repo.favorites.first() }) { true }.forEach { fav ->
            runDb { repo.delete(fav) }
        }
        awaitHistory { rows -> rows.none { !it.isFavorite } }
        pollDb({ repo.favorites.first() }) { it.isEmpty() }

        runDb {
            repo.record("hello", "hola", "en", "es")
            repo.record("hello", "hola2", "en", "es")
            repo.record("cat", "gato", "en", "es")
            repo.record("", "ignored", "en", "es")
            repo.record("dog", "", "en", "es")
        }

        val two = awaitHistory { rows ->
            rows.count { it.sourceText == "hello" || it.sourceText == "cat" } == 2
        }
        val mine = two.filter { it.sourceText == "hello" || it.sourceText == "cat" }
        assertEquals(2, mine.size)
        val hello = mine.single { it.sourceText == "hello" }
        assertEquals("hola2", hello.translatedText)

        runDb { repo.setFavorite(hello.id, true) }
        val favorites = pollDb({ repo.favorites.first() }) { rows -> rows.any { it.id == hello.id } }
        assertEquals(listOf(hello.id), favorites.map { it.id })

        runDb { repo.clearHistory() }
        val afterClear = awaitHistory { rows -> rows.none { !it.isFavorite } }
        assertTrue(afterClear.any { it.id == hello.id && it.isFavorite })

        assertEquals(1, runDb { repo.delete(hello) })
        awaitHistory { rows -> rows.none { it.id == hello.id } }
        assertTrue(repo === TranslateRepository.get(context))
    }

    @Test
    fun `repository prunes non favorites beyond cap but keeps favorites`() {
        runDb { repo.clearHistory() }
        val existingFavorites = pollDb({ repo.favorites.first() }) { true }
        runDb { existingFavorites.forEach { repo.delete(it) } }
        pollDb({ repo.favorites.first() }) { it.isEmpty() }

        runDb { repo.record("favorite-seed", "pinned", "en", "es") }
        val seed = awaitHistory { rows -> rows.any { it.sourceText == "favorite-seed" } }
            .single { it.sourceText == "favorite-seed" }
        runDb { repo.setFavorite(seed.id, true) }

        runDb {
            repeat(TranslateRepository.MAX_HISTORY + 5) { index ->
                repo.record("bulk-$index", "bulk-$index", "en", "fr")
            }
        }

        val history = awaitHistory { rows ->
            rows.count { !it.isFavorite } <= TranslateRepository.MAX_HISTORY
        }
        assertTrue(history.any { it.id == seed.id && it.isFavorite })
        assertEquals(TranslateRepository.MAX_HISTORY, history.count { !it.isFavorite })
    }
}

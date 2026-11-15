package com.vayunmathur.health.data

import android.content.Context
import com.vayunmathur.library.log.Log
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The vaccine reference list the "add a vaccination" picker searches, shipped inside the APK.
 *
 * This is the CDC's CVX code set — the vocabulary FHIR's `Immunization.vaccineCode` is expected to
 * use — as emitted by `scripts/generate_med_db.py`. Around 250 rows, so unlike [MedicationCatalog]
 * it is simply parsed into memory and filtered with `contains`: an FTS index and an unpack-to-disk
 * step would cost more than they save at this size.
 *
 * Public domain, published by the CDC as part of the IIS standards.
 */
object VaccineCatalog {

    private const val TAG = "VaccineCatalog"
    private const val ASSET = "cvx.json"

    /** Immutable value describing vaccine. */
    @Serializable
    data class Vaccine(
        /** CVX code, e.g. "208". Kept as a string because FHIR codes are strings. */
        val code: String,
        /** Short name, e.g. "COVID-19, mRNA, LNP-S, PF, 30 mcg/0.3 mL dose". */
        val name: String,
        /** Full vaccine name, longer and more formal than [name]. */
        val fullName: String,
        /**
         * Whether the CDC still lists the code as active.
         *
         * Retired codes are in the catalogue on purpose — an old record uses the code that was
         * current when the shot was given — but they rank below active ones so today's vaccines
         * come first.
         */
        val active: Boolean = true,
    )

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cache: List<Vaccine>? = null

    suspend fun all(context: Context): List<Vaccine> = withContext(Dispatchers.IO) {
        cache ?: load(context).also { cache = it }
    }

    /**
     * Vaccines matching [query], active codes first and then ranked so a prefix match on the short
     * name comes before a match buried in the full name.
     *
     * Every token has to appear somewhere, which mirrors the implicit AND that
     * [MedicationCatalog]'s FTS search gives, so the two pickers behave the same way.
     */
    suspend fun search(context: Context, query: String): List<Vaccine> {
        val vaccines = all(context)
        val tokens = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return vaccines
        return vaccines
            .filter { vaccine ->
                val haystack = "${vaccine.name} ${vaccine.fullName} ${vaccine.code}".lowercase()
                tokens.all { haystack.contains(it) }
            }
            .sortedWith(
                compareByDescending<Vaccine> { it.active }
                    .thenByDescending { it.name.lowercase().startsWith(tokens.first()) }
                    .thenBy { it.name.length }
                    .thenBy { it.name }
            )
    }

    private fun load(context: Context): List<Vaccine> = try {
        context.assets.open(ASSET).bufferedReader().use {
            json.decodeFromString<List<Vaccine>>(it.readText())
        }
    } catch (e: IOException) {
        Log.error(TAG, "No bundled vaccine catalogue", e)
        emptyList()
    } catch (e: IllegalArgumentException) {
        Log.error(TAG, "No bundled vaccine catalogue", e)
        emptyList()
    }
}

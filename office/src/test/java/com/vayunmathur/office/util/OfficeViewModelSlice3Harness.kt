package com.vayunmathur.office.util

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/** In-memory [SharedPreferences] so settings paths run for real on the plain JVM. */
class FakeSharedPreferences : SharedPreferences {
    private val data = mutableMapOf<String, Any?>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): Map<String, *> = data.toMap()
    override fun getString(key: String, defValue: String?): String? =
        if (data.containsKey(key)) data[key] as String? else defValue
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        if (data.containsKey(key)) @Suppress("UNCHECKED_CAST") (data[key] as Set<String>?) else defValues
    override fun getInt(key: String, defValue: Int): Int = (data[key] as Int?) ?: defValue
    override fun getLong(key: String, defValue: Long): Long = (data[key] as Long?) ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = (data[key] as Float?) ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = (data[key] as Boolean?) ?: defValue
    override fun contains(key: String): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor()
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.add(l)
    }
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.remove(l)
    }

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clear = false

        override fun putString(k: String, v: String?): SharedPreferences.Editor = apply { pending[k] = v }
        override fun putStringSet(k: String, v: Set<String>?): SharedPreferences.Editor = apply { pending[k] = v }
        override fun putInt(k: String, v: Int): SharedPreferences.Editor = apply { pending[k] = v }
        override fun putLong(k: String, v: Long): SharedPreferences.Editor = apply { pending[k] = v }
        override fun putFloat(k: String, v: Float): SharedPreferences.Editor = apply { pending[k] = v }
        override fun putBoolean(k: String, v: Boolean): SharedPreferences.Editor = apply { pending[k] = v }
        override fun remove(k: String): SharedPreferences.Editor = apply { pending[k] = null }
        override fun clear(): SharedPreferences.Editor = apply { clear = true }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (clear) data.clear()
            for ((k, v) in pending) {
                if (v == null) data.remove(k) else data[k] = v
            }
            pending.clear()
        }
    }
}

/**
 * Slice 3/4 shared harness: an [OfficeViewModel] on the plain JVM with a mocked [Application].
 *
 * The mock answers `applicationContext`/`filesDir` (a real temp dir, so the DataStore-backed
 * `hasOnlineIdentity()` in `init` resolves to false) and `getSharedPreferences` (in-memory fakes,
 * so the settings paths run for real). `Dispatchers.setMain` serves `viewModelScope`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class OfficeViewModelSlice3Harness {
    val dispatcher = StandardTestDispatcher()
    lateinit var tmpDir: File
    lateinit var app: Application
    val prefs = FakeSharedPreferences()

    @BeforeTest
    fun harnessSetUp() {
        Dispatchers.setMain(dispatcher)
        tmpDir = Files.createTempDirectory("office-vm-test").toFile()
        app = mock(Application::class.java)
        `when`(app.applicationContext).thenReturn(app)
        `when`(app.filesDir).thenReturn(tmpDir)
        `when`(app.getSharedPreferences(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(prefs)
    }

    @AfterTest
    fun harnessTearDown() {
        Dispatchers.resetMain()
        tmpDir.deleteRecursively()
    }

    fun newVm(): OfficeViewModel = OfficeViewModel(app)

    suspend fun idleVm(): OfficeViewModel {
        val vm = newVm()
        // NOTE: runCurrent(), never advanceUntilIdle(): enabling auto-save starts an
        // infinite while(true){delay()} loop on viewModelScope, which advanceUntilIdle
        // would chase forever. runCurrent only runs tasks due at the current virtual time.
        dispatcher.scheduler.runCurrent()
        return vm
    }

    fun loadedVm(doc: com.vayunmathur.library.ui.odf.OdfDocument): OfficeViewModel {
        val vm = newVm()
        vm.stateMutable.value = OfficeViewModel.ViewState.Loaded(doc)
        return vm
    }
}

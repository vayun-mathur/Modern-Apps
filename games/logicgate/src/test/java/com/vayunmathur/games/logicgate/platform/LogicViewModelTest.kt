package com.vayunmathur.games.logicgate.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LogicViewModelTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    @BeforeTest fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(25)
        }
        assertTrue(cond(), "timed out: $desc")
    }

    @Test fun `progression loads all levels with first unlocked`() {
        val vm = LogicViewModel(app, compute = Dispatchers.Unconfined, io = Dispatchers.Unconfined)
        await("progression") { vm.progression.value.levels.isNotEmpty() }
        val levels = vm.progression.value.levels
        assertEquals(28, levels.size)
        assertTrue(levels.first().unlocked)
        assertEquals(0, vm.progression.value.solved)
        assertTrue(vm.sandbox.value.components.isEmpty())
        assertNotNull(vm.achievementsManager)
        vm.dismissAchievement()
    }

    @Test fun `open level loads editor state and reopening same level is ignored`() {
        val vm = LogicViewModel(app, compute = Dispatchers.Unconfined, io = Dispatchers.Unconfined)
        await("progression") { vm.progression.value.levels.isNotEmpty() }
        vm.openLevel("does-not-exist")
        assertNull(vm.game.value)
        vm.openLevel("not")
        await("level open") { vm.game.value?.level?.id == "not" }
        val first = vm.game.value!!
        assertEquals("and", first.nextLevelId)
        assertTrue(first.chips.isNotEmpty())
        vm.openLevel("not") // same level: no-op
        assertTrue(vm.game.value === first || vm.game.value?.fitToken == first.fitToken)
        vm.openLevel("and")
        await("and open") { vm.game.value?.level?.id == "and" }
    }

    @Test fun `component create open rename via editor and delete`() {
        val vm = LogicViewModel(app, compute = Dispatchers.Unconfined, io = Dispatchers.Unconfined)
        await("progression") { vm.progression.value.levels.isNotEmpty() }
        val id = vm.createComponent("  My Gate ", "")
        assertTrue(id.startsWith("custom:"))
        assertEquals(1, vm.sandbox.value.components.size)
        assertEquals("My Gate", vm.sandbox.value.components.first().name)
        vm.openComponent(id)
        assertEquals(id, vm.game.value?.sandbox?.id)
        vm.openComponent("custom:missing")
        assertEquals(id, vm.game.value?.sandbox?.id)
        vm.deleteComponent(id)
        assertTrue(vm.sandbox.value.components.isEmpty())
        assertNull(vm.game.value)
        vm.deleteComponent(id) // already gone: no crash
    }

    @Test fun `contract derived state helpers`() {
        val card = LevelCard("not", 1, 0, com.vayunmathur.games.logicgate.domain.levels.Chapter.BASICS, unlocked = true, stars = 0, bestCost = null, par = 1)
        assertEquals(false, card.completed)
        assertEquals(true, card.copy(bestCost = 1).completed)
        val sandbox = SandboxUiState(listOf(
            ComponentCard("b", "beta", "zeta", 1, 1, 2),
            ComponentCard("a", "alpha", "Alpha", 2, 1, null),
        ))
        assertEquals(listOf("Alpha", "zeta"), sandbox.groups.map { it.first })
        assertNull(sandbox.groups.first().second.first().cost)
    }
}

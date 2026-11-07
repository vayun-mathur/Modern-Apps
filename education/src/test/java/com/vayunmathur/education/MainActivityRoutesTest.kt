package com.vayunmathur.education

import com.vayunmathur.library.util.NavBackStack
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class MainActivityRoutesTest {
    @Test fun routesCarryPayloads() {
        assertEquals(Route.Home, Route.Home)
        assertEquals(Route.Course("c1"), Route.Course("c1"))
        assertNotEquals<Route>(Route.Course("c1"), Route.Course("c2"))
        assertEquals("c1", Route.Course("c1").courseId)
        assertEquals("u1", Route.UnitScreen("u1").unitId)
        assertEquals("l1", Route.LessonScreen("l1").lessonId)
        assertEquals("e1", Route.Quiz("e1").exerciseId)
        assertEquals("http://x/v.mp4", Route.VideoPlayer("http://x/v.mp4", "T").videoUrl)
        assertEquals("T", Route.VideoPlayer("http://x/v.mp4", "T").title)
        assertEquals("l1", Route.K2Lesson("l1").lessonId)
        assertEquals("e1", Route.K2Quiz("e1").exerciseId)
        assertEquals(3, Route.K2Reward(3).stars)
        val results = Route.Results(7, 5, 2)
        assertEquals(7, results.total); assertEquals(5, results.correct); assertEquals(2, results.stars)
        assertEquals(Route.ParentGate, Route.ParentGate)
        assertEquals(Route.Parent, Route.Parent)
        assertEquals(Route.Badges, Route.Badges)
    }

    @Test fun pushChainReplacesSameClassOnly() {
        val bs = NavBackStack<Route>(arrayOf(Route.Home))
        bs.pushChain(Route.Course("c1"))
        assertEquals(listOf(Route.Home, Route.Course("c1")), bs.backStack)
        bs.pushChain(Route.Course("c2")) // same class: replace, don't stack
        assertEquals(listOf(Route.Home, Route.Course("c2")), bs.backStack)
        bs.pushChain(Route.UnitScreen("u1")) // different class: push
        assertEquals(3, bs.backStack.size)
        bs.pushChain(Route.UnitScreen("u2"))
        assertEquals(Route.UnitScreen("u2"), bs.last())
        assertEquals(3, bs.backStack.size)
        bs.pop(); assertEquals(Route.Course("c2"), bs.last())
        bs.reset(Route.Home); assertEquals(listOf(Route.Home), bs.backStack)
    }

    @Test fun mainActivityLaunchesWithoutCrashing() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.create().start().resume()
        // Content loads content + DB on IO, then flips ready. Give the looper a chance.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(controller.get().isFinishing.not())
        controller.pause().stop().destroy()
    }
}

package com.vayunmathur.todo.platform

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.vayunmathur.todo.domain.TodoView
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class IntentRouterTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun openTaskExtra() {
        IntentRouter.consume()
        IntentRouter.handle(Intent().putExtra(IntentRouter.EXTRA_OPEN_TASK, "abc123"))
        assertEquals(IntentRouter.Request.OpenTask("abc123"), IntentRouter.pending.value)
        IntentRouter.consume()
        assertNull(IntentRouter.pending.value)
    }
    @Test fun openViewExtra() {
        IntentRouter.handle(Intent().putExtra(IntentRouter.EXTRA_VIEW, "today"))
        assertEquals(IntentRouter.Request.OpenView("today"), IntentRouter.pending.value)
        IntentRouter.consume()
    }
    @Test fun quickAddAction() {
        IntentRouter.handle(Intent(IntentRouter.ACTION_QUICK_ADD).putExtra(Intent.EXTRA_TEXT, "buy milk"))
        assertEquals(IntentRouter.Request.QuickAdd("buy milk"), IntentRouter.pending.value)
        IntentRouter.consume()
    }
    @Test fun quickAddActionWithoutText() {
        IntentRouter.handle(Intent(IntentRouter.ACTION_QUICK_ADD))
        assertEquals(IntentRouter.Request.QuickAdd(""), IntentRouter.pending.value)
        IntentRouter.consume()
    }
    @Test fun sendActionWithSubject() {
        val i = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "body text").putExtra(Intent.EXTRA_SUBJECT, "Subject:")
        IntentRouter.handle(i)
        assertEquals(IntentRouter.Request.QuickAdd("Subject: body text"), IntentRouter.pending.value)
        IntentRouter.consume()
    }
    @Test fun sendActionSubjectAlreadyInText() {
        val i = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "Subject: body").putExtra(Intent.EXTRA_SUBJECT, "Subject:")
        IntentRouter.handle(i)
        assertEquals(IntentRouter.Request.QuickAdd("Subject: body"), IntentRouter.pending.value)
        IntentRouter.consume()
    }
    @Test fun sendActionWithoutTextIgnored() {
        IntentRouter.consume()
        IntentRouter.handle(Intent(Intent.ACTION_SEND))
        assertNull(IntentRouter.pending.value)
    }
    @Test fun nullAndUnknownIgnored() {
        IntentRouter.consume()
        IntentRouter.handle(null)
        assertNull(IntentRouter.pending.value)
        IntentRouter.handle(Intent(Intent.ACTION_VIEW))
        assertNull(IntentRouter.pending.value)
    }
    @Test fun openTaskTakesPrecedenceOverView() {
        val i = Intent().putExtra(IntentRouter.EXTRA_OPEN_TASK, "t1").putExtra(IntentRouter.EXTRA_VIEW, "today")
        IntentRouter.handle(i)
        assertTrue(IntentRouter.pending.value is IntentRouter.Request.OpenTask)
        IntentRouter.consume()
    }
    @Test fun routeTypes() {
        assertEquals(com.vayunmathur.todo.Route.TodoList, com.vayunmathur.todo.Route.TodoList)
        assertEquals(5L, com.vayunmathur.todo.Route.TodoDetail(5L, "n").id)
        assertEquals("n", com.vayunmathur.todo.Route.TodoDetail(5L, "n").newSyncId)
        assertNull(com.vayunmathur.todo.Route.TodoDetail(0L).newSyncId)
    }
}

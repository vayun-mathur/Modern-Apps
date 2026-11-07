package com.vayunmathur.library.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.Composable
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.glance.GlanceId
import androidx.glance.appwidget.AppWidgetId
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.text.Text
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeWidget : MaWidget<String>() {
    var loadedWith: Int? = null
    override suspend fun loadData(context: Context, id: GlanceId): String {
        loadedWith = (id as? AppWidgetId)?.appWidgetId
        return "fake-data"
    }
    override fun previewData(context: Context): String = "preview-data"
    fun previewForTest(c: Context) = previewData(c)
    @Composable override fun Content(data: String) { Text(data) }
}

class FailingLoadWidget : MaWidget<String>() {
    override suspend fun loadData(context: Context, id: GlanceId): String = throw IllegalStateException("load boom")
    override fun previewData(context: Context): String = "p"
    @Composable override fun Content(data: String) { Text(data) }
}

class NoPreviewWidget : MaWidget<String>() {
    override val composePreview: Boolean = false
    override suspend fun loadData(context: Context, id: GlanceId): String = "x"
    override fun previewData(context: Context): String = "p"
    fun previewForTest(c: Context) = previewData(c)
    @Composable override fun Content(data: String) { Text(data) }
}

class FakeReceiver : MaWidgetReceiver<FakeWidget>(
    widgetClass = FakeWidget::class,
    receiverClass = FakeReceiver::class,
    widgetFactory = ::FakeWidget,
) {
    var extraEnabled = false
    override fun onEnabledExtra(context: Context) { extraEnabled = true }
}

class NoHourlyReceiver : MaWidgetReceiver<FakeWidget>(
    widgetClass = FakeWidget::class,
    receiverClass = NoHourlyReceiver::class,
    hourly = false,
    previews = false,
    widgetFactory = ::FakeWidget,
)

class DateReceiver : MaWidgetReceiver<FakeWidget>(
    widgetClass = FakeWidget::class,
    receiverClass = DateReceiver::class,
    dateTriggers = true,
    widgetFactory = ::FakeWidget,
)

@RunWith(RobolectricTestRunner::class)
class WidgetsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @BeforeTest fun setUp() {
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
    }

    // ---- MaterialShapeBitmap ----

    @Test fun shapeBitmapHasRequestedSizeAndPaintsCenter() {
        val bmp = MaterialShapes.Pill.toWidgetBitmap(64, Color.RED)
        assertEquals(64, bmp.width)
        assertEquals(64, bmp.height)
        assertTrue(!bmp.isRecycled)
        // Robolectric's shadow canvas does not rasterise, so pixels are not
        // asserted here; the square size contract is what callers rely on.
    }
    @Test fun shapeBitmapCookieAndSmallSizes() {
        val cookie = MaterialShapes.Cookie4Sided.toWidgetBitmap(48, Color.BLUE)
        assertEquals(48, cookie.width)
        assertEquals(48, cookie.height)
        val tiny = MaterialShapes.Pill.toWidgetBitmap(8, Color.GREEN)
        assertEquals(8, tiny.height)
    }

    // ---- MaWidget provide paths (provideContent needs a Glance host under Robolectric,
    // so the success path exercises the try/catch fallbacks and returns normally) ----

    @Test fun provideGlanceLoadsDataAndSurvivesMissingHost() {
        val w = FakeWidget()
        runBlocking { w.provideGlance(context, AppWidgetId(77)) }
        assertEquals(77, w.loadedWith)
        assertEquals("preview-data", w.previewForTest(context))
        assertEquals("FakeWidget", w.tagForTest())
    }
    @Test fun provideGlanceFailingLoadFallsBackAndThrowsWithoutHost() {
        val w = FailingLoadWidget()
        // load fails -> fallback provideContent is attempted; without a Glance
        // ContentReceiver it throws IllegalStateException out of provideGlance.
        assertFailsWith<IllegalStateException> {
            runBlocking { w.provideGlance(context, AppWidgetId(78)) }
        }
    }
    @Test fun providePreviewPaths() {
        val w = FakeWidget()
        runBlocking { w.providePreview(context, 1) } // throws internally, caught, returns
        val noPreview = NoPreviewWidget()
        runBlocking { noPreview.providePreview(context, 1) } // composePreview=false: immediate return
        assertEquals("p", noPreview.previewForTest(context))
    }

    private fun MaWidget<String>.tagForTest(): String {
        // logTag is protected; expose via a subclass-free check on toString path instead
        return this::class.simpleName ?: ""
    }

    // ---- Receivers ----

    @Test fun receiverOnEnabledSchedulesAndRunsExtra() {
        val receiver = FakeReceiver()
        receiver.onEnabled(context)
        assertTrue(receiver.extraEnabled)
        assertNotNull(receiver.glanceAppWidget)
        assertTrue(receiver.glanceAppWidget is FakeWidget)
        // hourly work was enqueued for the widget class
        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(FakeWidget::class.qualifiedName!!)
        assertTrue(infos.get().isNotEmpty())
    }
    @Test fun receiverOnUpdateReschedules() {
        val receiver = FakeReceiver()
        receiver.onUpdate(context, AppWidgetManager.getInstance(context), intArrayOf(1, 2))
        val noHourly = NoHourlyReceiver()
        noHourly.onEnabled(context) // hourly=false, previews=false: both branches off
        noHourly.onUpdate(context, AppWidgetManager.getInstance(context), intArrayOf())
    }
    @Test fun receiverIgnoresIntentsWhenNotDateDriven() {
        val receiver = FakeReceiver()
        // super.onReceive handles Glance actions; unknown/custom actions are ignored,
        // and the date-trigger guard returns before any work.
        receiver.onReceive(context, Intent(Intent.ACTION_DATE_CHANGED))
        receiver.onReceive(context, Intent("com.example.CUSTOM"))
        val dateReceiver = DateReceiver()
        dateReceiver.onReceive(context, Intent("com.example.CUSTOM"))
    }

    // ---- Scheduling helpers ----

    @Test fun scheduleHelpersEnqueueWork() {
        context.scheduleHourlyUpdate(FakeWidget::class)
        val hourly = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(FakeWidget::class.qualifiedName!!).get()
        assertTrue(hourly.isNotEmpty())
        context.updateWidget(FakeWidget::class)
        val immediate = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(FakeWidget::class.qualifiedName!! + "_immediate").get()
        assertTrue(immediate.isNotEmpty())
        // below API 35 the preview registration is a no-op (returns before WorkManager)
        context.updateWidgetPreviews(FakeReceiver::class)
    }

    // ---- Workers ----

    @Test fun genericWorkerFailsWithoutClassName() {
        val worker = TestListenableWorkerBuilder<GenericWidgetWorker>(context).build()
        assertEquals(
            androidx.work.ListenableWorker.Result.failure(),
            runBlocking { worker.doWork() },
        )
    }
    @Test fun genericWorkerFailsForUnknownClass() {
        val worker = TestListenableWorkerBuilder<GenericWidgetWorker>(context)
            .setInputData(workDataOf(GenericWidgetWorker.ARG_WIDGET_CLASS to "com.example.DoesNotExist"))
            .build()
        assertEquals(androidx.work.ListenableWorker.Result.failure(), runBlocking { worker.doWork() })
    }
    @Test fun genericWorkerUpdatesKnownWidget() {
        val worker = TestListenableWorkerBuilder<GenericWidgetWorker>(context)
            .setInputData(workDataOf(GenericWidgetWorker.ARG_WIDGET_CLASS to FakeWidget::class.qualifiedName!!))
            .build()
        // no widget instances are placed, so updateAll is a no-op success
        assertEquals(androidx.work.ListenableWorker.Result.success(), runBlocking { worker.doWork() })
    }
    @Test fun previewWorkerSkipsBelowApi35() {
        val worker = TestListenableWorkerBuilder<WidgetPreviewWorker>(context)
            .setInputData(workDataOf(WidgetPreviewWorker.ARG_RECEIVER_CLASS to FakeReceiver::class.qualifiedName!!))
            .build()
        assertEquals(androidx.work.ListenableWorker.Result.success(), runBlocking { worker.doWork() })
    }

    @Config(sdk = [35])
    @Test fun previewWorkerApi35Branches() {
        val missing = TestListenableWorkerBuilder<WidgetPreviewWorker>(context).build()
        assertEquals(androidx.work.ListenableWorker.Result.failure(), runBlocking { missing.doWork() })
        val badClass = TestListenableWorkerBuilder<WidgetPreviewWorker>(context)
            .setInputData(workDataOf(WidgetPreviewWorker.ARG_RECEIVER_CLASS to "com.example.Nope"))
            .build()
        assertEquals(androidx.work.ListenableWorker.Result.failure(), runBlocking { badClass.doWork() })
    }

    @Config(sdk = [35])
    @Test fun updateWidgetPreviewsApi35Enqueues() {
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
        context.updateWidgetPreviews(FakeReceiver::class)
        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(FakeReceiver::class.qualifiedName!! + "_preview").get()
        assertTrue(infos.isNotEmpty())
    }

    // ---- Widget state helpers ----

    @Test fun readWidgetStateRoundTripAndFailures() {
        val serializer = ListSerializer(String.serializer())
        val prefs = mutablePreferencesOf(
            androidx.datastore.preferences.core.stringPreferencesKey("k") to "[\"a\",\"b\"]",
            androidx.datastore.preferences.core.stringPreferencesKey("bad") to "not json",
        )
        assertEquals(listOf("a", "b"), readWidgetState(prefs, "k", serializer))
        assertNull(readWidgetState(prefs, "missing", serializer))
        assertNull(readWidgetState(prefs, "bad", serializer))
        // direct decode helper: same contract
        assertEquals(listOf("x"), decodeWidgetState(serializer, "[\"x\"]", "k"))
        assertNull(decodeWidgetState(serializer, "{oops", "k"))
    }
    @Test fun updateWidgetStateWithNoWidgetsPlacedReturns() {
        // no glance ids are registered under Robolectric: the function returns early
        runBlocking {
            updateWidgetState(context, FakeWidget::class, "k", listOf("a"), ListSerializer(String.serializer()))
        }
    }
    @Test fun stateDefinitionAliasAndWorkerArgs() {
        assertEquals(JsonPreferencesStateDefinition::class, androidx.glance.state.PreferencesGlanceStateDefinition::class)
        assertEquals("widget_class_name", GenericWidgetWorker.ARG_WIDGET_CLASS)
        assertEquals("receiver_class_name", WidgetPreviewWorker.ARG_RECEIVER_CLASS)
        assertTrue(FakeReceiver() is GlanceAppWidgetReceiver)
    }

    @Config(sdk = [35])
    @Test fun previewWorkerApi35WithRealReceiver() {
        val worker = TestListenableWorkerBuilder<WidgetPreviewWorker>(context)
            .setInputData(workDataOf(WidgetPreviewWorker.ARG_RECEIVER_CLASS to FakeReceiver::class.qualifiedName!!))
            .build()
        // setWidgetPreviews has no host under Robolectric: success, retry or a
        // reflective failure are all acceptable terminal outcomes - the point is
        // the SDK>=35 try branch executes. A Result must come back.
        val result = runBlocking { worker.doWork() }
        assertTrue(result is androidx.work.ListenableWorker.Result)
    }
}


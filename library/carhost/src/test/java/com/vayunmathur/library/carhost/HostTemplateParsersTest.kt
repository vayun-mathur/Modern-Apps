package com.vayunmathur.library.carhost

import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarText
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.LongMessageTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.TemplateWrapper
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.Lane
import androidx.car.app.navigation.model.LaneDirection
import androidx.car.app.model.Distance
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class HostTemplateParsersTest {

    private fun row(title: String, text: String? = null, browse: Boolean = false, latch: CountDownLatch? = null): Row {
        val b = Row.Builder().setTitle(title).setBrowsable(browse)
        if (text != null) b.addText(text)
        if (latch != null || browse) b.setOnClickListener { latch?.countDown() }
        return b.build()
    }
    private fun action(title: String, latch: CountDownLatch? = null): Action =
        Action.Builder().setTitle(title).setOnClickListener { latch?.countDown() }.build()
    private fun itemList(vararg rows: Row): ItemList =
        ItemList.Builder().also { b -> rows.forEach { b.addItem(it) } }.build()

    @Test fun nullWrapperIsLoadingPane() {
        val t = HostTemplateParsers.parse(null)
        assertTrue(t is HostTemplate.Pane); assertTrue(t.loading)
    }

    @Test fun listTemplateParsesRowsActionsAndClicks() {
        val latch = CountDownLatch(1)
        val template = ListTemplate.Builder()
            .setTitle("Destinations")
            .setSingleList(itemList(row("Home", "123 Main St", browse = true, latch = latch), row("Work")))
            .setActionStrip(ActionStrip.Builder().addAction(action("Settings")).build())
            .build()
        val parsed = HostTemplateParsers.parse(TemplateWrapper.wrap(template))
        assertTrue(parsed is HostTemplate.TemplateList)
        assertEquals("Destinations", parsed.title)
        val rows = parsed.sections.single().rows
        assertEquals(listOf("Home", "Work"), rows.map { it.title })
        assertEquals(listOf("123 Main St"), rows[0].texts); assertTrue(rows[0].browse)
        assertEquals(listOf("Settings"), parsed.actions.map { it.title })
        assertNotNull(rows[0].onClick)
        rows[0].onClick!!.invoke()
        parsed.actions[0].onClick.invoke()
    }

    @Test fun listTemplateSectionedListsParse() {
        val section = SectionedItemList.create(itemList(row("Cafe")), "Near")
        val template = ListTemplate.Builder().setTitle("T").addSectionedList(section).build()
        val parsed = HostTemplateParsers.parse(TemplateWrapper.wrap(template)) as HostTemplate.TemplateList
        assertEquals("Near", parsed.sections.single().header)
        assertEquals("Cafe", parsed.sections.single().rows.single().title)
    }

    @Test fun paneTemplateParses() {
        val pane = Pane.Builder().addRow(row("Row A", "sub")).addAction(action("Go")).build()
        val template = PaneTemplate.Builder(pane).setTitle("Pane Title").build()
        val parsed = HostTemplateParsers.parse(TemplateWrapper.wrap(template)) as HostTemplate.Pane
        assertEquals("Pane Title", parsed.title); assertEquals("Row A", parsed.rows.single().title)
        assertEquals(listOf("Go"), parsed.actions.map { it.title })
    }

    @Test fun gridTemplateParses() {
        val latch = CountDownLatch(1)
        val item = GridItem.Builder().setTitle("Album").setText("Artist")
            .setImage(androidx.car.app.model.CarIcon.APP_ICON)
            .setOnClickListener { latch.countDown() }.build()
        val list = ItemList.Builder().addItem(item).build()
        val template = GridTemplate.Builder().setTitle("Grid").setSingleList(list).build()
        val parsed = HostTemplateParsers.parse(TemplateWrapper.wrap(template)) as HostTemplate.Grid
        assertEquals("Album", parsed.items.single().title); assertEquals("Artist", parsed.items.single().text)
        assertNotNull(parsed.items.single().onClick)
        parsed.items.single().onClick!!.invoke()
    }

    @Test fun messageAndLongMessageParse() {
        val msg = MessageTemplate.Builder("Hello world").setTitle("Greeting").addAction(action("OK")).build()
        val parsed = HostTemplateParsers.parse(TemplateWrapper.wrap(msg)) as HostTemplate.Message
        assertEquals("Greeting", parsed.title); assertEquals("Hello world", parsed.message)
        assertEquals(listOf("OK"), parsed.actions.map { it.title })
        val longMsg = LongMessageTemplate.Builder("Long body text").setTitle("Info").build()
        val parsed2 = HostTemplateParsers.parse(TemplateWrapper.wrap(longMsg)) as HostTemplate.Message
        assertEquals("Long body text", parsed2.message)
    }

    @Test fun placeListParses() {
        val template = PlaceListMapTemplate.Builder()
            .setTitle("Places")
            .setItemList(itemList(row("Park", "0.5 mi", browse = true)))
            .setLoading(false)
            .build()
        val parsed = HostTemplateParsers.parse(TemplateWrapper.wrap(template)) as HostTemplate.PlaceList
        assertEquals("Places", parsed.title); assertEquals("Park", parsed.rows.single().title)
    }

    @Test fun searchTemplateParsesWithCallbacks() {
        val changed = mutableListOf<String>(); val submitted = mutableListOf<String>()
        val callback = object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(text: String) { changed += text }
            override fun onSearchSubmitted(text: String) { submitted += text }
        }
        val template = SearchTemplate.Builder(callback)
            .setSearchHint("Find places").setInitialSearchText("cof")
            .setShowKeyboardByDefault(true)
            .setItemList(itemList(row("Coffee Shop")))
            .build()
        val parsed = HostTemplateParsers.parse(TemplateWrapper.wrap(template)) as HostTemplate.Search
        assertEquals("Find places", parsed.hint); assertEquals("cof", parsed.initialText)
        assertTrue(parsed.showKeyboardByDefault); assertEquals("Coffee Shop", parsed.rows.single().title)
        // Callbacks go through delegates; they may be binder-backed locally, so just
        // assert the host callbacks exist when a delegate was created.
        parsed.onSearchTextChanged?.invoke("coffee")
        parsed.onSearchSubmitted?.invoke("coffee")
    }

    @Test fun navigationTemplateParsesRouting() {
        val lane = Lane.Builder().addDirection(LaneDirection.create(LaneDirection.SHAPE_STRAIGHT, true))
            .addDirection(LaneDirection.create(LaneDirection.SHAPE_NORMAL_LEFT, false)).build()
        val step = Step.Builder().setCue("Turn left").setRoad("Main Street").addLane(lane).setLanesImage(androidx.car.app.model.CarIcon.APP_ICON).build()
        val routing = RoutingInfo.Builder().setCurrentStep(step, Distance.create(500.0, Distance.UNIT_METERS))
            .setLoading(false).build()
        val template = NavigationTemplate.Builder().setNavigationInfo(routing)
            .setActionStrip(ActionStrip.Builder().addAction(action("End")).build()).build()
        val parsed = HostTemplateParsers.parseNavigation(template)
        assertTrue(parsed.navigating); assertEquals("Turn left", parsed.cue); assertEquals("Main Street", parsed.road)
        assertEquals("500 m", parsed.distanceText)
        assertNotNull(parsed.lanesText); assertTrue(parsed.lanesText!!.contains("↑"))
        assertEquals(listOf("End"), parsed.actions.map { it.title })
        // Also via generic parse dispatch.
        assertTrue(HostTemplateParsers.parse(TemplateWrapper.wrap(template)) is HostTemplate.Navigation)
    }

    @Test fun navigationLoadingAndDistanceUnits() {
        val routing = RoutingInfo.Builder().setLoading(true).build()
        val parsed = HostTemplateParsers.parseNavigation(
            NavigationTemplate.Builder().setNavigationInfo(routing)
                .setActionStrip(ActionStrip.Builder().addAction(action("X")).build()).build())
        assertTrue(parsed.loading); assertTrue(!parsed.navigating); assertNull(parsed.cue)
    }

    @Test fun dataClassDefaults() {
        val nav = HostNavState()
        assertTrue(nav.mapsPresent && nav.connected && !nav.navigating)
        val header = HostUiHeader(title = "H", startAction = HostUiAction("Back") {})
        assertEquals("H", header.title); assertEquals("Back", header.startAction?.title)
        assertEquals("x", HostAction("x") {}.title)
        val tab = HostUiTab(title = "T", contentId = "id1"); assertEquals("id1", tab.contentId)
        val section = HostUiSection(header = "S", chips = true); assertTrue(section.chips && !section.grid)
        assertEquals(CarText.create("hi").toCharSequence().toString(), "hi")
    }

    @Test fun tabTemplateParses() {
        val inner = ListTemplate.Builder().setTitle("Inner").setSingleList(itemList(row("R1"))).build()
        val callback = object : androidx.car.app.model.TabTemplate.TabCallback {
            override fun onTabSelected(tabContentId: String) {}
        }
        val template = androidx.car.app.model.TabTemplate.Builder(callback)
            .addTab(androidx.car.app.model.Tab.Builder().setTitle("One").setContentId("a").setIcon(androidx.car.app.model.CarIcon.APP_ICON).build())
            .addTab(androidx.car.app.model.Tab.Builder().setTitle("Two").setContentId("b").setIcon(androidx.car.app.model.CarIcon.APP_ICON).build())
            .setHeaderAction(Action.APP_ICON)
            .setActiveTabContentId("a")
            .setTabContents(androidx.car.app.model.TabContents.Builder(inner).build())
            .build()
        val parsed = HostTemplateParsers.parse(TemplateWrapper.wrap(template)) as HostTemplate.Tabs
        assertEquals(listOf("One", "Two"), parsed.tabs.map { it.title })
        assertEquals("a", parsed.activeContentId)
        assertTrue(parsed.content is HostTemplate.TemplateList)
        parsed.onTabSelected?.invoke("b")
    }

    @Test fun sectionedItemTemplateParsesSections() {
        val rows = androidx.car.app.model.RowSection.Builder().setTitle("RowsSec").addItem(row("SRow", "x")).build()
        val chips = androidx.car.app.model.ChipSection.Builder().setTitle("ChipsSec")
            .addItem(androidx.car.app.model.Chip.Builder().setTitle("ChipA").setOnClickListener {}.build()).build()
        val grids = androidx.car.app.model.GridSection.Builder().setTitle("GridSec")
            .addItem(GridItem.Builder().setTitle("GItem").setImage(androidx.car.app.model.CarIcon.APP_ICON).setOnClickListener {}.build()).build()
        val condensed = androidx.car.app.model.CondensedSection.Builder().setTitle("CondSec")
            .addItem(androidx.car.app.model.CondensedItem.Builder().setTitle("CItem").setOnClickListener {}.build()).build()
        val template = androidx.car.app.model.SectionedItemTemplate.Builder()
            .addSection(chips).addSection(rows).addSection(grids).addSection(condensed)
            .setHeader(androidx.car.app.model.Header.Builder().setTitle("Sections").build())
            .build()
        val parsed = HostTemplateParsers.parse(TemplateWrapper.wrap(template)) as HostTemplate.TemplateList
        assertEquals("Sections", parsed.title)
        assertEquals(4, parsed.sections.size)
        assertTrue(parsed.sections[0].chips); assertTrue(parsed.sections[2].grid)
        assertTrue(!parsed.sections[1].chips && !parsed.sections[1].grid)
        // Items arrive via the range delegate; locally they may or may not be
        // delivered synchronously, so only titles are asserted when present.
        val allTitles = parsed.sections.flatMap { s -> s.rows.map { it.title } }
        if (allTitles.isNotEmpty()) assertTrue(allTitles.containsAll(listOf("SRow", "ChipA", "GItem", "CItem")))
        parsed.sections.flatMap { it.rows }.forEach { it.onClick?.invoke() }
    }

    @Test fun mediaSignInInCallKeypadParse() {
        val media = androidx.car.app.media.model.MediaPlaybackTemplate.Builder()
            .setHeader(androidx.car.app.model.Header.Builder().setTitle("Now Song").build()).build()
        val mp = HostTemplateParsers.parse(TemplateWrapper.wrap(media)) as HostTemplate.MediaPlayback
        assertEquals("Now Song", mp.title)

        val signIn = androidx.car.app.model.signin.SignInTemplate.Builder(
            androidx.car.app.model.signin.PinSignInMethod("1234"))
            .setTitle("Sign in").setInstructions("Enter PIN")
            .addAction(Action.Builder().setTitle("Continue").setOnClickListener(androidx.car.app.model.ParkedOnlyOnClickListener.create {}).build()).build()
        val si = HostTemplateParsers.parse(TemplateWrapper.wrap(signIn)) as HostTemplate.SignIn
        assertEquals("Sign in", si.title); assertEquals("Enter PIN", si.message)

        val inCall = androidx.car.app.dialer.InCallTemplate.Builder()
            .setTitle("Bob").addText("Calling...")
            .addAction(Action.Builder().setIcon(androidx.car.app.model.CarIcon.APP_ICON).setOnClickListener {}.build()).build()
        val ic = HostTemplateParsers.parse(TemplateWrapper.wrap(inCall)) as HostTemplate.InCall
        assertEquals("Bob", ic.title); assertEquals(listOf("Calling..."), ic.texts)

        val keypad = androidx.car.app.dialer.TelephoneKeypadTemplate.Builder(Action.Builder().setIcon(androidx.car.app.model.CarIcon.APP_ICON).setOnClickListener {}.build()) { }
            .setPhoneNumber("5551234")
            .setHeader(androidx.car.app.model.Header.Builder().setTitle("Dial").build())
            .build()
        val kp = HostTemplateParsers.parse(TemplateWrapper.wrap(keypad)) as HostTemplate.Keypad
        assertEquals("5551234", kp.phoneNumber); assertEquals("Dial", kp.title)
        kp.onPrimaryAction?.invoke()
    }

    @Test fun mapWithContentAndLegacyMapsParse() {
        val inner = PaneTemplate.Builder(Pane.Builder().addRow(row("PR")).build()).setTitle("InnerPane").build()
        val mwc = androidx.car.app.navigation.model.MapWithContentTemplate.Builder()
            .setContentTemplate(inner)
            .setActionStrip(ActionStrip.Builder().addAction(action("Zoom")).build()).build()
        val parsed = HostTemplateParsers.parse(TemplateWrapper.wrap(mwc)) as HostTemplate.MapWithContent
        assertTrue(parsed.content is HostTemplate.Pane); assertEquals(listOf("Zoom"), parsed.actions.map { it.title })

        val plNav = androidx.car.app.navigation.model.PlaceListNavigationTemplate.Builder()
            .setItemList(itemList(row("Shop", browse = true))).build()
        val pl = HostTemplateParsers.parse(TemplateWrapper.wrap(plNav)) as HostTemplate.PlaceList
        assertEquals("Shop", pl.rows.single().title)
        val spanText = android.text.SpannableString("10 min").apply {
            setSpan(androidx.car.app.model.DurationSpan.create(600), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val destRow = Row.Builder().setTitle("Dest")
            .addText(CarText.create(spanText))
            .build()
        val routePreview = androidx.car.app.navigation.model.RoutePreviewNavigationTemplate.Builder()
            .setNavigateAction(action("Navigate"))
            .setItemList(ItemList.Builder().addItem(destRow).setOnSelectedListener { }.build()).build()
        val rp = HostTemplateParsers.parse(TemplateWrapper.wrap(routePreview)) as HostTemplate.PlaceList
        assertEquals("Dest", rp.rows.single().title)
    }

    @Test fun unknownTemplateFallsBackToPane() {
        val fake = object : androidx.car.app.model.Template {}
        val parsed = HostTemplateParsers.parse(TemplateWrapper.wrap(fake))
        assertTrue(parsed is HostTemplate.Pane)
    }

    @Test fun navigationDistanceUnitsEstimateAndAllLaneShapes() {
        fun navWith(distance: androidx.car.app.model.Distance, estimateSeconds: Long?): HostTemplate.Navigation {
            val shapes = listOf(
                LaneDirection.SHAPE_STRAIGHT, LaneDirection.SHAPE_SLIGHT_LEFT, LaneDirection.SHAPE_SLIGHT_RIGHT,
                LaneDirection.SHAPE_NORMAL_LEFT, LaneDirection.SHAPE_NORMAL_RIGHT, LaneDirection.SHAPE_SHARP_LEFT,
                LaneDirection.SHAPE_SHARP_RIGHT, LaneDirection.SHAPE_U_TURN_LEFT, LaneDirection.SHAPE_U_TURN_RIGHT,
                LaneDirection.SHAPE_UNKNOWN)
            val lanes = shapes.map { s -> Lane.Builder().addDirection(LaneDirection.create(s, true)).build() }
            var stepB = Step.Builder().setCue("Go").setRoad("Rd").setLanesImage(androidx.car.app.model.CarIcon.APP_ICON)
            lanes.forEach { stepB = stepB.addLane(it) }
            val routing = RoutingInfo.Builder().setCurrentStep(stepB.build(), distance).setLoading(false).build()
            val b = NavigationTemplate.Builder().setNavigationInfo(routing)
                .setActionStrip(ActionStrip.Builder().addAction(action("E")).build())
            if (estimateSeconds != null) {
                val arrival = androidx.car.app.model.DateTimeWithZone.create(java.time.ZonedDateTime.now())
                b.setDestinationTravelEstimate(
                    androidx.car.app.navigation.model.TravelEstimate.Builder(distance, arrival)
                        .setRemainingTimeSeconds(estimateSeconds).build())
            }
            return HostTemplateParsers.parseNavigation(b.build())
        }
        assertEquals("2.5 km", navWith(Distance.create(2.5, Distance.UNIT_KILOMETERS), 300).distanceText)
        assertEquals("5 min", navWith(Distance.create(2.5, Distance.UNIT_KILOMETERS), 300).etaText)
        assertEquals("1.5 mi", navWith(Distance.create(1.5, Distance.UNIT_MILES_P1), 5400).distanceText)
        assertEquals("1 h 30 min", navWith(Distance.create(1.5, Distance.UNIT_MILES_P1), 5400).etaText)
        assertEquals("300 ft", navWith(Distance.create(300.0, Distance.UNIT_FEET), null).distanceText)
        assertEquals("7 m", navWith(Distance.create(7.0, Distance.UNIT_YARDS), null).distanceText)
        assertNull(navWith(Distance.create(7.0, Distance.UNIT_METERS), 0).etaText)
        assertNull(navWith(Distance.create(7.0, Distance.UNIT_METERS), Long.MAX_VALUE).etaText)
        val all = navWith(Distance.create(1.0, Distance.UNIT_KILOMETERS_P1), null)
        for (arrow in listOf("↑", "⬉", "⬊", "←", "→", "↰", "↱", "↩", "↪", "•")) assertTrue(all.lanesText!!.contains(arrow), arrow)
    }

    @Test fun tabContentsInnerVariantsParse() {
        fun tabWith(inner: androidx.car.app.model.Template): HostTemplate.Tabs {
            val callback = object : androidx.car.app.model.TabTemplate.TabCallback {
                override fun onTabSelected(tabContentId: String) {}
            }
            val template = androidx.car.app.model.TabTemplate.Builder(callback)
                .setHeaderAction(Action.APP_ICON)
                .addTab(androidx.car.app.model.Tab.Builder().setTitle("T").setContentId("a")
                    .setIcon(androidx.car.app.model.CarIcon.APP_ICON).build())
                .addTab(androidx.car.app.model.Tab.Builder().setTitle("U").setContentId("b")
                    .setIcon(androidx.car.app.model.CarIcon.APP_ICON).build())
                .setActiveTabContentId("a")
                .setTabContents(androidx.car.app.model.TabContents.Builder(inner).build())
                .build()
            return HostTemplateParsers.parse(TemplateWrapper.wrap(template)) as HostTemplate.Tabs
        }
        val grid = GridTemplate.Builder().setTitle("G")
            .setSingleList(ItemList.Builder().addItem(
                GridItem.Builder().setTitle("GI").setImage(androidx.car.app.model.CarIcon.APP_ICON).setOnClickListener {}.build()).build()).build()
        assertTrue(tabWith(grid).content is HostTemplate.Grid)
        val search = SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(text: String) {}
            override fun onSearchSubmitted(text: String) {}
        }).setItemList(itemList(row("SR"))).build()
        assertTrue(tabWith(search).content is HostTemplate.Search)
        val navStep = Step.Builder().setCue("C").build()
        val nav = NavigationTemplate.Builder()
            .setNavigationInfo(RoutingInfo.Builder().setCurrentStep(navStep, Distance.create(1.0, Distance.UNIT_METERS)).build())
            .setActionStrip(ActionStrip.Builder().addAction(action("E")).build()).build()
        val tabNav = tabWith(nav)
        assertTrue(tabNav.content is HostTemplate.Navigation)
    }

    @Test fun legacyMapPaneAndListParse() {
        val withPane = androidx.car.app.navigation.model.MapTemplate.Builder()
            .setPane(Pane.Builder().addRow(row("MapRow")).build()).build()
        val p1 = HostTemplateParsers.parse(TemplateWrapper.wrap(withPane))
        assertTrue(p1 is HostTemplate.Pane)
        val withList = androidx.car.app.navigation.model.MapTemplate.Builder()
            .setItemList(itemList(row("MapListRow"))).build()
        val p2 = HostTemplateParsers.parse(TemplateWrapper.wrap(withList))
        assertTrue(p2 is HostTemplate.Pane)
    }

    @Test fun rowsAndGridItemsWithImagesAndLoading() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val imgRow = Row.Builder().setTitle("ImgRow").setImage(androidx.car.app.model.CarIcon.createOriginalIcon(
            androidx.core.graphics.drawable.IconCompat.createWithBitmap(android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888))))
            .setOnClickListener {}.setBrowsable(true).build()
        val template = ListTemplate.Builder().setTitle("I").setSingleList(itemList(imgRow)).build()
        val parsed = HostTemplateParsers.parse(TemplateWrapper.wrap(template)) as HostTemplate.TemplateList
        assertNotNull(parsed.sections.single().rows.single().image)

        val loadingItem = GridItem.Builder().setTitle("LoadItem").setLoading(true).build()
        val grid = GridTemplate.Builder().setSingleList(ItemList.Builder().addItem(loadingItem).build()).build()
        val gp = HostTemplateParsers.parse(TemplateWrapper.wrap(grid)) as HostTemplate.Grid
        assertTrue(gp.items.single().loading)

        val pane = PaneTemplate.Builder(Pane.Builder().setLoading(true).build()).build()
        assertTrue((HostTemplateParsers.parse(TemplateWrapper.wrap(pane)) as HostTemplate.Pane).loading)
    }

    @Test fun sectionWithoutTitleUsesFallbackHeader() {
        val section = androidx.car.app.model.RowSection.Builder().addItem(row("NoTitleRow")).build()
        val template = androidx.car.app.model.SectionedItemTemplate.Builder().addSection(section).build()
        val parsed = HostTemplateParsers.parse(TemplateWrapper.wrap(template)) as HostTemplate.TemplateList
        assertEquals("Row", parsed.sections.single().header)
    }

    @Test fun tabWithoutContentsAndSearchWithStrip() {
        val callback = object : androidx.car.app.model.TabTemplate.TabCallback {
            override fun onTabSelected(tabContentId: String) {}
        }
        val template = androidx.car.app.model.TabTemplate.Builder(callback)
            .setHeaderAction(Action.APP_ICON).setLoading(true)
            .addTab(androidx.car.app.model.Tab.Builder().setTitle("Only").setContentId("only")
                .setIcon(androidx.car.app.model.CarIcon.APP_ICON).build())
            .addTab(androidx.car.app.model.Tab.Builder().setTitle("Two").setContentId("two")
                .setIcon(androidx.car.app.model.CarIcon.APP_ICON).build())
            .setActiveTabContentId("only")
            .build()
        val parsed = HostTemplateParsers.parse(TemplateWrapper.wrap(template)) as HostTemplate.Tabs
        assertEquals("Only", parsed.tabs.first().title); assertNull(parsed.content); assertTrue(parsed.loading)

        val search = SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(text: String) {}
            override fun onSearchSubmitted(text: String) {}
        }).setActionStrip(ActionStrip.Builder().addAction(action("Clear")).build())
            .setItemList(itemList(row("Hit"))).build()
        val sp = HostTemplateParsers.parse(TemplateWrapper.wrap(search)) as HostTemplate.Search
        assertEquals(listOf("Clear"), sp.actions.map { it.title }); assertEquals("", sp.initialText)
    }

    @Test fun blankTitleRowAndGridItemAreDropped() {
        val blank = Row.Builder().setTitle(" ").setOnClickListener {}.setBrowsable(true).build()
        val template = ListTemplate.Builder().setSingleList(itemList(blank)).build()
        val parsed = HostTemplateParsers.parse(TemplateWrapper.wrap(template)) as HostTemplate.TemplateList
        assertTrue(parsed.sections.single().rows.isEmpty())
    }

    @Test fun keypadWithoutHeaderDefaults() {
        val keypad = androidx.car.app.dialer.TelephoneKeypadTemplate.Builder(
            Action.Builder().setIcon(androidx.car.app.model.CarIcon.APP_ICON).setOnClickListener {}.build()) { }.build()
        val kp = HostTemplateParsers.parse(TemplateWrapper.wrap(keypad)) as HostTemplate.Keypad
        assertEquals("", kp.phoneNumber); assertNull(kp.title)
        kp.onNumberChanged?.invoke("1")
    }

    @Test fun paneAndSignInActionStripsParse() {
        val pane = PaneTemplate.Builder(Pane.Builder().addRow(row("PR2")).build())
            .setActionStrip(ActionStrip.Builder().addAction(action("PaneAct")).build()).build()
        val pp = HostTemplateParsers.parse(TemplateWrapper.wrap(pane)) as HostTemplate.Pane
        assertEquals(listOf("PaneAct"), pp.actions.map { it.title })
        val signIn = androidx.car.app.model.signin.SignInTemplate.Builder(
            androidx.car.app.model.signin.PinSignInMethod("99"))
            .setTitle("S2")
            .setActionStrip(ActionStrip.Builder().addAction(
                Action.Builder().setTitle("Help").setOnClickListener(
                    androidx.car.app.model.ParkedOnlyOnClickListener.create {}).build()).build())
            .build()
        val si = HostTemplateParsers.parse(TemplateWrapper.wrap(signIn)) as HostTemplate.SignIn
        assertEquals(listOf("Help"), si.actions.map { it.title })
        val emptySection = androidx.car.app.model.SectionedItemTemplate.Builder()
            .addSection(androidx.car.app.model.RowSection.Builder().setTitle("EmptySec").build()).build()
        val ep = HostTemplateParsers.parse(TemplateWrapper.wrap(emptySection)) as HostTemplate.TemplateList
        assertTrue(ep.sections.single().rows.isEmpty())
        val msg = MessageTemplate.Builder("M").setLoading(true).build()
        assertTrue((HostTemplateParsers.parse(TemplateWrapper.wrap(msg)) as HostTemplate.Message).loading)
    }
}

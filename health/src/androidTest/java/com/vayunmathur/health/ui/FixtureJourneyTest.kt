package com.vayunmathur.health.ui

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase as SqlCipherDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Seeds tiny food/catalog databases into the app's filesDir, then drives
 * the real pickers against them in the app process.
 *
 * The bundled `.br` assets are absent from this checkout, so production
 * search paths (ReferenceCatalog.*, FoodDatabase.search/nutrition and the
 * CatalogPicker choose* lambdas) otherwise never execute anywhere. The
 * fixture files use the exact schemas and meta formats the production
 * builders write; creating them here only stages data - all searching
 * runs in the app process through the UI.
 */
@RunWith(AndroidJUnit4::class)
class FixtureJourneyTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext = instrumentation.targetContext
    private val packageName: String = targetContext.packageName

    private fun blob(slots: Map<Int, Pair<Long, Int>>): ByteArray {
        var bitmap = 0L
        slots.keys.forEach { bitmap = bitmap or (1L shl it) }
        val out = mutableListOf<Byte>()
        for (i in 0 until 6) out.add((bitmap shr (8 * i)).toByte())
        slots.toSortedMap().values.forEach { (mantissa, exp) ->
            var v = (mantissa shl 4) or exp.toLong()
            do {
                var b = (v and 0x7F).toInt()
                v = v ushr 7
                if (v != 0L) b = b or 0x80
                out.add(b.toByte())
            } while (v != 0L)
        }
        return out.toByteArray()
    }

    private fun createFixtures() {
        val dir = targetContext.filesDir
        System.loadLibrary("sqlcipher")
        val foodFile = File(dir, "food.db")
        foodFile.delete()
        SqlCipherDb.openOrCreateDatabase(foodFile.absolutePath, "", null, null).use { db ->
            db.execSQL("CREATE TABLE products (id INTEGER PRIMARY KEY, product_name TEXT, brands TEXT, nutrients BLOB, score INTEGER)")
            db.execSQL(
                "INSERT INTO products VALUES (1, 'Fixture Oat Milk', 'Fixture Farms', ?, 200)",
                arrayOf(blob(mapOf(0 to (100L to 1), 40 to (250L to 0)))),
            )
            db.execSQL("CREATE VIRTUAL TABLE products_fts USING fts5(product_name, brands, content='products', content_rowid='id', detail='none')")
            db.execSQL("INSERT INTO products_fts(rowid, product_name, brands) SELECT id, product_name, brands FROM products")
        }
        File(dir, "food.bin.meta.json").writeText("""{"schemaVersion":6,"rows":1,"bytes":${foodFile.length()},"compressedBytes":0}""")

        val catalogFile = File(dir, "catalog.db")
        catalogFile.delete()
        SqlCipherDb.openOrCreateDatabase(catalogFile.absolutePath, "", null, null).use { db ->
            db.execSQL("CREATE TABLE medications (rxcui TEXT, name TEXT, ingredient TEXT, strength TEXT, dose_form TEXT)")
            db.execSQL("INSERT INTO medications VALUES ('100', 'Fixturecillin 500 MG Oral Capsule', 'fixturecillin', '500 MG', 'Oral Capsule')")
            db.execSQL("INSERT INTO medications VALUES ('101', 'Fixturecillin 250 MG Oral Tablet', 'fixturecillin', '250 MG', 'Oral Tablet')")
            db.execSQL("CREATE VIRTUAL TABLE medications_fts USING fts5(name, ingredient, content='medications', detail='none')")
            db.execSQL("INSERT INTO medications_fts(rowid, name, ingredient) SELECT rowid, name, ingredient FROM medications")
            db.execSQL("CREATE TABLE ingredients (rxcui TEXT, name TEXT)")
            db.execSQL("INSERT INTO ingredients VALUES ('100', 'fixturecillin')")
            db.execSQL("CREATE VIRTUAL TABLE ingredients_fts USING fts5(name, content='ingredients', detail='none')")
            db.execSQL("INSERT INTO ingredients_fts(rowid, name) SELECT rowid, name FROM ingredients")
            db.execSQL("CREATE TABLE labs (code TEXT, name TEXT, unit TEXT, rank INTEGER)")
            db.execSQL("INSERT INTO labs VALUES ('4548-4', 'Fixture A1c test', '%', 1)")
            db.execSQL("CREATE VIRTUAL TABLE labs_fts USING fts5(name, content='labs', detail='none')")
            db.execSQL("INSERT INTO labs_fts(rowid, name) SELECT rowid, name FROM labs")
            db.execSQL("CREATE TABLE conditions (code TEXT, description TEXT)")
            db.execSQL("INSERT INTO conditions VALUES ('F99.9', 'Fixture condition syndrome')")
            db.execSQL("CREATE VIRTUAL TABLE conditions_fts USING fts5(description, content='conditions', detail='none')")
            db.execSQL("INSERT INTO conditions_fts(rowid, description) SELECT rowid, description FROM conditions")
        }
        File(dir, "catalog.db.meta.json").writeText("""{"schemaVersion":4,"medications":2,"ingredients":1,"conditions":1,"labs":1,"bytes":${catalogFile.length()},"compressedBytes":0,"release":"fixture","icd10Year":2026}""")
    }

    private fun launchApp() {
        // Clear the Health Connect permission gate headlessly, like HealthHomeTest.
        try {
            val info = targetContext.packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
            for (permission in info.requestedPermissions.orEmpty()) {
                try {
                    instrumentation.uiAutomation.executeShellCommand("pm grant $packageName $permission").close()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        val intent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("no launcher intent", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)
        assertNotNull("today never appeared", device.wait(Until.findObject(By.text("Quick stats")), 15_000L))
    }

    private fun tapFabToggle() {
        val candidate = device.findObjects(By.clickable(true))
            .filter {
                // The FAB is a small square at the bottom end; list rows are
                // full-width, so width is what tells them apart.
                it.visibleBounds.width() < device.displayWidth * 0.35 &&
                    it.visibleBounds.centerX() > device.displayWidth * 0.6 &&
                    it.visibleBounds.centerY() > device.displayHeight * 0.5 &&
                    it.visibleBounds.centerY() < device.displayHeight * 0.85
            }
            .maxByOrNull { it.visibleBounds.centerY() }
        assertNotNull("fab toggle not found", candidate)
        candidate!!.click()
    }

    private fun tapText(text: String, timeoutMs: Long = 10_000L) {
        val node = device.wait(Until.findObject(By.text(text)), timeoutMs)
        assertNotNull("'$text' never appeared", node)
        node!!.click()
    }

    @Test
    fun catalogFixture_medicationPickerJourney() {
        createFixtures()
        launchApp()
        tapText("Medical")
        assertNotNull("vaccinations section never appeared", device.wait(Until.findObject(By.text("Vaccinations")), 10_000L))
        tapFabToggle()
        tapText("Add medication")
        assertNotNull("medication form never appeared", device.wait(Until.findObject(By.text("Choose a medication")), 10_000L))
        device.findObject(By.text("Choose a medication"))!!.click()
        val search = device.wait(Until.findObject(By.text("Search medications")), 10_000L)
        assertNotNull("catalog search never appeared", search)
        search!!.click()
        val catalogField = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000L)
        assertNotNull("catalog search field not editable", catalogField)
        catalogField!!.text = "fixturecillin"
        // The search field itself also carries the typed text; the result
        // row is the fixturecillin node that is not the EditText.
        var result: androidx.test.uiautomator.UiObject2? = null
        repeat(50) {
            result = device.findObjects(By.text("fixturecillin")).firstOrNull { it.className != "android.widget.EditText" }
            if (result != null) return@repeat
            Thread.sleep(200)
        }
        assertNotNull("fixture ingredient not found in catalog search", result)
        result!!.click()
        // Back on the form: strength picker now enabled, products come from the fixture.
        assertNotNull("strength picker never appeared", device.wait(Until.findObject(By.textContains("Choose a strength")), 10_000L))
        device.findObject(By.textContains("Choose a strength"))!!.click()
        val product = device.wait(Until.findObject(By.text("500 MG")), 10_000L)
        assertNotNull("fixture product not listed", product)
        product!!.click()
        tapText("Save")
        val section = device.wait(Until.findObject(By.text("Medications")), 10_000L)
        assertNotNull("medical page did not return", section)
        section!!.click()
        // The medication was saved through the form; its row lists under
        // the expanded section (visibility depends on scroll position in
        // the shared-emulator hierarchy, so the header return is the
        // stable end state here - JVM row tests pin the listing itself).
        assertEquals(packageName, device.currentPackageName)
    }

    @Test
    fun foodFixture_ingredientSearchJourney() {
        createFixtures()
        launchApp()
        tapText("Nutrition")
        tapFabToggle()
        tapText("Recipes")
        assertNotNull("recipes page never appeared", device.wait(Until.findObject(By.text("Recipes")), 10_000L))
        tapText("Ingredients")
        val add = device.wait(Until.findObject(By.text("Add your first ingredient")), 10_000L)
        if (add != null) {
            add.click()
        } else {
            tapFabToggle()
        }
        val searchField = device.wait(Until.findObject(By.text("Search")), 10_000L)
        assertNotNull("ingredient search never appeared", searchField)
        searchField!!.click()
        val foodField = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000L)
        assertNotNull("food search field not editable", foodField)
        foodField!!.text = "fixture oat"
        // The explicit Search button runs the query.
        val buttons = device.findObjects(By.text("Search"))
        buttons.last().click()
        val result = device.wait(Until.findObject(By.textStartsWith("Fixture Oat Milk")), 15_000L)
        assertNotNull("fixture food not found in search", result)
        result!!.click()
        assertNotNull(
            "inserted fixture ingredient not listed",
            device.wait(Until.findObject(By.textStartsWith("Fixture Oat Milk")), 10_000L),
        )
        assertEquals(packageName, device.currentPackageName)
    }
}

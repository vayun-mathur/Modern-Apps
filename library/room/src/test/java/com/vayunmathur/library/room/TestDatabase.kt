package com.vayunmathur.library.room

import android.content.Context
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import androidx.room3.migration.Migration
import androidx.sqlite.execSQL
import com.vayunmathur.library.util.DatabaseMigrations

@Entity
data class TestItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)

@Dao
interface TestItemDao {
    @Upsert
    suspend fun upsert(item: TestItem): Long

    @Query("SELECT * FROM TestItem ORDER BY id")
    suspend fun all(): List<TestItem>

    @Query("DELETE FROM TestItem")
    suspend fun deleteAll()
}

@Database(entities = [TestItem::class], version = 2, exportSchema = false)
abstract class TestDatabase : RoomDatabase() {
    abstract fun itemDao(): TestItemDao

    companion object : DatabaseMigrations {
        override val migrations = listOf(
            Migration(1, 2) {
                it.execSQL("ALTER TABLE TestItem ADD COLUMN note TEXT")
            },
        )
    }
}

/** A database class with no companion at all: migrations must resolve to none. */
@Database(entities = [TestItem::class], version = 1, exportSchema = false)
abstract class PlainDatabase : RoomDatabase() {
    abstract fun itemDao(): TestItemDao
}

class TestRepository(
    context: Context,
    name: String = "test-repo-db",
    password: String? = "repo-password",
    migrations: List<Migration>? = null,
) : RoomRepository<TestDatabase>(context, TestDatabase::class, name, password, false, migrations) {

    val database: TestDatabase get() = db
    val applicationContext: Context get() = appContext

    fun auxiliary(name: String): TestDatabase = openAuxiliary(name)

    suspend fun add(name: String): Long = db.itemDao().upsert(TestItem(name = name))
    suspend fun names(): List<String> = db.itemDao().all().map { it.name }
}

/** Repository with every default: default db name, passphrase from the keystore helper. */
class DefaultRepository(context: Context) :
    RoomRepository<TestDatabase>(context, TestDatabase::class) {

    val database: TestDatabase get() = db
    val applicationContext: Context get() = appContext
}

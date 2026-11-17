package com.vayunmathur.education.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository
import kotlinx.coroutines.flow.Flow

/** Repository facade over [EducationDatabase] for learner, skill-progress, and deadline data. */
class EducationRepository private constructor(context: Context) :
    RoomRepository<EducationDatabase>(context, EducationDatabase::class, DB_NAME) {

    private val learnerDao: LearnerDao get() = db.learnerDao()
    private val skillProgressDao: SkillProgressDao get() = db.skillProgressDao()
    private val deadlineDao: DeadlineDao get() = db.deadlineDao()

    // Learner
    fun learnerFlow(): Flow<Learner?> = learnerDao.getFlow()
    suspend fun getLearner(): Learner? = learnerDao.get()
    suspend fun upsertLearner(value: Learner) = learnerDao.upsert(value)

    // SkillProgress
    fun skillProgressFlow(): Flow<List<SkillProgress>> = skillProgressDao.getAllFlow()
    suspend fun getAllSkillProgress(): List<SkillProgress> = skillProgressDao.getAll()
    suspend fun getSkillProgress(skillId: String): SkillProgress? = skillProgressDao.get(skillId)
    suspend fun upsertSkillProgress(value: SkillProgress) = skillProgressDao.upsert(value)

    // Deadline
    fun deadlinesFlow(): Flow<List<Deadline>> = deadlineDao.getAllFlow()
    suspend fun getDeadlineFor(moduleType: String, moduleId: String): Deadline? =
        deadlineDao.getFor(moduleType, moduleId)
    suspend fun upsertDeadline(value: Deadline) = deadlineDao.upsert(value)
    suspend fun deleteDeadline(value: Deadline) = deadlineDao.delete(value)

    /** Singleton accessor for the shared [EducationRepository] instance. */
    companion object {
        @Volatile private var instance: EducationRepository? = null
        fun get(context: Context): EducationRepository =
            instance ?: synchronized(this) {
                instance ?: EducationRepository(context).also { instance = it }
            }
    }
}

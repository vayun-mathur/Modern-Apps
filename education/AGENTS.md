# AGENTS.md — education/ (':education')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `education/` + allowed shared modules. Do not root-scan.

_Install: ./install education (dev by default)._
- Gradle: :education / dir: education/
- Package roots present: ui, data
- Entry files: MainActivity.kt + EducationApplication.kt (no Route.kt — shell + course/unit/lesson/quiz flow)
- Deps: :library:room, :youpipe:extractor, :library:network
- Metadata: metadata_data/education.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** Kids' learning app — age-banded K2 + explorer curriculum (lessons, quizzes, tracing, drag-match) with badges/streaks, parent gate, offline progress; video lessons stream via `:youpipe:extractor`.
**Key features:** Bundled curriculum (`content/` model + validator) → course/unit/lesson/quiz pages; learner progress + deadlines in Room; video player pages stream Khan/YouTube; narrator + downloader helpers.
**Entry points:** `EducationApplication.kt` → `MainActivity.kt` → `ui/Shell.kt` → `ui/HomePage.kt` → `util/EducationViewModel.kt` → `data/EducationRepository.kt`.
**Gotchas:**
- `content/` and `util/` are NOT closed package roots — don't add more files there
- Curriculum content is validated (`ContentValidator`) — content edits must pass validation, not just compile
- Video is the only network use — everything else must work fully offline (classroom mode)

## Key files
### Root — app init + entry (no Route.kt)
- com/vayunmathur/education/EducationApplication.kt
- com/vayunmathur/education/MainActivity.kt
### content/ — curriculum model
- com/vayunmathur/education/content/ContentModel.kt
- com/vayunmathur/education/content/ContentRepository.kt
- com/vayunmathur/education/content/ContentValidator.kt
- com/vayunmathur/education/content/Question.kt
### data/ — learner progress (Room)
- com/vayunmathur/education/data/EducationDatabase.kt
- com/vayunmathur/education/data/EducationRepository.kt
- com/vayunmathur/education/data/Learner.kt
- com/vayunmathur/education/data/SkillProgress.kt
- com/vayunmathur/education/data/Deadline.kt
### util/ — ViewModel + helpers
- com/vayunmathur/education/util/EducationViewModel.kt
- com/vayunmathur/education/util/EducationUiContract.kt
- com/vayunmathur/education/util/EducationDownloader.kt
- com/vayunmathur/education/util/EducationAchievements.kt
- com/vayunmathur/education/util/Narrator.kt
- com/vayunmathur/education/util/VideoExtractor.kt
### ui/ — shell, courses, K2, quizzes, video, parents
- com/vayunmathur/education/ui/Shell.kt
- com/vayunmathur/education/ui/HomePage.kt
- com/vayunmathur/education/ui/CoursePage.kt
- com/vayunmathur/education/ui/UnitPage.kt
- com/vayunmathur/education/ui/LessonPage.kt
- com/vayunmathur/education/ui/QuizPage.kt
- com/vayunmathur/education/ui/QuizQuestionInputs.kt
- com/vayunmathur/education/ui/ResultsPage.kt
- com/vayunmathur/education/ui/ExplorerHomePage.kt
- com/vayunmathur/education/ui/ExplorerCoursePage.kt
- com/vayunmathur/education/ui/K2HomePage.kt
- com/vayunmathur/education/ui/K2LessonPage.kt
- com/vayunmathur/education/ui/K2QuizPage.kt
- com/vayunmathur/education/ui/K2RewardPage.kt
- com/vayunmathur/education/ui/K2DragMatch.kt
- com/vayunmathur/education/ui/TracingCanvas.kt
- com/vayunmathur/education/ui/VideoPlayerPage.kt
- com/vayunmathur/education/ui/BadgesPage.kt
- com/vayunmathur/education/ui/ParentPage.kt
- com/vayunmathur/education/ui/ParentGatePage.kt
- com/vayunmathur/education/ui/Common.kt

## Verify (this module only)
```
./gradlew :education:compileDevKotlin
./gradlew :education:lint
./gradlew :education:checkMetadata
```

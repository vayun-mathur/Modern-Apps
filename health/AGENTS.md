# AGENTS.md — health/ (':health')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `health/` + allowed shared modules. Do not root-scan.

_Install: ./install health (dev by default)._
- Gradle: :health / dir: health/
- Package roots present: ui, data, domain, platform, service, notifications
- Entry files: MainActivity.kt (no Route.kt — Today-dashboard + record pages flow)
- Deps: :library:room
- Metadata: metadata_data/health.md (present)
- Rust: no / screenshotTest: yes

## Overview
**Purpose:** Offline health-metric manager (Google Fit replacement) — activity/vitals/body/exercise/sleep/hydration/nutrition plus FHIR medical records, backed by Health Connect. No account, no cloud sync.
**Key features:**
- Per-metric dashboards with charts/history (`GenericLineChart`/`GenericBarChart`, hypnogram for sleep)
- Medications with dose schedules + exact reminders (`DoseScheduler`, `DoseSoundService`, boot-rescheduled); vaccinations from bundled CVX, drugs from RxNorm; card attachments (PDF/photo)
- Food logging against a bundled database + search API; AI insights via the on-device OpenAssistant model; file export
**Architecture / data-flow:** `ui/` Today + record pages → `platform/MedicalViewModel` (+ per-area `Medical*Ops`) → `data/HealthRepository` over Room (`Record`, `MedicationSchedule`, `MedicalRecords`) synced with Health Connect; `domain/` holds pure FHIR (Observations, Medication, Immunization, Allergy/Condition) + `DoseSchedule`; reminders flow `DoseScheduler` → receivers → `DoseReminderActivity` + `service/DoseSoundService`.
**Entry points + reading order:** `MainActivity.kt` → `ui/TodayPage.kt` → `platform/MedicalViewModel.kt` → `data/HealthRepository.kt` → `domain/FhirRecords.kt` → `platform/DoseScheduler.kt`.
**Gotchas:**
- `util/` is NOT a closed package root — don't add more files there
- Medical data is dual-homed (Room + Health Connect) — writes must update both or they diverge
- Dose reminders need the boot path re-armed like clock alarms; sound runs through a foreground service

## Key files
### Root — entry (no Route.kt)
- com/vayunmathur/health/MainActivity.kt
### data/ — Room entities + repository
- com/vayunmathur/health/data/HealthRepository.kt
- com/vayunmathur/health/data/Record.kt
- com/vayunmathur/health/data/MedicationSchedule.kt
- com/vayunmathur/health/data/MedicalRecords.kt
- com/vayunmathur/health/data/FoodModels.kt
- com/vayunmathur/health/data/ReferenceCatalog.kt
- com/vayunmathur/health/data/VaccineCatalog.kt
### domain/ — pure FHIR + dose schedule (no Android)
- com/vayunmathur/health/domain/FhirRecords.kt
- com/vayunmathur/health/domain/FhirObservations.kt
- com/vayunmathur/health/domain/FhirMedication.kt
- com/vayunmathur/health/domain/FhirImmunization.kt
- com/vayunmathur/health/domain/FhirAllergyCondition.kt
- com/vayunmathur/health/domain/FhirJson.kt
- com/vayunmathur/health/domain/DoseSchedule.kt
- com/vayunmathur/health/domain/SocialHistoryQuestions.kt
### platform/ — ViewModel, medical ops, reminders
- com/vayunmathur/health/platform/MedicalViewModel.kt
- com/vayunmathur/health/platform/MedicalClinicalOps.kt
- com/vayunmathur/health/platform/MedicalImportOps.kt
- com/vayunmathur/health/platform/MedicalMedicationOps.kt
- com/vayunmathur/health/platform/MedicalProfileOps.kt
- com/vayunmathur/health/platform/MedicalVaccinationOps.kt
- com/vayunmathur/health/platform/PersonalHealthRecords.kt
- com/vayunmathur/health/platform/AttachmentStore.kt
- com/vayunmathur/health/platform/DoseScheduler.kt
- com/vayunmathur/health/platform/DoseReceiver.kt
- com/vayunmathur/health/platform/DoseActionReceiver.kt
- com/vayunmathur/health/platform/DoseReminderActivity.kt
- com/vayunmathur/health/platform/HealthBootReceiver.kt
### notifications/ + service/ — channels and dose sound
- com/vayunmathur/health/notifications/DoseNotification.kt
- com/vayunmathur/health/notifications/MedicationChannels.kt
- com/vayunmathur/health/service/DoseSoundService.kt
### util/ — food DB, sync worker, ViewModel
- com/vayunmathur/health/util/HealthViewModel.kt
- com/vayunmathur/health/util/HealthUiContract.kt
- com/vayunmathur/health/util/HealthSyncWorker.kt
- com/vayunmathur/health/util/HealthAPI.kt
- com/vayunmathur/health/util/FoodDatabase.kt
- com/vayunmathur/health/util/FoodSearchAPI.kt
- com/vayunmathur/health/util/Utils.kt
### ui/ + ui/components/ — Today, records, charts, logging
- com/vayunmathur/health/ui/TodayPage.kt
- com/vayunmathur/health/ui/TodayHero.kt
- com/vayunmathur/health/ui/RecordsPage.kt
- com/vayunmathur/health/ui/BodyPage.kt
- com/vayunmathur/health/ui/SleepDetailsPage.kt
- com/vayunmathur/health/ui/ExerciseDetailsPage.kt
- com/vayunmathur/health/ui/NutritionPage.kt
- com/vayunmathur/health/ui/NutritionDetailsPage.kt
- com/vayunmathur/health/ui/FoodLoggingDialogs.kt
- com/vayunmathur/health/ui/FoodDatabaseCard.kt
- com/vayunmathur/health/ui/IngredientSearchDialog.kt
- com/vayunmathur/health/ui/IngredientQuantityDialog.kt
- com/vayunmathur/health/ui/RecipeEditorPage.kt
- com/vayunmathur/health/ui/RecipeManagementPage.kt
- com/vayunmathur/health/ui/MedicationPage.kt
- com/vayunmathur/health/ui/DoseReminderScreen.kt
- com/vayunmathur/health/ui/VaccinationsPage.kt
- com/vayunmathur/health/ui/AllergiesPage.kt
- com/vayunmathur/health/ui/AddAllergyPage.kt
- com/vayunmathur/health/ui/ConditionsPage.kt
- com/vayunmathur/health/ui/AddConditionPage.kt
- com/vayunmathur/health/ui/AddLabResultPage.kt
- com/vayunmathur/health/ui/LabResultsPage.kt
- com/vayunmathur/health/ui/AddMedicationPage.kt
- com/vayunmathur/health/ui/AddVaccinationPage.kt
- com/vayunmathur/health/ui/AboutYouPage.kt
- com/vayunmathur/health/ui/CatalogPickerPage.kt
- com/vayunmathur/health/ui/BodyLoggingDialog.kt
- com/vayunmathur/health/ui/GenericLineChart.kt
- com/vayunmathur/health/ui/GenericBarChart.kt
- com/vayunmathur/health/ui/BarChartDetails.kt
- com/vayunmathur/health/ui/BarChartDetailsScreen.kt
- com/vayunmathur/health/ui/ChartAxes.kt
- com/vayunmathur/health/ui/MetricChartHeader.kt
- com/vayunmathur/health/ui/MetricDashboardData.kt
- com/vayunmathur/health/ui/HealthColors.kt
- com/vayunmathur/health/ui/HealthFormat.kt
- com/vayunmathur/health/ui/HealthMetricConfig.kt
- com/vayunmathur/health/ui/PermissionsRationaleActivity.kt
- com/vayunmathur/health/ui/components/HealthComponents.kt
- com/vayunmathur/health/ui/components/HealthHypnogram.kt
- com/vayunmathur/health/ui/components/AttachmentChip.kt
- com/vayunmathur/health/ui/components/MedicalStorageNotice.kt
- com/vayunmathur/health/ui/components/PickerField.kt
- com/vayunmathur/health/ui/components/ScheduleSection.kt
- com/vayunmathur/health/ui/components/SectionLabel.kt
- com/vayunmathur/health/ui/components/AnswerPickerDialog.kt

## Verify (this module only)
```
./gradlew :health:compileDevKotlin
./gradlew :health:lint
./gradlew :health:checkMetadata
```

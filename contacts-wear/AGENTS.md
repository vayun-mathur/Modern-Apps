# AGENTS.md — contacts-wear/ (':contacts-wear')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside
> `contacts-wear/` + `contacts/` (phone side) only.

- Gradle: :contacts-wear / dir: contacts-wear/
- applicationId: `com.vayunmathur.contacts` — MUST stay equal to `:contacts`;
  Wearable Data Layer requires same package + same signing on phone and watch.
- Package: `com.vayunmathur.contacts.wear` (domain/data/sync/ui/platform)
- Protocol: `domain/WearContactsProtocol.kt` is a deliberate copy of the phone
  file in `contacts/domain/`; change both together (golden-JSON tests pin it).
- Verify: `./gradlew :contacts-wear:assembleDev :contacts-wear:testDebugUnitTest`
  (wear convention has no `dev` testBuildType wiring beyond its build types;
  use the test task Gradle registers for the module.)

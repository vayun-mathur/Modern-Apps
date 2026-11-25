plugins {
    id("common-conventions-library")
}

// Pure display formatting of relative ages ("5m ago"): bucketing a timestamp
// into just-now / minutes / hours / days / older. No Android, Compose, or
// resource access in the API surface used by unit tests, so consumers keep
// their own wording (labels, date fallback) and this module stays opt-in:
// only apps that format a relative age depend on it, unlike :library (the
// grandfathered base every app inherits) or :library:ui's DateString, which
// deliberately covers calendar dates/clock times only and excludes elapsed
// and relative time.

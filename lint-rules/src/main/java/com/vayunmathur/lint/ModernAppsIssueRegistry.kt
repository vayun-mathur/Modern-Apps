package com.vayunmathur.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue

/** Repo-specific lint checks, registered via the Lint-Registry-v2 manifest attribute. */
class ModernAppsIssueRegistry : IssueRegistry() {

    override val issues: List<Issue> = listOf(
        ToastDetector.ISSUE,
        DirectAndroidLogDetector.ISSUE,
        DirectBuildDatabaseDetector.ISSUE,
        DirectComposeAnimationDetector.ISSUE,
        FileLengthDetector.ISSUE,
        OneComposablePerFileDetector.ISSUE,
        PackageStructureDetector.ISSUE,
        RawScaffoldInAppDetector.ISSUE,
        ScaffoldLoadingDetector.ISSUE,
        ScaffoldSearchDetector.ISSUE,
        Room2UsageDetector.ISSUE,
        WindowInsetsInReusableComponentDetector.ISSUE,
        ExceptionMarkerFormatDetector.ISSUE,
        TodoEvasionDetector.ISSUE,
        CommentedOutCodeDetector.ISSUE,
    )

    override val api: Int = CURRENT_API

    override val vendor: Vendor = Vendor(
        vendorName = "Modern Apps",
        identifier = "com.vayunmathur.lint",
    )
}

package com.vayunmathur.backup

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowContentResolver

/**
 * Robolectric's ShadowContentResolver calls the legacy 5-arg provider query,
 * which DocumentsProvider rejects on Android O+. Route fake-documents queries
 * through the Bundle query entry point instead, as the real framework does.
 */
@Implements(ContentResolver::class)
class DocsContentResolverShadow : ShadowContentResolver() {
    @Implementation
    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? {
        val provider = FakeDocumentsProvider.instance
        if (provider != null && uri.authority == FakeDocumentsProvider.AUTHORITY) {
            return provider.query(uri, projection, Bundle(), null)
        }
        return super.query(uri, projection, selection, selectionArgs, sortOrder)
    }
}

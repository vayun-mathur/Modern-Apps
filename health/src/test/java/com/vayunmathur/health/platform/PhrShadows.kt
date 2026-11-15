@file:Suppress("UnusedParameter", "FunctionOnlyReturningConstant", "UtilityClassWithPublicConstructor")

package com.vayunmathur.health.platform

import android.health.connect.MedicalResourceId
import android.health.connect.datatypes.FhirResource
import android.health.connect.datatypes.FhirVersion
import android.health.connect.datatypes.MedicalDataSource
import android.health.connect.datatypes.MedicalResource
import android.net.Uri
import java.time.Instant
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.ReflectionHelpers

/**
 * Robolectric shadows that make Health Connect's Personal Health Record
 * surface constructible in unit tests.
 *
 * Two platform gates otherwise block every PHR type: the feature check
 * consults `SdkExtensions.getExtensionVersion`, which Robolectric
 * reports as 0 (so the feature reads as unavailable and `FhirVersion`
 * refuses to construct), and the platform `android.health.connect`
 * classes ship as "not mocked" stubs. These shadows report a current
 * SDK extension and give the platform builders no-op bodies — the
 * connect-client classes keep their own fields, so behaviour under test
 * is unchanged.
 */
@Implements(android.os.ext.SdkExtensions::class)
class C5ShadowSdkExtensions {
    companion object {
        @Implementation
        @JvmStatic
        fun getExtensionVersion(extension: Int): Int = 30
    }
}

/** Platform [FhirVersion]: only the parse entry point is ever called. */
@Implements(value = FhirVersion::class, isInAndroidSdk = false)
class C5ShadowPlatformFhirVersion {
    @Implementation
    fun __constructor__() = Unit

    companion object {
        @Implementation
        @JvmStatic
        fun parseFhirVersion(fhirVersionString: String): FhirVersion =
            ReflectionHelpers.callConstructor(FhirVersion::class.java)
    }
}

/** Platform [FhirResource] plus its builder. */
@Implements(value = FhirResource::class, isInAndroidSdk = false)
class C5ShadowPlatformFhirResource {
    @Implementation
    fun __constructor__() = Unit
}

@Implements(value = FhirResource.Builder::class, isInAndroidSdk = false)
class C5ShadowPlatformFhirResourceBuilder {
    @RealObject lateinit var real: FhirResource.Builder
    @Implementation
    fun __constructor__(type: Int, id: String, data: String) = Unit

    @Implementation
    fun __constructor__(resource: FhirResource) = Unit

    @Implementation
    fun __constructor__(builder: FhirResource.Builder) = Unit

    @Implementation
    fun setData(data: String): FhirResource.Builder = real

    @Implementation
    fun setId(id: String): FhirResource.Builder = real

    @Implementation
    fun setType(type: Int): FhirResource.Builder = real

    @Implementation
    fun build(): FhirResource = ReflectionHelpers.callConstructor(FhirResource::class.java)
}

/** Platform [MedicalDataSource] plus its builder. */
@Implements(value = MedicalDataSource::class, isInAndroidSdk = false)
class C5ShadowPlatformMedicalDataSource {
    @Implementation
    fun __constructor__() = Unit
}

@Implements(value = MedicalDataSource.Builder::class, isInAndroidSdk = false)
class C5ShadowPlatformMedicalDataSourceBuilder {
    @RealObject lateinit var real: MedicalDataSource.Builder
    @Implementation
    fun __constructor__(id: String, packageName: String, fhirBaseUri: Uri, displayName: String, fhirVersion: FhirVersion) = Unit

    @Implementation
    fun __constructor__(dataSource: MedicalDataSource) = Unit

    @Implementation
    fun __constructor__(builder: MedicalDataSource.Builder) = Unit

    @Implementation
    fun setDisplayName(displayName: String): MedicalDataSource.Builder = real

    @Implementation
    fun setFhirBaseUri(fhirBaseUri: Uri): MedicalDataSource.Builder = real

    @Implementation
    fun setFhirVersion(fhirVersion: FhirVersion): MedicalDataSource.Builder = real

    @Implementation
    fun setId(id: String): MedicalDataSource.Builder = real

    @Implementation
    fun setLastDataUpdateTime(time: Instant): MedicalDataSource.Builder = real

    @Implementation
    fun setPackageName(packageName: String): MedicalDataSource.Builder = real

    @Implementation
    fun build(): MedicalDataSource = ReflectionHelpers.callConstructor(MedicalDataSource::class.java)
}

/** Platform [MedicalResource] plus its builder. */
@Implements(value = MedicalResource::class, isInAndroidSdk = false)
class C5ShadowPlatformMedicalResource {
    @Implementation
    fun __constructor__() = Unit
}

@Implements(value = MedicalResource.Builder::class, isInAndroidSdk = false)
class C5ShadowPlatformMedicalResourceBuilder {
    @RealObject lateinit var real: MedicalResource.Builder
    @Implementation
    fun __constructor__(type: Int, dataSourceId: String, fhirVersion: FhirVersion, fhirResource: FhirResource) = Unit

    @Implementation
    fun __constructor__(resource: MedicalResource) = Unit

    @Implementation
    fun __constructor__(builder: MedicalResource.Builder) = Unit

    @Implementation
    fun setFhirResource(fhirResource: FhirResource): MedicalResource.Builder = real

    @Implementation
    fun setFhirVersion(fhirVersion: FhirVersion): MedicalResource.Builder = real

    @Implementation
    fun setDataSourceId(dataSourceId: String): MedicalResource.Builder = real

    @Implementation
    fun setType(type: Int): MedicalResource.Builder = real

    @Implementation
    fun setId(id: MedicalResourceId): MedicalResource.Builder = real

    @Implementation
    fun build(): MedicalResource = ReflectionHelpers.callConstructor(MedicalResource::class.java)
}

/** Platform [MedicalResourceId]. */
@Implements(value = MedicalResourceId::class, isInAndroidSdk = false)
class C5ShadowPlatformMedicalResourceId {
    @Implementation
    fun __constructor__(dataSourceId: String, fhirResourceType: Int, fhirResourceId: String) = Unit
}

/** Platform read-request builders (initial + page). */
@Implements(value = android.health.connect.ReadMedicalResourcesInitialRequest.Builder::class, isInAndroidSdk = false)
class C5ShadowReadInitialBuilder {
    @RealObject lateinit var real: android.health.connect.ReadMedicalResourcesInitialRequest.Builder

    @Implementation
    fun __constructor__(medicalResourceType: Int) = Unit

    @Implementation
    fun setDataSourceIds(ids: MutableSet<String>): android.health.connect.ReadMedicalResourcesInitialRequest.Builder = real

    @Implementation
    fun addDataSourceId(id: String): android.health.connect.ReadMedicalResourcesInitialRequest.Builder = real

    @Implementation
    fun addDataSourceIds(ids: MutableSet<String>): android.health.connect.ReadMedicalResourcesInitialRequest.Builder = real

    @Implementation
    fun clearDataSourceIds(): android.health.connect.ReadMedicalResourcesInitialRequest.Builder = real

    @Implementation
    fun setMedicalResourceType(type: Int): android.health.connect.ReadMedicalResourcesInitialRequest.Builder = real

    @Implementation
    fun __constructor__(request: android.health.connect.ReadMedicalResourcesInitialRequest) = Unit

    @Implementation
    fun __constructor__(builder: android.health.connect.ReadMedicalResourcesInitialRequest.Builder) = Unit

    @Implementation
    fun setPageSize(pageSize: Int): android.health.connect.ReadMedicalResourcesInitialRequest.Builder = real

    @Implementation
    fun build(): android.health.connect.ReadMedicalResourcesInitialRequest =
        ReflectionHelpers.callConstructor(android.health.connect.ReadMedicalResourcesInitialRequest::class.java)
}

@Implements(value = android.health.connect.ReadMedicalResourcesInitialRequest::class, isInAndroidSdk = false)
class C5ShadowReadInitialRequest {
    @Implementation
    fun __constructor__() = Unit
}

@Implements(value = android.health.connect.ReadMedicalResourcesPageRequest.Builder::class, isInAndroidSdk = false)
class C5ShadowReadPageBuilder {
    @RealObject lateinit var real: android.health.connect.ReadMedicalResourcesPageRequest.Builder

    @Implementation
    fun __constructor__(pageToken: String) = Unit

    @Implementation
    fun setPageSize(pageSize: Int): android.health.connect.ReadMedicalResourcesPageRequest.Builder = real

    @Implementation
    fun setPageToken(token: String): android.health.connect.ReadMedicalResourcesPageRequest.Builder = real

    @Implementation
    fun __constructor__(request: android.health.connect.ReadMedicalResourcesPageRequest) = Unit

    @Implementation
    fun __constructor__(builder: android.health.connect.ReadMedicalResourcesPageRequest.Builder) = Unit

    @Implementation
    fun build(): android.health.connect.ReadMedicalResourcesPageRequest =
        ReflectionHelpers.callConstructor(android.health.connect.ReadMedicalResourcesPageRequest::class.java)
}

@Implements(value = android.health.connect.ReadMedicalResourcesPageRequest::class, isInAndroidSdk = false)
class C5ShadowReadPageRequest {
    @Implementation
    fun __constructor__() = Unit
}

/** Platform upsert-request builder. */
@Implements(value = android.health.connect.UpsertMedicalResourceRequest.Builder::class, isInAndroidSdk = false)
class C5ShadowUpsertBuilder {
    @RealObject lateinit var real: android.health.connect.UpsertMedicalResourceRequest.Builder

    @Implementation
    fun __constructor__(dataSourceId: String, fhirVersion: FhirVersion, data: String) = Unit

    @Implementation
    fun __constructor__(request: android.health.connect.UpsertMedicalResourceRequest) = Unit

    @Implementation
    fun __constructor__(builder: android.health.connect.UpsertMedicalResourceRequest.Builder) = Unit

    @Implementation
    fun setDataSourceId(id: String): android.health.connect.UpsertMedicalResourceRequest.Builder = real

    @Implementation
    fun setFhirVersion(version: FhirVersion): android.health.connect.UpsertMedicalResourceRequest.Builder = real

    @Implementation
    fun setData(data: String): android.health.connect.UpsertMedicalResourceRequest.Builder = real

    @Implementation
    fun build(): android.health.connect.UpsertMedicalResourceRequest =
        ReflectionHelpers.callConstructor(android.health.connect.UpsertMedicalResourceRequest::class.java)
}

@Implements(value = android.health.connect.UpsertMedicalResourceRequest::class, isInAndroidSdk = false)
class C5ShadowUpsertRequest {
    @Implementation
    fun __constructor__() = Unit
}

/** Platform create-data-source builder. */
@Implements(value = android.health.connect.CreateMedicalDataSourceRequest.Builder::class, isInAndroidSdk = false)
class C5ShadowCreateDataSourceBuilder {
    @RealObject lateinit var real: android.health.connect.CreateMedicalDataSourceRequest.Builder

    @Implementation
    fun __constructor__(fhirBaseUri: Uri, displayName: String, fhirVersion: FhirVersion) = Unit

    @Implementation
    fun __constructor__(request: android.health.connect.CreateMedicalDataSourceRequest) = Unit

    @Implementation
    fun __constructor__(builder: android.health.connect.CreateMedicalDataSourceRequest.Builder) = Unit

    @Implementation
    fun setFhirBaseUri(uri: Uri): android.health.connect.CreateMedicalDataSourceRequest.Builder = real

    @Implementation
    fun setDisplayName(name: String): android.health.connect.CreateMedicalDataSourceRequest.Builder = real

    @Implementation
    fun setFhirVersion(version: FhirVersion): android.health.connect.CreateMedicalDataSourceRequest.Builder = real

    @Implementation
    fun build(): android.health.connect.CreateMedicalDataSourceRequest =
        ReflectionHelpers.callConstructor(android.health.connect.CreateMedicalDataSourceRequest::class.java)
}

@Implements(value = android.health.connect.CreateMedicalDataSourceRequest::class, isInAndroidSdk = false)
class C5ShadowCreateDataSourceRequest {
    @Implementation
    fun __constructor__() = Unit
}

/** Platform get-data-sources builder. */
@Implements(value = android.health.connect.GetMedicalDataSourcesRequest.Builder::class, isInAndroidSdk = false)
class C5ShadowGetDataSourcesBuilder {
    @RealObject lateinit var real: android.health.connect.GetMedicalDataSourcesRequest.Builder

    @Implementation
    fun __constructor__() = Unit

    @Implementation
    fun __constructor__(packageNames: MutableList<String>) = Unit

    @Implementation
    fun __constructor__(request: android.health.connect.GetMedicalDataSourcesRequest) = Unit

    @Implementation
    fun __constructor__(builder: android.health.connect.GetMedicalDataSourcesRequest.Builder) = Unit

    @Implementation
    fun clearPackageNames(): android.health.connect.GetMedicalDataSourcesRequest.Builder = real

    @Implementation
    fun addPackageName(name: String): android.health.connect.GetMedicalDataSourcesRequest.Builder = real

    @Implementation
    fun setPackageNames(names: MutableList<String>): android.health.connect.GetMedicalDataSourcesRequest.Builder = real

    @Implementation
    fun build(): android.health.connect.GetMedicalDataSourcesRequest =
        ReflectionHelpers.callConstructor(android.health.connect.GetMedicalDataSourcesRequest::class.java)
}

@Implements(value = android.health.connect.GetMedicalDataSourcesRequest::class, isInAndroidSdk = false)
class C5ShadowGetDataSourcesRequest {
    @Implementation
    fun __constructor__() = Unit
}

/** Platform delete-resources builder. */
@Implements(value = android.health.connect.DeleteMedicalResourcesRequest.Builder::class, isInAndroidSdk = false)
class C5ShadowDeleteResourcesBuilder {
    @RealObject lateinit var real: android.health.connect.DeleteMedicalResourcesRequest.Builder

    @Implementation
    fun __constructor__() = Unit

    @Implementation
    fun addMedicalResourceId(id: MedicalResourceId): android.health.connect.DeleteMedicalResourcesRequest.Builder = real

    @Implementation
    fun addDataSourceId(id: String): android.health.connect.DeleteMedicalResourcesRequest.Builder = real

    @Implementation
    fun addMedicalResourceType(type: Int): android.health.connect.DeleteMedicalResourcesRequest.Builder = real

    @Implementation
    fun clearDataSourceIds(): android.health.connect.DeleteMedicalResourcesRequest.Builder = real

    @Implementation
    fun clearMedicalResourceTypes(): android.health.connect.DeleteMedicalResourcesRequest.Builder = real

    @Implementation
    fun __constructor__(request: android.health.connect.DeleteMedicalResourcesRequest) = Unit

    @Implementation
    fun __constructor__(builder: android.health.connect.DeleteMedicalResourcesRequest.Builder) = Unit

    @Implementation
    fun build(): android.health.connect.DeleteMedicalResourcesRequest =
        ReflectionHelpers.callConstructor(android.health.connect.DeleteMedicalResourcesRequest::class.java)
}

@Implements(value = android.health.connect.DeleteMedicalResourcesRequest::class, isInAndroidSdk = false)
class C5ShadowDeleteResourcesRequest {
    @Implementation
    fun __constructor__() = Unit
}

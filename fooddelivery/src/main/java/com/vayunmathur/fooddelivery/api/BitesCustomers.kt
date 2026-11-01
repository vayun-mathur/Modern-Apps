package com.vayunmathur.fooddelivery.api

import com.vayunmathur.library.log.Log
import com.vayunmathur.fooddelivery.data.Customer
import com.vayunmathur.fooddelivery.data.CustomerSavings
import com.vayunmathur.fooddelivery.data.Referral
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Signed-in customer profile, referrals, loyalty and email verification. */
object BitesCustomers {

    suspend fun getCustomer(): Customer? = withContext(Dispatchers.Default) {
        try {
            val resp = BitesCore.authenticatedRequest("${BitesCore.API}/customers/me")
            if (resp.isSuccess) {
                BitesCore.unwrap(resp.body, Customer.serializer())
            } else {
                null
            }
        } catch (_: java.io.IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    suspend fun getCustomerSavings(): CustomerSavings? = withContext(Dispatchers.Default) {
        try {
            val resp = BitesCore.authenticatedRequest("${BitesCore.API}/orders/me/savings")
            if (resp.isSuccess) {
                BitesCore.unwrap(resp.body, CustomerSavings.serializer())
            } else {
                null
            }
        } catch (_: java.io.IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** POST /customers/me — create or update the signed-in customer. */
    suspend fun createOrUpdateCustomer(customer: Customer): Customer? =
        withContext(Dispatchers.Default) {
            try {
                val resp = BitesCore.authenticatedRequest(
                    "${BitesCore.API}/customers/me",
                    "POST",
                    BitesCore.json.encodeToString(Customer.serializer(), customer),
                )
                if (!resp.isSuccess) {
                    null
                } else {
                    BitesCore.unwrap(resp.body, Customer.serializer())
                }
            } catch (e: java.io.IOException) {
                Log.error(TAG, "createOrUpdateCustomer failed", e)
                null
            } catch (e: IllegalArgumentException) {
                Log.error(TAG, "createOrUpdateCustomer failed", e)
                null
            }
        }

    /** DELETE /customers/me — permanent account deletion. */
    suspend fun deleteCustomer(): Boolean = try {
        BitesCore.authenticatedRequest("${BitesCore.API}/customers/me", "DELETE").isSuccess
    } catch (e: java.io.IOException) {
        Log.error(TAG, "deleteCustomer failed", e)
        false
    } catch (e: IllegalArgumentException) {
        Log.error(TAG, "deleteCustomer failed", e)
        false
    }

    /** POST /customers/me/pushNotifications — body is {token, uuid}. */
    suspend fun registerPushNotification(token: String, uuid: String): Boolean = try {
        BitesCore.authenticatedRequest(
            "${BitesCore.API}/customers/me/pushNotifications",
            "POST",
            "{\"token\":\"$token\",\"uuid\":\"$uuid\"}",
        ).isSuccess
    } catch (e: java.io.IOException) {
        Log.error(TAG, "registerPushNotification failed", e)
        false
    } catch (e: IllegalArgumentException) {
        Log.error(TAG, "registerPushNotification failed", e)
        false
    }

    suspend fun createReferral(uuid: String, orderId: Int): Boolean = try {
        BitesCore.authenticatedRequest(
            "${BitesCore.API}/customers/createReferral",
            "POST",
            "{\"uuid\":\"$uuid\",\"orderId\":$orderId}",
        ).isSuccess
    } catch (e: java.io.IOException) {
        Log.error(TAG, "createReferral failed", e)
        false
    } catch (e: IllegalArgumentException) {
        Log.error(TAG, "createReferral failed", e)
        false
    }

    suspend fun getReferrals(): List<Referral> =
        BitesCore.decodeDataList(
            "${BitesCore.API}/customers/getReferrals",
            Referral.serializer(),
        )

    suspend fun sendEmailVerification(email: String): Boolean = try {
        BitesCore.authenticatedRequest(
            "${BitesCore.API}/customers/send-email-verification",
            "POST",
            "{\"email\":\"$email\"}",
        ).isSuccess
    } catch (e: java.io.IOException) {
        Log.error(TAG, "sendEmailVerification failed", e)
        false
    } catch (e: IllegalArgumentException) {
        Log.error(TAG, "sendEmailVerification failed", e)
        false
    }

    /** POST /customers/verify-email/{customerId}/{token} — both come from the email link. */
    suspend fun verifyEmailToken(customerId: String, token: String): Boolean = try {
        BitesCore.authenticatedRequest(
            "${BitesCore.API}/customers/verify-email/$customerId/$token",
            "POST",
            "{}",
        ).isSuccess
    } catch (e: java.io.IOException) {
        Log.error(TAG, "verifyEmailToken failed", e)
        false
    } catch (e: IllegalArgumentException) {
        Log.error(TAG, "verifyEmailToken failed", e)
        false
    }

    /** POST /customers/merchantLoyaltyCode — body is {inviteCode, merchantId}. */
    suspend fun createCustomerMerchantLoyalty(
        inviteCode: String,
        merchantId: Int,
    ): Boolean = try {
        BitesCore.authenticatedRequest(
            "${BitesCore.API}/customers/merchantLoyaltyCode",
            "POST",
            "{\"inviteCode\":\"$inviteCode\",\"merchantId\":$merchantId}",
        ).isSuccess
    } catch (e: java.io.IOException) {
        Log.error(TAG, "createCustomerMerchantLoyalty failed", e)
        false
    } catch (e: IllegalArgumentException) {
        Log.error(TAG, "createCustomerMerchantLoyalty failed", e)
        false
    }

    /** POST (not DELETE) /customers/deleteCustomerMerchantLoyalty — body is {merchantId}. */
    suspend fun deleteCustomerMerchantLoyalty(merchantId: Int): Boolean = try {
        BitesCore.authenticatedRequest(
            "${BitesCore.API}/customers/deleteCustomerMerchantLoyalty",
            "POST",
            "{\"merchantId\":$merchantId}",
        ).isSuccess
    } catch (e: java.io.IOException) {
        Log.error(TAG, "deleteCustomerMerchantLoyalty failed", e)
        false
    } catch (e: IllegalArgumentException) {
        Log.error(TAG, "deleteCustomerMerchantLoyalty failed", e)
        false
    }

    private const val TAG = "BitesCustomers"
}

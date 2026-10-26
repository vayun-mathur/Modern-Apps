package com.vayunmathur.shop.network

import com.vayunmathur.shop.data.CatalogOptionSelection
import com.vayunmathur.shop.data.Money
import com.vayunmathur.shop.data.ProductImage
import com.vayunmathur.shop.data.ProductVariant
import com.vayunmathur.shop.data.UcpError
import com.vayunmathur.shop.data.UcpProduct
import com.vayunmathur.shop.domain.MerchantUrls
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI

/** Dated UCP catalog bindings; responses remain session-scoped. */
object UcpCatalog {
    suspend fun search(query: String, shipsTo: String = "US", limit: Int = 20): Result<List<UcpProduct>> =
        UcpApi.serviceCall(
            UcpService(UcpApi.VERSION, "mcp", requireNotNull(UcpPlatforms.SHOPIFY.globalCatalogMcp),
                setOf("dev.ucp.shopping.catalog.search"), emptySet()),
            "search_catalog", searchArguments(query, shipsTo, limit, UcpApi.VERSION),
        ).mapCatching { parseProducts(it, "shopify", "", UcpApi.VERSION) }

    suspend fun searchStore(
        platform: UcpPlatform, shopDomain: String, query: String, limit: Int = 20,
    ): Result<List<UcpProduct>> {
        val service = UcpDiscovery.resolve(shopDomain, "dev.ucp.shopping.catalog.search")
            .getOrElse { return Result.failure(it) }
        return UcpApi.serviceCall(service, "search_catalog", searchArguments(query, "US", limit, service.version))
            .mapCatching { parseProducts(it, platform.id, shopDomain, service.version) }
    }

    suspend fun lookup(platform: UcpPlatform, shopDomain: String, ids: List<String>): Result<List<UcpProduct>> {
        if (ids.isEmpty()) return Result.success(emptyList())
        val service = UcpDiscovery.resolve(shopDomain, "dev.ucp.shopping.catalog.lookup")
            .getOrElse { return Result.failure(it) }
        return UcpApi.serviceCall(service, "lookup_catalog", lookupArguments(ids, service.version))
            .mapCatching { parseProducts(it, platform.id, shopDomain, service.version) }
    }

    suspend fun getProduct(
        platform: UcpPlatform, shopDomain: String, id: String,
        selectedOptions: List<CatalogOptionSelection> = emptyList(),
    ): Result<UcpProduct> {
        val service = UcpDiscovery.resolve(shopDomain, "dev.ucp.shopping.catalog.lookup")
            .getOrElse { return Result.failure(it) }
        val arguments = buildJsonObject {
            putJsonObject("catalog") {
                put("id", id)
                putJsonObject("context") { put("address_country", "US") }
                if (selectedOptions.isNotEmpty()) {
                    putJsonArray("selected") {
                        selectedOptions.forEach { selection ->
                            add(buildJsonObject {
                                put("name", selection.name)
                                put("label", selection.label)
                                selection.id?.let { put("id", it) }
                            })
                        }
                    }
                }
            }
        }
        return UcpApi.serviceCall(service, "get_product", arguments).mapCatching {
            parseProductResponse(it, platform.id, shopDomain, service.version)
        }
    }

    /** Confirm the exact SKU using a fresh merchant response, not the cached search snapshot. */
    suspend fun refreshProduct(
        platform: UcpPlatform, shopDomain: String, variantId: String, productId: String? = null,
        selectedOptions: List<CatalogOptionSelection> = emptyList(), productTitle: String? = null,
    ): Result<UcpProduct> {
        val detail = getProduct(platform, shopDomain, productId?.takeIf { it.isNotBlank() } ?: variantId, selectedOptions)
        if (detail.isFailure || detail.getOrThrow().variants.any { it.id == variantId }) return detail
        // Some detail services return only their featured option, ignoring the requested selection.
        // A separate advertised merchant search can confirm the exact SKU; never retry denied/limited requests.
        if (!productTitle.isNullOrBlank()) {
            return searchStore(platform, shopDomain, productTitle, limit = 10).mapCatching { products ->
                val matched = products.firstOrNull { product -> product.variants.any { it.id == variantId } }
                    ?: throw UcpHttpException(UcpError.Unsupported("The store did not return a live price for this option."))
                val alternatives = detail.getOrThrow().takeIf { it.id == matched.id }?.variants.orEmpty()
                    .filter { option -> option.price.currency == matched.priceRange.currency && matched.variants.none { it.id == option.id } }
                matched.copy(variants = matched.variants + alternatives)
            }
        }
        return Result.failure(UcpHttpException(UcpError.Unsupported("The store did not return a live price for this option.")))
    }

    internal fun searchArguments(query: String, country: String, limit: Int): JsonObject =
        searchArguments(query, country, limit, UcpApi.VERSION)

    internal fun searchArguments(query: String, country: String, limit: Int, version: String): JsonObject =
        when (version) {
            UcpApi.VERSION -> augustSearchArguments(query, country, limit)
            UcpApi.APRIL_VERSION -> aprilSearchArguments(query, country, limit)
            else -> malformed("Unsupported catalog version")
        }

    // MCP's catalog argument wraps the dated REST request; serviceCall owns transport projection.
    private fun augustSearchArguments(query: String, country: String, limit: Int): JsonObject = buildJsonObject {
        putJsonObject("catalog") {
            put("query", query)
            putJsonObject("context") { put("address_country", country) }
            putJsonObject("pagination") { put("limit", limit.coerceIn(1, 50)) }
        }
    }

    private fun aprilSearchArguments(query: String, country: String, limit: Int): JsonObject = buildJsonObject {
        putJsonObject("catalog") {
            put("query", query)
            putJsonObject("context") { put("address_country", country) }
            putJsonObject("pagination") { put("limit", limit.coerceIn(1, 50)) }
        }
    }

    internal fun lookupArguments(ids: List<String>, version: String): JsonObject {
        requireVersion(version)
        return buildJsonObject {
            putJsonObject("catalog") { putJsonArray("ids") { ids.forEach { add(JsonPrimitive(it)) } } }
        }
    }

    /** Strict wire entry point: the expected version comes from the selected discovery leaf. */
    internal fun parseProducts(
        result: JsonObject, platformId: String, shopDomain: String, expectedVersion: String,
    ): List<UcpProduct> {
        validateResponse(result, expectedVersion)
        val products = result["products"].asArray() ?: malformed("Missing products")
        return products.map { element ->
            val product = element.asObject() ?: malformed("Invalid product")
            when (expectedVersion) {
                UcpApi.VERSION -> parseAugustProduct(product, platformId, shopDomain)
                UcpApi.APRIL_VERSION -> parseAprilProduct(product, platformId, shopDomain)
                else -> malformed("Unsupported catalog version")
            }
        }
    }

    internal fun parseProductResponse(
        result: JsonObject, platformId: String, shopDomain: String, expectedVersion: String,
    ): UcpProduct {
        validateResponse(result, expectedVersion)
        val product = result["product"].asObject() ?: malformed("Missing product")
        return when (expectedVersion) {
            UcpApi.VERSION -> parseAugustProduct(product, platformId, shopDomain)
            UcpApi.APRIL_VERSION -> parseAprilProduct(product, platformId, shopDomain)
            else -> malformed("Unsupported catalog version")
        }
    }

    private fun parseAugustProduct(o: JsonObject, platformId: String, shop: String): UcpProduct {
        validateProduct(o)
        val range = priceRange(o, ::parseMoney)
        val variants = o["variants"].asArray()!!.map { element ->
            val variant = element.asObject() ?: malformed("Invalid variant")
            validateVariant(variant)
            projectVariant(variant, parseMoney(variant["price"].asObject()!!), augustQuantity(variant))
        }
        return projectProduct(o, platformId, shop, range, variants)
    }

    private fun parseAprilProduct(o: JsonObject, platformId: String, shop: String): UcpProduct {
        validateProduct(o)
        val range = priceRange(o, ::parseAprilMoney)
        val variants = o["variants"].asArray()!!.map { element ->
            val variant = element.asObject() ?: malformed("Invalid variant")
            validateVariant(variant)
            // April has no quantity_unit descriptor. Do not import August semantics into this leaf.
            projectVariant(variant, parseAprilMoney(variant["price"].asObject()!!), true)
        }
        return projectProduct(o, platformId, shop, range, variants)
    }

    private fun validateResponse(result: JsonObject, version: String) {
        requireVersion(version)
        if (string(result["ucp"].asObject(), "version") != version) malformed("Unexpected UCP response version")
        if (result["ucp"].asObject()?.text("status") == "error" ||
            result["messages"].asArray()?.any { it.asObject()?.text("type") == "error" } == true) {
            throw UcpHttpException(UcpError.Rpc("Merchant returned a UCP error"))
        }
    }

    private fun requireVersion(version: String) {
        if (version != UcpApi.VERSION && version != UcpApi.APRIL_VERSION) malformed("Unsupported catalog version")
    }

    private fun validateProduct(o: JsonObject) {
        if (string(o, "id").isNullOrBlank() || string(o, "title") == null ||
            o["description"].asObject() == null || o["price_range"].asObject() == null ||
            o["variants"].asArray().isNullOrEmpty()) malformed("Invalid dated product")
    }

    private fun validateVariant(o: JsonObject) {
        if (string(o, "id").isNullOrBlank() || string(o, "title") == null ||
            o["description"].asObject() == null || o["price"].asObject() == null) malformed("Invalid dated variant")
    }

    private fun priceRange(o: JsonObject, money: (JsonObject) -> Money): Pair<Money, Money> {
        val range = o["price_range"].asObject() ?: malformed("Missing price range")
        val min = money(range["min"].asObject() ?: malformed("Missing minimum price"))
        val max = money(range["max"].asObject() ?: malformed("Missing maximum price"))
        if (min.currency != max.currency || min.amount > max.amount) malformed("Invalid price range")
        return min to max
    }

    private fun augustQuantity(o: JsonObject): Boolean {
        if (!o.containsKey("quantity_unit")) return true
        val unit = o["quantity_unit"].asObject() ?: return false
        return string(unit, "unit") == "C62" && string(unit, "display_text") != null &&
            (!unit.containsKey("scale") || integer(unit, "scale") == 0L) &&
            (!unit.containsKey("increment") || integer(unit, "increment") == 1L)
    }

    private fun projectVariant(o: JsonObject, price: Money, supportedQuantity: Boolean): ProductVariant = ProductVariant(
        id = string(o, "id")!!,
        title = string(o, "title")!!,
        price = price,
        available = o["availability"].asObject()?.get("available") == JsonPrimitive(true) && supportedQuantity,
        optionValues = o["options"].asArray()?.mapNotNull { string(it.asObject(), "label") }.orEmpty(),
        sku = string(o, "sku"),
        storeName = sellerName(o),
        shopDomain = sellerDomain(o),
        selectedOptions = parseSelectedOptions(o),
    )

    private fun projectProduct(
        o: JsonObject, platformId: String, defaultShop: String, price: Pair<Money, Money>, variants: List<ProductVariant>,
    ): UcpProduct {
        val url = string(o, "url")?.let(MerchantUrls::httpsUrl).orEmpty()
        val shop = MerchantUrls.domain(defaultShop)
            ?: variants.firstOrNull()?.shopDomain?.takeIf { it.isNotBlank() }
            ?: string(o, "shop_domain")?.let(MerchantUrls::domain)
            ?: merchantDomainFromUrl(url)
        val images = o["media"].asArray()?.mapNotNull { item ->
            val media = item.asObject() ?: return@mapNotNull null
            if (string(media, "type") != "image") return@mapNotNull null
            val image = string(media, "url")?.let(MerchantUrls::httpsUrl) ?: return@mapNotNull null
            val host = URI(image).host.lowercase()
            if (host != shop && host != "cdn.shopify.com") return@mapNotNull null
            ProductImage(image, string(media, "alt_text"))
        }.orEmpty()
        return UcpProduct(
            id = string(o, "id")!!, title = string(o, "title")!!,
            description = string(o["description"].asObject(), "plain").orEmpty(),
            priceRange = price.first, priceRangeMax = price.second,
            url = url, platformId = platformId, shopDomain = shop,
            images = images, variants = variants,
            descriptionHtml = string(o["description"].asObject(), "html").orEmpty(),
            storeName = variants.firstOrNull { it.shopDomain.isBlank() || it.shopDomain == shop }?.storeName.orEmpty(),
            checkoutEligible = shop.isNotBlank() && variants.isNotEmpty() && o["native_commerce"] != JsonPrimitive(false),
        )
    }

    private fun parseSelectedOptions(variant: JsonObject): List<CatalogOptionSelection> =
        variant["options"].asArray()?.mapNotNull { element ->
            val option = element.asObject() ?: return@mapNotNull null
            val name = string(option, "name") ?: return@mapNotNull null
            val label = string(option, "label") ?: return@mapNotNull null
            CatalogOptionSelection(name, label, string(option, "id"))
        }.orEmpty()

    private fun sellerName(variant: JsonObject): String =
        string(variant["seller"].asObject(), "name")?.trim()?.take(200).orEmpty()

    private fun sellerDomain(variant: JsonObject): String {
        val seller = variant["seller"].asObject()
        return string(seller, "domain")?.let(MerchantUrls::domain)
            ?: string(seller, "url")?.let(MerchantUrls::httpsUrl)?.let { MerchantUrls.domain(URI(it).host) }
            ?: ""
    }

    private fun merchantDomainFromUrl(url: String): String =
        url.takeIf { it.isNotBlank() }?.let { URI(it).host.lowercase() }
            ?.takeUnless { it == "shop.app" || it.endsWith(".shop.app") || it == "catalog.shopify.com" }.orEmpty()

    private fun string(o: JsonObject?, key: String): String? =
        (o?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun integer(o: JsonObject, key: String): Long? =
        (o[key] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toLongOrNull()

    private fun malformed(reason: String): Nothing = throw UcpHttpException(UcpError.Malformed(reason))

    // Compatibility parser for existing callers/fixtures without the UCP response envelope.
    internal fun parseProducts(result: JsonObject, platformId: String, shopDomain: String): List<UcpProduct> {
        val items = result["products"].asArray()
            ?: throw UcpHttpException(UcpError.Malformed("Missing products"))
        return items.mapNotNull { it.asObject()?.let { product -> parseProduct(product, platformId, shopDomain) } }
    }

    internal fun parseProduct(o: JsonObject, platformId: String, defaultShop: String): UcpProduct? {
        val id = o.text("id")?.takeIf { it.isNotBlank() } ?: return null
        val variants = o["variants"].asArray()?.mapNotNull { it.asObject()?.let(::parseVariant) }.orEmpty()
        val range = o["price_range"].asObject()
        val url = o.text("url").orEmpty()
        val shop = MerchantUrls.domain(defaultShop)
            ?: variants.firstOrNull()?.shopDomain?.takeIf { it.isNotBlank() }
            ?: o.text("shop_domain")?.let(MerchantUrls::domain)
            ?: MerchantUrls.httpsUrl(url)?.let(::merchantDomainFromUrl).orEmpty()
        val images = o["media"].asArray()?.mapNotNull { item ->
            val media = item.asObject() ?: return@mapNotNull null
            if (media.text("type") != "image") return@mapNotNull null
            val imageUrl = media.text("url")?.let(MerchantUrls::httpsUrl) ?: return@mapNotNull null
            val imageHost = URI(imageUrl).host
            if (imageHost != shop && imageHost != "cdn.shopify.com") return@mapNotNull null
            ProductImage(imageUrl, media.text("alt_text"))
        }.orEmpty()
        return UcpProduct(
            id = id,
            title = o.text("title").orEmpty(),
            description = o["description"].asObject()?.text("plain") ?: o.text("description").orEmpty(),
            descriptionHtml = o["description"].asObject()?.text("html").orEmpty(),
            storeName = variants.firstOrNull { it.shopDomain.isBlank() || it.shopDomain == shop }?.storeName.orEmpty(),
            priceRange = range?.get("min").asObject()?.let(::parseMoney) ?: variants.firstOrNull()?.price ?: Money(),
            priceRangeMax = range?.get("max").asObject()?.let(::parseMoney),
            url = url,
            platformId = platformId,
            shopDomain = shop,
            images = images,
            variants = variants,
            // Core catalog eligibility is not payment permission. Discovery checks checkout support later.
            checkoutEligible = o["native_commerce"].isTruthy() ?: (shop.isNotBlank() && variants.isNotEmpty()),
        )
    }

    internal fun parseVariant(o: JsonObject): ProductVariant? {
        val id = o.text("id")?.takeIf { it.isNotBlank() } ?: return null
        val price = o["price"].asObject()?.let { runCatching { parseMoney(it) }.getOrNull() } ?: return null
        // The UI supports integer 'each' quantities, not weighed/fractional quantities or subscriptions.
        val unit = o["quantity_unit"].asObject()
        val supportedQuantity = unit == null || (unit.text("unit") == "C62" && unit.money("scale") == 0L)
        return ProductVariant(
            id = id,
            title = o.text("title").orEmpty(),
            price = price,
            available = (o["availability"].asObject()?.get("available").isTruthy() == true) && supportedQuantity,
            optionValues = o["options"].asArray()?.mapNotNull { it.asObject()?.text("label") }.orEmpty(),
            sku = o.text("sku"),
            storeName = sellerName(o),
            shopDomain = sellerDomain(o),
            selectedOptions = parseSelectedOptions(o),
        )
    }

    internal fun parseMoney(o: JsonObject): Money {
        val money = parseAprilMoney(o)
        if (money.amount > 9_007_199_254_740_991L) malformed("Amount exceeds August catalog limit")
        return money
    }

    private fun parseAprilMoney(o: JsonObject): Money {
        val amount = integer(o, "amount")?.takeIf { it >= 0 } ?: malformed("Invalid amount")
        val currency = string(o, "currency")?.takeIf { it.matches(Regex("^[A-Z]{3}$")) }
            ?: malformed("Invalid currency")
        return Money(amount, currency)
    }
}

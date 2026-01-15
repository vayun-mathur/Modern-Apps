package com.vayunmathur.crypto.token

import kotlinx.serialization.Serializable
import org.sol4k.Constants.TOKEN_2022_PROGRAM_ID
import org.sol4k.Constants.TOKEN_PROGRAM_ID

@Serializable
data class TokenInfo(
    val symbol: String,
    val name: String,
    val category: Category,
    val mintAddress: String,
    val decimals: Int,
    val programAddress: String,
) {

    companion object {
        val SPL_TOKEN = TOKEN_PROGRAM_ID.toBase58()
        val TOKEN_2022 = TOKEN_2022_PROGRAM_ID.toBase58()

        enum class Category(val displayName: String) {
            NORMAL("Tokens"), JUPITER_LEND("Lending"), XSTOCK("Stocks"), PRED_MARKET("Prediction Markets")
        }

        val SOL = TokenInfo(
            "SOL",
            "Solana",
            Category.NORMAL,
            "So11111111111111111111111111111111111111111",
            9,
            ""
        )
        val USDC = TokenInfo(
            "USDC",
            "USD Coin",
            Category.NORMAL,
            "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
            6,
            SPL_TOKEN
        )

        private val TOKEN_LIST_MAIN = listOf(
            SOL,
            // STABLECOINS
            TokenInfo("EURC", "EURC", Category.NORMAL, "HzwqbKZw8HxMN6bF2yFZNrht3c2iXXzpKcFu7uBEDKtr", 6, SPL_TOKEN),
            USDC,
            TokenInfo("USDT", "USDT", Category.NORMAL, "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB", 6, SPL_TOKEN),
            TokenInfo("USDS", "USDS", Category.NORMAL, "USDSwr9ApdHk5bvJKMjzff41FfuX8bSxdKcR81vTwcA", 6,SPL_TOKEN),
            TokenInfo("USDG", "USDG", Category.NORMAL, "2u1tszSeqZ3qBWF3uNGPFc8TzMk2tdiwknnRMWGWjGWH", 6, SPL_TOKEN),

            // Major Coins
            TokenInfo("wSOL", "Solana (Wrapped)", Category.NORMAL, "So11111111111111111111111111111111111111112", 9, SPL_TOKEN),
            TokenInfo("wETH", "Etherium (Wrapped)", Category.NORMAL, "7vfCXTUXx5WJV5JADk17DUJ4ksgau7utNKj4b963voxs", 8, SPL_TOKEN),
            TokenInfo("wBTC", "Bitcoin (Wrapped)", Category.NORMAL, "3NZ9JMVBmGAqocybic2c7LQCJScmgsAZ6vQqTDzcqmJh", 8, SPL_TOKEN),

            // STOCKS
            TokenInfo("ABTx", "Abbott", Category.XSTOCK, "XsHtf5RpxsQ7jeJ9ivNewouZKJHbPxhPoEy6yYvULr7", 8, TOKEN_2022),
            TokenInfo("ABBVx", "AbbVie", Category.XSTOCK, "XswbinNKyPmzTa5CskMbCPvMW6G5CMnZXZEeQSSQoie", 8, TOKEN_2022),
            TokenInfo("ACNx", "Accenture", Category.XSTOCK, "Xs5UJzmCRQ8DWZjskExdSQDnbE6iLkRu2jjrRAB1JSU", 8, TOKEN_2022),
            TokenInfo("GOOGLx", "Google", Category.XSTOCK, "XsCPL9dNWBMvFtTmwcCA5v3xWPSMEBCszbQdiLLq6aN", 8, TOKEN_2022),
            TokenInfo("AMZNx", "Amazon", Category.XSTOCK, "Xs3eBt7uRfJX8QUs4suhyU8p2M6DoUDrJyWBa8LLZsg", 8, TOKEN_2022),
            TokenInfo("AMBRx", "Amber", Category.XSTOCK, "XsaQTCgebC2KPbf27KUhdv5JFvHhQ4GDAPURwrEhAzb", 8, TOKEN_2022),
            TokenInfo("AAPLx", "Apple", Category.XSTOCK, "XsbEhLAtcf6HdfpFZ5xEMdqW8nfAvcsP5bdudRLJzJp", 8, TOKEN_2022),
            TokenInfo("APPx", "AppLovin", Category.XSTOCK, "XsPdAVBi8Zc1xvv53k4JcMrQaEDTgkGqKYeh7AYgPHV", 8, TOKEN_2022),
            TokenInfo("AZNx", "AstraZeneca", Category.XSTOCK, "Xs3ZFkPYT2BN7qBMqf1j1bfTeTm1rFzEFSsQ1z3wAKU", 8, TOKEN_2022),
            TokenInfo("BACx", "Bank of America", Category.XSTOCK, "XswsQk4duEQmCbGzfqUUWYmi7pV7xpJ9eEmLHXCaEQP", 8, TOKEN_2022),
            TokenInfo("BRK.Bx", "Berkshire Hathaway", Category.XSTOCK, "Xs6B6zawENwAbWVi7w92rjazLuAr5Az59qgWKcNb45x", 8, TOKEN_2022),
            TokenInfo("AVGOx", "Broadcom", Category.XSTOCK, "XsgSaSvNSqLTtFuyWPBhK9196Xb9Bbdyjj4fH3cPJGo", 8, TOKEN_2022),
            TokenInfo("CVXx", "Chevron", Category.XSTOCK, "XsNNMt7WTNA2sV3jrb1NNfNgapxRF5i4i6GcnTRRHts", 8, TOKEN_2022),
            TokenInfo("CRCLx", "Circle", Category.XSTOCK, "XsueG8BtpquVJX9LVLLEGuViXUungE6WmK5YZ3p3bd1", 8, TOKEN_2022),
            TokenInfo("CSCOx", "Cisco", Category.XSTOCK, "Xsr3pdLQyXvDJBFgpR5nexCEZwXvigb8wbPYp4YoNFf", 8, TOKEN_2022),
            TokenInfo("KOx", "Coca-Cola", Category.XSTOCK, "XsaBXg8dU5cPM6ehmVctMkVqoiRG2ZjMo1cyBJ3AykQ", 8, TOKEN_2022),
            TokenInfo("COINx", "Coinbase", Category.XSTOCK, "Xs7ZdzSHLU9ftNJsii5fCeJhoRWSC32SQGzGQtePxNu", 8, TOKEN_2022),
            TokenInfo("CMCSAx", "Comcast", Category.XSTOCK, "XsvKCaNsxg2GN8jjUmq71qukMJr7Q1c5R2Mk9P8kcS8", 8, TOKEN_2022),
            TokenInfo("CRWDx", "CrowdStrike", Category.XSTOCK, "Xs7xXqkcK7K8urEqGg52SECi79dRp2cEKKuYjUePYDw", 8, TOKEN_2022),
            TokenInfo("DHRx", "Danaher", Category.XSTOCK, "Xseo8tgCZfkHxWS9xbFYeKFyMSbWEvZGFV1Gh53GtCV", 8, TOKEN_2022),
            TokenInfo("DFDVx", "DFDV", Category.XSTOCK, "Xs2yquAgsHByNzx68WJC55WHjHBvG9JsMB7CWjTLyPy", 8, TOKEN_2022),
            TokenInfo("LLYx", "Eli Lilly", Category.XSTOCK, "Xsnuv4omNoHozR6EEW5mXkw8Nrny5rB3jVfLqi6gKMH", 8, TOKEN_2022),
            TokenInfo("XOMx", "Exxon Mobil", Category.XSTOCK, "XsaHND8sHyfMfsWPj6kSdd5VwvCayZvjYgKmmcNL5qh", 8, TOKEN_2022),
            TokenInfo("GMEx", "Gamestop", Category.XSTOCK, "Xsf9mBktVB9BSU5kf4nHxPq5hCBJ2j2ui3ecFGxPRGc", 8, TOKEN_2022),
            TokenInfo("GLDx", "Gold", Category.XSTOCK, "Xsv9hRk1z5ystj9MhnA7Lq4vjSsLwzL2nxrwmwtD3re", 8, TOKEN_2022),
            TokenInfo("GSx", "Goldman Sachs", Category.XSTOCK, "XsgaUyp4jd1fNBCxgtTKkW64xnnhQcvgaxzsbAq5ZD1", 8, TOKEN_2022),
            TokenInfo("HDx", "Home Depot", Category.XSTOCK, "XszjVtyhowGjSC5odCqBpW1CtXXwXjYokymrk7fGKD3", 8, TOKEN_2022),
            TokenInfo("HONx", "Honeywell", Category.XSTOCK, "XsRbLZthfABAPAfumWNEJhPyiKDW6TvDVeAeW7oKqA2", 8, TOKEN_2022),
            TokenInfo("INTCx", "Intel", Category.XSTOCK, "XshPgPdXFRWB8tP1j82rebb2Q9rPgGX37RuqzohmArM", 8, TOKEN_2022),
            TokenInfo("IBMx", "IBM", Category.XSTOCK, "XspwhyYPdWVM8XBHZnpS9hgyag9MKjLRyE3tVfmCbSr", 8, TOKEN_2022),
            TokenInfo("JNJx", "Johnson & Johnson", Category.XSTOCK, "XsGVi5eo1Dh2zUpic4qACcjuWGjNv8GCt3dm5XcX6Dn", 8, TOKEN_2022),
            TokenInfo("JPMx", "JPMorgan Chase", Category.XSTOCK, "XsMAqkcKsUewDrzVkait4e5u4y8REgtyS7jWgCpLV2C", 8, TOKEN_2022),
            TokenInfo("LINx", "Linde", Category.XSTOCK, "XsSr8anD1hkvNMu8XQiVcmiaTP7XGvYu7Q58LdmtE8Z", 8, TOKEN_2022),
            TokenInfo("MRVLx", "Marvell", Category.XSTOCK, "XsuxRGDzbLjnJ72v74b7p9VY6N66uYgTCyfwwRjVCJA", 8, TOKEN_2022),
            TokenInfo("MAx", "Mastercard", Category.XSTOCK, "XsApJFV9MAktqnAc6jqzsHVujxkGm9xcSUffaBoYLKC", 8, TOKEN_2022),
            TokenInfo("MCDx", "McDonald's", Category.XSTOCK, "XsqE9cRRpzxcGKDXj1BJ7Xmg4GRhZoyY1KpmGSxAWT2", 8, TOKEN_2022),
            TokenInfo("MDTx", "Medtronic", Category.XSTOCK, "XsDgw22qRLTv5Uwuzn6T63cW69exG41T6gwQhEK22u2", 8, TOKEN_2022),
            TokenInfo("MRKx", "Merck", Category.XSTOCK, "XsnQnU7AdbRZYe2akqqpibDdXjkieGFfSkbkjX1Sd1X", 8, TOKEN_2022),
            TokenInfo("METAx", "Meta", Category.XSTOCK, "Xsa62P5mvPszXL1krVUnU5ar38bBSVcWAB6fmPCo5Zu", 8, TOKEN_2022),
            TokenInfo("MSFTx", "Microsoft", Category.XSTOCK, "XspzcW1PRtgf6Wj92HCiZdjzKCyFekVD8P5Ueh3dRMX", 8, TOKEN_2022),

            TokenInfo("MSTRx", "MicroStrategy", Category.XSTOCK, "XsP7xzNPvEHS1m6qfanPUGjNmdnmsLKEoNAnHjdxxyZ", 8, TOKEN_2022),
            TokenInfo("QQQx", "QQQ", Category.XSTOCK, "Xs8S1uUs1zvS2p7iwtsG3b6fkhpvmwz4GYU3gWAmWHZ", 8, TOKEN_2022),
            TokenInfo("NFLXx", "Netflix", Category.XSTOCK, "XsEH7wWfJJu2ZT3UCFeVfALnVA6CP5ur7Ee11KmzVpL", 8, TOKEN_2022),
            TokenInfo("NVOx", "Novo Nordisk", Category.XSTOCK, "XsfAzPzYrYjd4Dpa9BU3cusBsvWfVB9gBcyGC87S57n", 8, TOKEN_2022),
            TokenInfo("NVDAx", "Nvidia", Category.XSTOCK, "Xsc9qvGR1efVDFGLrVsmkzv3qi45LTBjeUKSPmx9qEh", 8, TOKEN_2022),
            TokenInfo("OPENx", "OPEN", Category.XSTOCK, "XsGtpmjhmC8kyjVSWL4VicGu36ceq9u55PTgF8bhGv6", 8, TOKEN_2022),
            TokenInfo("ORCLx", "Oracle", Category.XSTOCK, "XsjFwUPiLofddX5cWFHW35GCbXcSu1BCUGfxoQAQjeL", 8, TOKEN_2022),
            TokenInfo("PLTRx", "Palantir", Category.XSTOCK, "XsoBhf2ufR8fTyNSjqfU71DYGaE6Z3SUGAidpzriAA4", 8, TOKEN_2022),
            TokenInfo("PEPx", "PepsiCo", Category.XSTOCK, "Xsv99frTRUeornyvCfvhnDesQDWuvns1M852Pez91vF", 8, TOKEN_2022),
            TokenInfo("PFEx", "Pfizer", Category.XSTOCK, "XsAtbqkAP1HJxy7hFDeq7ok6yM43DQ9mQ1Rh861X8rw", 8, TOKEN_2022),
            TokenInfo("PMx", "Phillip Morris", Category.XSTOCK, "Xsba6tUnSjDae2VcopDB6FGGDaxRrewFCDa5hKn5vT3", 8, TOKEN_2022),
            TokenInfo("PGx", "Procter & Gamble", Category.XSTOCK, "XsYdjDjNUygZ7yGKfQaB6TxLh2gC6RRjzLtLAGJrhzV", 8, TOKEN_2022),
            TokenInfo("HOODx", "Robinhood", Category.XSTOCK, "XsvNBAYkrDRNhA7wPHQfX3ZUXZyZLdnCQDfHZ56bzpg", 8, TOKEN_2022),
            TokenInfo("CRMx", "Salesforce", Category.XSTOCK, "XsczbcQ3zfcgAEt9qHQES8pxKAVG5rujPSHQEXi4kaN", 8, TOKEN_2022),
            TokenInfo("SPYx", "S&P 500", Category.XSTOCK, "XsoCS1TfEyfFhfvj8EtZ528L3CaKBDBRqRapnBbDF2W", 8, TOKEN_2022),
            TokenInfo("STRCx", "Strategy PP Variable", Category.XSTOCK, "Xs78JED6PFZxWc2wCEPspZW9kL3Se5J7L5TChKgsidH", 8, TOKEN_2022),
            TokenInfo("TBLLx", "TBLL", Category.XSTOCK, "XsqBC5tcVQLYt8wqGCHRnAUUecbRYXoJCReD6w7QEKp", 8, TOKEN_2022),
            TokenInfo("TSLAx", "Tesla", Category.XSTOCK, "XsDoVfqeBukxuZHWhdvWHBhgEHjGNst4MLodqsJHzoB", 8, TOKEN_2022),
            TokenInfo("TMOx", "Thermo Fisher", Category.XSTOCK, "Xs8drBWy3Sd5QY3aifG9kt9KFs2K3PGZmx7jWrsrk57", 8, TOKEN_2022),
            TokenInfo("TONXx", "TON", Category.XSTOCK, "XscE4GUcsYhcyZu5ATiGUMmhxYa1D5fwbpJw4K6K4dp", 8, TOKEN_2022),
            TokenInfo("TQQQx", "TQQQ", Category.XSTOCK, "XsjQP3iMAaQ3kQScQKthQpx9ALRbjKAjQtHg6TFomoc", 8, TOKEN_2022),
            TokenInfo("UNHx", "UnitedHealth", Category.XSTOCK, "XszvaiXGPwvk2nwb3o9C1CX4K6zH8sez11E6uyup6fe", 8, TOKEN_2022),
            TokenInfo("VTIx", "Vanguard", Category.XSTOCK, "XsssYEQjzxBCFgvYFFNuhJFBeHNdLWYeUSP8F45cDr9", 8, TOKEN_2022),
            TokenInfo("Vx", "Visa", Category.XSTOCK, "XsqgsbXwWogGJsNcVZ3TyVouy2MbTkfCFhCGGGcQZ2p", 8, TOKEN_2022),
            TokenInfo("WMTx", "Walmart", Category.XSTOCK, "Xs151QeqTCiuKtinzfRATnUESM2xTU6V9Wy8Vy538ci", 8, TOKEN_2022),




            // Jupiter Lend
            TokenInfo("jlUSDC", "Lended USDC", Category.JUPITER_LEND, "9BEcn9aPEmhSPbPQeFGjidRiEKki46fVQDyPpSQXPA2D", 6, SPL_TOKEN),
            TokenInfo("jlUSDT", "Lended USDT", Category.JUPITER_LEND, "Cmn4v2wipYV41dkakDvCgFJpxhtaaKt11NyWV8pjSE8A", 6, SPL_TOKEN),
            TokenInfo("jlWSOL", "Lended WSOL", Category.JUPITER_LEND, "2uQsyo1fXXQkDtcpXnLofWy88PxcvnfH2L8FPSE62FVU", 6, SPL_TOKEN),
            TokenInfo("jlEURC", "Lended EURC", Category.JUPITER_LEND, "GcV9tEj62VncGithz4o4N9x6HWXARxuRgEAYk9zahNA8", 6, SPL_TOKEN),
            TokenInfo("jlUSDS", "Lended USDS", Category.JUPITER_LEND, "j14XLJZSVMcUYpAfajdZRpnfHUpJieZHS4aPektLWvh", 6, SPL_TOKEN),
            TokenInfo("jlUSDG", "Lended USDG", Category.JUPITER_LEND, "9fvHrYNw1A8Evpcj7X2yy4k4fT7nNHcA9L6UsamNHAif", 6, SPL_TOKEN)
        )

        private var TOKEN_LIST_PREDICTION = listOf<TokenInfo>()

        var TOKEN_LIST: List<TokenInfo> = TOKEN_LIST_MAIN + TOKEN_LIST_PREDICTION
            private set

        var TOKEN_MAP = TOKEN_LIST.associateBy { it.mintAddress }
            private set

        fun update(predTokens: List<TokenInfo>) {
            TOKEN_LIST_PREDICTION = predTokens
            TOKEN_LIST = TOKEN_LIST_MAIN + TOKEN_LIST_PREDICTION
            TOKEN_MAP = TOKEN_LIST.associateBy { it.mintAddress }
        }

        fun BY_TYPE(category: Category) = TOKEN_LIST.filter { it.category == category }
    }
}
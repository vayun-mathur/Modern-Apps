package com.vayunmathur.crypto.api

import com.vayunmathur.crypto.api.SolanaAPI.RPCResult
import com.vayunmathur.crypto.token.Token
import com.vayunmathur.crypto.token.TokenInfo
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.sol4k.Connection
import org.sol4k.Keypair
import org.sol4k.PublicKey
import org.sol4k.RpcUrl
import org.sol4k.TransactionMessage
import org.sol4k.VersionedTransaction
import org.sol4k.instruction.CreateAssociatedToken2022AccountInstruction
import org.sol4k.instruction.CreateAssociatedTokenAccountInstruction
import org.sol4k.instruction.SplTransferInstruction
import kotlin.math.pow

typealias TokenAccountByOwnerData = List<TokenAccountByOwnerDataItem>

fun TokenAccountByOwnerData.toTokens(): List<Token> {
    return this.mapNotNull { item ->
        item.account.toToken()
    }
}

fun TokenAccount.toToken(): Token? {
    return TokenInfo.TOKEN_MAP[this.data.parsed.info.mint]?.let {
        Token(
            tokenInfo = it,
            amount = this.data.parsed.info.tokenAmount.uiAmount
        )
    }
}

@Serializable
data class TokenAccountByOwnerDataItem(
    val pubkey: String,
    val account: TokenAccount
)

@Serializable
data class TokenAccount(
    val executable: Boolean,
    val lamports: ULong,
    val owner: String,
    val rentEpoch: ULong,
    val space: ULong,
    val data: Data
) {
    @Serializable
    data class Data(
        val program: String,
        val parsed: Parsed,
        val space: ULong
    ) {
        @Serializable
        data class Parsed(
            val info: Info,
            val type: String
        ) {
            @Serializable
            data class Info(
                val mint: String,
                val owner: String,
                val tokenAmount: TokenAmount
            ) {
                @Serializable
                data class TokenAmount(
                    val amount: String,
                    val decimals: Int,
                    val uiAmount: Double,
                    val uiAmountString: String
                )
            }
        }
    }
}

@Serializable
data class PriceData(
    val usdPrice: Double,
    val blockId: Long = 0,
    val decimals: Int,
    val priceChange24h: Double = 0.0
)

typealias PriceResponse = Map<String, PriceData>

typealias RPCValueResult<T> = RPCResult<RPCResult.Result<T>>

val JSON = Json {
    prettyPrint = true
    isLenient = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

val client = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(JSON)
    }
}

object SolanaAPI {

    private val connection = Connection(RpcUrl.MAINNNET)

    suspend fun getTokenAccountsByOwner(wallet: Keypair): List<Token> {
        try {
            val tokens1 =
                rpcCallV<TokenAccountByOwnerData>("getTokenAccountsByOwner", buildJsonArray {
                    add(wallet.publicKey.toBase58())
                    add(buildJsonObject {
                        put("programId", "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
                    })
                    add(buildJsonObject {
                        put("commitment", "finalized")
                        put("encoding", "jsonParsed")
                    })
                })!!.toTokens()
            val tokens2 =
                rpcCallV<TokenAccountByOwnerData>("getTokenAccountsByOwner", buildJsonArray {
                    add(wallet.publicKey.toBase58())
                    add(buildJsonObject {
                        put("programId", "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb")
                    })
                    add(buildJsonObject {
                        put("commitment", "finalized")
                        put("encoding", "jsonParsed")
                    })
                })!!.toTokens()
            val solanaLamports = rpcCallV<ULong>("getBalance", buildJsonArray {
                add(wallet.publicKey.toBase58())
                add(buildJsonObject {
                    put("commitment", "finalized")
                    put("encoding", "jsonParsed")
                })
            })
            val solanaToken = Token(TokenInfo.SOL, solanaLamports!!.toDouble() / 1000000000)
            return (tokens1 + tokens2 + solanaToken)
        } catch(e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
    }

    private suspend inline fun <reified T> rpcCallV(method: String, params: JsonArray): T? {
        val response: HttpResponse = client.post(HELIUS_URL) {
            contentType(ContentType.Application.Json)
            setBody(
                RPCRequest(
                    method = method,
                    params = params
                )
            )
        }
        return response.body<RPCValueResult<T>>().result.value
    }

    private suspend inline fun <reified T> rpcCall(method: String, params: JsonArray): T? {
        val response: HttpResponse = client.post(HELIUS_URL) {
            contentType(ContentType.Application.Json)
            setBody(
                RPCRequest(
                    method = method,
                    params = params
                )
            )
        }
        return response.body<RPCResult<T>>().result
    }

    fun transfer(from: Keypair, token: TokenInfo, recipient: PublicKey, amount: Double) {
        val blockhash = connection.getLatestBlockhash()

        val receiverAssociatedAccount = PublicKey.findProgramDerivedAddress(recipient, PublicKey(token.mintAddress))
        val holderAssociatedAccount = PublicKey.findProgramDerivedAddress(from.publicKey, PublicKey(token.mintAddress))
        val splTransferInstruction = SplTransferInstruction(
            holderAssociatedAccount.publicKey,
            receiverAssociatedAccount.publicKey,
            PublicKey(token.mintAddress),
            from.publicKey,
            (amount*10.0.pow(token.decimals)).toLong(),
            token.decimals
        )
        val message = TransactionMessage.newMessage(
            from.publicKey,
            blockhash,
            splTransferInstruction
        )
        val transaction = VersionedTransaction(message)
        transaction.sign(from)

        connection.sendTransaction(transaction)
    }

    fun createTokenAccount(wallet: Keypair, token: TokenInfo) {
        val blockhash = connection.getLatestBlockhash()
        val programID = PublicKey(token.programAddress)
        val (associatedAccount) = PublicKey.findProgramDerivedAddress(wallet.publicKey, PublicKey(token.mintAddress), programID)
        val instruction = when(token.category) {
            TokenInfo.Companion.Category.NORMAL, TokenInfo.Companion.Category.JUPITER_LEND, TokenInfo.Companion.Category.PRED_MARKET ->
                CreateAssociatedTokenAccountInstruction(
                    payer = wallet.publicKey,
                    associatedToken = associatedAccount,
                    owner = wallet.publicKey,
                    mint = PublicKey(token.mintAddress),
                )
            TokenInfo.Companion.Category.XSTOCK -> CreateAssociatedToken2022AccountInstruction(
                payer = wallet.publicKey,
                associatedToken = associatedAccount,
                owner = wallet.publicKey,
                mint = PublicKey(token.mintAddress),
            )
        }
        val message = TransactionMessage.newMessage(wallet.publicKey, blockhash, instruction)
        val transaction = VersionedTransaction(message)
        transaction.sign(wallet)
        connection.sendTransaction(transaction)
    }

    suspend fun sendTransaction(signedTransactionBase64: String): String? {
        return rpcCall<String>("sendTransaction", buildJsonArray {
            add(signedTransactionBase64)
            add(buildJsonObject {
                put("skipPreflight", true)
                put("maxRetries", 3)
                put("encoding", "base64")
            })
        })
    }

    private const val HELIUS_URL = "https://docs-demo.solana-mainnet.quiknode.pro/ "

    @Serializable
    data class RPCRequest(
        val jsonrpc: String = "2.0",
        val id: UInt = System.currentTimeMillis().toUInt(),
        val method: String,
        val params: JsonArray
    )


    @Serializable
    data class RPCResult<T>(
        val jsonrpc: String,
        val result: T,
        val id: UInt
    ) {
        @Serializable
        data class Result<T>(
            val context: JsonElement,
            val value: T
        )
    }
}
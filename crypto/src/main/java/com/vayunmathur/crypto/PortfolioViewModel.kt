package com.vayunmathur.crypto

import android.app.Application
import android.widget.Toast
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.crypto.api.PendingOrder
import com.vayunmathur.crypto.api.PredictionMarket
import com.vayunmathur.crypto.api.SolanaAPI
import com.vayunmathur.crypto.token.JupiterLendRepository
import com.vayunmathur.crypto.token.Token
import com.vayunmathur.crypto.token.TokenInfo
import com.vayunmathur.crypto.token.TokenPriceRepository
import io.ktor.util.encodeBase64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.sol4k.Base58
import org.sol4k.Keypair
import org.sol4k.PublicKey
import org.sol4k.VersionedTransaction
import kotlin.math.pow

class PortfolioViewModel(private val application: Application) : AndroidViewModel(application) {

    private val sharedPreferences = application.getSharedPreferences("crypto_prefs", Application.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private var ignoreNextFetch = false

    private val _tokens = MutableStateFlow<List<Token>>(emptyList())
    val tokens: StateFlow<List<Token>> = _tokens

    private val _normalTokens = MutableStateFlow<List<Token>>(emptyList())
    val normalTokens: StateFlow<List<Token>> = _normalTokens

    private val _lendTokens = MutableStateFlow<List<Token>>(emptyList())
    val lendTokens: StateFlow<List<Token>> = _lendTokens

    private val _stockTokens = MutableStateFlow<List<Token>>(emptyList())
    val stockTokens: StateFlow<List<Token>> = _stockTokens

    private val _predTokens = MutableStateFlow<List<Token>>(emptyList())
    val predTokens: StateFlow<List<Token>> = _predTokens

    private val _predictionMarkets = MutableStateFlow<List<PredictionMarket.Event>>(emptyList())
    val predictionMarkets: StateFlow<List<PredictionMarket.Event>> = _predictionMarkets


    lateinit var wallet: Keypair
        private set

    private val _walletInitialized = MutableStateFlow(false)
    val walletInitialized: StateFlow<Boolean> = _walletInitialized

    init {
        viewModelScope.launch {
            tokens.collect { allTokens ->
                _normalTokens.value =
                    allTokens.filter { it.tokenInfo.category == TokenInfo.Companion.Category.NORMAL }.sortedByDescending(Token::totalValue)
                _stockTokens.value =
                    allTokens.filter { it.tokenInfo.category == TokenInfo.Companion.Category.XSTOCK }.sortedByDescending(Token::totalValue)
                _lendTokens.value =
                    allTokens.filter { it.tokenInfo.category == TokenInfo.Companion.Category.JUPITER_LEND }.sortedByDescending(Token::totalValue)
                _predTokens.value =
                    allTokens.filter { it.tokenInfo.category == TokenInfo.Companion.Category.PRED_MARKET }.sortedByDescending(Token::totalValue)
            }
        }
        TokenPriceRepository.init(application)
        JupiterLendRepository.init(application)

        updatePredictionMarkers()

        val cachedTokens = sharedPreferences.getString("cached_tokens", null)
        if (cachedTokens != null) {
            val decodedTokens = json.decodeFromString<List<Token>>(cachedTokens)
            _tokens.value = decodedTokens
        }

        val savedPrivateKey = sharedPreferences.getString("private_key", null)
        if (savedPrivateKey != null) {
            initializeWallet(savedPrivateKey)
        }
    }

    fun createWallet() {
        val keypair = Keypair.generate()
        initializeWallet(Base58.encode(keypair.secret))
    }

    fun initializeWallet(privateKey: String) {
        try {
            wallet = Keypair.fromSecretKey(Base58.decode(privateKey))
            sharedPreferences.edit {
                putString("private_key", privateKey)
            }
            _walletInitialized.value = true
            startDataFetching()
        } catch (e: Exception) {
            // Handle invalid private key
            println("Invalid private key: $e")
            _walletInitialized.value = false
        }
    }

    fun logout() {
        sharedPreferences.edit {
            remove("private_key")
            remove("cached_tokens")
        }
        _walletInitialized.value = false
    }

    private fun startDataFetching() {
        viewModelScope.launch {
            while(true) {
                fetchTokens()
                delay(15000)
            }
        }
    }

    private suspend fun fetchTokens() {
        if (ignoreNextFetch) {
            ignoreNextFetch = false
            return
        }
        if (!::wallet.isInitialized) return

        TokenPriceRepository.update()
        JupiterLendRepository.update()
        val fetchedTokens = SolanaAPI.getTokenAccountsByOwner(wallet)
        if(fetchedTokens.isEmpty()) return

        sharedPreferences.edit {
            putString("cached_tokens", json.encodeToString(fetchedTokens))
        }

        _tokens.value = fetchedTokens
    }

    fun updatePredictionMarkers() {
        viewModelScope.launch {
            val events = PredictionMarket.getPredictionMarkets()
            _predictionMarkets.value = events
            val tokens = events.flatMap { event -> event.markets.associateWith { event }.toList() }.flatMap { (market, event) ->
                listOf(
                    TokenInfo(
                        symbol = "Contracts",
                        name = "Yes ${market.subtitle}: ${event.title}",
                        category = TokenInfo.Companion.Category.PRED_MARKET,
                        mintAddress = market.yesMint,
                        decimals = 6,
                        programAddress = TokenInfo.SPL_TOKEN
                    ),
                    TokenInfo(
                        symbol = "Contracts",
                        name = "No ${market.subtitle}: ${event.title}",
                        category = TokenInfo.Companion.Category.PRED_MARKET,
                        mintAddress = market.noMint,
                        decimals = 6,
                        programAddress = TokenInfo.SPL_TOKEN
                    ),
                )
            }
            TokenInfo.update(tokens)
        }
    }

    fun signTransaction(transaction: String): String {
        val t = VersionedTransaction.from(transaction)
        t.sign(wallet)
        return t.serialize().encodeBase64()
    }

    fun sendToken(token: TokenInfo, recipient: PublicKey, amount: Double) {
        viewModelScope.launch {
            CoroutineScope(Dispatchers.IO).launch {
                SolanaAPI.transfer(wallet, token, recipient, amount)
            }
        }
    }

    fun placeOrder(order: PendingOrder) {
        viewModelScope.launch {
            CoroutineScope(Dispatchers.IO).launch {
                val signedTransaction = signTransaction(order.transaction)
                val response = SolanaAPI.sendTransaction(signedTransaction)
                println(response)
                if (response != null) {
                    ignoreNextFetch = true
                    val allTokens = _tokens.value.toMutableList()
                    val inputToken = allTokens.find { it.tokenInfo.mintAddress == order.inputMint }
                    val outputToken = allTokens.find { it.tokenInfo.mintAddress == order.outputMint }

                    if (inputToken != null && outputToken != null) {
                        val inputAmount = order.inAmount.toDouble() / 10.0.pow(inputToken.tokenInfo.decimals)
                        val outputAmount = order.outAmount.toDouble() / 10.0.pow(outputToken.tokenInfo.decimals)

                        val updatedInputToken = inputToken.copy(amount = inputToken.amount - inputAmount)
                        val updatedOutputToken = outputToken.copy(amount = outputToken.amount + outputAmount)

                        val inputIndex = allTokens.indexOf(inputToken)
                        if (inputIndex != -1) {
                            allTokens[inputIndex] = updatedInputToken
                        }

                        val outputIndex = allTokens.indexOf(outputToken)
                        if (outputIndex != -1) {
                            allTokens[outputIndex] = updatedOutputToken
                        }

                        _tokens.value = allTokens
                    }
                } else {
                    showToast("Transaction failed")
                }
            }
        }
    }

    fun createTokenAccount(tokenInfo: TokenInfo) {
        viewModelScope.launch {
            CoroutineScope(Dispatchers.IO).launch {
                SolanaAPI.createTokenAccount(wallet, tokenInfo)
            }
        }
    }

    private suspend fun showToast(message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(application.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun getPrivateKey(): String? {
        return sharedPreferences.getString("private_key", null)
    }
}

class PortfolioViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PortfolioViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return PortfolioViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

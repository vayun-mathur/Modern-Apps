package com.vayunmathur.passwords

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.passwords.data.PasswordDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PasswordViewModel(application: Application) : AndroidViewModel(application) {
    private val _passwords = MutableStateFlow<List<Password>>(emptyList())
    val passwords: StateFlow<List<Password>> = _passwords.asStateFlow()
    val database = PasswordDatabase.getInstance(getApplication())

    init {
        // collect repository flow into state
        viewModelScope.launch {
            database.passwordDao().getAll().collect {
                _passwords.value = it
            }
        }
    }

    fun insert(password: Password, onComplete: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = database.passwordDao().insert(password)
            onComplete(id)
        }
    }

    fun update(password: Password, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            database.passwordDao().update(password)
            onComplete()
        }
    }

    fun delete(password: Password, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            database.passwordDao().delete(password)
            onComplete()
        }
    }
}



class PasswordViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PasswordViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return PasswordViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

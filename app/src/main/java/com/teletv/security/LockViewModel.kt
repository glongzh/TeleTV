package com.teletv.security

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-level lock state. Starts locked when a PIN is set; relocks whenever the
 * app leaves the foreground (ProcessLifecycleOwner ON_STOP — configuration
 * changes do NOT trigger it, so rotating/resizing never falsely relocks).
 */
class LockViewModel(private val pinRepo: PinRepository) : ViewModel(), DefaultLifecycleObserver {

    private val _locked = MutableStateFlow(pinRepo.isPinSet())
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        // App went to background: require the PIN again on return.
        if (pinRepo.isPinSet()) _locked.value = true
    }

    /** Returns true and unlocks on a correct PIN; false leaves the app locked. */
    fun tryUnlock(pin: String): Boolean =
        pinRepo.verify(pin).also { ok -> if (ok) _locked.value = false }

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        super.onCleared()
    }

    class Factory(private val pinRepo: PinRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LockViewModel(pinRepo) as T
    }
}

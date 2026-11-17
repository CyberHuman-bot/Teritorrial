package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.TerminalProfile
import com.example.data.repository.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProfilesViewModel(
    private val currentUserId: String,
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    fun clearMessage() {
        _actionMessage.value = null
    }

    fun createProfile(
        name: String,
        preferredShell: String,
        promptSymbol: String,
        aiModelName: String,
        aiTemperature: Double,
        proactiveMonitoring: Boolean,
        autoSuggestEnabled: Boolean,
        cpuAlertThreshold: Long,
        memAlertThreshold: Long,
        activateImmediately: Boolean,
        existingProfiles: List<TerminalProfile>,
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            val result = profileRepository.createProfile(
                userId = currentUserId,
                name = name,
                preferredShell = preferredShell,
                promptSymbol = promptSymbol,
                aiModelName = aiModelName,
                aiTemperature = aiTemperature,
                proactiveMonitoring = proactiveMonitoring,
                autoSuggestEnabled = autoSuggestEnabled,
                cpuAlertThreshold = cpuAlertThreshold,
                memAlertThreshold = memAlertThreshold,
                activateImmediately = activateImmediately,
                existingProfileIds = existingProfiles.map { it.id },
            )
            _isBusy.value = false
            result.onSuccess {
                _actionMessage.value = "Created profile '${name.trim()}'"
            }.onFailure { err ->
                _actionMessage.value = err.localizedMessage ?: "Failed to create profile."
            }
        }
    }

    fun updateProfile(
        profile: TerminalProfile,
        name: String,
        preferredShell: String,
        promptSymbol: String,
        aiModelName: String,
        aiTemperature: Double,
        proactiveMonitoring: Boolean,
        autoSuggestEnabled: Boolean,
        cpuAlertThreshold: Long,
        memAlertThreshold: Long,
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            val result = profileRepository.updateProfileSettings(
                userId = currentUserId,
                profileId = profile.id,
                name = name,
                preferredShell = preferredShell,
                promptSymbol = promptSymbol,
                aiModelName = aiModelName,
                aiTemperature = aiTemperature,
                proactiveMonitoring = proactiveMonitoring,
                autoSuggestEnabled = autoSuggestEnabled,
                cpuAlertThreshold = cpuAlertThreshold,
                memAlertThreshold = memAlertThreshold,
                activeProfile = profile.activeProfile,
            )
            _isBusy.value = false
            result.onSuccess {
                _actionMessage.value = "Updated '${name.trim()}'"
            }.onFailure { err ->
                _actionMessage.value = err.localizedMessage ?: "Failed to update profile."
            }
        }
    }

    fun loadProfile(targetId: String, allProfiles: List<TerminalProfile>) {
        viewModelScope.launch {
            _isBusy.value = true
            val result = profileRepository.activateProfile(
                userId = currentUserId,
                targetProfileId = targetId,
                allProfileIds = allProfiles.map { it.id },
            )
            _isBusy.value = false
            result.onSuccess {
                val loadedName = allProfiles.firstOrNull { it.id == targetId }?.name ?: "profile"
                _actionMessage.value = "Loaded '$loadedName' into terminal."
            }.onFailure { err ->
                _actionMessage.value = err.localizedMessage ?: "Failed to switch profile."
            }
        }
    }

    fun clearHistory(profileId: String) {
        viewModelScope.launch {
            profileRepository.updateCommandHistory(currentUserId, profileId, emptyList())
                .onSuccess { _actionMessage.value = "Command history cleared." }
                .onFailure { err -> _actionMessage.value = err.localizedMessage ?: "Failed to clear history." }
        }
    }

    fun deleteProfile(profile: TerminalProfile, remainingCount: Int) {
        if (remainingCount <= 1) {
            _actionMessage.value = "At least one terminal profile must remain."
            return
        }
        viewModelScope.launch {
            profileRepository.deleteProfile(currentUserId, profile.id)
                .onSuccess { _actionMessage.value = "Deleted profile '${profile.name}'." }
                .onFailure { err -> _actionMessage.value = err.localizedMessage ?: "Failed to delete profile." }
        }
    }
}

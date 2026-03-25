package com.fauxx.ui.viewmodels

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.di.PreferenceKeys
import com.fauxx.targeting.layer1.AgeRange
import com.fauxx.targeting.layer1.DemographicProfileDao
import com.fauxx.targeting.layer1.Gender
import com.fauxx.targeting.layer1.Profession
import com.fauxx.targeting.layer1.Region
import com.fauxx.targeting.layer1.UserDemographicProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnboardingUiState(
    val step: Int = 0,
    val ageRange: AgeRange? = null,
    val gender: Gender? = null,
    val interests: Set<CategoryPool> = emptySet(),
    val profession: Profession? = null,
    val region: Region? = null
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val dao: DemographicProfileDao,
    private val dataStore: DataStore<Preferences>
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState

    fun setAgeRange(age: AgeRange) { _uiState.value = _uiState.value.copy(ageRange = age) }
    fun setGender(gender: Gender) { _uiState.value = _uiState.value.copy(gender = gender) }
    fun setProfession(prof: Profession) { _uiState.value = _uiState.value.copy(profession = prof) }
    fun setRegion(region: Region) { _uiState.value = _uiState.value.copy(region = region) }

    fun toggleInterest(cat: CategoryPool) {
        val current = _uiState.value.interests.toMutableSet()
        if (cat in current) current.remove(cat) else current.add(cat)
        _uiState.value = _uiState.value.copy(interests = current)
    }

    fun next() { _uiState.value = _uiState.value.copy(step = _uiState.value.step + 1) }

    fun skip() { _uiState.value = _uiState.value.copy(step = _uiState.value.step + 1) }

    fun saveAndFinish() {
        viewModelScope.launch {
            val state = _uiState.value
            // Only save if at least one field was set
            val hasData = state.ageRange != null || state.gender != null ||
                state.profession != null || state.region != null || state.interests.isNotEmpty()

            if (hasData) {
                dao.upsert(
                    UserDemographicProfile(
                        ageRange = state.ageRange,
                        gender = state.gender,
                        profession = state.profession,
                        region = state.region,
                        interestsJson = UserDemographicProfile.serializeInterests(state.interests)
                    )
                )
            }
            dataStore.edit { it[PreferenceKeys.ONBOARDING_COMPLETED] = true }
        }
    }
}

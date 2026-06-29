package com.fauxx

import com.fauxx.data.model.PoisonProfile
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.modules.LocationDiagnostics
import com.fauxx.support.MainDispatcherRule
import com.fauxx.ui.viewmodels.ModulesViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * #202: the mock-location AppOp is only re-evaluated when LocationSpoofModule (re)starts. The
 * screen calls [ModulesViewModel.refreshLocationStatus] on ON_RESUME so designating Fauxx as the
 * mock-location app in Developer Options is picked up live, but ONLY when Location is enabled and
 * the last attempt failed — so a routine screen resume doesn't re-kick an in-flight spoof route.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModulesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val failureFlow = MutableStateFlow(LocationDiagnostics.StartFailure.NOT_MOCK_APP)
    private val diagnostics: LocationDiagnostics = mockk(relaxed = true) {
        every { lastStartFailure } returns failureFlow
        coEvery { requestStart() } returns Unit
    }

    private fun viewModel(locationEnabled: Boolean): ModulesViewModel {
        val profileRepo: PoisonProfileRepository = mockk(relaxed = true) {
            every { getProfile() } returns PoisonProfile(locationSpoofEnabled = locationEnabled)
        }
        return ModulesViewModel(profileRepo, diagnostics)
    }

    @Test
    fun `refreshLocationStatus re-checks when enabled and the last attempt failed`() = runTest {
        failureFlow.value = LocationDiagnostics.StartFailure.NOT_MOCK_APP
        val vm = viewModel(locationEnabled = true)

        vm.refreshLocationStatus()
        advanceUntilIdle()

        coVerify(exactly = 1) { diagnostics.requestStart() }
    }

    @Test
    fun `refreshLocationStatus does nothing when the gate is already OK`() = runTest {
        failureFlow.value = LocationDiagnostics.StartFailure.OK
        val vm = viewModel(locationEnabled = true)

        vm.refreshLocationStatus()
        advanceUntilIdle()

        coVerify(exactly = 0) { diagnostics.requestStart() }
    }

    @Test
    fun `refreshLocationStatus does nothing when location is disabled`() = runTest {
        failureFlow.value = LocationDiagnostics.StartFailure.NOT_MOCK_APP
        val vm = viewModel(locationEnabled = false)

        vm.refreshLocationStatus()
        advanceUntilIdle()

        coVerify(exactly = 0) { diagnostics.requestStart() }
    }
}

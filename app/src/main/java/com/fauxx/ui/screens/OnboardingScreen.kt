package com.fauxx.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.fauxx.R
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.targeting.layer1.AgeRange
import com.fauxx.targeting.layer1.Gender
import com.fauxx.targeting.layer1.InterestMapping
import com.fauxx.targeting.layer1.MappingConfidence
import com.fauxx.targeting.layer1.Profession
import com.fauxx.targeting.layer1.Region
import com.fauxx.ui.format.displayNameRes
import com.fauxx.ui.viewmodels.OnboardingViewModel

/**
 * Optional first-launch demographic self-report flow.
 * Screens: Welcome → Age Range → Gender → Interests → Profession → Region → Done.
 *
 * Every screen has a "Skip" button visually equal in prominence to "Next".
 * All fields are optional. The app functions identically with Layer 0 uniform weights
 * if the user skips every question.
 */
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Content per step
        when (uiState.step) {
            0 -> WelcomeStep()
            1 -> AgeRangeStep(
                selected = uiState.ageRange,
                onSelect = { viewModel.setAgeRange(it) }
            )
            2 -> GenderStep(
                selected = uiState.gender,
                onSelect = { viewModel.setGender(it) }
            )
            3 -> InterestsStep(
                selected = uiState.interests,
                onToggle = { viewModel.toggleInterest(it) },
                customMappings = uiState.customInterestMappings,
                onAddCustom = { viewModel.addCustomInterest(it) },
                onRemoveCustom = { viewModel.removeCustomInterest(it) }
            )
            4 -> ProfessionStep(
                selected = uiState.profession,
                onSelect = { viewModel.setProfession(it) }
            )
            5 -> RegionStep(
                selected = uiState.region,
                onSelect = { viewModel.setRegion(it) }
            )
            else -> DoneStep()
        }

        Spacer(Modifier.height(32.dp))

        // Navigation buttons — Skip and Next are equal prominence
        val isLastStep = uiState.step >= 5
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = {
                    if (isLastStep) {
                        viewModel.saveAndFinish()
                        onFinish()
                    } else {
                        viewModel.skip()
                        if (viewModel.uiState.value.step > 5) {
                            viewModel.saveAndFinish()
                            onFinish()
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(if (isLastStep) R.string.onboarding_skip_all else R.string.onboarding_skip))
            }

            Button(
                onClick = {
                    if (isLastStep) {
                        viewModel.saveAndFinish()
                        onFinish()
                    } else {
                        viewModel.next()
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(if (isLastStep) R.string.onboarding_done else R.string.onboarding_next))
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.headlineLarge,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.onboarding_subtitle),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.onboarding_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DisclosureBullet(stringResource(R.string.onboarding_bullet_search))
            DisclosureBullet(stringResource(R.string.onboarding_bullet_browse))
            DisclosureBullet(stringResource(R.string.onboarding_bullet_fingerprint))
            DisclosureBullet(stringResource(R.string.onboarding_bullet_dns))
            DisclosureBullet(stringResource(R.string.onboarding_bullet_battery))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.onboarding_optional_profile),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun DisclosureBullet(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "\u2022",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AgeRangeStep(selected: AgeRange?, onSelect: (AgeRange) -> Unit) {
    StepContainer(
        title = stringResource(R.string.onboarding_age_title),
        subtitle = stringResource(R.string.onboarding_age_subtitle)
    ) {
        AgeRange.values().forEach { age ->
            ElevatedFilterChip(
                selected = selected == age,
                onClick = { onSelect(age) },
                label = { Text(stringResource(age.displayNameRes())) }
            )
        }
    }
}

@Composable
private fun GenderStep(selected: Gender?, onSelect: (Gender) -> Unit) {
    StepContainer(
        title = stringResource(R.string.onboarding_gender_title),
        subtitle = stringResource(R.string.onboarding_gender_subtitle)
    ) {
        Gender.values().forEach { gender ->
            ElevatedFilterChip(
                selected = selected == gender,
                onClick = { onSelect(gender) },
                label = { Text(stringResource(gender.displayNameRes())) }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InterestsStep(
    selected: Set<CategoryPool>,
    onToggle: (CategoryPool) -> Unit,
    customMappings: List<InterestMapping>,
    onAddCustom: (String) -> Unit,
    onRemoveCustom: (Int) -> Unit
) {
    Column {
        Text(
            text = stringResource(R.string.onboarding_interests_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = stringResource(R.string.onboarding_interests_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CategoryPool.values().forEach { cat ->
                ElevatedFilterChip(
                    selected = cat in selected,
                    onClick = { onToggle(cat) },
                    label = { Text(cat.name.lowercase().replace("_", " ")) }
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.onboarding_custom_interests_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = stringResource(R.string.onboarding_custom_interests_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        var textFieldValue by remember { mutableStateOf("") }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = textFieldValue,
                onValueChange = { textFieldValue = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.onboarding_custom_interest_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (textFieldValue.isNotBlank()) {
                        onAddCustom(textFieldValue)
                        textFieldValue = ""
                    }
                })
            )
            IconButton(onClick = {
                if (textFieldValue.isNotBlank()) {
                    onAddCustom(textFieldValue)
                    textFieldValue = ""
                }
            }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.onboarding_add_interest_cd))
            }
        }

        if (customMappings.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                customMappings.forEachIndexed { index, mapping ->
                    CustomInterestChip(mapping = mapping, onRemove = { onRemoveCustom(index) })
                }
            }
        }
    }
}

@Composable
private fun CustomInterestChip(mapping: InterestMapping, onRemove: () -> Unit) {
    val categoryLabel = mapping.category?.name?.lowercase()?.replace("_", " ")
    val label = if (categoryLabel != null) {
        "${mapping.interest} → $categoryLabel"
    } else {
        "${mapping.interest} (${stringResource(R.string.onboarding_unmapped_suffix)})"
    }
    val containerColor = when (mapping.confidence) {
        MappingConfidence.HIGH -> MaterialTheme.colorScheme.primaryContainer
        MappingConfidence.LOW -> MaterialTheme.colorScheme.tertiaryContainer
        MappingConfidence.NONE -> MaterialTheme.colorScheme.errorContainer
    }

    InputChip(
        selected = true,
        onClick = onRemove,
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        trailingIcon = {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.onboarding_remove_cd),
                modifier = Modifier.size(16.dp)
            )
        }
    )
}

@Composable
private fun ProfessionStep(selected: Profession?, onSelect: (Profession) -> Unit) {
    StepContainer(
        title = stringResource(R.string.onboarding_profession_title),
        subtitle = stringResource(R.string.onboarding_profession_subtitle)
    ) {
        Profession.values().forEach { prof ->
            ElevatedFilterChip(
                selected = selected == prof,
                onClick = { onSelect(prof) },
                label = { Text(stringResource(prof.displayNameRes())) }
            )
        }
    }
}

@Composable
private fun RegionStep(selected: Region?, onSelect: (Region) -> Unit) {
    StepContainer(
        title = stringResource(R.string.onboarding_region_title),
        subtitle = stringResource(R.string.onboarding_region_subtitle)
    ) {
        Region.values().forEach { region ->
            ElevatedFilterChip(
                selected = selected == region,
                onClick = { onSelect(region) },
                label = { Text(stringResource(region.displayNameRes())) }
            )
        }
    }
}

@Composable
private fun DoneStep() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.onboarding_done_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.onboarding_done_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepContainer(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Column {
        Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

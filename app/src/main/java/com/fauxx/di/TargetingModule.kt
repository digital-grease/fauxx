package com.fauxx.di

import android.content.Context
import com.fauxx.targeting.TargetingEngine
import com.fauxx.targeting.WeightNormalizer
import com.fauxx.targeting.layer0.UniformEntropyLayer
import com.fauxx.targeting.layer1.CustomInterestMapper
import com.fauxx.targeting.layer1.DemographicDistanceMap
import com.fauxx.targeting.layer1.DemographicProfileDao
import com.fauxx.targeting.layer1.SelfReportLayer
import com.fauxx.targeting.layer2.AdversarialScraperLayer
import com.fauxx.targeting.layer2.CategoryMapper
import com.fauxx.targeting.layer2.PlatformProfileDao
import com.fauxx.targeting.layer3.PersonaGenerator
import com.fauxx.targeting.layer3.PersonaHistoryDao
import com.fauxx.targeting.layer3.PersonaRotationLayer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing all targeting layer singletons and the TargetingEngine orchestrator.
 */
@Module
@InstallIn(SingletonComponent::class)
object TargetingModule {

    @Provides
    @Singleton
    fun provideWeightNormalizer(): WeightNormalizer = WeightNormalizer()

    @Provides
    @Singleton
    fun provideUniformEntropyLayer(): UniformEntropyLayer = UniformEntropyLayer()

    @Provides
    @Singleton
    fun provideDemographicDistanceMap(@ApplicationContext context: Context): DemographicDistanceMap =
        DemographicDistanceMap(context)

    @Provides
    @Singleton
    fun provideCustomInterestMapper(categoryMapper: CategoryMapper): CustomInterestMapper =
        CustomInterestMapper(categoryMapper)

    @Provides
    @Singleton
    fun provideSelfReportLayer(
        dao: DemographicProfileDao,
        distanceMap: DemographicDistanceMap,
        customInterestMapper: CustomInterestMapper
    ): SelfReportLayer = SelfReportLayer(dao, distanceMap, customInterestMapper)

    @Provides
    @Singleton
    fun provideAdversarialScraperLayer(dao: PlatformProfileDao): AdversarialScraperLayer =
        AdversarialScraperLayer(dao)

    @Provides
    @Singleton
    fun providePersonaGenerator(
        @ApplicationContext context: Context,
        historyDao: PersonaHistoryDao,
        demographicProfileDao: DemographicProfileDao
    ): PersonaGenerator = PersonaGenerator(context, historyDao, demographicProfileDao)

    @Provides
    @Singleton
    fun providePersonaRotationLayer(
        generator: PersonaGenerator,
        historyDao: PersonaHistoryDao
    ): PersonaRotationLayer = PersonaRotationLayer(generator, historyDao)

    @Provides
    @Singleton
    fun provideTargetingEngine(
        l0: UniformEntropyLayer,
        l1: SelfReportLayer,
        l2: AdversarialScraperLayer,
        l3: PersonaRotationLayer,
        normalizer: WeightNormalizer
    ): TargetingEngine = TargetingEngine(l0, l1, l2, l3, normalizer)
}

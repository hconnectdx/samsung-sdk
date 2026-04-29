package kr.co.hconnect.samsung_sdk.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kr.co.hconnect.samsung_sdk.tracker.HealthTrackerManager
import kr.co.hconnect.samsung_sdk.tracker.HealthTrackerProcessorImpl
import kr.co.hconnect.samsung_sdk.tracker.TrackerManager
import kr.co.hconnect.samsung_sdk.tracker.TrackerProcessor
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SamsungSdkModule {

    @Binds
    @Singleton
    abstract fun bindTrackerManager(impl: HealthTrackerManager): TrackerManager

    @Binds
    abstract fun bindTrackerProcessor(impl: HealthTrackerProcessorImpl): TrackerProcessor
}

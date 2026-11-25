package dev.flipper.zerokit.sample.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.flipper.api.Flipper
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FlipperModule {
    @Provides
    @Singleton
    fun provideFlipper(@ApplicationContext context: Context): Flipper = Flipper.create(context)
}

package dev.flipper.zerokit.sample.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.flipper.api.Flipper
import dev.flipper.zerokit.sample.data.repository.ThemeRepositoryImpl
import dev.flipper.zerokit.sample.domain.repository.ThemeRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindTheme(impl: ThemeRepositoryImpl): ThemeRepository

    companion object {
        @Provides
        @Singleton
        fun provideFlipper(@ApplicationContext context: Context): Flipper =
            Flipper.create(context)
    }
}

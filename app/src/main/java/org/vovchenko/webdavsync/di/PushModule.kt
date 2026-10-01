package org.vovchenko.webdavsync.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.vovchenko.webdavsync.push.AccountPushClients
import org.vovchenko.webdavsync.push.PushAccountClients
import org.vovchenko.webdavsync.push.PushDistributor
import org.vovchenko.webdavsync.push.UnifiedPushDistributor

@Module
@InstallIn(SingletonComponent::class)
abstract class PushModule {
    @Binds
    abstract fun bindPushDistributor(impl: UnifiedPushDistributor): PushDistributor

    @Binds
    abstract fun bindPushAccountClients(impl: AccountPushClients): PushAccountClients
}

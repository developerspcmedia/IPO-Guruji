package com.example

import android.content.Context
import com.example.data.local.IpoDatabase
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.AdminOverrideRepository
import com.example.data.repository.AllotmentRepository
import com.example.data.repository.IpoNewsRepository
import com.example.data.repository.IpoRepository
import com.example.data.repository.LiveIpoCloudRepository
import com.example.data.repository.UsIpoRepository
import com.example.data.repository.UserProfileRepository

/**
 * Application-scoped dependency container holding single shared instances of the database
 * and repositories so configuration changes reuse the same instances as retained ViewModels.
 */
class AppContainer(context: Context) {
    private val appContext: Context = context.applicationContext ?: context

    val database: IpoDatabase by lazy {
        IpoDatabase.getInstance(appContext)
    }

    val adminOverrideRepository: AdminOverrideRepository by lazy {
        AdminOverrideRepository(
            context = appContext,
            overrideDao = database.adminOverrideDao(),
        )
    }

    val liveIpoCloudRepository: LiveIpoCloudRepository by lazy {
        LiveIpoCloudRepository(appContext)
    }

    val preferencesRepository: UserPreferencesRepository by lazy {
        UserPreferencesRepository(appContext)
    }

    val ipoRepository: IpoRepository by lazy {
        IpoRepository(
            ipoDao = database.ipoDao(),
            adminOverrideRepository = adminOverrideRepository,
            liveIpoCloudRepository = liveIpoCloudRepository,
            preferencesRepository = preferencesRepository,
        )
    }

    val allotmentRepository: AllotmentRepository by lazy {
        AllotmentRepository(database.allotmentDao())
    }

    val usIpoRepository: UsIpoRepository by lazy {
        UsIpoRepository(database.usIpoDao())
    }

    val userProfileRepository: UserProfileRepository by lazy {
        UserProfileRepository(appContext)
    }

    val newsRepository: IpoNewsRepository by lazy {
        IpoNewsRepository()
    }
}

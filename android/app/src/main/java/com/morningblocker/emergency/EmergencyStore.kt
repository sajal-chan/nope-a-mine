package com.morningblocker.emergency

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

private val Context.emergencyStateStore by preferencesDataStore(name = "emergency_access")

fun emergencyRepository(context: Context, provider: suspend () -> EmergencyContext): EmergencyRepository =
    EmergencyRepository(context.applicationContext.emergencyStateStore, provider)

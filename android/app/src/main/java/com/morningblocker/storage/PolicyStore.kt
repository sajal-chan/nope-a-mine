package com.morningblocker.storage

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

// One delegate at file scope guarantees one DataStore per application file in this process.
private val Context.morningPolicyStore by preferencesDataStore(name = "morning_policy")

fun policyRepository(context: Context): PolicyRepository =
    PolicyRepository(context.applicationContext.morningPolicyStore)

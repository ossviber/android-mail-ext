package ch.protonmail.android.extidentities.presentation.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ch.protonmail.android.extidentities.domain.ExternalIdentityId
import ch.protonmail.android.extidentities.domain.usecase.GetExternalIdentity
import ch.protonmail.android.extidentities.domain.usecase.SetupExternalSentAutomation
import ch.protonmail.android.extidentities.presentation.R
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Debug-only headless trigger for the sent e-mail labeling so it can be verified
 * over adb while the device is locked: `adb shell am broadcast -n <component>
 * -a ch.protonmail.android.extidentities.RUN_AUTOMATION --ei identityId 1`.
 */
class AutomationTestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val entry = EntryPointAccessors.fromApplication(
            context.applicationContext,
            AutomationEntryPoint::class.java
        )
        CoroutineScope(Dispatchers.Default).launch {
            try {
                run(entry, context, intent)
            } catch (t: Throwable) {
                Timber.e(t, "auto-test: crashed")
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun run(entry: AutomationEntryPoint, context: Context, intent: Intent) {
        val identityId = intent.getLongExtra(EXTRA_IDENTITY_ID, 1L)
        Timber.i("auto-test: start id=" + identityId)
        val identity = entry.getExternalIdentity()(ExternalIdentityId(identityId))
        if (identity == null) {
            Timber.i("auto-test: no identity " + identityId)
            return
        }
        val labelBase = context.getString(R.string.ext_identities_automation_folder_base)
        entry.setupAutomation()(ExternalIdentityId(identityId), labelBase).fold(
            ifLeft = { error -> Timber.i("auto-test: label setup failed: " + error) },
            ifRight = { updated ->
                Timber.i(
                    "auto-test: label ok id=" + updated.sentLabelId +
                        " name=" + updated.sentLabelName
                )
            }
        )
    }

    @EntryPoint
    @InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface AutomationEntryPoint {
        fun getExternalIdentity(): GetExternalIdentity
        fun setupAutomation(): SetupExternalSentAutomation
    }

    private companion object {
        const val EXTRA_IDENTITY_ID = "identityId"
    }
}
package io.github.trevarj.motd.push

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.unifiedpush.android.connector.UnifiedPush
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Testable seam over the static UnifiedPush connector API.
 *
 * Interface only for WP-R0; WP-R2 adds the impl over the static connector and its Hilt binding.
 */
interface UnifiedPushApi {
    fun getDistributors(): List<String>

    fun getAckDistributor(): String?

    fun saveDistributor(distributor: String)

    fun registerApp(instance: String)

    fun unregisterApp(instance: String)
}

/**
 * Real [UnifiedPushApi] over the static `org.unifiedpush.android.connector.UnifiedPush` calls
 * (connector 3.3.3). Signatures verified against the artifact:
 *   getDistributors(context) -> List<String>
 *   getAckDistributor(context) -> String?
 *   saveDistributor(context, distributor)
 *   register(context, instance, ...)   // extra args default
 *   unregister(context, instance)
 *
 * All calls take the application context; the instance string is `networkId.toString()`.
 */
@Singleton
class UnifiedPushApiImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : UnifiedPushApi {
        override fun getDistributors(): List<String> = UnifiedPush.getDistributors(context)

        override fun getAckDistributor(): String? = UnifiedPush.getAckDistributor(context)

        override fun saveDistributor(distributor: String) = UnifiedPush.saveDistributor(context, distributor)

        override fun registerApp(instance: String) = UnifiedPush.register(context, instance)

        override fun unregisterApp(instance: String) = UnifiedPush.unregister(context, instance)
    }

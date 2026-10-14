package com.hazbu.xblock

import android.content.Context
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import android.os.ParcelFileDescriptor
import java.util.concurrent.atomic.AtomicBoolean

object RemoteConfig {
    private const val TAG = "xBlock"
    private val registered = AtomicBoolean(false)

    fun connect(context: Context) {
        if (!registered.compareAndSet(false, true)) return
        try {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    try {
                        val local = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                        val remote = service.getRemotePreferences(Constants.REMOTE_PREFS_GROUP)
                        val packages = local.getStringSet(Constants.KEY_PACKAGES, emptySet()) ?: emptySet()
                        remote.edit().putStringSet(Constants.KEY_PACKAGES, packages).apply()
                        val domains = AdBlockUtils.domainsFile(context)
                        if (domains.isFile && domains.length() > 0) {
                            service.deleteRemoteFile(Constants.DOMAIN_BLOOM_FILE)
                            val fd = service.openRemoteFile(Constants.DOMAIN_BLOOM_FILE)
                            ParcelFileDescriptor.AutoCloseOutputStream(fd).use { output ->
                                output.write(DomainBloom.build(domains))
                            }
                        }
                        Log.d(TAG, "Remote SDK package config synced: ${packages.size}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Remote config sync failed", e)
                    }
                }

                override fun onServiceDied(service: XposedService) {
                    Log.w(TAG, "Xposed service disconnected")
                }
            })
        } catch (e: Exception) {
            registered.set(false)
            Log.e(TAG, "Xposed service unavailable", e)
        }
    }
}

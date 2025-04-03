package com.algorigo.logger

import android.content.Context
import com.datadog.android.Datadog
import com.datadog.android.core.configuration.Configuration
import com.datadog.android.log.Logger
import com.datadog.android.log.Logs
import com.datadog.android.log.LogsConfiguration
import com.datadog.android.privacy.TrackingConsent

class DataDogLogDelegate(
    context: Context,
    clientToken: String,
    env: String,
    service: String,
    variant: String = "android",
    verboseLevel: Level = Level.DEBUG,
    remoteLevel: Level = Level.INFO,
    networkInfoEnabled: Boolean = false,
    logcatLogEnabled: Boolean = true,
    remoteSampleRate: Float = 100f,
    bundleWithTraceEnabled: Boolean = true,
    bundleWithRumEnabled: Boolean = true,
) : LogDelegate {
    private val datadogLogger = mutableMapOf<String, Logger>()
    private var remoteLevel = Level.INFO
    private var networkInfoEnabled = false
    private var logcatLogEnabled = true
    private var remoteSampleRate = 100f
    private var bundleWithTraceEnabled = true
    private var bundleWithRumEnabled = true
    private val tagMap = mutableMapOf<String, String>()
    private val attributeMap = mutableMapOf<String, String>()

    init {
        if (!Datadog.isInitialized()) {
            val configuration = Configuration.Builder(
                clientToken = clientToken,
                env = env,
                variant = variant,
                service = service,
            ).build()
            Datadog.initialize(context, configuration, TrackingConsent.GRANTED)
            Logs.enable(LogsConfiguration.Builder().build())
            Datadog.setVerbosity(verboseLevel.intValue)

            this.remoteLevel = remoteLevel
            this.networkInfoEnabled = networkInfoEnabled
            this.logcatLogEnabled = logcatLogEnabled
            this.remoteSampleRate = remoteSampleRate
            this.bundleWithTraceEnabled = bundleWithTraceEnabled
            this.bundleWithRumEnabled = bundleWithRumEnabled
        }
    }

    override fun initTag(tagName: String) {
        if (Datadog.isInitialized() && !datadogLogger.containsKey(tagName)) {
            val logger = Logger.Builder().setNetworkInfoEnabled(networkInfoEnabled)
                .setRemoteLogThreshold(remoteLevel.intValue)
                .setLogcatLogsEnabled(logcatLogEnabled)
                .setRemoteSampleRate(remoteSampleRate)
                .setBundleWithTraceEnabled(bundleWithTraceEnabled)
                .setBundleWithRumEnabled(bundleWithRumEnabled)
                .setName(tagName)
                .build()
            tagMap.forEach {
                logger.addTag(it.key, it.value)
            }
            attributeMap.forEach {
                logger.addAttribute(it.key, it.value)
            }
            datadogLogger[tagName] = logger
        }
    }

    fun setLevel(level: Level) {
        Datadog.setVerbosity(level.intValue)
    }

    fun addDDTag(tagName: String, tag: String) {
        val lowerName = tagName.lowercase()
        val lowerTag = tag.lowercase()
        tagMap[lowerName] = lowerTag
        datadogLogger.values.forEach {
            it.addTag(lowerName, lowerTag)
        }
    }

    fun removeDDTag(tagName: String) {
        val lowerName = tagName.lowercase()
        tagMap.remove(lowerName)
        datadogLogger.values.forEach {
            it.removeTag(lowerName)
        }
    }

    fun addAttribute(attributeName: String, attribute: String) {
        attributeMap[attributeName] = attribute
        datadogLogger.values.forEach {
            it.addAttribute(attributeName, attribute)
        }
    }

    fun removeAttribute(attributeName: String) {
        attributeMap.remove(attributeName)
        datadogLogger.values.forEach {
            it.removeAttribute(attributeName)
        }
    }

    @Synchronized
    fun getLogger(tagName: String): Logger? {
        return datadogLogger[tagName]
    }
}
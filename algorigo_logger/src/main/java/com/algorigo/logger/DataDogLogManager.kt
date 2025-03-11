package com.algorigo.logger

import android.content.Context
import com.datadog.android.Datadog
import com.datadog.android.core.configuration.Configuration
import com.datadog.android.log.Logger
import com.datadog.android.log.Logs
import com.datadog.android.log.LogsConfiguration
import com.datadog.android.privacy.TrackingConsent

object DataDogLogManager {
    private val datadogLogger = mutableMapOf<String, Logger>()
    private var networkInfoEnabled = true
    private var logcatLogEnabled = true
    private var remoteSampleRate = 100f
    private var bundleWithTraceEnabled = true
    private var bundleWithRumEnabled = true
    private val tagMap = mutableMapOf<String, String>()
    private val attributeMap = mutableMapOf<String, String>()

    fun initDataDog(
        context: Context,
        clientToken: String,
        env: String,
        variant: String,
        service: String,
        networkInfoEnabled: Boolean = true,
        logcatLogEnabled: Boolean = true,
        remoteSampleRate: Float = 100f,
        bundleWithTraceEnabled: Boolean = true,
        bundleWithRumEnabled: Boolean = true,
    ) {
        if (Datadog.isInitialized()) {
            return
        }

        val configuration = Configuration.Builder(
            clientToken = clientToken,
            env = env,
            variant = variant,
            service = service,
        ).build()
        Datadog.initialize(context, configuration, TrackingConsent.GRANTED)
        Logs.enable(LogsConfiguration.Builder().build())
        this.networkInfoEnabled = networkInfoEnabled
        this.logcatLogEnabled = logcatLogEnabled
        this.remoteSampleRate = remoteSampleRate
        this.bundleWithTraceEnabled = bundleWithTraceEnabled
        this.bundleWithRumEnabled = bundleWithRumEnabled
    }

    fun initTag(tag: Tag) {
        if (Datadog.isInitialized() && !datadogLogger.containsKey(tag.name)) {
            val logger = Logger.Builder().setNetworkInfoEnabled(networkInfoEnabled)
                .setLogcatLogsEnabled(logcatLogEnabled)
                .setRemoteSampleRate(remoteSampleRate)
                .setBundleWithTraceEnabled(bundleWithTraceEnabled)
                .setBundleWithRumEnabled(bundleWithRumEnabled)
                .setName(tag.name)
                .build()
            tagMap.forEach {
                logger.addTag(it.key, it.value)
            }
            attributeMap.forEach {
                logger.addAttribute(it.key, it.value)
            }
            datadogLogger[tag.name] = logger
        }
    }

    fun setLevel(level: Level) {
        Datadog.setVerbosity(level.intValue)
    }

    fun addDDTag(tagName: String, tag: String) {
        tagMap[tagName] = tag
        datadogLogger.values.forEach {
            it.addTag(tagName, tag)
        }
    }

    fun removeDDTag(tagName: String) {
        tagMap.remove(tagName)
        datadogLogger.values.forEach {
            it.removeTag(tagName)
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
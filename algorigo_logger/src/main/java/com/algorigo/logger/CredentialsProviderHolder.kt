package com.algorigo.logger

import android.content.Context
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.auth.CognitoCachingCredentialsProvider
import com.amazonaws.internal.StaticCredentialsProvider
import com.amazonaws.regions.Regions

sealed class CredentialsProviderHolder {
    abstract val credentialsProvider: AWSCredentialsProvider

    class AccessKeyProvider(accessKey: String, secretKey: String) : CredentialsProviderHolder() {
        private val basicAWSCredentials = BasicAWSCredentials(accessKey, secretKey)
        override val credentialsProvider: AWSCredentialsProvider
            get() = StaticCredentialsProvider(basicAWSCredentials)
    }

    class IdentityPoolProvider(context: Context, identityPoolId: String, awsRegionString: String) : CredentialsProviderHolder() {
        private val cognitoCachingCredentialsProvider = CognitoCachingCredentialsProvider(context, identityPoolId, Regions.fromName(awsRegionString))
        override val credentialsProvider: AWSCredentialsProvider
            get() = cognitoCachingCredentialsProvider
    }
}

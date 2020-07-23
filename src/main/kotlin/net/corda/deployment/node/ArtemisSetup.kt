package net.corda.deployment.node

import io.kubernetes.client.openapi.ApiClient
import net.corda.deployment.node.kubernetes.SecretCreator
import org.apache.commons.lang3.RandomStringUtils

class ArtemisSetup(private val namespace: String, private val randomSuffix: String) {

    fun generateArtemisSecrets(api: () -> ApiClient): ArtemisSecrets {
        val artemisSecretsName = "artemis-${randomSuffix}"
        val artemisStorePassSecretKey = "artemisstorepass"
        val artemisTrustPassSecretKey = "artemistrustpass"
        val artemisClusterPassSecretKey = "artemisclusterpass";
        val artemisSecret = SecretCreator.createStringSecret(
            artemisSecretsName,
            listOf(
                artemisStorePassSecretKey to RandomStringUtils.randomAlphanumeric(32),
                artemisTrustPassSecretKey to RandomStringUtils.randomAlphanumeric(32),
                artemisClusterPassSecretKey to RandomStringUtils.randomAlphanumeric(32)
            ).toMap()
            , namespace, api
        )
        return ArtemisSecrets(
            artemisSecretsName,
            artemisStorePassSecretKey,
            artemisTrustPassSecretKey,
            artemisClusterPassSecretKey
        )
    }
}

data class ArtemisSecrets(
    val secretName: String,
    val keyStorePasswordKey: String,
    val trustStorePasswordKey: String,
    val clusterPasswordKey: String
)
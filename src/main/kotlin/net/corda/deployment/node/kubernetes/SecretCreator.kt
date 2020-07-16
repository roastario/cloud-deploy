package net.corda.deployment.node.kubernetes

import com.microsoft.azure.management.containerservice.KubernetesCluster
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1Secret
import io.kubernetes.client.openapi.models.V1SecretBuilder
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import net.corda.deployment.node.kubernetes.SecretCreator.Companion.createStringSecret
import java.io.InputStreamReader


class SecretCreator {
    companion object {
        fun createStringSecret(
            name: String,
            secrets: Map<String, String>,
            namespace: String,
            client: ApiClient
        ): V1Secret {

            //as secrets are base64 we USUALLY would encode them as base64, but due to k8s-sdk "feature" it encodes them for you automatically
            //Base64.getEncoder().encode(it.value.toByteArray())
            val encodedSecrets = secrets.map { it.key to it.value.toByteArray(Charsets.UTF_8) }.toMap()
            return createByteArraySecret(name, encodedSecrets, namespace, client)
        }

        fun createByteArraySecret(secretsName: String, secrets: Map<String, ByteArray>, namespace: String, client: ApiClient): V1Secret {
            val coreV1Api = CoreV1Api(client)
            val secret = V1SecretBuilder()
                .withApiVersion("v1")
                .withKind("Secret")
                .withNewMetadata()
                .withCreationTimestamp(null)
                .withName(secretsName)
                .withNamespace(namespace)
                .endMetadata()
                .withData(secrets)
                .withType("Opaque")
                .build()

            return try {
                coreV1Api.createNamespacedSecret(namespace, secret, "true", null, null)
            } catch (e: ApiException) {
                println(e.responseBody)
                throw e
            }
        }

    }
}


fun KubernetesCluster.deploySecret(
    name: String,
    secrets: Map<String, String>,
    namespace: String
): V1Secret {
    val apiClientToUse = this.adminKubeConfigContent().inputStream().use { config ->
        ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(InputStreamReader(config))).build()
    }.also { it.isDebugging = true }
    return createStringSecret(name, secrets, namespace, apiClientToUse)
}

fun main() {
    val apiClient = ClientBuilder.defaultClient().also { it.isDebugging = true }
    createStringSecret("test-secret", mapOf("username" to "stefano"), "default", apiClient)
    println()
}
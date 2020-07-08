package net.corda.deployment.node

import com.microsoft.azure.credentials.AzureCliCredentials
import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.containerservice.KubernetesCluster
import com.microsoft.rest.LogLevel
import io.kubernetes.client.custom.IntOrString
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.Configuration
import io.kubernetes.client.openapi.models.*
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import io.kubernetes.client.util.Yaml
import java.io.InputStreamReader
import java.net.URL

fun deployHelloWorld(createdFloatCluster: KubernetesCluster) {
    val apiClientTo = createdFloatCluster.adminKubeConfigContent().inputStream().use { config ->
        ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(InputStreamReader(config))).build()
    }
    Configuration.setDefaultApiClient(apiClientTo)

    val l = Yaml.loadAll(
        URL("https://raw.githubusercontent.com/kubernetes/ingress-nginx/master/deploy/static/provider/cloud/deploy.yaml").readText(
            Charsets.UTF_8
        )
    )

    l.forEach { simpleApply.create(it, "ingress-nginx") }

    simpleApply.create(V1NamespaceBuilder().withNewMetadata().withName("helloworld").endMetadata().build())

    val aksHelloDeploymentOne = V1DeploymentBuilder()
        .withKind("Deployment")
        .withApiVersion("apps/v1")
        .withNewMetadata()
        .withName("aks-helloworld-one")
        .endMetadata()
        .withNewSpec()
        .withReplicas(1)
        .withNewSelector()
        .withMatchLabels(("app" to "aks-helloworld-one").asMap())
        .endSelector()
        .withNewTemplate()
        .withNewMetadata()
        .withLabels(("app" to "aks-helloworld-one").asMap())
        .endMetadata()
        .withNewSpec()
        .addNewContainer()
        .withName("aks-helloworld-one")
        .withImage("neilpeterson/aks-helloworld:v1")
        .withPorts(
            listOf(
                V1ContainerPortBuilder().withContainerPort(80).build()
            )
        )
        .withEnv(V1EnvVarBuilder().withName("TITLE").withValue("Welcome to Azure Kubernetes Service (AKS)").build())
        .endContainer()
        .endSpec()
        .endTemplate()
        .endSpec()
        .build()

    val aksHelloDeploymentTwo = V1DeploymentBuilder()
        .withKind("Deployment")
        .withApiVersion("apps/v1")
        .withNewMetadata()
        .withName("aks-helloworld-two")
        .endMetadata()
        .withNewSpec()
        .withReplicas(1)
        .withNewSelector()
        .withMatchLabels(("app" to "aks-helloworld-two").asMap())
        .endSelector()
        .withNewTemplate()
        .withNewMetadata()
        .withLabels(("app" to "aks-helloworld-two").asMap())
        .endMetadata()
        .withNewSpec()
        .addNewContainer()
        .withName("aks-helloworld-two")
        .withImage("neilpeterson/aks-helloworld:v1")
        .withPorts(
            listOf(
                V1ContainerPortBuilder().withContainerPort(80).build()
            )
        )
        .withEnv(V1EnvVarBuilder().withName("TITLE").withValue("AKS Ingress Demo").build())
        .endContainer()
        .endSpec()
        .endTemplate()
        .endSpec()
        .build()

    val helloWorldServiceOne = V1ServiceBuilder()
        .withApiVersion("v1")
        .withKind("Service")
        .withNewMetadata()
        .withName("aks-helloworld-one")
        .endMetadata()
        .withNewSpec()
        .withType("ClusterIP")
        .withPorts(V1ServicePortBuilder().withPort(80).build())
        .withSelector(("app" to "aks-helloworld-one").asMap())
        .endSpec()
        .build()

    val helloWorldServiceTwo = V1ServiceBuilder()
        .withApiVersion("v1")
        .withKind("Service")
        .withNewMetadata()
        .withName("aks-helloworld-two")
        .endMetadata()
        .withNewSpec()
        .withType("ClusterIP")
        .withPorts(V1ServicePortBuilder().withPort(80).build())
        .withSelector(("app" to "aks-helloworld-two").asMap())
        .endSpec()
        .build()

    val helloWorldIngress = ExtensionsV1beta1IngressBuilder()
        .withApiVersion("extensions/v1beta1")
        .withKind("Ingress")
        .withNewMetadata()
        .withName("hello-world-ingress")
        .withAnnotations(
            listOf(
                "kubernetes.io/ingress.class" to "nginx",
                "nginx.ingress.kubernetes.io/ssl-redirect" to "false",
                "nginx.ingress.kubernetes.io/rewrite-target" to "/$2"
            ).toMap()
        ).endMetadata()
        .withNewSpec()
        .withRules(
            ExtensionsV1beta1IngressRule().http(
                ExtensionsV1beta1HTTPIngressRuleValue().paths(
                    listOf(
                        ExtensionsV1beta1HTTPIngressPath().backend(
                            ExtensionsV1beta1IngressBackend()
                                .serviceName("aks-helloworld-one")
                                .servicePort(IntOrString(80))
                        ).path("/(.*)"),
                        ExtensionsV1beta1HTTPIngressPath().backend(
                            ExtensionsV1beta1IngressBackend()
                                .serviceName("aks-helloworld-two")
                                .servicePort(IntOrString(80))
                        ).path("/hello-world-two(/|\$)(.*)")
                    )
                )
            )
        )
        .endSpec()
        .build()

    val helloWorldStaticIngress = ExtensionsV1beta1IngressBuilder()
        .withApiVersion("extensions/v1beta1")
        .withKind("Ingress")
        .withNewMetadata()
        .withName("hello-world-ingress-static")
        .withAnnotations(
            listOf(
                "kubernetes.io/ingress.class" to "nginx",
                "nginx.ingress.kubernetes.io/ssl-redirect" to "false",
                "nginx.ingress.kubernetes.io/rewrite-target" to "/static/$2"
            ).toMap()
        ).endMetadata()
        .withNewSpec()
        .withRules(
            ExtensionsV1beta1IngressRule().http(
                ExtensionsV1beta1HTTPIngressRuleValue().paths(
                    listOf(
                        ExtensionsV1beta1HTTPIngressPath().backend(
                            ExtensionsV1beta1IngressBackend()
                                .serviceName("aks-helloworld-one")
                                .servicePort(IntOrString(80))
                        ).path("/static(/|\$)(.*)")
                    )
                )
            )
        )
        .endSpec()
        .build()


    try {
        simpleApply.create(aksHelloDeploymentOne, "helloworld")
        simpleApply.create(aksHelloDeploymentTwo, "helloworld")
        simpleApply.create(helloWorldServiceOne, "helloworld")
        simpleApply.create(helloWorldServiceTwo, "helloworld")
        simpleApply.create(helloWorldIngress, "helloworld")
        simpleApply.create(helloWorldStaticIngress, "helloworld")
    } catch (e: ApiException) {
        System.err.println(e.responseBody)
        throw e
    }


}

private fun <A, B> Pair<A, B>.asMap(): Map<A, B> {
    return listOf(this).toMap()
}

fun main() {
    val a: Azure = Azure.configure()
        .withLogLevel(LogLevel.BODY_AND_HEADERS)
        .authenticate(AzureCliCredentials.create())
        .withSubscription("c412941a-4362-4923-8737-3d33a8d1cdc6")

    val byResourceGroup = a.kubernetesClusters().getByResourceGroup("stefano-playground", "test-cluster1jt0o4b-floats")
    deployHelloWorld(byResourceGroup)
}
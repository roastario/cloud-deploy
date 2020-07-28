package net.corda.deployment.node

import com.google.gson.reflect.TypeToken
import io.kubernetes.client.PodLogs
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.apis.BatchV1Api
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.*
import io.kubernetes.client.util.Watch
import net.corda.deployment.node.storage.AzureFilesDirectory
import okhttp3.OkHttpClient
import org.apache.commons.io.IOUtils
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException


fun AzureFilesDirectory.toK8sMount(mountName: String, readOnly: Boolean): V1Volume {
    return V1VolumeBuilder()
        .withName(mountName)
        .withNewAzureFile()
        .withShareName(this.legacyClient.name)
        .withSecretName(this.azureFileSecrets.secretName)
        .withReadOnly(readOnly)
        .endAzureFile()
        .build()
}

fun azureFileMount(
    mountName: String,
    share: AzureFilesDirectory,
    readOnly: Boolean
): V1Volume {
    return V1VolumeBuilder()
        .withName(mountName)
        .withNewAzureFile()
        .withShareName(share.legacyClient.name)
        .withSecretName(share.azureFileSecrets.secretName)
        .withReadOnly(readOnly)
        .endAzureFile()
        .build()
}

fun secretVolumeWithAll(
    mountName: String,
    secretName: String
): V1Volume {
    return V1VolumeBuilder()
        .withName(mountName)
        .withNewSecret()
        .withSecretName(secretName)
        .endSecret()
        .build()
}

fun secretEnvVar(
    key: String,
    secretName: String,
    secretKey: String
): V1EnvVar {
    return V1EnvVarBuilder().withName(key)
        .withNewValueFrom()
        .withNewSecretKeyRef()
        .withName(secretName)
        .withKey(secretKey)
        .endSecretKeyRef()
        .endValueFrom()
        .build()
}

fun baseSetupJobBuilder(
    jobName: String,
    command: List<String>
): V1PodSpecFluent.ContainersNested<V1PodTemplateSpecFluent.SpecNested<V1JobSpecFluent.TemplateNested<V1JobFluent.SpecNested<V1JobBuilder>>>> {
    return V1JobBuilder()
        .withApiVersion("batch/v1")
        .withKind("Job")
        .withNewMetadata()
        .withName(jobName)
        .endMetadata()
        .withNewSpec()
        .withTtlSecondsAfterFinished(100)
        .withNewTemplate()
        .withNewSpec()
        .addNewContainer()
        .withName(jobName)
        .withImage("corda/enterprise-setup:4.5")
        .withImagePullPolicy("IfNotPresent")
        .withCommand(command)
}

fun keyValueEnvVar(key: String?, value: String?): V1EnvVar {
    return V1EnvVarBuilder()
        .withName(key)
        .withValue(value)
        .build()
}

fun licenceAcceptEnvVar() = keyValueEnvVar("ACCEPT_LICENSE", "Y")

fun waitForJob(
    job: V1Job,
    namespace: String,
    clientSource: () -> ApiClient,
    duration: Duration = Duration.ofMinutes(5)
): V1Job {
    val client = clientSource()
    val httpClient: OkHttpClient = client.httpClient.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()
    client.httpClient = httpClient
    val api = BatchV1Api(client)
    val watch: Watch<V1Job> = Watch.createWatch(
        client,
        api.listNamespacedJobCall(
            namespace,
            null,
            null,
            null,
            null,
            "job-name=${job.metadata?.name}",
            null,
            null,
            Math.toIntExact(duration.seconds),
            true,
            null
        ),
        object : TypeToken<Watch.Response<V1Job>>() {}.type
    )

    watch.use {
        watch.forEach {
            if (it.`object`.status?.succeeded == 1) {
                return it.`object`
            } else {
                println("job ${job.metadata?.name} has not completed yet")
            }
        }
    }

    throw TimeoutException("job ${job.metadata?.name} did not complete within expected time")
}

fun dumpLogsForJob(job: V1Job, clientSource: () -> ApiClient) {
    val client = clientSource()
    val pod = CoreV1Api(client).listNamespacedPod(
        "testingzone",
        "true",
        null,
        null,
        null,
        "job-name=${job.metadata?.name}", 10, null, 30, false
    ).items.first()
    val logs = PodLogs(client.also { it.httpClient = it.httpClient.newBuilder().readTimeout(0, TimeUnit.SECONDS).build() })
    val logStream = logs.streamNamespacedPodLog(pod)
    logStream.use {
        IOUtils.copy(it, System.out, 128)
    }
}
package net.corda.deployment.node

import com.microsoft.azure.credentials.AzureCliCredentials
import com.microsoft.azure.management.Azure
import com.microsoft.rest.LogLevel
import io.kubernetes.client.PodLogs
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.*
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.Yaml
import net.corda.deployment.node.config.ConfigGenerators
import net.corda.deployment.node.database.H2_DB
import net.corda.deployment.node.hsm.KeyVaultCreator
import net.corda.deployment.node.kubernetes.SecretCreator
import net.corda.deployment.node.kubernetes.allowAllFailures
import net.corda.deployment.node.principals.ServicePrincipalCreator
import net.corda.deployment.node.storage.AzureFileShareCreator
import net.corda.deployment.node.storage.AzureFilesDirectory
import net.corda.deployment.node.storage.uploadFromByteArray
import net.corda.deployments.node.config.AzureKeyVaultConfigParams
import net.corda.deployments.node.config.NodeConfigParams
import org.apache.commons.io.IOUtils
import org.apache.commons.lang3.RandomStringUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.random.nextUInt


@ExperimentalUnsignedTypes
fun main(args: Array<String>) {

    val nmsSetup = Yaml.loadAll(Thread.currentThread().contextClassLoader.getResourceAsStream("yaml/dummynms.yaml").reader())
    allowAllFailures { simpleApply.apply(nmsSetup, namespace = "testingzone") }
    val bouncyCastleProvider = BouncyCastleProvider()
    Security.addProvider(bouncyCastleProvider)

    val mngAzure: Azure = Azure.configure()
        .withLogLevel(LogLevel.BODY_AND_HEADERS)
        .authenticate(AzureCliCredentials.create())
        .withSubscription("c412941a-4362-4923-8737-3d33a8d1cdc6")


    val resourceGroup = mngAzure.resourceGroups().getByName("stefano-playground")
    val randSuffix = Random.nextUInt().toString(36).toLowerCase()

    val defaultClient = ClientBuilder.defaultClient().also { it.isDebugging = true }

    val azureFileShareCreator = AzureFileShareCreator(azure = mngAzure, resourceGroup = resourceGroup, runSuffix = randSuffix)

    val configDir = azureFileShareCreator.createDirectoryFor("config")
    val hsmDir = azureFileShareCreator.createDirectoryFor("hsm")
    val azureFilesSecretName = "azure-files-secret-$randSuffix"
    SecretCreator.createStringSecret(
        azureFilesSecretName,
        listOf(
            "azurestorageaccountname" to configDir.storageAccount.name(),
            "azurestorageaccountkey" to configDir.storageAccount.keys[0].value()
        ).toMap()
        , "testingzone", defaultClient
    )

    val dbParams = H2_DB

    val nodeConfigParams = NodeConfigParams.builder()
        .withX500Name("O=BigCorporation,L=New York,C=US")
        .withEmailAddress("stefano.franz@r3.com")
        .withNodeSSLKeystorePassword("thisIsAP@ssword")
        .withNodeTrustStorePassword("thisIsAP@ssword")
        .withP2pAddress("localhost")
        .withP2pPort(1234)
        .withArtemisServerAddress("localhost")
        .withArtemisServerPort(1234)
        .withArtemisSSLKeyStorePath("/opt/corda/somewhere")
        .withArtemisSSLKeyStorePass("thisIsAP@ssword")
        .withArtemisTrustStorePath("/opt/corda/somewhere")
        .withArtemisTrustStorePass("thisIsAP@ssword")
        .withRpcPort(1234)
        .withRpcAdminPort(1234)
        .withDoormanURL("http://networkservices:8080")
        .withNetworkMapURL("http://networkservices:8080")
        .withRpcUsername("u")
        .withRpcPassword("p")
        .withDataSourceClassName(dbParams.type.dataSourceClass)
        .withDataSourceURL(dbParams.jdbcURL)
        .withDataSourceUsername(dbParams.username)
        .withDataSourcePassword(dbParams.password)
        .withAzureKeyVaultConfPath(NodeConfigParams.NODE_AZ_KV_CONFIG_PATH)
        .build()

    val servicePrincipalCreator = ServicePrincipalCreator(azure = mngAzure, resourceGroup = resourceGroup, runSuffix = randSuffix)
    val keyVaultCreator = KeyVaultCreator(azure = mngAzure, resourceGroup = resourceGroup, runSuffix = randSuffix)
    val servicePrincipal = servicePrincipalCreator.createClusterServicePrincipal()
    val vault = keyVaultCreator.createKeyVaultAndConfigureServicePrincipalAccess(servicePrincipal)

    val keyVaultParams = AzureKeyVaultConfigParams
        .builder()
        .withServicePrincipalCredentialsFilePath(AzureKeyVaultConfigParams.CREDENTIALS_P12_PATH)
        .withServicePrincipalCredentialsFilePassword(servicePrincipal.p12FilePassword)
        .withKeyVaultClientId(servicePrincipal.servicePrincipal.applicationId())
        .withKeyAlias(servicePrincipal.p12KeyAlias)
        .withKeyVaultURL(vault.vaultUri())
        .withKeyVaultProtectionMode(AzureKeyVaultConfigParams.KEY_PROTECTION_MODE_SOFTWARE)
        .build()

    val keyVaultCredentialFileReference =
        hsmDir.fileShare.rootDirectoryReference.getFileReference(AzureKeyVaultConfigParams.CREDENTIALS_P12_FILENAME)
    //upload p12 ready for use to auth
    keyVaultCredentialFileReference.uploadFromByteArray(servicePrincipal.p12File.readBytes())

    val nodeConfigFileReference = configDir.fileShare.rootDirectoryReference.getFileReference(NodeConfigParams.NODE_CONFIG_FILENAME)
    val nodeConf = ConfigGenerators.generateConfigFromParams(nodeConfigParams)
    //upload node.conf
    nodeConfigFileReference.uploadFromByteArray(nodeConf.toByteArray())


    val azureKeyVaultConfigFileReference =
        configDir.fileShare.rootDirectoryReference.getFileReference(NodeConfigParams.NODE_AZ_KV_CONFIG_FILENAME)
    val azKvConf = ConfigGenerators.generateConfigFromParams(keyVaultParams)
    azureKeyVaultConfigFileReference.uploadFromByteArray(azKvConf.toByteArray())

    val certificatesShare = azureFileShareCreator.createDirectoryFor("certificates")

    val jobName = "initial-registration-$randSuffix"

    val initialRegistrationJob = initialRegistrationJob(jobName, azureFilesSecretName, hsmDir, configDir, certificatesShare)

    val artemisStoreDirectory = azureFileShareCreator.createDirectoryFor("artemisstores")

    val artemisSecretsName = "artemis-${randSuffix}"
    val artemisStorePassSecretKey = "artemisstorepass"
    val artemisTrustPassSecretKey = "artemistrustpass"
    SecretCreator.createStringSecret(
        artemisSecretsName,
        listOf(
            artemisStorePassSecretKey to RandomStringUtils.randomAlphanumeric(32),
            artemisTrustPassSecretKey to RandomStringUtils.randomAlphanumeric(32)
        ).toMap()
        , "testingzone", defaultClient
    )
    val generateArtemisStoresJobName = "gen-stores-${randSuffix}"
    val generateArtemisStoresJob = generateArtemisStoresJob(
        generateArtemisStoresJobName,
        azureFilesSecretName,
        artemisSecretsName,
        artemisStorePassSecretKey,
        artemisTrustPassSecretKey,
        artemisStoreDirectory
    )



    simpleApply.create(generateArtemisStoresJob, "testingzone")



    while (CoreV1Api(defaultClient).listNamespacedPod(
            "testingzone",
            "true",
            null,
            null,
            null,
            "job-name=${jobName}", 10, null, 30, false
        ).items.firstOrNull()?.status?.containerStatuses?.firstOrNull { it.ready }?.ready != true
    ) {
        println("No matching job, waiting")
        Thread.sleep(5000)
    }


    val pod = CoreV1Api(defaultClient).listNamespacedPod(
        "testingzone",
        "true",
        null,
        null,
        null,
        "job-name=${jobName}", 10, null, 30, false
    ).items.first()
    val logs = PodLogs(defaultClient.also { it.httpClient = it.httpClient.newBuilder().readTimeout(0, TimeUnit.SECONDS).build() })
    IOUtils.copy(logs.streamNamespacedPodLog(pod), System.out, 128)

}

private fun generateArtemisStoresJob(
    jobName: String,
    azureFilesSecretName: String,
    artemisSecretsName: String,
    artemisStorePassSecretKey: String,
    artemisTrustPassSecretKey: String,
    workingDir: AzureFilesDirectory
): V1Job {
    val initialRegistrationJob = V1JobBuilder()
        .withApiVersion("batch/v1")
        .withKind("Job")
        .withNewMetadata()
        .withName(jobName)
        .endMetadata()
        .withNewSpec()
        .withNewTemplate()
        .withNewSpec()
        .addNewContainer()
        .withName(jobName)
        .withImage("corda/setup:latest")
        .withCommand(listOf("generate-artemis-keystores"))
        .withVolumeMounts(
            V1VolumeMountBuilder()
                .withName("azureworkingdir")
                .withMountPath("/tmp/artemisGeneration").build()
        )
        .withImagePullPolicy("IfNotPresent")
        .withEnv(
            V1EnvVarBuilder().withName("WORKING_DIR").withValue("/tmp/artemisGeneration").build(),
            V1EnvVarBuilder().withName("ARTEMIS_STORE_PASS")
                .withNewValueFrom()
                .withNewSecretKeyRef()
                .withName(artemisSecretsName)
                .withKey(artemisStorePassSecretKey)
                .endSecretKeyRef()
                .endValueFrom()
                .build(),
            V1EnvVarBuilder().withName("ARTEMIS_TRUST_PASS").withNewValueFrom()
                .withNewSecretKeyRef()
                .withName(artemisSecretsName)
                .withKey(artemisTrustPassSecretKey)
                .endSecretKeyRef()
                .endValueFrom()
                .build()
        )
        .endContainer()
        .withVolumes(
            V1VolumeBuilder()
                .withName("azureworkingdir")
                .withNewAzureFile()
                .withShareName(workingDir.fileShare.name)
                .withSecretName(azureFilesSecretName)
                .withReadOnly(false).endAzureFile().build()
        )
        .withRestartPolicy("Never")
        .endSpec()
        .endTemplate()
        .endSpec()
        .build()
    return initialRegistrationJob
}

private fun initialRegistrationJob(
    jobName: String,
    azureFilesSecretName: String,
    hsmDir: AzureFilesDirectory,
    configDir: AzureFilesDirectory,
    certificatesShare: AzureFilesDirectory
): V1Job {
    val initialRegistrationJob = V1JobBuilder()
        .withApiVersion("batch/v1")
        .withKind("Job")
        .withNewMetadata()
        .withName(jobName)
        .endMetadata()
        .withNewSpec()
        .withNewTemplate()
        .withNewSpec()
        .addNewContainer()
        .withName(jobName)
        .withImage("corda/setup:latest")
        .withCommand(listOf("perform-registration"))
        .withVolumeMounts(
            V1VolumeMountBuilder()
                .withName("azurehsmcredentialsdir")
                .withMountPath(AzureKeyVaultConfigParams.CREDENTIALS_DIR).build(),
            V1VolumeMountBuilder()
                .withName("azurecordaconfigdir")
                .withMountPath(NodeConfigParams.NODE_CONFIG_DIR).build(),
            V1VolumeMountBuilder()
                .withName("azurecordacertificatesdir")
                .withMountPath(NodeConfigParams.NODE_CERTIFICATES_DIR).build()
//            V1VolumeMountBuilder()
//                .withName("azureCordaPersistenceDir")
//                .withMountPath(NodeConfigParams.NODE_BASE_DIR + "/" + "persistence").build()
        )
        .withImagePullPolicy("IfNotPresent")
        .withEnv(
            V1EnvVarBuilder().withName("TRUST_ROOT_DOWNLOAD_URL").withValue("http://networkservices:8080/truststore").build(),
            V1EnvVarBuilder().withName("TRUST_ROOT_PATH").withValue(NodeConfigParams.NODE_NETWORK_TRUST_ROOT_PATH).build(),
            V1EnvVarBuilder().withName("TRUSTSTORE_PASSWORD").withValue("trustpass").build(),
            V1EnvVarBuilder().withName("BASE_DIR").withValue(NodeConfigParams.NODE_BASE_DIR).build(),
            V1EnvVarBuilder().withName("CONFIG_FILE_PATH").withValue(NodeConfigParams.NODE_CONFIG_PATH).build(),
            V1EnvVarBuilder().withName("CERTIFICATE_SAVE_FOLDER").withValue(NodeConfigParams.NODE_CERTIFICATES_DIR).build()
        )
        .endContainer()
        .withVolumes(
            V1VolumeBuilder()
                .withName("azurehsmcredentialsdir")
                .withNewAzureFile()
                .withShareName(hsmDir.fileShare.name)
                .withSecretName(azureFilesSecretName)
                .withReadOnly(true).endAzureFile().build(),
            V1VolumeBuilder()
                .withName("azurecordaconfigdir")
                .withNewAzureFile()
                .withShareName(configDir.fileShare.name)
                .withSecretName(azureFilesSecretName)
                .withReadOnly(true).endAzureFile()
                .build(),
            V1VolumeBuilder()
                .withName("azurecordacertificatesdir")
                .withNewAzureFile()
                .withShareName(certificatesShare.fileShare.name)
                .withSecretName(azureFilesSecretName)
                .withReadOnly(false).endAzureFile().build()

        )
        .withRestartPolicy("Never")
        .endSpec()
        .endTemplate()
        .endSpec()
        .build()
    return initialRegistrationJob
}
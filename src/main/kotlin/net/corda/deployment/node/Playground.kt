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
import net.corda.deployments.node.config.AzureKeyVaultConfigParams
import net.corda.deployments.node.config.BridgeConfigParams
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

    val certificatesShare = azureFileShareCreator.createDirectoryFor("certificates")
    val networkParametersShare = azureFileShareCreator.createDirectoryFor("network-files")
    val azureFilesSecretName = "azure-files-secret-$randSuffix"
    SecretCreator.createStringSecret(
        azureFilesSecretName,
        listOf(
            "azurestorageaccountname" to certificatesShare.storageAccount.name(),
            "azurestorageaccountkey" to certificatesShare.storageAccount.keys[0].value()
        ).toMap()
        , "testingzone", defaultClient
    )

    val dbParams = H2_DB

    val nodeSSLStorePassword = RandomStringUtils.randomAlphanumeric(20)
    val nodeTrustStorePassword = RandomStringUtils.randomAlphanumeric(20)

    val nodeConfigParams = NodeConfigParams.builder()
        .withX500Name("O=BigCorporation,L=New York,C=US")
        .withEmailAddress("stefano.franz@r3.com")
        .withNodeSSLKeystorePassword(NodeConfigParams.NODE_SSL_KEYSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .withNodeTrustStorePassword(NodeConfigParams.NODE_TRUSTSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .withP2pAddress("localhost")
        .withP2pPort(1234)
        .withArtemisServerAddress("localhost")
        .withArtemisServerPort(1234)
        .withArtemisSSLKeyStorePath(NodeConfigParams.NODE_ARTEMIS_SSL_KEYSTORE_PATH)
        .withArtemisSSLKeyStorePass(NodeConfigParams.NODE_ARTEMIS_SSL_KEYSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .withArtemisTrustStorePath(NodeConfigParams.NODE_ARTEMIS_TRUSTSTORE_PATH)
        .withArtemisTrustStorePass(NodeConfigParams.NODE_ARTEMIS_TRUSTSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
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
        .withServicePrincipalCredentialsFilePassword("\${${AzureKeyVaultConfigParams.KEY_VAULT_CERTIFICATES_PASSWORD_ENV_VAR_NAME}}")
        .withKeyVaultClientId("\${${AzureKeyVaultConfigParams.KEY_VAULT_CLIENT_ID_ENV_VAR_NAME}}")
        .withKeyAlias(servicePrincipal.p12KeyAlias)
        .withKeyVaultURL(vault.vaultUri())
        .withKeyVaultProtectionMode(AzureKeyVaultConfigParams.KEY_PROTECTION_MODE_SOFTWARE)
        .build()


    val nodeConf = ConfigGenerators.generateConfigFromParams(nodeConfigParams)
    val azKvConf = ConfigGenerators.generateConfigFromParams(keyVaultParams)
    val nodeConfigSecretName = "node-config-secrets-$randSuffix"

    val nodeConfigSecrets = SecretCreator.createStringSecret(
        nodeConfigSecretName,
        listOf(
            NodeConfigParams.NODE_CONFIG_FILENAME to nodeConf,
            NodeConfigParams.NODE_AZ_KV_CONFIG_FILENAME to azKvConf
        ).toMap()
        , "testingzone", defaultClient
    )

    val keyVaultCredentialsSecretName = "az-kv-password-secrets-$randSuffix"
    val azKeyVaultCredentialsFilePasswordKey = "az-kv-password"
    val azKeyVaultCredentialsClientIdKey = "az-kv-client-id"

    val keyVaultCredentialsPasswordSecret = SecretCreator.createStringSecret(
        keyVaultCredentialsSecretName,
        listOf(
            azKeyVaultCredentialsFilePasswordKey to servicePrincipal.p12FilePassword,
            azKeyVaultCredentialsClientIdKey to servicePrincipal.servicePrincipal.applicationId()
        ).toMap()
        , "testingzone", defaultClient
    )

    val p12FileSecretName = "keyvault-auth-file-secrets-$randSuffix"
    SecretCreator.createByteArraySecret(
        p12FileSecretName,
        listOf(
            AzureKeyVaultConfigParams.CREDENTIALS_P12_FILENAME to servicePrincipal.p12Bytes
        ).toMap(),
        "testingzone", defaultClient
    )


    val jobName = "initial-registration-$randSuffix"

    val initialRegistrationJob = initialRegistrationJob(
        jobName,
        azureFilesSecretName,
        nodeConfigSecretName,
        keyVaultCredentialsSecretName,
        p12FileSecretName,
        azKeyVaultCredentialsFilePasswordKey,
        azKeyVaultCredentialsClientIdKey,
        certificatesShare,
        networkParametersShare
    )

    val artemisStoreDirectory = azureFileShareCreator.createDirectoryFor("artemisstores")

    val artemisSecretsName = "artemis-${randSuffix}"
    val artemisStorePassSecretKey = "artemisstorepass"
    val artemisTrustPassSecretKey = "artemistrustpass"
    val artemisSecret = SecretCreator.createStringSecret(
        artemisSecretsName,
        listOf(
            artemisStorePassSecretKey to RandomStringUtils.randomAlphanumeric(32),
            artemisTrustPassSecretKey to RandomStringUtils.randomAlphanumeric(32)
        ).toMap()
        , "testingzone", defaultClient
    )
    val generateArtemisStoresJobName = "gen-artemis-stores-${randSuffix}"


    val tunnelStoresDirectory = azureFileShareCreator.createDirectoryFor("tunnelstores")
    val tunnelSecretName = "tunnelstoresecret"
    val tunnelEntryPasswordKey = "tunnelentrypassword"
    val tunnelKeyStorePasswordKey = "tunnelsslkeystorepassword"
    val tunnelTrustStorePasswordKey = "tunneltruststorepassword";
    val createStringSecret = SecretCreator.createStringSecret(
        tunnelSecretName,
        listOf(
            tunnelEntryPasswordKey to RandomStringUtils.randomAlphanumeric(32),
            tunnelKeyStorePasswordKey to RandomStringUtils.randomAlphanumeric(32),
            tunnelTrustStorePasswordKey to RandomStringUtils.randomAlphanumeric(32)
        ).toMap()
        , "testingzone", defaultClient
    )

    val generateTunnelStoresJobName = "gen-tunnel-stores-${randSuffix}"

    val generateArtemisStoresJob = generateArtemisStoresJob(
        generateArtemisStoresJobName,
        azureFilesSecretName,
        artemisSecretsName,
        artemisStorePassSecretKey,
        artemisTrustPassSecretKey,
        artemisStoreDirectory
    )

    val generateTunnelStoresJob = generateTunnelStores(
        generateTunnelStoresJobName,
        azureFilesSecretName,
        tunnelSecretName,
        tunnelKeyStorePasswordKey,
        tunnelTrustStorePasswordKey,
        tunnelEntryPasswordKey,
        tunnelStoresDirectory
    )

    simpleApply.create(initialRegistrationJob, "testingzone")
    simpleApply.create(generateArtemisStoresJob, "testingzone")
    simpleApply.create(generateTunnelStoresJob, "testingzone")



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


private fun generateTunnelStores(
    jobName: String,
    azureFilesSecretName: String,
    tunnelSecretName: String,
    tunnelKeyStorePasswordKey: String,
    tunnelTrustStorePasswordKey: String,
    tunnelEntryPasswordKey: String,
    workingDirShare: AzureFilesDirectory
): V1Job {
    val workingDirMountName = "azureworkingdir"
    val workingDir = "/tmp/tunnelGeneration"
    return V1JobBuilder()
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
        .withCommand(listOf("generate-tunnel-keystores"))
        .withVolumeMounts(
            V1VolumeMountBuilder()
                .withName(workingDirMountName)
                .withMountPath(workingDir).build()
        )
        .withImagePullPolicy("IfNotPresent")
        .withEnv(
            V1EnvVarBuilder().withName("ACCEPT_LICENSE").withValue("Y").build(),
            V1EnvVarBuilder().withName("WORKING_DIR").withValue(workingDir).build(),
            V1EnvVarBuilder()
                .withName(BridgeConfigParams.BRIDGE_CERTIFICATE_ORGANISATION_ENV_VAR_NAME)
                .withValue(BridgeConfigParams.BRIDGE_CERTIFICATE_ORGANISATION)
                .build(),
            V1EnvVarBuilder()
                .withName(BridgeConfigParams.BRIDGE_CERTIFICATE_ORGANISATION_UNIT_ENV_VAR_NAME)
                .withValue(BridgeConfigParams.BRIDGE_CERTIFICATE_ORGANISATION_UNIT)
                .build(),
            V1EnvVarBuilder()
                .withName(BridgeConfigParams.BRIDGE_CERTIFICATE_LOCALITY_ENV_VAR_NAME)
                .withValue(BridgeConfigParams.BRIDGE_CERTIFICATE_LOCALITY)
                .build(),
            V1EnvVarBuilder()
                .withName(BridgeConfigParams.BRIDGE_CERTIFICATE_COUNTRY_ENV_VAR_NAME)
                .withValue(BridgeConfigParams.BRIDGE_CERTIFICATE_COUNTRY)
                .build(),
            V1EnvVarBuilder().withName(BridgeConfigParams.BRIDGE_TUNNEL_KEYSTORE_PASSWORD_ENV_VAR_NAME)
                .withNewValueFrom()
                .withNewSecretKeyRef()
                .withName(tunnelSecretName)
                .withKey(tunnelKeyStorePasswordKey)
                .endSecretKeyRef()
                .endValueFrom()
                .build(),
            V1EnvVarBuilder().withName(BridgeConfigParams.BRIDGE_TUNNEL_TRUST_PASSWORD_ENV_VAR_NAME)
                .withNewValueFrom()
                .withNewSecretKeyRef()
                .withName(tunnelSecretName)
                .withKey(tunnelTrustStorePasswordKey)
                .endSecretKeyRef()
                .endValueFrom()
                .build(),
            V1EnvVarBuilder().withName(BridgeConfigParams.BRIDGE_TUNNEL_ENTRY_PASSWORD_ENV_VAR_NAME)
                .withNewValueFrom()
                .withNewSecretKeyRef()
                .withName(tunnelSecretName)
                .withKey(tunnelEntryPasswordKey)
                .endSecretKeyRef()
                .endValueFrom()
                .build()
        )
        .endContainer()
        .withVolumes(
            V1VolumeBuilder()
                .withName(workingDirMountName)
                .withNewAzureFile()
                .withShareName(workingDirShare.fileShare.name)
                .withSecretName(azureFilesSecretName)
                .withReadOnly(false).endAzureFile().build()
        )
        .withRestartPolicy("Never")
        .endSpec()
        .endTemplate()
        .endSpec()
        .build()
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
            V1EnvVarBuilder().withName("ACCEPT_LICENSE").withValue("Y").build(),
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
    nodeConfigSecretsName: String,
    credentialsSecretName: String,
    p12FileSecretName: String,
    azKeyVaultCredentialsFilePasswordKey: String,
    azKeyVaultCredentialsClientIdKey: String,
    certificatesShare: AzureFilesDirectory,
    networkParametersShare: AzureFilesDirectory
): V1Job {
    val p12FileFolderMountName = "azurehsmcredentialsdir"
    val configFilesFolderMountName = "azurecordaconfigdir"
    val certificatesFolderMountName = "azurecordacertificatesdir"
    val networkFolderMountName = "networkdir"

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
                .withName(p12FileFolderMountName)
                .withMountPath(AzureKeyVaultConfigParams.CREDENTIALS_DIR).build(),
            V1VolumeMountBuilder()
                .withName(configFilesFolderMountName)
                .withMountPath(NodeConfigParams.NODE_CONFIG_DIR).build(),
            V1VolumeMountBuilder()
                .withName(certificatesFolderMountName)
                .withMountPath(NodeConfigParams.NODE_CERTIFICATES_DIR).build(),
            V1VolumeMountBuilder()
                .withName(networkFolderMountName)
                .withMountPath(NodeConfigParams.NODE_NETWORK_PARAMETERS_SETUP_DIR).build()
        )
        .withImagePullPolicy("IfNotPresent")
        .withEnv(
            V1EnvVarBuilder().withName("TRUST_ROOT_DOWNLOAD_URL").withValue("http://networkservices:8080/truststore").build(),
            V1EnvVarBuilder().withName("TRUST_ROOT_PATH").withValue(NodeConfigParams.NODE_NETWORK_TRUST_ROOT_PATH).build(),
            V1EnvVarBuilder().withName("TRUSTSTORE_PASSWORD").withValue("trustpass").build(),
            V1EnvVarBuilder().withName("BASE_DIR").withValue(NodeConfigParams.NODE_BASE_DIR).build(),
            V1EnvVarBuilder().withName("CONFIG_FILE_PATH").withValue(NodeConfigParams.NODE_CONFIG_PATH).build(),
            V1EnvVarBuilder().withName("CERTIFICATE_SAVE_FOLDER").withValue(NodeConfigParams.NODE_CERTIFICATES_DIR).build(),
            V1EnvVarBuilder().withName("NETWORK_PARAMETERS_SAVE_FOLDER").withValue(NodeConfigParams.NODE_NETWORK_PARAMETERS_SETUP_DIR).build(),
            V1EnvVarBuilder().withName(AzureKeyVaultConfigParams.KEY_VAULT_CERTIFICATES_PASSWORD_ENV_VAR_NAME).withNewValueFrom()
                .withNewSecretKeyRef()
                .withName(credentialsSecretName)
                .withKey(azKeyVaultCredentialsFilePasswordKey)
                .endSecretKeyRef()
                .endValueFrom()
                .build(),
            V1EnvVarBuilder().withName(AzureKeyVaultConfigParams.KEY_VAULT_CLIENT_ID_ENV_VAR_NAME).withNewValueFrom()
                .withNewSecretKeyRef()
                .withName(credentialsSecretName)
                .withKey(azKeyVaultCredentialsClientIdKey)
                .endSecretKeyRef()
                .endValueFrom()
                .build()
        )
        .endContainer()
        .withVolumes(
            V1VolumeBuilder()
                .withName(p12FileFolderMountName)
                .withNewSecret()
                .withSecretName(p12FileSecretName)
                .endSecret()
                .build(),
            V1VolumeBuilder()
                .withName(configFilesFolderMountName)
                .withNewSecret()
                .withSecretName(nodeConfigSecretsName)
                .endSecret()
                .build(),
            V1VolumeBuilder()
                .withName(certificatesFolderMountName)
                .withNewAzureFile()
                .withShareName(certificatesShare.fileShare.name)
                .withSecretName(azureFilesSecretName)
                .withReadOnly(false)
                .endAzureFile()
                .build(),
            V1VolumeBuilder()
                .withName(networkFolderMountName)
                .withNewAzureFile()
                .withShareName(networkParametersShare.fileShare.name)
                .withSecretName(azureFilesSecretName)
                .withReadOnly(false)
                .endAzureFile()
                .build()
        )
        .withRestartPolicy("Never")
        .endSpec()
        .endTemplate()
        .endSpec()
        .build()
    return initialRegistrationJob
}

fun String.toEnvVar(): String {
    return "\${$this}"
}
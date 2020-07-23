package net.corda.deployment.node

import com.azure.storage.file.share.ShareFileClient
import com.microsoft.azure.credentials.AzureCliCredentials
import com.microsoft.azure.management.Azure
import com.microsoft.azure.management.compute.Disk
import com.microsoft.azure.management.compute.DiskSkuTypes
import com.microsoft.azure.management.resources.ResourceGroup
import com.microsoft.rest.LogLevel
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.Yaml
import net.corda.deployment.node.config.ConfigGenerators
import net.corda.deployment.node.database.H2_DB
import net.corda.deployment.node.hsm.KeyVaultCreator
import net.corda.deployment.node.kubernetes.SecretCreator
import net.corda.deployment.node.kubernetes.allowAllFailures
import net.corda.deployment.node.principals.ServicePrincipalCreator
import net.corda.deployment.node.storage.AzureFileShareCreator
import net.corda.deployment.node.storage.uploadFromByteArray
import net.corda.deployments.node.config.*
import org.apache.commons.lang3.RandomStringUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.time.Duration
import kotlin.system.exitProcess


@ExperimentalUnsignedTypes
fun main(args: Array<String>) {
    val defaultClientSource = { ClientBuilder.defaultClient() }

    val nmsSetup = Yaml.loadAll(Thread.currentThread().contextClassLoader.getResourceAsStream("yaml/dummynms.yaml").reader())
    val namespace = "testingzone"
    allowAllFailures { simpleApply.apply(nmsSetup, namespace = namespace) }
    val bouncyCastleProvider = BouncyCastleProvider()
    Security.addProvider(bouncyCastleProvider)

    val mngAzure: Azure = Azure.configure()
        .withLogLevel(LogLevel.BODY_AND_HEADERS)
        .authenticate(AzureCliCredentials.create())
        .withSubscription("c412941a-4362-4923-8737-3d33a8d1cdc6")

    val resourceGroup = mngAzure.resourceGroups().getByName("stefano-playground")
    val randSuffix = RandomStringUtils.randomAlphanumeric(8).toLowerCase()

    val servicePrincipalCreator = ServicePrincipalCreator(azure = mngAzure, resourceGroup = resourceGroup, runSuffix = randSuffix)
    val keyVaultCreator = KeyVaultCreator(azure = mngAzure, resourceGroup = resourceGroup, runSuffix = randSuffix)
    val servicePrincipal = servicePrincipalCreator.createClusterServicePrincipal()
    val vault = keyVaultCreator.createKeyVaultAndConfigureServicePrincipalAccess(servicePrincipal)
    /// END NON K8S /////

    val azureFileShareCreator = AzureFileShareCreator(
        azure = mngAzure,
        resourceGroup = resourceGroup,
        runSuffix = randSuffix,
        namespace = namespace,
        api = defaultClientSource
    )


    val initialRegistrationResultShare = azureFileShareCreator.createDirectoryFor("node-certificates")
    val networkParametersFromInitialRegistrationShare = azureFileShareCreator.createDirectoryFor("network-files")

    val dbParams = H2_DB

    val firewallSetup = FirewallSetup(namespace, randSuffix)
    val firewallTunnelSecrets = firewallSetup.generateFirewallTunnelSecrets(defaultClientSource)

    //configure artemis
    val artemisSetup = ArtemisSetup(mngAzure, resourceGroup, azureFileShareCreator, namespace, randSuffix)
    val artemisSecrets = artemisSetup.generateArtemisSecrets(defaultClientSource)
    val generatedArtemisStores = artemisSetup.generateArtemisStores(defaultClientSource)
    val configuredArtemisBroker = artemisSetup.configureArtemisBroker(defaultClientSource)
    val deployedArtemis = artemisSetup.deploy(defaultClientSource)


    val nodeX500 = "O=BigCorporation,L=New York,C=US"
    val nodeEmail = "stefano.franz@r3.com"
    val nodeP2PAddress = "localhost"
    val doormanURL = "http://networkservices:8080"
    val networkMapURL = "http://networkservices:8080"
    val rpcUsername = "u"
    val rpcPassword = "p"
    val nodeConfigParams = NodeConfigParams.builder()
        .withX500Name(nodeX500)
        .withEmailAddress(nodeEmail)
        .withNodeSSLKeystorePassword(NodeConfigParams.NODE_SSL_KEYSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .withNodeTrustStorePassword(NodeConfigParams.NODE_TRUSTSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .withP2pAddress(nodeP2PAddress)
        .withP2pPort(NodeConfigParams.NODE_P2P_PORT)
        .withArtemisServerAddress(deployedArtemis.serviceName)
        .withArtemisServerPort(ArtemisConfigParams.ARTEMIS_ACCEPTOR_PORT)
        .withArtemisSSLKeyStorePath(NodeConfigParams.NODE_ARTEMIS_SSL_KEYSTORE_PATH)
        .withArtemisSSLKeyStorePass(NodeConfigParams.NODE_ARTEMIS_SSL_KEYSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .withArtemisTrustStorePath(NodeConfigParams.NODE_ARTEMIS_TRUSTSTORE_PATH)
        .withArtemisTrustStorePass(NodeConfigParams.NODE_ARTEMIS_TRUSTSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .withRpcPort(NodeConfigParams.NODE_RPC_PORT)
        .withRpcAdminPort(NodeConfigParams.NODE_RPC_ADMIN_PORT)
        .withDoormanURL(doormanURL)
        .withNetworkMapURL(networkMapURL)
        .withRpcUsername(rpcUsername)
        .withRpcPassword(rpcPassword)
        .withDataSourceClassName(dbParams.type.dataSourceClass)
        .withDataSourceURL(dbParams.jdbcURL)
        .withDataSourceUsername(NodeConfigParams.NODE_DATASOURCE_USERNAME_ENV_VAR_NAME.toEnvVar())
        .withDataSourcePassword(NodeConfigParams.NODE_DATASOURCE_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .withAzureKeyVaultConfPath(NodeConfigParams.NODE_AZ_KV_CONFIG_PATH)
        .build()


    val keyVaultParams = AzureKeyVaultConfigParams
        .builder()
        .withServicePrincipalCredentialsFilePath(AzureKeyVaultConfigParams.CREDENTIALS_P12_PATH)
        .withServicePrincipalCredentialsFilePassword(AzureKeyVaultConfigParams.KEY_VAULT_CERTIFICATES_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .withKeyVaultClientId(AzureKeyVaultConfigParams.KEY_VAULT_CLIENT_ID_ENV_VAR_NAME.toEnvVar())
        .withKeyAlias(servicePrincipal.p12KeyAlias)
        .withKeyVaultURL(vault.vaultUri())
        .withKeyVaultProtectionMode(AzureKeyVaultConfigParams.KEY_PROTECTION_MODE_SOFTWARE)
        .build()


    val nodeConf = ConfigGenerators.generateConfigFromParams(nodeConfigParams)
    val azKvConf = ConfigGenerators.generateConfigFromParams(keyVaultParams)
    val nodeConfigFilesSecretName = "node-config-secrets-$randSuffix"

    val nodeConfigFilesSecret = SecretCreator.createStringSecret(
        nodeConfigFilesSecretName,
        listOf(
            NodeConfigParams.NODE_CONFIG_FILENAME to nodeConf,
            NodeConfigParams.NODE_AZ_KV_CONFIG_FILENAME to azKvConf
        ).toMap()
        , namespace, defaultClientSource
    )

    val nodeDatasourceSecretName = "node-datasource-secrets-$randSuffix"
    val nodeDatasourceURLSecretKey = "node-datasource-url"
    val nodeDatasourceUsernameSecretKey = "node-datasource-user"
    val nodeDatasourcePasswordSecretyKey = "node-datasource-password"
    val nodeDataSourceSecrets = SecretCreator.createStringSecret(
        nodeDatasourceSecretName,
        listOf(
            nodeDatasourceURLSecretKey to dbParams.jdbcURL,
            nodeDatasourceUsernameSecretKey to RandomStringUtils.randomAlphanumeric(20),
            nodeDatasourcePasswordSecretyKey to RandomStringUtils.randomAlphanumeric(20)
        ).toMap(),
        namespace, defaultClientSource
    )

    val nodeStoresSecretName = "node-keystores-secrets-$randSuffix"
    val nodeKeyStorePasswordSecretKey = "node-ssl-keystore-password"
    val sharedTrustStorePasswordSecretKey = "shared-truststore-password"

    val nodeStoresSecrets = SecretCreator.createStringSecret(
        nodeStoresSecretName,
        listOf(
            nodeKeyStorePasswordSecretKey to RandomStringUtils.randomAlphanumeric(20),
            sharedTrustStorePasswordSecretKey to RandomStringUtils.randomAlphanumeric(20)
        ).toMap(),
        namespace, defaultClientSource
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
        , namespace, defaultClientSource
    )

    val p12FileSecretName = "keyvault-auth-file-secrets-$randSuffix"
    val p12Secret = SecretCreator.createByteArraySecret(
        p12FileSecretName,
        listOf(
            AzureKeyVaultConfigParams.CREDENTIALS_P12_FILENAME to servicePrincipal.p12Bytes
        ).toMap(),
        namespace, defaultClientSource
    )

    val jobName = "initial-registration-$randSuffix"

    val initialRegistrationJob = initialRegistrationJob(
        jobName,
        nodeConfigFilesSecretName,
        keyVaultCredentialsSecretName,
        p12FileSecretName,
        azKeyVaultCredentialsFilePasswordKey,
        azKeyVaultCredentialsClientIdKey,
        nodeDatasourceSecretName,
        nodeDatasourceURLSecretKey,
        nodeDatasourceUsernameSecretKey,
        nodeDatasourcePasswordSecretyKey,
        artemisSecrets,
        nodeStoresSecretName,
        nodeKeyStorePasswordSecretKey,
        sharedTrustStorePasswordSecretKey,
        initialRegistrationResultShare,
        networkParametersFromInitialRegistrationShare
    )

    val tunnelStoresShare = azureFileShareCreator.createDirectoryFor("tunnel-stores")
    val generateTunnelStoresJobName = "gen-tunnel-stores-${randSuffix}"
    val generateTunnelStoresJob = generateTunnelStores(
        generateTunnelStoresJobName,
        firewallTunnelSecrets,
        tunnelStoresShare
    )


    val importNodeToBridgeJobName = "import-node-ssl-to-bridge-${randSuffix}"
    val bridgeCertificatesShare = azureFileShareCreator.createDirectoryFor("bridge-certs")
    val bridgeCertificatesSecretName = "bridge-certs-secerts-$randSuffix"
    val bridgeSSLKeyStorePasswordSecretKey = "bridgesslpassword"

    val bridgeSSLSecret = SecretCreator.createStringSecret(
        bridgeCertificatesSecretName,
        listOf(
            bridgeSSLKeyStorePasswordSecretKey to RandomStringUtils.randomAlphanumeric(20)
        ).toMap(),
        namespace, defaultClientSource
    )

    val importNodeKeyStoreToBridgeJob = importNodeKeyStoreToBridgeJob(
        importNodeToBridgeJobName,
        nodeStoresSecretName,
        nodeKeyStorePasswordSecretKey,
        bridgeCertificatesSecretName,
        bridgeSSLKeyStorePasswordSecretKey,
        initialRegistrationResultShare,
        bridgeCertificatesShare
    )

    simpleApply.create(initialRegistrationJob, namespace)
    waitForJob(namespace, initialRegistrationJob, defaultClientSource)
    dumpLogsForJob(initialRegistrationJob, defaultClientSource)

    simpleApply.create(generateTunnelStoresJob, namespace)
    waitForJob(namespace, generateTunnelStoresJob, defaultClientSource)
    dumpLogsForJob(generateTunnelStoresJob, defaultClientSource)

    simpleApply.create(importNodeKeyStoreToBridgeJob, namespace)
    waitForJob(namespace, importNodeKeyStoreToBridgeJob, defaultClientSource)
    dumpLogsForJob(importNodeKeyStoreToBridgeJob, defaultClientSource)


    val artemisAddress = deployedArtemis.serviceName
    val floatAddress = "floatAddress"
    val bridgeConfig = BridgeConfigParams.builder()
        .withArtemisAddress(artemisAddress)
        .withArtemisPort(ArtemisConfigParams.ARTEMIS_ACCEPTOR_PORT)
        .withArtemisKeyStorePath(BridgeConfigParams.BRIDGE_ARTEMIS_SSL_KEYSTORE_PATH)
        .withArtemisKeyStorePassword(BridgeConfigParams.BRIDGE_ARTEMIS_KEYSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .withArtemisTrustStorePath(BridgeConfigParams.BRIDGE_ARTEMIS_TRUSTSTORE_PATH)
        .withArtemisTrustStorePassword(BridgeConfigParams.BRIDGE_ARTEMIS_TRUSTSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .withFloatAddress(floatAddress)
        .withFloatPort(FloatConfigParams.FLOAT_INTERNAL_PORT)
        .withExpectedFloatCertificateSubject(FloatConfigParams.FLOAT_CERTIFICATE_SUBJECT)
        .withTunnelKeyStorePath(BridgeConfigParams.BRIDGE_TUNNEL_SSL_KEYSTORE_PATH)
        .withTunnelKeyStorePassword(BridgeConfigParams.BRIDGE_TUNNEL_KEYSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .withTunnelTrustStorePath(BridgeConfigParams.BRIDGE_TUNNEL_TRUSTSTORE_PATH)
        .withTunnelTrustStorePassword(BridgeConfigParams.BRIDGE_TUNNEL_TRUSTSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .withTunnelEntryPassword(BridgeConfigParams.BRIDGE_TUNNEL_ENTRY_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .withNetworkParamsPath(BridgeConfigParams.BRIDGE_NETWORK_PARAMETERS_PATH)
        .withBridgeKeyStorePath(BridgeConfigParams.BRIDGE_SSL_KEYSTORE_PATH)
        .withBridgeKeyStorePassword(BridgeConfigParams.BRIDGE_KEYSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .withBridgeTrustStorePath(BridgeConfigParams.BRIDGE_TRUSTSTORE_PATH)
        .withBridgeTrustStorePassword(BridgeConfigParams.BRIDGE_TRUSTSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .build()

    val floatTunnelShare = azureFileShareCreator.createDirectoryFor("float-tunnel")
    val generatedTunnelTrustStoreFileReference =
        tunnelStoresShare.modernClient.rootDirectoryClient.getFileClient(TunnelConfigParams.TUNNEL_TRUSTSTORE_FILENAME)
    val generatedFloatTunnelKeyStoreFileReference =
        tunnelStoresShare.modernClient.rootDirectoryClient.getFileClient(TunnelConfigParams.TUNNEL_FLOAT_KEYSTORE_FILENAME)
    val generatedBridgeTunnelKeyStoreFileReference =
        tunnelStoresShare.modernClient.rootDirectoryClient.getFileClient(TunnelConfigParams.TUNNEL_BRDIGE_KEYSTORE_FILENAME)
    val floatTunnelTrustStoreFileReference =
        floatTunnelShare.modernClient.rootDirectoryClient.getFileClient(TunnelConfigParams.TUNNEL_TRUSTSTORE_FILENAME)
    val floatTunnelKeyStoreFileReference =
        floatTunnelShare.modernClient.rootDirectoryClient.getFileClient(TunnelConfigParams.TUNNEL_FLOAT_KEYSTORE_FILENAME)
    floatTunnelTrustStoreFileReference.createFrom(generatedTunnelTrustStoreFileReference)
    floatTunnelKeyStoreFileReference.createFrom(generatedFloatTunnelKeyStoreFileReference)

    val floatConfig = FloatConfigParams.builder()
        .withBaseDir(FloatConfigParams.FLOAT_BASE_DIR)
        .withExpectedBridgeCertificateSubject(BridgeConfigParams.BRIDGE_CERTIFICATE_SUBJECT)
        .withExternalBindAddress(FloatConfigParams.ALL_LOCAL_ADDRESSES)
        .withExternalPort(FloatConfigParams.FLOAT_EXTERNAL_PORT)
        .withInternalBindAddress(FloatConfigParams.ALL_LOCAL_ADDRESSES)
        .withInternalPort(FloatConfigParams.FLOAT_INTERNAL_PORT)
        .withNetworkParametersPath(FloatConfigParams.FLOAT_NETWORK_PARAMETERS_PATH)
        .withTunnelKeyStorePassword(FloatConfigParams.FLOAT_TUNNEL_SSL_KEYSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .withTunnelKeystorePath(FloatConfigParams.FLOAT_TUNNEL_SSL_KEYSTORE_PATH)
        .withTunnelTrustStorePassword(FloatConfigParams.FLOAT_TUNNEL_TRUSTSTORE_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .withTunnelTrustStorePath(FloatConfigParams.FLOAT_TUNNEL_TRUSTSTORE_PATH)
        .withTunnelStoresEntryPassword(FloatConfigParams.FLOAT_TUNNEL_ENTRY_PASSWORD_ENV_VAR_NAME.toEnvVar())
        .build()

    val floatConfigShare = azureFileShareCreator.createDirectoryFor("float-config")
    val floatConfigFileReference =
        floatConfigShare.legacyClient.rootDirectoryReference.getFileReference(FloatConfigParams.FLOAT_CONFIG_FILENAME)
    floatConfigFileReference.uploadFromByteArray(ConfigGenerators.generateConfigFromParams(floatConfig).toByteArray(Charsets.UTF_8))

    val floatNetworkParamsShare = azureFileShareCreator.createDirectoryFor("float-network-params")
    val floatNetworkParamsFileReference =
        floatNetworkParamsShare.modernClient.rootDirectoryClient.getFileClient(FloatConfigParams.FLOAT_NETWORK_PARAMS_FILENAME)
    val generatedNetworkParamsFileReference =
        networkParametersFromInitialRegistrationShare.modernClient.rootDirectoryClient.getFileClient(NodeConfigParams.NETWORK_PARAMETERS_FILENAME)

    floatNetworkParamsFileReference.createFrom(generatedNetworkParamsFileReference)

    val floatDeployment = createFloatDeployment(
        namespace,
        randSuffix,
        floatConfigShare,
        floatTunnelShare,
        floatNetworkParamsShare,
        firewallTunnelSecrets
    )

    println(Yaml.dump(floatDeployment))
    simpleApply.create(floatDeployment, namespace, defaultClientSource())

    val bridgeConfigShare = azureFileShareCreator.createDirectoryFor("bridge-config")
    val bridgeConfigFileReference =
        bridgeConfigShare.legacyClient.rootDirectoryReference.getFileReference(BridgeConfigParams.BRIDGE_CONFIG_FILENAME)
    bridgeConfigFileReference.uploadFromByteArray(ConfigGenerators.generateConfigFromParams(bridgeConfig).toByteArray(Charsets.UTF_8))

    val bridgeNetworkParamsShare = azureFileShareCreator.createDirectoryFor("bridge-network-params")
    val bridgeNetworkParamsFileReference =
        bridgeNetworkParamsShare.modernClient.rootDirectoryClient.getFileClient(BridgeConfigParams.BRIDGE_NETWORK_PARAMETERS_FILENAME)

    bridgeNetworkParamsFileReference.createFrom(generatedNetworkParamsFileReference)

    val bridgeTunnelShare = azureFileShareCreator.createDirectoryFor("bridge-tunnel")
    val bridgeTunnelTrustStoreFileReference =
        bridgeTunnelShare.modernClient.rootDirectoryClient.getFileClient(TunnelConfigParams.TUNNEL_TRUSTSTORE_FILENAME)
    val bridgeTunnelKeyStoreFileReference =
        bridgeTunnelShare.modernClient.rootDirectoryClient.getFileClient(TunnelConfigParams.TUNNEL_BRDIGE_KEYSTORE_FILENAME)
    bridgeTunnelTrustStoreFileReference.createFrom(generatedTunnelTrustStoreFileReference)
    bridgeTunnelKeyStoreFileReference.createFrom(generatedBridgeTunnelKeyStoreFileReference)

    val bridgeArtemisStoresShare = azureFileShareCreator.createDirectoryFor("bridge-artemis-stores")
    val bridgeArtemisKeyStoreFileReference =
        bridgeArtemisStoresShare.modernClient.rootDirectoryClient.getFileClient(ArtemisConfigParams.ARTEMIS_BRIDGE_KEYSTORE_FILENAME)
    val bridgeArtemisTrustStoreFileReference =
        bridgeArtemisStoresShare.modernClient.rootDirectoryClient.getFileClient(ArtemisConfigParams.ARTEMIS_TRUSTSTORE_FILENAME)

    val generatedArtemisBridgeKeyStoreFileReference = generatedArtemisStores.bridgeStore
    val generatedArtemisTrustStoreFileReference = generatedArtemisStores.trustStore

    bridgeArtemisKeyStoreFileReference.createFrom(generatedArtemisBridgeKeyStoreFileReference)
    bridgeArtemisTrustStoreFileReference.createFrom(generatedArtemisTrustStoreFileReference)

    val bridgeTrustStoreFileReference =
        bridgeCertificatesShare.modernClient.rootDirectoryClient.getFileClient(BridgeConfigParams.BRIDGE_TRUSTSTORE_FILENAME)
    val trustStoreFromInitialRegistration =
        initialRegistrationResultShare.modernClient.rootDirectoryClient.getFileClient(NodeConfigParams.NODE_TRUSTSTORE_FILENAME)
    bridgeTrustStoreFileReference.createFrom(trustStoreFromInitialRegistration)

    val bridgeDeployment = createBridgeDeployment(
        namespace,
        randSuffix,
        bridgeConfigShare,
        bridgeTunnelShare,
        bridgeArtemisStoresShare,
        bridgeCertificatesShare,
        networkParametersFromInitialRegistrationShare,
        firewallTunnelSecrets,
        artemisSecrets,
        bridgeCertificatesSecretName,
        bridgeSSLKeyStorePasswordSecretKey,
        nodeStoresSecretName,
        sharedTrustStorePasswordSecretKey
    )

    println(Yaml.dump(bridgeDeployment))
    simpleApply.create(bridgeDeployment, namespace, defaultClientSource())

    exitProcess(0)
}

fun ShareFileClient.createFrom(source: ShareFileClient, timeout: Duration = Duration.ofMinutes(5)) {
    val sizeToCopy = source.properties.contentLength
    if (!this.exists()) {
        this.create(sizeToCopy)
    } else {
        this.delete()
        this.create(sizeToCopy)
    }
    val poller = this.beginCopy(
        source.fileUrl,
        null,
        null
    )
    poller.waitForCompletion(timeout)
}


fun String.toEnvVar(): String {
    return "\${$this}"
}
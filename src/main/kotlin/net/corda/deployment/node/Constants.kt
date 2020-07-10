package net.corda.deployment.node

import net.corda.deployment.node.Constants.Node.Companion.NODE_P2P_PORT

class Constants {

    class Float {
        companion object {
            val FLOAT_BASE_DIR = "/opt/corda"


            val FLOAT_INTERNAL_PORT = 10000
            val FLOAT_INTERNAL_PORT_CONFIG_SUB_KEY = "floatPort"
            val FLOAT_INTERNALL_ADDRESS_CONFIG_SUB_KEY = "floatInternalBindAddress"

            val FLOAT_EXTERNAL_PORT = NODE_P2P_PORT
            val FLOAT_EXTERNAL_PORT_CONFIG_SUB_KEY = "p2pPort"
            val FLOAT_EXTERNAL_ADDRESS_CONFIG_SUB_KEY = "floatExternalBindAddress"


            val FLOAT_TUNNEL_SSL_KEYSTORE_LOCATION = "$FLOAT_BASE_DIR/tunnel/float.jks"
            val FLOAT_TUNNEL_SSL_KEYSTORE_LOCATION_CONFIG_SUB_KEY = "sslKeystorePath"

            val FLOAT_TUNNEL_TRUSTSTORE_LOCATION = "$FLOAT_BASE_DIR/tunnel/tunnel-truststore.jks"
            val FLOAT_TUNNEL_TRUSTSTORE_LOCATION_CONFIG_SUB_KEY = "trustStorePath"

            val EXPECTED_BRIDGE_CERTIFICATE_SUBJECT = "CN=bridge, O=Tunnel, L=LONDON, C=GB"
            val EXPECTED_BRIDGE_CERTIFICATE_SUBJECT_CONFIG_SUB_KEY = "expectedCertificateSubject"

            val FLOAT_NETWORK_PARAMETERS_PATH = "$FLOAT_BASE_DIR/network/network-params"
            val FLOAT_NETWORK_PARAMETERS_PATH_CONFIG_SUB_KEY = "networkParamsPath"
        }
    }

    class Artemis {
        val ARTEMIS_PORT = 10000
    }

    class Bridge {
        companion object {
            val BRIDGE_BASE_DIR = "/opt/corda"
            val EXPECTED_FLOAT_CERTIFICATE_SUBJECT = "CN=float, O=Tunnel, L=LONDON, C=GB"
            val EXPECTED_FLOAT_CERTIFICATE_SUBJECT_CONFIG_SUB_KEY = "expectedCertificateSubject"

            val ARTEMIS_ADDRESS_CONFIG_SUB_KEY = "artemisAddress"
            val ARTEMIS_PORT_CONFIG_SUB_KEY = "artemisPort"
            val ARTEMIS_SSL_KEY_STORE_PATH_CONFIG_SUB_KEY = "artemisSSLKeyStorePath"
            val ARTEMIS_KEY_STORE_PASSWORD_CONFIG_SUB_KEY = "artemisSSLKeyStorePass"
            val ARTEMIS_TRUST_STORE_PATH_CONFIG_SUB_KEY = "artemisTrustStorePath"
            val ARTEMIS_TRUST_STORE_PASSWORD_CONFIG_SUB_KEY = "artemisTrustStorePass"

            val FLOAT_ADDRESS_CONFIG_SUB_KEY = "floatAddress"
            val FLOAT_PORT_CONFIG_SUB_KEY = "floatPort"

            val BRIDGE_TUNNEL_SSL_KEYSTORE_LOCATION = "${BRIDGE_BASE_DIR}/tunnel/bridge.jks"
            val FLOAT_TUNNEL_SSL_KEYSTORE_LOCATION_CONFIG_SUB_KEY = "sslKeystorePath"

            val BRIDGE_TUNNEL_TRUSTSTORE_LOCATION = "${BRIDGE_BASE_DIR}/tunnel/tunnel-truststore.jks"
            val BRIDGE_TUNNEL_TRUSTSTORE_LOCATION_CONFIG_SUB_KEY = "trustStorePath"

            val BRIDGE_TUNNEL_SSL_KEYSTORE_PASSWORD_CONFIG_SUB_KEY = "tunnelKeyStorePassword"
            val BRIDGE_TUNNEL_TRUSTSTORE_PASSWORD_CONFIG_SUB_KEY = "tunnelTrustStorePassword"

            val BRIDGE_NETWORK_PARAMETERS_PATH = "${BRIDGE_BASE_DIR}/network/network-params"
            val BRIDGE_NETWORK_PARAMETERS_FILE_PATH_CONFIG_SUB_KEY = "networkParamsPath"

            val BRIDGE_SSL_KEYSTORE_PATH = "${BRIDGE_BASE_DIR}/certificates/bridge.jks"
            val BRIDGE_SSL_KEYSTORE_PATH_CONFIG_SUB_KEY = "bridgeSSLKeyStorePath"
            val BRIDGE_SSL_KEYSTORE_PASSWORD_CONFIG_SUB_KEY = "bridgeSSLKeyStorePass"

            val BRIDGE_TRUSTSTORE_PATH = "${BRIDGE_BASE_DIR}/certificates/truststore.jks"
            val BRIDGE_TRUSTSTORE_PATH_CONFIG_SUB_KEY = "bridgeTrustStorePath"
            val BRIDGE_TRUSTSTORE_PASSWORD_CONFIG_SUB_KEY = "bridgeTrustStorePass"
        }
    }

    class Node {
        companion object {

            val NODE_BASE_DIR = "/opt/corda"

            val NODE_X500_CONFIG_SUB_KEY = "x500"
            val NODE_SSL_KEYSTORE_PASSWORD_CONFIG_SUB_KEY = "nodeSSLKeystorePassword"
            val NODE_TRUSTSTORE_PASSWORD_CONFIG_SUB_KEY = "nodeTrustStorePassword"
            val NODE_P2P_PUBLIC_ADDRESS_CONFIG_SUB_KEY = "p2pAddress"

            val NODE_P2P_PORT: Int = 10200
            val NODE_P2P_PORT_CONFIG_SUB_KEY = "p2pPort"

            val NODE_ARTEMIS_ADDRESS_CONFIG_SUB_KEY = "messagingServerIp"
            val NODE_ARTEMIS_PORT_CONFIG_SUB_KEY = "messagingServerPort"

            val NODE_ARTEMIS_SSL_KEY_STORE_PATH_CONFIG_SUB_KEY = "artemisSSLKeyStorePath"
            val NODE_ARTEMIS_KEY_STORE_PASSWORD_CONFIG_SUB_KEY = "artemisSSLKeyStorePass"
            val NODE_ARTEMIS_TRUST_STORE_PATH_CONFIG_SUB_KEY = "artemisTrustStorePath"
            val NODE_ARTEMIS_TRUST_STORE_PASSWORD_CONFIG_SUB_KEY = "artemisTrustStorePass"

            val NODE_RPC_PORT = 10001
            val NODE_RPC_PORT_CONFIG_SUB_KEY = "rpcPort"
            val NODE_RPC_ADMIN_PORT = 10002
            val NODE_RPC_PORT_CONFIG_SUB = "rpcAdminPort"

            val NODE_DOORMAN_MAP_URL_CONFIG_SUB_KEY = "doormanURL"
            val NODE_NETWORK_MAP_URL_CONFIG_SUB_KEY = "networkMapURL"

            val NODE_RPC_PASSWORD_CONFIG_SUB_KEY = "rpcPassword"
            val NODE_RPC_USERNAME_CONFIG_SUB_KEY = "rpcUser"

            val NODE_DATASOURCE_CLASS_NAME_CONFIG_SUB_KEY = "dataSourceClassName"
            val NODE_DATASOURCE_URL_CONFIG_SUB_KEY = "dataSourceURL"
            val NODE_DATASOURCE_USER_CONFIG_SUB_KEY = "dataSourceUsername"
            val NODE_DATASOURCE_PASSWORD_CONFIG_SUB_KEY = "dataSourcePassword"

            val NODE_AZURE_KEY_VAULT_CONFIG_FILE_CONFIG_SUB_KEY = "azureKeyVaultConfPath"
        }
    }

    class AzureKeyVault {
        companion object {
            val SERVICE_PRINCIPAL_CRED_FILE_PATH = "/etc/corda/keyVault.conf"
            val SERVICE_PRINCIPAL_CRED_FILE_PATH_CONFIG_SUB_KEY = "servicePrincipalAuthFilePath"
            val SERVICE_PRINCIPAL_CRED_FILE_PASSWORD_CONFIG_SUB_KEY = "servicePrincipalAuthFilePassword"

            val KEY_ALIAS_CONFIG_SUB_KEY = "keyAlias"
            val VAULT_URL_CONFIG_SUB_KEY = "keyVaultURL"
            val CLIENT_ID_CONFIG_SUB_KEY = "keyVaultClientId"
            val KEY_PROTECTION_MODE_HARDWARE = "HARDWARE"
            val KEY_PROTECTION_MODE_SOFTWARE = "SOFTWARE"
            val KEY_PROTECTION_MODE_CONFIG_SUB_KEY = "keyVaultProtectionMode"
        }
    }


}
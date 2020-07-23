package net.corda.deployments.node.config;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

@SubstitutableSource.SubstitutionTarget(targetConfig = "config/bridge_with_float.conf")
public final class BridgeConfigParams implements SubstitutableSource {

    public static final String BRIDGE_BASE_DIR = "/opt/corda";
    public static final String BRIDGE_CONFIG_DIR = "/etc/corda";
    public static final String BRIDGE_CONFIG_FILENAME = "bridge.conf";
    public static final String BRIDGE_CONFIG_PATH = BRIDGE_CONFIG_DIR + "/" + BRIDGE_CONFIG_FILENAME;

    public static final String BRIDGE_TUNNEL_STORES_DIR = BRIDGE_BASE_DIR + "/tunnel";
    public static final String BRIDGE_TUNNEL_SSL_KEYSTORE_FILENAME = TunnelConfigParams.TUNNEL_BRDIGE_KEYSTORE_FILENAME;
    public static final String BRIDGE_TUNNEL_TRUSTSTORE_FILENAME = TunnelConfigParams.TUNNEL_TRUSTSTORE_FILENAME;
    public static final String BRIDGE_TUNNEL_SSL_KEYSTORE_PATH = BRIDGE_TUNNEL_STORES_DIR + "/" + BRIDGE_TUNNEL_SSL_KEYSTORE_FILENAME;
    public static final String BRIDGE_TUNNEL_TRUSTSTORE_PATH = BRIDGE_TUNNEL_STORES_DIR + "/" + BRIDGE_TUNNEL_TRUSTSTORE_FILENAME;

    public static final String BRIDGE_TUNNEL_KEYSTORE_PASSWORD_ENV_VAR_NAME = "TUNNEL_SSL_KEYSTORE_PASSWORD";
    public static final String BRIDGE_TUNNEL_TRUST_PASSWORD_ENV_VAR_NAME = "TUNNEL_TRUSTSTORE_PASSWORD";
    public static final String BRIDGE_TUNNEL_ENTRY_PASSWORD_ENV_VAR_NAME = "TUNNEL_ENTRY_PASSWORD";

    public static final String BRIDGE_CERTIFICATES_DIR = BRIDGE_BASE_DIR + "/certificates";
    public static final String BRIDGE_SSL_KEYSTORE_FILENAME = "bridge.jks";
    public static final String BRIDGE_SSL_KEYSTORE_PATH = BRIDGE_CERTIFICATES_DIR + "/" + BRIDGE_SSL_KEYSTORE_FILENAME;
    public static final String BRIDGE_TRUSTSTORE_FILENAME = "truststore.jks";
    public static final String BRIDGE_TRUSTSTORE_PATH = BRIDGE_CERTIFICATES_DIR + "/" + BRIDGE_TRUSTSTORE_FILENAME;

    public static final String BRIDGE_NETWORK_PARAMETERS_DIR = BRIDGE_BASE_DIR + "/" + "network";
    public static final String BRIDGE_NETWORK_PARAMETERS_FILENAME = "network-parameters";
    public static final String BRIDGE_NETWORK_PARAMETERS_PATH = BRIDGE_NETWORK_PARAMETERS_DIR + "/" + BRIDGE_NETWORK_PARAMETERS_FILENAME;

    public static String BRIDGE_CERTIFICATE_ORGANISATION = "CordaDeployerTunnel";
    public static String BRIDGE_CERTIFICATE_ORGANISATION_ENV_VAR_NAME = "ORGANISATION";
    public static String BRIDGE_CERTIFICATE_ORGANISATION_UNIT = "Azure";
    public static String BRIDGE_CERTIFICATE_ORGANISATION_UNIT_ENV_VAR_NAME = "ORGANISATION_UNIT";
    public static String BRIDGE_CERTIFICATE_LOCALITY = "CLOUD";
    public static String BRIDGE_CERTIFICATE_LOCALITY_ENV_VAR_NAME = "LOCALITY";
    public static String BRIDGE_CERTIFICATE_COUNTRY = "GB";
    public static String BRIDGE_CERTIFICATE_COUNTRY_ENV_VAR_NAME = "COUNTRY";
    public static String BRIDGE_CERTIFICATE_COMMON_NAME = "Bridge";
    public static String BRIDGE_CERTIFICATE_SUBJECT = "CN=" + BRIDGE_CERTIFICATE_COMMON_NAME + ", O=" + BRIDGE_CERTIFICATE_ORGANISATION
            + ", OU=" + BRIDGE_CERTIFICATE_ORGANISATION_UNIT + ", L=" + BRIDGE_CERTIFICATE_LOCALITY + ", C=" + BRIDGE_CERTIFICATE_COUNTRY;

    public static String BRIDGE_ARTEMIS_STORES_DIR = BRIDGE_BASE_DIR + "/artemis";
    public static String BRIDGE_ARTEMIS_SSL_KEYSTORE_FILENAME = ArtemisConfigParams.ARTEMIS_BRIDGE_KEYSTORE_FILENAME;
    public static String BRIDGE_ARTEMIS_SSL_KEYSTORE_PATH = BRIDGE_ARTEMIS_STORES_DIR + "/" + BRIDGE_ARTEMIS_SSL_KEYSTORE_FILENAME;

    public static String BRIDGE_ARTEMIS_TRUSTSTORE_FILENAME = ArtemisConfigParams.ARTEMIS_TRUSTSTORE_FILENAME;
    public static String BRIDGE_ARTEMIS_TRUSTSTORE_PATH = BRIDGE_ARTEMIS_STORES_DIR + "/" + BRIDGE_ARTEMIS_TRUSTSTORE_FILENAME;


    public static String BRIDGE_ARTEMIS_KEYSTORE_PASSWORD_ENV_VAR_NAME = "ARTEMIS_KEYSTORE_PASSWORD";
    public static String BRIDGE_ARTEMIS_TRUSTSTORE_PASSWORD_ENV_VAR_NAME = "ARTEMIS_TRUSTSTORE_PASSWORD";

    public static String BRIDGE_KEYSTORE_PASSWORD_ENV_VAR_NAME = "KEYSTORE_PASSWORD";
    public static String BRIDGE_TRUSTSTORE_PASSWORD_ENV_VAR_NAME = "TRUSTSTORE_PASSWORD";


    @NotNull
    private final String artemisAddress;
    private final int artemisPort;
    @NotNull
    private final String artemisKeyStorePath;
    @NotNull
    private final String artemisKeyStorePassword;
    @NotNull
    private final String artemisTrustStorePath;
    @NotNull
    private final String artemisTrustStorePassword;
    @NotNull
    private final String floatAddress;
    private final int floatPort;
    @NotNull
    private final String expectedFloatCertificateSubject;
    @NotNull
    private final String tunnelKeyStorePath;
    @NotNull
    private final String tunnelKeyStorePassword;
    @NotNull
    private final String tunnelTrustStorePath;
    @NotNull
    private final String tunnelTrustStorePassword;
    @NotNull
    private final String tunnelEntryPassword;
    @NotNull
    private final String networkParamsPath;
    @NotNull
    private final String bridgeKeyStorePath;
    @NotNull
    private final String bridgeKeyStorePassword;
    @NotNull
    private final String bridgeTrustStorePath;
    @NotNull
    private final String bridgeTrustStorePassword;


    public BridgeConfigParams(@NotNull String artemisAddress,
                              int artemisPort,
                              @NotNull String artemisKeyStorePath,
                              @NotNull String artemisKeyStorePassword,
                              @NotNull String artemisTrustStorePath,
                              @NotNull String artemisTrustStorePassword,
                              @NotNull String floatAddress,
                              int floatPort,
                              @NotNull String expectedFloatCertificateSubject,
                              @NotNull String tunnelKeyStorePath,
                              @NotNull String tunnelKeyStorePassword,
                              @NotNull String tunnelTrustStorePath,
                              @NotNull String tunnelTrustStorePassword,
                              @NotNull String networkParamsPath,
                              @NotNull String bridgeKeyStorePath,
                              @NotNull String bridgeKeyStorePassword,
                              @NotNull String bridgeTrustStorePath,
                              @NotNull String bridgeTrustStorePassword,
                              @NotNull String tunnelEntryPassword) {
        this.artemisAddress = artemisAddress;
        this.artemisPort = artemisPort;
        this.artemisKeyStorePath = artemisKeyStorePath;
        this.artemisKeyStorePassword = artemisKeyStorePassword;
        this.artemisTrustStorePath = artemisTrustStorePath;
        this.artemisTrustStorePassword = artemisTrustStorePassword;
        this.floatAddress = floatAddress;
        this.floatPort = floatPort;
        this.expectedFloatCertificateSubject = expectedFloatCertificateSubject;
        this.tunnelKeyStorePath = tunnelKeyStorePath;
        this.tunnelKeyStorePassword = tunnelKeyStorePassword;
        this.tunnelTrustStorePath = tunnelTrustStorePath;
        this.tunnelTrustStorePassword = tunnelTrustStorePassword;
        this.networkParamsPath = networkParamsPath;
        this.bridgeKeyStorePath = bridgeKeyStorePath;
        this.bridgeKeyStorePassword = bridgeKeyStorePassword;
        this.bridgeTrustStorePath = bridgeTrustStorePath;
        this.bridgeTrustStorePassword = bridgeTrustStorePassword;
        this.tunnelEntryPassword = tunnelEntryPassword;
    }

    public static BridgeConfigParamsBuilder builder() {
        return new BridgeConfigParamsBuilder();
    }

    public String getArtemisAddress() {
        return artemisAddress;
    }

    public int getArtemisPort() {
        return artemisPort;
    }

    public String getArtemisKeyStorePath() {
        return artemisKeyStorePath;
    }

    public String getArtemisKeyStorePassword() {
        return artemisKeyStorePassword;
    }

    public String getArtemisTrustStorePath() {
        return artemisTrustStorePath;
    }

    public String getArtemisTrustStorePassword() {
        return artemisTrustStorePassword;
    }

    public String getFloatAddress() {
        return floatAddress;
    }

    public int getFloatPort() {
        return floatPort;
    }

    public String getExpectedFloatCertificateSubject() {
        return expectedFloatCertificateSubject;
    }

    public String getTunnelKeyStorePath() {
        return tunnelKeyStorePath;
    }

    public String getTunnelKeyStorePassword() {
        return tunnelKeyStorePassword;
    }

    public String getTunnelTrustStorePath() {
        return tunnelTrustStorePath;
    }

    public String getTunnelTrustStorePassword() {
        return tunnelTrustStorePassword;
    }

    public String getNetworkParamsPath() {
        return networkParamsPath;
    }

    public String getBridgeKeyStorePath() {
        return bridgeKeyStorePath;
    }

    public String getBridgeKeyStorePassword() {
        return bridgeKeyStorePassword;
    }

    public String getBridgeTrustStorePath() {
        return bridgeTrustStorePath;
    }

    public String getBridgeTrustStorePassword() {
        return bridgeTrustStorePassword;
    }

    public String getTunnelEntryPassword() {
        return tunnelEntryPassword;
    }

    public static final class BridgeConfigParamsBuilder {
        private String artemisAddress;
        private int artemisPort;
        private String artemisKeyStorePath;
        private String artemisKeyStorePassword;
        private String artemisTrustStorePath;
        private String artemisTrustStorePassword;
        private String floatAddress;
        private int floatPort;
        private String expectedFloatCertificateSubject;
        private String tunnelKeyStorePath;
        private String tunnelKeyStorePassword;
        private String tunnelTrustStorePath;
        private String tunnelTrustStorePassword;
        private String networkParamsPath;
        private String bridgeKeyStorePath;
        private String bridgeKeyStorePassword;
        private String bridgeTrustStorePath;
        private String bridgeTrustStorePassword;
        private String bridgeTunnelEntryPassword;

        private BridgeConfigParamsBuilder() {
        }


        public BridgeConfigParamsBuilder withArtemisAddress(String artemisAddress) {
            this.artemisAddress = artemisAddress;
            return this;
        }

        public BridgeConfigParamsBuilder withArtemisPort(int artemisPort) {
            this.artemisPort = artemisPort;
            return this;
        }

        public BridgeConfigParamsBuilder withArtemisKeyStorePath(String artemisKeyStorePath) {
            this.artemisKeyStorePath = artemisKeyStorePath;
            return this;
        }

        public BridgeConfigParamsBuilder withArtemisKeyStorePassword(String artemisKeyStorePassword) {
            this.artemisKeyStorePassword = artemisKeyStorePassword;
            return this;
        }

        public BridgeConfigParamsBuilder withArtemisTrustStorePath(String artemisTrustStorePath) {
            this.artemisTrustStorePath = artemisTrustStorePath;
            return this;
        }

        public BridgeConfigParamsBuilder withArtemisTrustStorePassword(String artemisTrustStorePassword) {
            this.artemisTrustStorePassword = artemisTrustStorePassword;
            return this;
        }

        public BridgeConfigParamsBuilder withFloatAddress(String floatAddress) {
            this.floatAddress = floatAddress;
            return this;
        }

        public BridgeConfigParamsBuilder withFloatPort(int floatPort) {
            this.floatPort = floatPort;
            return this;
        }

        public BridgeConfigParamsBuilder withExpectedFloatCertificateSubject(String expectedFloatCertificateSubject) {
            this.expectedFloatCertificateSubject = expectedFloatCertificateSubject;
            return this;
        }

        public BridgeConfigParamsBuilder withTunnelKeyStorePath(String tunnelKeyStorePath) {
            this.tunnelKeyStorePath = tunnelKeyStorePath;
            return this;
        }

        public BridgeConfigParamsBuilder withTunnelKeyStorePassword(String tunnelKeyStorePassword) {
            this.tunnelKeyStorePassword = tunnelKeyStorePassword;
            return this;
        }

        public BridgeConfigParamsBuilder withTunnelTrustStorePath(String tunnelTrustStorePath) {
            this.tunnelTrustStorePath = tunnelTrustStorePath;
            return this;
        }

        public BridgeConfigParamsBuilder withTunnelTrustStorePassword(String tunnelTrustStorePassword) {
            this.tunnelTrustStorePassword = tunnelTrustStorePassword;
            return this;
        }

        public BridgeConfigParamsBuilder withNetworkParamsPath(String networkParamsPath) {
            this.networkParamsPath = networkParamsPath;
            return this;
        }

        public BridgeConfigParamsBuilder withBridgeKeyStorePath(String bridgeKeyStorePath) {
            this.bridgeKeyStorePath = bridgeKeyStorePath;
            return this;
        }

        public BridgeConfigParamsBuilder withBridgeKeyStorePassword(String bridgeKeyStorePassword) {
            this.bridgeKeyStorePassword = bridgeKeyStorePassword;
            return this;
        }

        public BridgeConfigParamsBuilder withBridgeTrustStorePath(String bridgeTrustStorePath) {
            this.bridgeTrustStorePath = bridgeTrustStorePath;
            return this;
        }

        public BridgeConfigParamsBuilder withBridgeTrustStorePassword(String bridgeTrustStorePassword) {
            this.bridgeTrustStorePassword = bridgeTrustStorePassword;
            return this;
        }

        public BridgeConfigParamsBuilder withTunnelEntryPassword(String bridgeTunnelEntryPassword) {
            this.bridgeTunnelEntryPassword = bridgeTunnelEntryPassword;
            return this;
        }

        @NotNull
        @Contract(value = " -> new", pure = true)
        public BridgeConfigParams build() {
            return new BridgeConfigParams(artemisAddress, artemisPort, artemisKeyStorePath, artemisKeyStorePassword, artemisTrustStorePath, artemisTrustStorePassword, floatAddress, floatPort, expectedFloatCertificateSubject, tunnelKeyStorePath, tunnelKeyStorePassword, tunnelTrustStorePath, tunnelTrustStorePassword, networkParamsPath, bridgeKeyStorePath, bridgeKeyStorePassword, bridgeTrustStorePath, bridgeTrustStorePassword, bridgeTunnelEntryPassword);
        }
    }
}

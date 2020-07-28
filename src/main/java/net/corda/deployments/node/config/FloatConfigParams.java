package net.corda.deployments.node.config;

import org.jetbrains.annotations.NotNull;

import static net.corda.deployments.node.config.BridgeConfigParams.*;

@SubstitutableSource.SubstitutionTarget(targetConfig = "config/float_with_bridge.conf")
public final class FloatConfigParams implements SubstitutableSource {
    @NotNull
    private final String externalBindAddress;
    private final int externalPort;
    @NotNull
    private final String internalBindAddress;
    private final int internalPort;
    @NotNull
    private final String expectedBridgeCertificateSubject;

    private final String tunnelKeyStorePassword;
    private final String tunnelTrustStorePassword;
    private final String tunnelKeystorePath;
    private final String tunnelTrustStorePath;

    @NotNull
    private final String networkParametersPath;
    private final String tunnelEntryPassword;


    @NotNull
    public static final String FLOAT_BASE_DIR = "/opt/corda";
    public static final int FLOAT_INTERNAL_PORT = 10000;
    public static final int FLOAT_EXTERNAL_PORT = 10200;
    public static final String FLOAT_CERTIFICATE_COMMON_NAME = "float";
    public static String FLOAT_CERTIFICATE_SUBJECT = "CN=" + FLOAT_CERTIFICATE_COMMON_NAME + ", O=" + BRIDGE_CERTIFICATE_ORGANISATION
            + ", OU=" + BRIDGE_CERTIFICATE_ORGANISATION_UNIT + ", L=" + BRIDGE_CERTIFICATE_LOCALITY + ", C=" + BRIDGE_CERTIFICATE_COUNTRY;

    public static final String FLOAT_NETWORK_DIR = FLOAT_BASE_DIR + "/network";
    public static final String FLOAT_NETWORK_PARAMS_FILENAME = "network-parameters";
    public static final String FLOAT_NETWORK_PARAMETERS_PATH = FLOAT_NETWORK_DIR + "/" + FLOAT_NETWORK_PARAMS_FILENAME;
    public static final String FLOAT_TUNNEL_STORES_DIR = FLOAT_BASE_DIR + "/certificates";
    public static final String FLOAT_TUNNEL_SSL_KEYSTORE_FILENAME = "float.jks";
    public static final String FLOAT_TUNNEL_SSL_KEYSTORE_PATH = FLOAT_TUNNEL_STORES_DIR + "/" + FLOAT_TUNNEL_SSL_KEYSTORE_FILENAME;
    public static final String FLOAT_TUNNEL_TRUSTSTORE_FILENAME = "tunnel-truststore.jks";
    public static final String FLOAT_TUNNEL_TRUSTSTORE_PATH = FLOAT_TUNNEL_STORES_DIR + "/" + FLOAT_TUNNEL_TRUSTSTORE_FILENAME;

    public static final String FLOAT_TUNNEL_SSL_KEYSTORE_PASSWORD_ENV_VAR_NAME = BridgeConfigParams.BRIDGE_TUNNEL_KEYSTORE_PASSWORD_ENV_VAR_NAME;
    public static final String FLOAT_TUNNEL_TRUSTSTORE_PASSWORD_ENV_VAR_NAME = BridgeConfigParams.BRIDGE_TUNNEL_TRUSTSTORE_PASSWORD_ENV_VAR_NAME;
    public static final String FLOAT_TUNNEL_ENTRY_PASSWORD_ENV_VAR_NAME = BridgeConfigParams.BRIDGE_TUNNEL_ENTRY_PASSWORD_ENV_VAR_NAME;

    public static final String FLOAT_CONFIG_DIR = "/etc/corda";
    public static final String FLOAT_CONFIG_FILENAME = "float.conf";
    public static final String FLOAT_CONFIG_PATH = FLOAT_CONFIG_DIR + "/" + FLOAT_CONFIG_FILENAME;

    public static final String ALL_LOCAL_ADDRESSES = ArtemisConfigParams.ARTEMIS_ACCEPTOR_ALL_LOCAL_ADDRESSES;


    public FloatConfigParams(@NotNull String externalBindAddress,
                             @NotNull Integer externalPort,
                             @NotNull String internalBindAddress,
                             @NotNull Integer internalPort,
                             @NotNull String expectedBridgeCertificateSubject,
                             @NotNull String tunnelKeyStorePassword,
                             @NotNull String tunnelTrustStorePassword,
                             @NotNull String tunnelKeystorePath,
                             @NotNull String tunnelTrustStorePath,
                             @NotNull String tunnelEntryPassword,
                             @NotNull String networkParametersPath) {
        this.externalBindAddress = externalBindAddress;
        this.externalPort = externalPort;
        this.internalBindAddress = internalBindAddress;
        this.internalPort = internalPort;
        this.expectedBridgeCertificateSubject = expectedBridgeCertificateSubject;
        this.tunnelKeyStorePassword = tunnelKeyStorePassword;
        this.tunnelTrustStorePassword = tunnelTrustStorePassword;
        this.tunnelKeystorePath = tunnelKeystorePath;
        this.tunnelTrustStorePath = tunnelTrustStorePath;
        this.tunnelEntryPassword = tunnelEntryPassword;
        this.networkParametersPath = networkParametersPath;
    }


    public static FloatConfigParamsBuilder builder() {
        return new FloatConfigParamsBuilder();
    }

    @NotNull
    public String getExternalBindAddress() {
        return externalBindAddress;
    }

    public int getExternalPort() {
        return externalPort;
    }

    @NotNull
    public String getInternalBindAddress() {
        return internalBindAddress;
    }

    public int getInternalPort() {
        return internalPort;
    }

    @NotNull
    public String getExpectedBridgeCertificateSubject() {
        return expectedBridgeCertificateSubject;
    }

    public String getTunnelKeyStorePassword() {
        return tunnelKeyStorePassword;
    }

    public String getTunnelTrustStorePassword() {
        return tunnelTrustStorePassword;
    }

    public String getTunnelKeystorePath() {
        return tunnelKeystorePath;
    }

    public String getTunnelTrustStorePath() {
        return tunnelTrustStorePath;
    }

    @NotNull
    public String getNetworkParametersPath() {
        return networkParametersPath;
    }

    public String getTunnelEntryPassword() {
        return tunnelEntryPassword;
    }

    public static final class FloatConfigParamsBuilder {
        private String baseDir;
        private String externalBindAddress;
        private int externalPort;
        private String internalBindAddress;
        private int internalPort;
        private String expectedBridgeCertificateSubject;
        private String tunnelKeyStorePassword;
        private String tunnelTrustStorePassword;
        private String tunnelKeystorePath;
        private String tunnelTrustStorePath;
        private String networkParametersPath;
        private String entryPassword;

        private FloatConfigParamsBuilder() {
        }

        public FloatConfigParamsBuilder withBaseDir(String baseDir) {
            this.baseDir = baseDir;
            return this;
        }

        public FloatConfigParamsBuilder withExternalBindAddress(String externalBindAddress) {
            this.externalBindAddress = externalBindAddress;
            return this;
        }

        public FloatConfigParamsBuilder withExternalPort(int externalPort) {
            this.externalPort = externalPort;
            return this;
        }

        public FloatConfigParamsBuilder withInternalBindAddress(String internalBindAddress) {
            this.internalBindAddress = internalBindAddress;
            return this;
        }

        public FloatConfigParamsBuilder withInternalPort(int internalPort) {
            this.internalPort = internalPort;
            return this;
        }

        public FloatConfigParamsBuilder withExpectedBridgeCertificateSubject(String expectedBridgeCertificateSubject) {
            this.expectedBridgeCertificateSubject = expectedBridgeCertificateSubject;
            return this;
        }

        public FloatConfigParamsBuilder withTunnelKeyStorePassword(String tunnelKeyStorePassword) {
            this.tunnelKeyStorePassword = tunnelKeyStorePassword;
            return this;
        }

        public FloatConfigParamsBuilder withTunnelTrustStorePassword(String tunnelTrustStorePassword) {
            this.tunnelTrustStorePassword = tunnelTrustStorePassword;
            return this;
        }

        public FloatConfigParamsBuilder withTunnelKeystorePath(String tunnelKeystorePath) {
            this.tunnelKeystorePath = tunnelKeystorePath;
            return this;
        }

        public FloatConfigParamsBuilder withTunnelTrustStorePath(String tunnelTrustStorePath) {
            this.tunnelTrustStorePath = tunnelTrustStorePath;
            return this;
        }

        public FloatConfigParamsBuilder withNetworkParametersPath(String networkParametersPath) {
            this.networkParametersPath = networkParametersPath;
            return this;
        }

        public FloatConfigParams build() {
            return new FloatConfigParams(externalBindAddress, externalPort, internalBindAddress, internalPort,
                    expectedBridgeCertificateSubject, tunnelKeyStorePassword, tunnelTrustStorePassword, tunnelKeystorePath,
                    tunnelTrustStorePath, entryPassword, networkParametersPath);
        }

        @NotNull
        public FloatConfigParamsBuilder withTunnelStoresEntryPassword(@NotNull String entryPassword) {
            this.entryPassword = entryPassword;
            return this;
        }
    }
}
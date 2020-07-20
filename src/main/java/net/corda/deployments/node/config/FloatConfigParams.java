package net.corda.deployments.node.config;

import org.jetbrains.annotations.NotNull;

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

    @NotNull
    public static final String FLOAT_BASE_DIR = "/opt/corda";
    public static final int FLOAT_INTERNAL_PORT = 10000;
    public static final int FLOAT_EXTERNAL_PORT = 10200;
    @NotNull
    public static final String FLOAT_TUNNEL_SSL_KEYSTORE_LOCATION = "/opt/corda/tunnel/float.jks";
    @NotNull
    public static final String FLOAT_TUNNEL_TRUSTSTORE_LOCATION = "/opt/corda/tunnel/tunnel-truststore.jks";
    @NotNull
    public static final String EXPECTED_BRIDGE_CERTIFICATE_SUBJECT = "CN=bridge, O=Tunnel, L=LONDON, C=GB";
    @NotNull
    public static final String FLOAT_NETWORK_PARAMETERS_PATH = "/opt/corda/network/network-params";

    public static final String FLOAT_CERTIFICATES_DIR = FLOAT_BASE_DIR + "/certificates";
    public static final String FLOAT_SSL_KEYSTORE_FILENAME = "float.jks";
    public static final String FLOAT_SSL_KEYSTORE_PATH = FLOAT_CERTIFICATES_DIR + "/" + FLOAT_SSL_KEYSTORE_FILENAME;
    public static final String FLOAT_TRUSTSTORE_FILENAME = "truststore.jks";
    public static final String FLOAT_TRUSTSTORE_PATH = FLOAT_CERTIFICATES_DIR + "/" + FLOAT_TRUSTSTORE_FILENAME;

    public static final String FLOAT_TUNNEL_KEYSTORE_PASSWORD_ENV_VAR_NAME = BridgeConfigParams.BRIDGE_TUNNEL_KEYSTORE_PASSWORD_ENV_VAR_NAME;
    public static final String FLOAT_TUNNEL_TRUST_PASSWORD_ENV_VAR_NAME = BridgeConfigParams.BRIDGE_TUNNEL_TRUST_PASSWORD_ENV_VAR_NAME;
    public static final String FLOAT_TUNNEL_ENTRY_PASSWORD_ENV_VAR_NAME = BridgeConfigParams.BRIDGE_TUNNEL_ENTRY_PASSWORD_ENV_VAR_NAME;

    public FloatConfigParams(@NotNull String externalBindAddress,
                             @NotNull Integer externalPort,
                             @NotNull String internalBindAddress,
                             @NotNull Integer internalPort,
                             @NotNull String expectedBridgeCertificateSubject,
                             @NotNull String tunnelKeyStorePassword,
                             @NotNull String tunnelTrustStorePassword,
                             @NotNull String tunnelKeystorePath,
                             @NotNull String tunnelTrustStorePath,
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
                    tunnelTrustStorePath, networkParametersPath);
        }
    }
}
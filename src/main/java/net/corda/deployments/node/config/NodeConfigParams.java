package net.corda.deployments.node.config;

import net.corda.deployments.node.config.SubstitutableSource.SubstitutionTarget;

@SubstitutionTarget(targetConfig = "config/node_with_external_artemis.conf")
public class NodeConfigParams implements SubstitutableSource {

    private final String x500Name;
    private final String nodeSSLKeystorePassword;
    private final String nodeTrustStorePassword;
    private final String p2pAddress;
    private final Integer p2pPort;
    private final String artemisServerIp;
    private final Integer artemisServerPort;
    private final String artemisSSLKeyStorePath;
    private final String artemisSSLKeyStorePass;
    private final String artemisTrustStorePath;
    private final String artemisTrustStorePass;
    private final Integer rpcPort;
    private final Integer rpcAdminPort;
    private final String doormanURL;
    private final String networkMapURL;
    private final String rpcUsername;
    private final String rpcPassword;
    private final String dataSourceClassName;
    private final String dataSourceURL;
    private final String dataSourceUsername;
    private final String dataSourcePassword;
    private final String azureKeyVaultConfPath;


    private static final String NODE_BASE_DIR = "/opt/corda";
    private static final Integer NODE_P2P_PORT = 10200;
    private static final Integer NODE_RPC_PORT = 10001;
    private static final Integer NODE_RPC_ADMIN_PORT = 10002;

    public NodeConfigParams(String x500Name,
                            String nodeSSLKeystorePassword,
                            String nodeTrustStorePassword,
                            String p2pAddress,
                            Integer p2pPort,
                            String artemisServerIp,
                            Integer artemisServerPort,
                            String artemisSSLKeyStorePath,
                            String artemisSSLKeyStorePass,
                            String artemisTrustStorePath,
                            String artemisTrustStorePass,
                            Integer rpcPort,
                            Integer rpcAdminPort,
                            String doormanURL,
                            String networkMapURL,
                            String rpcUsername,
                            String rpcPassword,
                            String dataSourceClassName,
                            String dataSourceURL,
                            String dataSourceUsername,
                            String dataSourcePassword,
                            String azureKeyVaultConfPath) {
        this.x500Name = x500Name;
        this.nodeSSLKeystorePassword = nodeSSLKeystorePassword;
        this.nodeTrustStorePassword = nodeTrustStorePassword;
        this.p2pAddress = p2pAddress;
        this.p2pPort = p2pPort;
        this.artemisServerIp = artemisServerIp;
        this.artemisServerPort = artemisServerPort;
        this.artemisSSLKeyStorePath = artemisSSLKeyStorePath;
        this.artemisSSLKeyStorePass = artemisSSLKeyStorePass;
        this.artemisTrustStorePath = artemisTrustStorePath;
        this.artemisTrustStorePass = artemisTrustStorePass;
        this.rpcPort = rpcPort;
        this.rpcAdminPort = rpcAdminPort;
        this.doormanURL = doormanURL;
        this.networkMapURL = networkMapURL;
        this.rpcUsername = rpcUsername;
        this.rpcPassword = rpcPassword;
        this.dataSourceClassName = dataSourceClassName;
        this.dataSourceURL = dataSourceURL;
        this.dataSourceUsername = dataSourceUsername;
        this.dataSourcePassword = dataSourcePassword;
        this.azureKeyVaultConfPath = azureKeyVaultConfPath;
    }

    public String getX500Name() {
        return x500Name;
    }

    public String getNodeSSLKeystorePassword() {
        return nodeSSLKeystorePassword;
    }

    public String getNodeTrustStorePassword() {
        return nodeTrustStorePassword;
    }

    public String getP2pAddress() {
        return p2pAddress;
    }

    public Integer getP2pPort() {
        return p2pPort;
    }

    public String getArtemisServerIp() {
        return artemisServerIp;
    }

    public Integer getArtemisServerPort() {
        return artemisServerPort;
    }

    public String getArtemisSSLKeyStorePath() {
        return artemisSSLKeyStorePath;
    }

    public String getArtemisSSLKeyStorePass() {
        return artemisSSLKeyStorePass;
    }

    public String getArtemisTrustStorePath() {
        return artemisTrustStorePath;
    }

    public String getArtemisTrustStorePass() {
        return artemisTrustStorePass;
    }

    public Integer getRpcPort() {
        return rpcPort;
    }

    public Integer getRpcAdminPort() {
        return rpcAdminPort;
    }

    public String getDoormanURL() {
        return doormanURL;
    }

    public String getNetworkMapURL() {
        return networkMapURL;
    }

    public String getRpcUsername() {
        return rpcUsername;
    }

    public String getRpcPassword() {
        return rpcPassword;
    }

    public String getDataSourceClassName() {
        return dataSourceClassName;
    }

    public String getDataSourceURL() {
        return dataSourceURL;
    }

    public String getDataSourceUsername() {
        return dataSourceUsername;
    }

    public String getDataSourcePassword() {
        return dataSourcePassword;
    }

    public String getAzureKeyVaultConfPath() {
        return azureKeyVaultConfPath;
    }

    public static NodeConfigParamsBuilder builder() {
        return new NodeConfigParamsBuilder();
    }


    public static final class NodeConfigParamsBuilder {
        private String baseDir;
        private String x500Name;
        private String nodeSSLKeystorePassword;
        private String nodeTrustStorePassword;
        private String p2pAddress;
        private Integer p2pPort;
        private String artemisServerIp;
        private Integer artemisServerPort;
        private String artemisSSLKeyStorePath;
        private String artemisSSLKeyStorePass;
        private String artemisTrustStorePath;
        private String artemisTrustStorePass;
        private Integer rpcPort;
        private Integer rpcAdminPort;
        private String doormanURL;
        private String networkMapURL;
        private String rpcUsername;
        private String rpcPassword;
        private String dataSourceClassName;
        private String dataSourceURL;
        private String dataSourceUsername;
        private String dataSourcePassword;
        private String azureKeyVaultConfPath;

        private NodeConfigParamsBuilder() {
        }


        public NodeConfigParamsBuilder withBaseDir(String baseDir) {
            this.baseDir = baseDir;
            return this;
        }

        public NodeConfigParamsBuilder withX500Name(String x500Name) {
            this.x500Name = x500Name;
            return this;
        }

        public NodeConfigParamsBuilder withNodeSSLKeystorePassword(String nodeSSLKeystorePassword) {
            this.nodeSSLKeystorePassword = nodeSSLKeystorePassword;
            return this;
        }

        public NodeConfigParamsBuilder withNodeTrustStorePassword(String nodeTrustStorePassword) {
            this.nodeTrustStorePassword = nodeTrustStorePassword;
            return this;
        }

        public NodeConfigParamsBuilder withP2pAddress(String p2pAddress) {
            this.p2pAddress = p2pAddress;
            return this;
        }

        public NodeConfigParamsBuilder withP2pPort(Integer p2pPort) {
            this.p2pPort = p2pPort;
            return this;
        }

        public NodeConfigParamsBuilder withArtemisServerIp(String artemisServerIp) {
            this.artemisServerIp = artemisServerIp;
            return this;
        }

        public NodeConfigParamsBuilder withArtemisServerPort(Integer artemisServerPort) {
            this.artemisServerPort = artemisServerPort;
            return this;
        }

        public NodeConfigParamsBuilder withArtemisSSLKeyStorePath(String artemisSSLKeyStorePath) {
            this.artemisSSLKeyStorePath = artemisSSLKeyStorePath;
            return this;
        }

        public NodeConfigParamsBuilder withArtemisSSLKeyStorePass(String artemisSSLKeyStorePass) {
            this.artemisSSLKeyStorePass = artemisSSLKeyStorePass;
            return this;
        }

        public NodeConfigParamsBuilder withArtemisTrustStorePath(String artemisTrustStorePath) {
            this.artemisTrustStorePath = artemisTrustStorePath;
            return this;
        }

        public NodeConfigParamsBuilder withArtemisTrustStorePass(String artemisTrustStorePass) {
            this.artemisTrustStorePass = artemisTrustStorePass;
            return this;
        }

        public NodeConfigParamsBuilder withRpcPort(Integer rpcPort) {
            this.rpcPort = rpcPort;
            return this;
        }

        public NodeConfigParamsBuilder withRpcAdminPort(Integer rpcAdminPort) {
            this.rpcAdminPort = rpcAdminPort;
            return this;
        }

        public NodeConfigParamsBuilder withDoormanURL(String doormanURL) {
            this.doormanURL = doormanURL;
            return this;
        }

        public NodeConfigParamsBuilder withNetworkMapURL(String networkMapURL) {
            this.networkMapURL = networkMapURL;
            return this;
        }

        public NodeConfigParamsBuilder withRpcUsername(String rpcUsername) {
            this.rpcUsername = rpcUsername;
            return this;
        }

        public NodeConfigParamsBuilder withRpcPassword(String rpcPassword) {
            this.rpcPassword = rpcPassword;
            return this;
        }

        public NodeConfigParamsBuilder withDataSourceClassName(String dataSourceClassName) {
            this.dataSourceClassName = dataSourceClassName;
            return this;
        }

        public NodeConfigParamsBuilder withDataSourceURL(String dataSourceURL) {
            this.dataSourceURL = dataSourceURL;
            return this;
        }

        public NodeConfigParamsBuilder withDataSourceUsername(String dataSourceUsername) {
            this.dataSourceUsername = dataSourceUsername;
            return this;
        }

        public NodeConfigParamsBuilder withDataSourcePassword(String dataSourcePassword) {
            this.dataSourcePassword = dataSourcePassword;
            return this;
        }

        public NodeConfigParamsBuilder withAzureKeyVaultConfPath(String azureKeyVaultConfPath) {
            this.azureKeyVaultConfPath = azureKeyVaultConfPath;
            return this;
        }

        public NodeConfigParams build() {
            return new NodeConfigParams(x500Name, nodeSSLKeystorePassword, nodeTrustStorePassword, p2pAddress, p2pPort,
                    artemisServerIp, artemisServerPort, artemisSSLKeyStorePath, artemisSSLKeyStorePass, artemisTrustStorePath,
                    artemisTrustStorePass, rpcPort, rpcAdminPort, doormanURL, networkMapURL, rpcUsername, rpcPassword, dataSourceClassName,
                    dataSourceURL, dataSourceUsername, dataSourcePassword, azureKeyVaultConfPath);
        }
    }
}
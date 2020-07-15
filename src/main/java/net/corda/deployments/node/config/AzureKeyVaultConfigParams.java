package net.corda.deployments.node.config;

@SubstitutableSource.SubstitutionTarget(targetConfig = "config/az_key_vault_hsm.conf")
public class AzureKeyVaultConfigParams implements SubstitutableSource {

    private final String servicePrincipalCredentialsFilePath;
    private final String servicePrincipalCredentialsFilePassword;
    private final String keyVaultClientId;
    private final String keyAlias;
    private final String keyVaultURL;
    private final String keyVaultProtectionMode;


    public static final String CREDENTIALS_DIR = "/etc/hsm";
    public static final String CREDENTIALS_P12_FILENAME = "az_kv.p12";
    public static final String CREDENTIALS_P12_PATH = CREDENTIALS_DIR + "/" + CREDENTIALS_P12_FILENAME;
    public static final String KEY_PROTECTION_MODE_HARDWARE = "HARDWARE";
    public static final String KEY_PROTECTION_MODE_SOFTWARE = "SOFTWARE";

    public AzureKeyVaultConfigParams(String servicePrincipalCredentialsFilePath, String servicePrincipalCredentialsFilePassword,
                                     String keyAlias, String keyVaultURL, String keyVaultClientId, String keyVaultProtectionMode) {
        this.servicePrincipalCredentialsFilePath = servicePrincipalCredentialsFilePath;
        this.servicePrincipalCredentialsFilePassword = servicePrincipalCredentialsFilePassword;
        this.keyAlias = keyAlias;
        this.keyVaultURL = keyVaultURL;
        this.keyVaultClientId = keyVaultClientId;
        this.keyVaultProtectionMode = keyVaultProtectionMode;
    }

    public String getServicePrincipalCredentialsFilePath() {
        return servicePrincipalCredentialsFilePath;
    }

    public String getServicePrincipalCredentialsFilePassword() {
        return servicePrincipalCredentialsFilePassword;
    }

    public String getKeyVaultClientId() {
        return keyVaultClientId;
    }

    public String getKeyAlias() {
        return keyAlias;
    }

    public String getKeyVaultURL() {
        return keyVaultURL;
    }

    public String getKeyVaultProtectionMode() {
        return keyVaultProtectionMode;
    }

    public static AzureKeyVaultConfigParamsBuilder builder() {
        return new AzureKeyVaultConfigParamsBuilder();
    }

    public static final class AzureKeyVaultConfigParamsBuilder {
        private String servicePrincipalCredentialsFilePath;
        private String servicePrincipalCredentialsFilePassword;
        private String keyAlias;
        private String keyVaultURL;
        private String keyVaultClientId;
        private String keyVaultProtectionMode;

        private AzureKeyVaultConfigParamsBuilder() {
        }

        public AzureKeyVaultConfigParamsBuilder withServicePrincipalCredentialsFilePath(String servicePrincipalCredentialsFilePath) {
            this.servicePrincipalCredentialsFilePath = servicePrincipalCredentialsFilePath;
            return this;
        }

        public AzureKeyVaultConfigParamsBuilder withServicePrincipalCredentialsFilePassword(
                String servicePrincipalCredentialsFilePassword) {
            this.servicePrincipalCredentialsFilePassword = servicePrincipalCredentialsFilePassword;
            return this;
        }

        public AzureKeyVaultConfigParamsBuilder withKeyAlias(String keyAlias) {
            this.keyAlias = keyAlias;
            return this;
        }

        public AzureKeyVaultConfigParamsBuilder withKeyVaultURL(String keyVaultURL) {
            this.keyVaultURL = keyVaultURL;
            return this;
        }

        public AzureKeyVaultConfigParamsBuilder withKeyVaultClientId(String keyVaultClientId) {
            this.keyVaultClientId = keyVaultClientId;
            return this;
        }

        public AzureKeyVaultConfigParamsBuilder withKeyVaultProtectionMode(String keyVaultProtectionMode) {
            this.keyVaultProtectionMode = keyVaultProtectionMode;
            return this;
        }

        public AzureKeyVaultConfigParams build() {
            return new AzureKeyVaultConfigParams(servicePrincipalCredentialsFilePath, servicePrincipalCredentialsFilePassword, keyAlias,
                    keyVaultURL, keyVaultClientId, keyVaultProtectionMode);
        }
    }
}

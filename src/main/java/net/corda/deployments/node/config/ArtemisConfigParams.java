package net.corda.deployments.node.config;

public class ArtemisConfigParams {

    public static final String ARTEMIS_DIR_TO_COPY_STORES_TO = "/tmp/artemisstores";
    public static final String ARTEMIS_DIR_TO_COPY_STORES_TO_ENV_NAME = "ARTEMIS_STORES_DIR";

    public static final String BRIDGE_DIR_TO_COPY_STORES_TO = "/tmp/bridgestores";
    public static final String BRIDGE_DIR_TO_COPY_STORES_TO_ENV_NAME = "BRIDGE_STORES_DIR";

    public static final String NODE_DIR_TO_COPY_STORES_TO = "/tmp/nodestores";
    public static final String NODE_DIR_TO_COPY_STORES_TO_ENV_NAME = "NODE_STORES_DIR";

    public static final String ARTEMIS_BROKER_BASE_DIR = "/opt/artemis-broker";
    public static final String ARTEMIS_STORES_DIR = "/etc/artemis";

    public static final String ARTEMIS_DATA_DIR_ENV_VAR_NAME = "ARTEMIS_DATA_DIR";
    public static final String ARTEMIS_DATA_DIR_PATH = "/opt/artemis-data";

    public static final String ARTEMIS_CERTIFICATE_ORGANISATION = "CordaDeployerTunnel";
    public static final String ARTEMIS_CERTIFICATE_ORGANISATION_ENV_VAR_NAME = "ORGANISATION";
    public static final String ARTEMIS_CERTIFICATE_ORGANISATION_UNIT = "Azure";
    public static final String ARTEMIS_CERTIFICATE_ORGANISATION_UNIT_ENV_VAR_NAME = "ORGANISATION_UNIT";
    public static final String ARTEMIS_CERTIFICATE_LOCALITY = "CLOUD";
    public static final String ARTEMIS_CERTIFICATE_LOCALITY_ENV_VAR_NAME = "LOCALITY";
    public static final String ARTEMIS_CERTIFICATE_COUNTRY = "GB";
    public static final String ARTEMIS_CERTIFICATE_COUNTRY_ENV_VAR_NAME = "COUNTRY";
    public static final String ARTEMIS_CERTIFICATE_COMMON_NAME = "artemis";

    public static final String ARTEMIS_CERTIFICATE_SUBJECT = "CN=" + ARTEMIS_CERTIFICATE_COMMON_NAME + ", O=" + ARTEMIS_CERTIFICATE_ORGANISATION
            + ", OU=" + ARTEMIS_CERTIFICATE_ORGANISATION_UNIT + ", L=" + ARTEMIS_CERTIFICATE_LOCALITY + ", C=" + ARTEMIS_CERTIFICATE_COUNTRY;


    public static final String ARTEMIS_USER_X500_ENV_VAR_NAME = "ARTEMIS_X500";

    public static final String ARTEMIS_ACCEPTOR_ALL_LOCAL_ADDRESSES = "0.0.0.0";
    public static final String ARTEMIS_ACCEPTOR_ADDRESS_ENV_VAR_NAME = "ACCEPTOR_ADDRESS";
    public static final Integer ARTEMIS_ACCEPTOR_PORT = 20001;
    public static final String ARTEMIS_ACCEPTOR_PORT_ENV_VAR_NAME = "ACCEPTOR_PORT";

    public static final String ARTEMIS_KEYSTORE_PATH_ENV_VAR_NAME = "ARTEMIS_KEYSTORE_PATH";
    public static final String ARTEMIS_TRUSTSTORE_PATH_ENV_VAR_NAME = "ARTEMIS_TRUSTSTORE_PATH";
    public static final String ARTEMIS_SSL_KEYSTORE_PASSWORD_ENV_VAR_NAME = "ARTEMIS_SSL_KEYSTORE_PASSWORD";
    public static final String ARTEMIS_TRUSTSTORE_PASSWORD_ENV_VAR_NAME = "ARTEMIS_TRUSTSTORE_PASSWORD";
    public static final String ARTEMIS_CLUSTER_PASSWORD_ENV_VAR_NAME = "ARTEMIS_CLUSTER_PASSWORD";

    public static final String ARTEMIS_TRUSTSTORE_FILENAME = "artemis-truststore.jks";
    public static final String ARTEMIS_SSL_KEYSTORE_FILENAME = "artemis.jks";
    public static final String ARTEMIS_NODE_KEYSTORE_FILENAME = "artemisnode.jks";
    public static final String ARTEMIS_BRIDGE_KEYSTORE_FILENAME = "artemisbridge.jks";

    public static final String ARTEMIS_SSL_KEYSTORE_PATH = ARTEMIS_STORES_DIR + "/" + ARTEMIS_SSL_KEYSTORE_FILENAME;
    public static final String ARTEMIS_TRUSTSTORE_PATH = ARTEMIS_STORES_DIR + "/" + ARTEMIS_TRUSTSTORE_FILENAME;


}

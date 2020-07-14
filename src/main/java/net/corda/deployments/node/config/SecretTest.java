package net.corda.deployments.node.config;

import com.google.common.collect.Maps;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.JSON;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1SecretBuilder;
import io.kubernetes.client.util.ClientBuilder;
import kotlin.Pair;
import kotlin.text.Charsets;

import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class SecretTest {

    public static void main(String[] args) throws IOException {
        HashMap<String, String> stringStringHashMap = new HashMap<>();
        stringStringHashMap.put("username", "stefano");
        secretIssueReproduce("default", "test-secret", stringStringHashMap);
    }

    static void secretIssueReproduce(String namespace, String name, Map<String, String> data) throws IOException {
        Base64.Encoder encoder = Base64.getEncoder();

        Map<String, byte[]> dataMap = data.entrySet()
                .stream()
                .map(entry -> new Pair<String, byte[]>(entry.getKey(), (entry.getValue().getBytes(Charsets.UTF_8))))
                .collect(Collectors.toMap(Pair<String, byte[]>::getFirst, Pair<String, byte[]>::getSecond));

        V1Secret secret = new V1SecretBuilder()
                .withApiVersion("v1")
                .withKind("Secret")
                .withNewMetadata()
                .withCreationTimestamp(null)
                .withName(name)
                .withNamespace(namespace)
                .endMetadata()
                .withData(dataMap)
                .withType("Opaque")
                .build();


        ApiClient apiClient = ClientBuilder.defaultClient();
        //        apiClient.setJSON(JSON)
        apiClient.setDebugging(true);
        try {
            System.out.println("About to create secret with dataMap: " + dataMap.entrySet().stream().map(entry -> entry.getKey() + "=" + new String(entry.getValue(), Charsets.UTF_8)).findFirst().get());
            new CoreV1Api(apiClient).createNamespacedSecret(namespace, secret, "false", null, null);
        } catch (ApiException ae) {
            System.err.println(ae.getResponseBody());
        }
    }
}

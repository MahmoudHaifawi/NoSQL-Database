package com.database.atypon.DBMS.database_system.index;

import com.database.atypon.DBMS.database_system.PathBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

/**
 * Forwards secondary-index operations from the DBMS gateway to a data node's REST API.
 *
 * <p>The node owns the index's type information (stored in each index's META page) and performs
 * value coercion itself, so the gateway forwards query values as plain strings — exactly what an
 * HTML form submits — without needing to know the indexed field's type.
 */
public class IndexRequest {

    /**
     * Creates a secondary index on {@code schema.field} by calling the node's admin endpoint.
     * Returns the node's success message, or throws with the node's error message.
     */
    public static String createIndex(String database, String schema, String field,
                                     String token, String nodeURL) throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        String url = nodeURL + PathBuilder.buildCreateIndexPath(database, schema, field);

        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", token);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        Vector<LinkedHashMap> responses = restTemplate.postForObject(url, request, Vector.class);
        if (responses == null || responses.isEmpty()) {
            throw new Exception("No response from node");
        }
        LinkedHashMap response = responses.get(0);
        if ("ERROR".equals(response.get("responseType"))) {
            throw new Exception(String.valueOf(response.get("message")));
        }
        return String.valueOf(response.get("message"));
    }

    /**
     * Runs an indexed query on the node and returns the matching document ids in result order.
     * The {@code body} carries database/schema/field/op and the optional value/high/order/limit/offset.
     */
    public static List<Integer> queryIds(Map<String, Object> body, String token, String nodeURL) throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        String url = nodeURL + PathBuilder.buildIndexQueryPath();

        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        LinkedHashMap response = restTemplate.postForObject(url, request, LinkedHashMap.class);
        if (response == null) {
            throw new Exception("No response from node");
        }
        if ("ERROR".equals(response.get("responseType"))) {
            throw new Exception(String.valueOf(response.get("message")));
        }

        List<Integer> ids = new ArrayList<>();
        Object content = response.get("content");
        if (content instanceof List) {
            for (Object raw : (List<?>) content) {
                if (raw instanceof Number) {
                    ids.add(((Number) raw).intValue());
                } else if (raw != null) {
                    ids.add(Integer.parseInt(raw.toString()));
                }
            }
        }
        return ids;
    }
}

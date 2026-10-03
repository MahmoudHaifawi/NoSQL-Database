package com.database.atypon.DBMS.service;

import com.database.atypon.DBMS.database_system.connection.DatabaseOperations;
import com.database.atypon.DBMS.model.Schema;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.util.HashMap;

@Service
public class WriteService {
    public String createSchema(Schema schema, String token, String nodeURL) throws Exception {
        DatabaseOperations databaseOperations = new DatabaseOperations(nodeURL, token);
        JSONObject schemaJson = new JSONObject();
        schemaJson.put("schemaName", schema.getSchemaName());
        schemaJson.put("schema", new JSONObject(schema.getSchema()));
        return databaseOperations.createSchema(schema.getDatabaseName(), schema.getSchemaName(), schemaJson);
    }

    public String createDocument(String databaseName, String schemaName, String documentJson,
                                 String token, String nodeURL) throws Exception {
        DatabaseOperations databaseOperations = new DatabaseOperations(nodeURL, token);
        // Parse the user-supplied JSON and carry each value with its JSON type (Integer/Double/
        // Boolean/String) so the node's schema-type validation sees the right classes.
        JSONObject parsed = new JSONObject(documentJson);
        HashMap<Object, Object> document = new HashMap<>();
        for (String key : parsed.keySet()) {
            document.put(key, parsed.get(key));
        }
        String raw = databaseOperations.createDocument(databaseName, schemaName, document);
        // The node replies with a JSON array of responses (one per node that handled the write).
        org.json.JSONArray responses = new org.json.JSONArray(raw);
        if (responses.isEmpty()) {
            throw new Exception("No response from node");
        }
        JSONObject first = responses.getJSONObject(0);
        if ("ERROR".equals(first.optString("responseType"))) {
            throw new Exception(first.optString("message"));
        }
        return first.optString("message");
    }

    public int updateDocument(String databaseName, String schemaName, String id, String documentJson,
                              int expectedVersion, String token, String nodeURL) throws Exception {
        JSONObject doc = new JSONObject(documentJson);
        doc.put("_version", expectedVersion); // carry the expected version into the node request body
        String raw = com.database.atypon.DBMS.database_system.write.WriteRequest
                .updateDocument(databaseName, schemaName, id, doc, token, nodeURL);
        org.json.JSONArray responses = new org.json.JSONArray(raw);
        if (responses.isEmpty()) {
            throw new Exception("No response from node");
        }
        JSONObject first = responses.getJSONObject(0);
        if ("ERROR".equals(first.optString("responseType"))) {
            throw new Exception(first.optString("message"));
        }
        return first.optInt("content", expectedVersion + 1); // the new version
    }
}













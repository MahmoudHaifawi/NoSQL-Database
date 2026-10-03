package com.database.atypon.DBMS.service;

import com.database.atypon.DBMS.database_system.read.ReadOperations;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Gateway-side read orchestration: fetches documents from the data node and shapes them for the
 * retrieval UI. The node returns read-all as a map of {@code "<id>.json" -> "<doc json string>"};
 * this flattens it to an id-ordered map of id to pretty-printed document.
 */
@Service
public class ReadService {

    public LinkedHashMap<String, String> readAll(String database, String schema,
                                                 String token, String nodeURL) {
        JSONObject content = ReadOperations.readAllDocuments(database, schema, token, nodeURL);

        List<String> keys = new ArrayList<>(content.keySet());
        keys.sort(Comparator.comparingInt(ReadService::idOf));

        LinkedHashMap<String, String> docs = new LinkedHashMap<>();
        for (String key : keys) {
            String id = key.endsWith(".json") ? key.substring(0, key.length() - ".json".length()) : key;
            docs.put(id, pretty(content.get(key)));
        }
        return docs;
    }

    public String readById(String database, String schema, String id, String token, String nodeURL) {
        JSONObject doc = ReadOperations.readDocumentById(database, schema, id, token, nodeURL);
        return doc.toString(2);
    }

    private static int idOf(String key) {
        try {
            String digits = key.endsWith(".json") ? key.substring(0, key.length() - ".json".length()) : key;
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE; // non-numeric keys sort last, stably
        }
    }

    private static String pretty(Object rawDoc) {
        try {
            return new JSONObject(rawDoc.toString()).toString(2);
        } catch (Exception e) {
            return String.valueOf(rawDoc);
        }
    }
}

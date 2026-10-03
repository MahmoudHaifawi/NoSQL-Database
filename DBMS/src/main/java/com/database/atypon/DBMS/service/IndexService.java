package com.database.atypon.DBMS.service;

import com.database.atypon.DBMS.database_system.index.IndexRequest;
import com.database.atypon.DBMS.database_system.read.ReadOperations;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gateway-side orchestration for the B+-tree secondary index demo.
 *
 * <p>A query is a two-step fan-out to the data node: first the index returns the matching
 * document ids (in result order, honouring ORDER BY / pagination), then each id is resolved to
 * its stored document via the existing read-by-id path. This mirrors how a real engine separates
 * the index scan from the heap fetch.
 */
@Service
public class IndexService {

    public String createIndex(String database, String schema, String field,
                              String token, String nodeURL) throws Exception {
        return IndexRequest.createIndex(database, schema, field, token, nodeURL);
    }

    /**
     * Runs an indexed query and resolves each returned id to its document, preserving index order.
     *
     * @return ordered map of document id to its JSON (pretty-printed for display)
     */
    public LinkedHashMap<Integer, String> query(Map<String, Object> body, String token, String nodeURL)
            throws Exception {
        List<Integer> ids = IndexRequest.queryIds(body, token, nodeURL);
        String database = (String) body.get("database");
        String schema = (String) body.get("schema");

        LinkedHashMap<Integer, String> results = new LinkedHashMap<>();
        for (Integer id : ids) {
            try {
                JSONObject doc = ReadOperations.readDocumentById(database, schema, String.valueOf(id),
                        token, nodeURL);
                results.put(id, doc.toString(2));
            } catch (Exception e) {
                // An index entry whose record can't be resolved is surfaced rather than dropped,
                // so a stale or inconsistent index is visible in the demo instead of silently hidden.
                results.put(id, "<unable to read document: " + e.getMessage() + ">");
            }
        }
        return results;
    }
}

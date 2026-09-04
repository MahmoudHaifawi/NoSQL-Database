package com.database.atypon.Node.services.write;

import com.database.atypon.Node.model.Network;
import com.database.atypon.Node.model.Node;
import com.database.atypon.Node.operations.write.WriteOperation;
import com.database.atypon.Node.utils.AffinityLoadBalancer;
import com.database.atypon.Node.utils.JsonKeys;
import com.database.atypon.Node.utils.PathBuilder;
import com.database.atypon.Node.utils.concurrent.Broadcaster;
import com.database.atypon.Node.utils.file_operations.fileWriter.FileWriter;
import com.database.atypon.Node.utils.response.Response;
import com.database.atypon.Node.utils.response.ResponseType;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Supplier;

@Service
public class WriteService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(WriteService.class);

    private final WriteOperation writeOperation;

    public WriteService(WriteOperation writeOperation) {
        this.writeOperation = writeOperation;
    }

    public Response createSchema(String database, HashMap<String, Object> schema) {
            JSONObject schemaJSON = new JSONObject(schema);
            String schemaName = schemaJSON.get(JsonKeys.SCHEMA_NAME).toString();

            // Assign the node affinity to the schema using the load balancer
            String nodeAffinity = AffinityLoadBalancer.assignAffinity(database, schemaName);

            // write the node affinity in the affinities directory
            writeNodeAffinity(database, schemaName, nodeAffinity);

            JSONObject schemaDetails = schemaJSON.getJSONObject(JsonKeys.SCHEMA);
            return writeOperation.createSchema(database, schemaName, schemaDetails);
    }

    public Response createDocument(String database, String schema, HashMap<String, Object> document) {
        try{
            JSONObject documentJSON = new JSONObject(document);
            return writeOperation.createDocument(database, schema, documentJSON);
        } catch (Exception e){
            return new Response(ResponseType.ERROR, e.getMessage());
        }
    }

    public List<Response> broadcastSchema(String database, HashMap<String, Object> schema) {
        List<Supplier<Response>> tasks = new ArrayList<>();
        for (Node node : Network.nodes) {
            tasks.add(() -> node.createSchema(database, schema));
        }
        return Broadcaster.broadcast(tasks);
    }

    public List<Response> broadcastDocument(String database, String schema, HashMap<String, Object> document) {
        List<Supplier<Response>> tasks = new ArrayList<>();
        for (Node node : Network.nodes) {
            tasks.add(() -> node.createDocument(database, schema, document));
        }
        return Broadcaster.broadcast(tasks);
    }

    private void writeNodeAffinity(String database, String schemaName, String nodeAffinity) {
        try{
            String schemaAffinityPath = PathBuilder.getPathToAffinity(database, schemaName);
            File schemaAffinityFile = new File(schemaAffinityPath);
            schemaAffinityFile.createNewFile();

            JSONObject schemaAffinity = new JSONObject();
            schemaAffinity.put(JsonKeys.NODE, nodeAffinity);

            FileWriter fileWriter = new FileWriter(schemaAffinityFile, schemaAffinity.toString());
            fileWriter.write();
        }catch (Exception e){
            log.error("Failed to write node affinity", e);
        }
    }
}

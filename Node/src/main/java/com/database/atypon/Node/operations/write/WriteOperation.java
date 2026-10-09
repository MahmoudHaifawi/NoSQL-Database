package com.database.atypon.Node.operations.write;

import com.database.atypon.Node.services.index.IndexManager;
import com.database.atypon.Node.utils.JsonKeys;
import com.database.atypon.Node.utils.PathBuilder;
import com.database.atypon.Node.utils.Validators;
import com.database.atypon.Node.utils.file_operations.fileReader.FileReader;
import com.database.atypon.Node.utils.file_operations.fileWriter.FileWriter;
import com.database.atypon.Node.utils.response.Response;
import com.database.atypon.Node.utils.response.ResponseType;
import org.json.JSONObject;
import org.springframework.stereotype.Component;

import java.io.File;

@Component
public class WriteOperation {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WriteOperation.class);

    private final IndexManager indexManager;

    public WriteOperation(IndexManager indexManager) {
        this.indexManager = indexManager;
    }

    public Response createSchema(String database, String schemaName, JSONObject schemaDetails) {
        if (!new File(PathBuilder.getPathToDatabase(database)).exists())
            return new Response(ResponseType.ERROR, "Database doesn't exist");
        try {
            Validators.validateSchema(schemaDetails, schemaName);
            synchronized (this) {
                JSONObject finalSchema = new JSONObject();
                JSONObject infoObject = new JSONObject();

                infoObject.put(JsonKeys.SCHEMA_NAME, schemaName);
                infoObject.put(JsonKeys.NEXT_ID, 0);

                finalSchema.put(JsonKeys.INFO, infoObject);
                finalSchema.put(JsonKeys.SCHEMA, schemaDetails);

                String pathToSchema = PathBuilder.getPathToSchema(database, schemaName);
                File schemaFile = new File(pathToSchema);

                if (schemaFile.exists())
                    return new Response(ResponseType.ERROR, "Schema already exists");

                schemaFile.createNewFile();

                //create directory to the data of the schema
                File schemaData = new File(PathBuilder.getPathToAllDocuments(database, schemaName));
                try {
                    schemaData.mkdir();
                } catch (Exception e) {
                    return new Response(ResponseType.ERROR, "Error creating schema data directory");
                }
                return new FileWriter(schemaFile, finalSchema.toString()).write();
            }
        } catch (Exception e) {
            return new Response(ResponseType.ERROR, e.getMessage());
        }
    }

    public Response createDocument(String database, String schema, JSONObject documentJSON) {
        try {
            Validators.validateDocument(documentJSON, schema, database);
        } catch (Exception e) {
            return new Response(ResponseType.ERROR, e.getMessage());
        }
        synchronized (this) {
            try {
                String pathToSchema = PathBuilder.getPathToSchema(database, schema);
                File schemaFile = new File(pathToSchema);
                FileReader fileReader = new FileReader(schemaFile);
                fileReader.read();

                JSONObject schemaJSON = new JSONObject(fileReader.getContent());
                JSONObject infoObject = schemaJSON.getJSONObject(JsonKeys.INFO);
                int nextId = infoObject.getInt(JsonKeys.NEXT_ID);

                updateSchemaInfo(database, schema, nextId + 1);

                String pathToDocument = PathBuilder.getPathToDocument(database, schema,
                        String.valueOf(nextId));
                File documentFile = new File(pathToDocument);
                documentFile.createNewFile();
                documentJSON.put(JsonKeys.VERSION, 1); // reserved, server-controlled
                FileWriter fileWriter = new FileWriter(documentFile, documentJSON.toString());
                fileWriter.write();
                try {
                    indexManager.onInsert(database, schema, nextId, documentJSON);
                } catch (Exception indexError) {
                    // indexes are derived/rebuildable; don't fail the write on a maintenance error
                    log.error("index maintenance failed for {}.{} doc {}", database, schema, nextId, indexError);
                }
                return new Response(ResponseType.SUCCESS, "Document created successfully");
            } catch (Exception e) {
                return new Response(ResponseType.ERROR, e.getMessage());
            }

        }
    }

    public Response updateDocument(String database, String schema, String id, JSONObject newDoc, int expectedVersion) {
        try {
            Validators.validateDocument(newDoc, schema, database);
        } catch (Exception e) {
            return new Response(ResponseType.ERROR, e.getMessage());
        }
        synchronized (this) {
            try {
                File documentFile = new File(PathBuilder.getPathToDocument(database, schema, id));
                if (!documentFile.exists())
                    return new Response(ResponseType.ERROR, "Document not found");

                FileReader fileReader = new FileReader(documentFile);
                fileReader.read();
                int storedVersion = new JSONObject(fileReader.getContent()).optInt(JsonKeys.VERSION, 1);
                if (storedVersion != expectedVersion)
                    return new Response(ResponseType.ERROR,
                            "Version conflict: document is at version " + storedVersion, storedVersion);

                int newVersion = storedVersion + 1;
                newDoc.put(JsonKeys.VERSION, newVersion);
                new FileWriter(documentFile, newDoc.toString()).write();
                fireOnUpdate(database, schema);
                return new Response(ResponseType.SUCCESS, "Document updated successfully", newVersion);
            } catch (Exception e) {
                return new Response(ResponseType.ERROR, e.getMessage());
            }
        }
    }

    public Response applyUpdate(String database, String schema, String id, JSONObject doc) {
        try {
            Validators.validateDocument(doc, schema, database);
        } catch (Exception e) {
            return new Response(ResponseType.ERROR, e.getMessage());
        }
        synchronized (this) {
            try {
                File documentFile = new File(PathBuilder.getPathToDocument(database, schema, id));
                if (!documentFile.exists())
                    documentFile.createNewFile();
                new FileWriter(documentFile, doc.toString()).write();
                fireOnUpdate(database, schema);
                return new Response(ResponseType.SUCCESS, "Document updated successfully");
            } catch (Exception e) {
                return new Response(ResponseType.ERROR, e.getMessage());
            }
        }
    }

    private void fireOnUpdate(String database, String schema) {
        try {
            indexManager.onUpdate(database, schema);
        } catch (Exception indexError) {
            // indexes are derived/rebuildable; don't fail the write on a maintenance error
            log.error("index maintenance failed for {}.{} on update", database, schema, indexError);
        }
    }

    public Response deleteDocument(String database, String schema, String id, int expectedVersion) {
        synchronized (this) {
            try {
                File documentFile = new File(PathBuilder.getPathToDocument(database, schema, id));
                if (!documentFile.exists())
                    return new Response(ResponseType.ERROR, "Document not found");

                FileReader fileReader = new FileReader(documentFile);
                fileReader.read();
                JSONObject doc = new JSONObject(fileReader.getContent());
                int storedVersion = doc.optInt(JsonKeys.VERSION, 1);
                if (storedVersion != expectedVersion)
                    return new Response(ResponseType.ERROR,
                            "Version conflict: document is at version " + storedVersion, storedVersion);

                if (!documentFile.delete())
                    return new Response(ResponseType.ERROR, "Failed to delete document");
                fireOnDelete(database, schema, Integer.parseInt(id), doc);
                return new Response(ResponseType.SUCCESS, "Document deleted successfully");
            } catch (Exception e) {
                return new Response(ResponseType.ERROR, e.getMessage());
            }
        }
    }

    public Response applyDelete(String database, String schema, String id) {
        synchronized (this) {
            try {
                File documentFile = new File(PathBuilder.getPathToDocument(database, schema, id));
                if (!documentFile.exists())
                    return new Response(ResponseType.SUCCESS, "Document already absent");
                FileReader fileReader = new FileReader(documentFile);
                fileReader.read();
                JSONObject doc = new JSONObject(fileReader.getContent());
                documentFile.delete();
                fireOnDelete(database, schema, Integer.parseInt(id), doc);
                return new Response(ResponseType.SUCCESS, "Document deleted successfully");
            } catch (Exception e) {
                return new Response(ResponseType.ERROR, e.getMessage());
            }
        }
    }

    private void fireOnDelete(String database, String schema, int docId, JSONObject doc) {
        try {
            indexManager.onDelete(database, schema, docId, doc);
        } catch (Exception indexError) {
            log.error("index maintenance failed for {}.{} doc {} on delete", database, schema, docId, indexError);
        }
    }

    private void updateSchemaInfo(String database, String schema, int i) throws Exception {
        try{
            String pathToSchema = PathBuilder.getPathToSchema(database, schema);
            File schemaFile = new File(pathToSchema);
            FileReader fileReader = new FileReader(schemaFile);
            fileReader.read();

            JSONObject schemaJSON = new JSONObject(fileReader.getContent());
            JSONObject infoObject = schemaJSON.getJSONObject(JsonKeys.INFO);
            infoObject.put(JsonKeys.NEXT_ID, i);

            FileWriter fileWriter = new FileWriter(schemaFile, schemaJSON.toString());
            fileWriter.write();
        }catch (Exception e){
            throw new Exception("Error updating schema info");
        }

    }
}

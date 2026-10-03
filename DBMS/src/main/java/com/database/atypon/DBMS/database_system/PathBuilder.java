package com.database.atypon.DBMS.database_system;

public class PathBuilder {

    public static String buildCreateDatabasePath(String databaseName){
        return "/admin/database/create?" + "databaseName=" + databaseName;
    }
    public static String buildCreateSchemaPath(String databaseName){
        return "/write/schema/new?" + "database=" + databaseName;
    }
    public static String buildCreateDocumentPath(String databaseName, String schemaName){
        return "/write/document/new?" + "database=" + databaseName + "&schema=" + schemaName;
    }
    public static String buildReadAllDocumentsPath(String databaseName, String schemaName){
        return "/user/read/all?" + "databaseName=" + databaseName + "&schemaName=" + schemaName;
    }
    public static String buildReadDocumentPath(String databaseName, String schemaName, String id){
        return "/user/read/document?" + "databaseName=" + databaseName + "&schemaName=" +
                schemaName + "&id=" + id;
    }

    public static String buildUpdateDocumentPath(String databaseName, String schemaName, String id){
        return "/write/document/update?" + "database=" + databaseName + "&schema=" + schemaName + "&id=" + id;
    }

    public static String buildCreateIndexPath(String database, String schema, String field){
        return "/admin/index/create?" + "database=" + database + "&schema=" + schema + "&field=" + field;
    }

    public static String buildIndexQueryPath(){
        return "/user/index/query";
    }

}

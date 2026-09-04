package com.database.atypon.Node.utils;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PathBuilderTest {

    @Test
    void buildsDocumentPath() {
        assertThat(PathBuilder.getPathToDocument("shop", "users", "3"))
                .isEqualTo("./data/shop/users-records/3.json");
    }

    @Test
    void buildsSchemaPath() {
        assertThat(PathBuilder.getPathToSchema("shop", "users"))
                .isEqualTo("./data/shop/schemas/users.json");
    }

    @Test
    void buildsAffinityAndRecordsPaths() {
        assertThat(PathBuilder.getPathToAffinity("shop", "users"))
                .isEqualTo("./data/shop/affinities/users.json");
        assertThat(PathBuilder.getPathToAllDocuments("shop", "users"))
                .isEqualTo("./data/shop/users-records/");
    }

    @Test
    void buildsInfoAndRootPaths() {
        assertThat(PathBuilder.getInfoPath()).isEqualTo("./data/info.json");
        assertThat(PathBuilder.getRootPath()).isEqualTo("./data/");
    }
}

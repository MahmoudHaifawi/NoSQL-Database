package com.database.atypon.Node.utils;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidatorsTest {

    @Test
    void acceptsSupportedTypes() throws Exception {
        JSONObject schema = new JSONObject()
                .put("Name", "String").put("Age", "Integer")
                .put("Active", "Boolean").put("Gpa", "Double");
        assertThat(Validators.validateSchema(schema, "users")).isTrue();
    }

    @Test
    void rejectsUnsupportedType() {
        JSONObject schema = new JSONObject().put("When", "Date");
        assertThatThrownBy(() -> Validators.validateSchema(schema, "events"))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("Invalid type");
    }

    @Test
    void rejectsEmptySchema() {
        assertThatThrownBy(() -> Validators.validateSchema(new JSONObject(), "x"))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("empty");
    }
}

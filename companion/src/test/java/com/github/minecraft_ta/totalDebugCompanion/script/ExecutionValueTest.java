package com.github.minecraft_ta.totalDebugCompanion.script;

import com.google.gson.JsonParseException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionValueTest {
    @Test
    void boundsDisplayTextWithoutChangingTheCanonicalValue() {
        ExecutionValue value = new ExecutionValue(
                new ExecutionText("java.lang.String", 16, false),
                new ExecutionText("abcdef", 6, false),
                new ExecutionText("", 0, false),
                ExecutionValue.Kind.STRING,
                0,
                0,
                false,
                List.of()
        );

        assertEquals("\"abc…\"", value.displayValue(3));
        assertEquals("abcdef", value.value().text());
    }

    @Test
    void parsesAValidatedSnapshotTree() {
        ExecutionResult result = ExecutionResult.parse("""
                {
                  "status":"RUN_COMPLETED",
                  "logs":{"text":"","totalCharacters":0,"truncated":false},
                  "value":{
                    "type":{"text":"example.Result","totalCharacters":14,"truncated":false},
                    "value":{"text":"Result","totalCharacters":6,"truncated":false},
                    "preview":{"text":"ready","totalCharacters":5,"truncated":false},
                    "kind":"OBJECT",
                    "identity":1,
                    "totalChildren":1,
                    "truncated":false,
                    "children":[{
                      "name":{"text":"answer","totalCharacters":6,"truncated":false},
                      "kind":"FIELD",
                      "value":{
                        "type":{"text":"java.lang.Integer","totalCharacters":17,"truncated":false},
                        "value":{"text":"42","totalCharacters":2,"truncated":false},
                        "preview":{"text":"","totalCharacters":0,"truncated":false},
                        "kind":"NUMBER",
                        "identity":0,
                        "totalChildren":0,
                        "truncated":false,
                        "children":[]
                      }
                    }]
                  },
                  "error":{"text":"","totalCharacters":0,"truncated":false}
                }
                """);
        ExecutionValue snapshot = result.value();

        assertEquals("answer", snapshot.children().getFirst().name().text());
        assertEquals("42", snapshot.children().getFirst().value().value().text());
    }

    @Test
    void rejectsAChildCountSmallerThanThePayload() {
        assertThrows(JsonParseException.class, () -> ExecutionResult.parse("""
                {"status":"RUN_COMPLETED",
                 "logs":{"text":"","totalCharacters":0,"truncated":false},
                 "value":{"type":{"text":"x","totalCharacters":1,"truncated":false},
                   "value":{"text":"x","totalCharacters":1,"truncated":false},
                   "preview":{"text":"","totalCharacters":0,"truncated":false},
                   "kind":"OBJECT","identity":1,
                   "totalChildren":0,"truncated":false,"children":[
                     {"name":{"text":"x","totalCharacters":1,"truncated":false},"kind":"FIELD","value":
                       {"type":{"text":"int","totalCharacters":3,"truncated":false},
                        "value":{"text":"1","totalCharacters":1,"truncated":false},
                        "preview":{"text":"","totalCharacters":0,"truncated":false},
                        "kind":"NUMBER","identity":0,
                        "totalChildren":0,"truncated":false,"children":[]}}
                   ]},
                 "error":{"text":"","totalCharacters":0,"truncated":false}
                }
                """));
    }
}

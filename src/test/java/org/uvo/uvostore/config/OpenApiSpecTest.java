package org.uvo.uvostore.config;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GAP-17. El contrato de la API versionado en {@code docs/api/}, un archivo por grupo de {@link OpenApiConfig}.
 *
 * <p>Producción exige {@code SWAGGER_ENABLED=false} (PROD-17c), así que fuera de desarrollo nadie puede leer
 * {@code /v3/api-docs}: lo que queda como contrato es el archivo exportado. Este test genera la spec de cada
 * grupo y la compara con la versionada, para que un endpoint o un DTO cambiado sin regenerar haga fallar el
 * build en vez de dejar el contrato viejo sin que nadie lo note.
 *
 * <p>Para regenerar: {@code ./mvnw test -Dtest=OpenApiSpecTest -Dopenapi.regenerate=true}. Con esa propiedad
 * el test escribe los archivos en lugar de compararlos.
 *
 * <p>La salida se normaliza (claves ordenadas, sangría de dos espacios, fin de línea {@code \n} y uno final)
 * para que sea la misma en cada corrida y en cada sistema operativo; {@code .gitattributes} fija el {@code LF}
 * en el checkout.
 */
class OpenApiSpecTest extends IntegrationTestSupport {

    private static final Path SPEC_DIR = Path.of("docs", "api");
    private static final boolean REGENERATE = Boolean.getBoolean("openapi.regenerate");
    private static final String REGENERATE_COMMAND = "./mvnw test -Dtest=OpenApiSpecTest -Dopenapi.regenerate=true";

    @ParameterizedTest(name = "grupo {0}")
    @ValueSource(strings = {"public", "admin", "customer", "pos", "platform"})
    void theVersionedSpecMatchesTheGeneratedOne(String group) throws Exception {
        String raw = mockMvc.perform(get("/v3/api-docs/" + group))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String generated = normalize(raw);
        Path file = SPEC_DIR.resolve("openapi-" + group + ".json");

        if (REGENERATE) {
            Files.createDirectories(SPEC_DIR);
            Files.writeString(file, generated, StandardCharsets.UTF_8);
            return;
        }
        if (!Files.exists(file)) {
            fail("Falta %s. Genéralo con: %s", file, REGENERATE_COMMAND);
        }
        String versioned = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        if (!versioned.equals(generated)) {
            fail("La API del grupo '%s' cambió y %s no está al día (primera diferencia en la línea %d). "
                    + "Regenérala con: %s, y revisa el diff antes de commitear.",
                    group, file, firstDifferentLine(versioned, generated), REGENERATE_COMMAND);
        }
    }

    private String normalize(String json) throws IOException {
        // Se lee como mapas y no como JsonNode: ORDER_MAP_ENTRIES_BY_KEYS ordena mapas a cualquier
        // profundidad, pero no los ObjectNode.
        Object tree = objectMapper.readValue(json, Object.class);
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withObjectIndenter(new DefaultIndenter("  ", "\n"));
        return objectMapper.writer(printer)
                .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsString(tree) + "\n";
    }

    private static int firstDifferentLine(String expected, String actual) {
        String[] a = expected.split("\n", -1);
        String[] b = actual.split("\n", -1);
        int common = Math.min(a.length, b.length);
        for (int i = 0; i < common; i++) {
            if (!a[i].equals(b[i])) {
                return i + 1;
            }
        }
        return common + 1;
    }
}

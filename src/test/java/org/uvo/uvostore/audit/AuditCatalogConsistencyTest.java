package org.uvo.uvostore.audit;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.uvo.uvostore.entity.audit.enums.AuditAction;
import org.uvo.uvostore.entity.audit.enums.AuditActorType;
import org.uvo.uvostore.entity.audit.enums.AuditResult;
import org.uvo.uvostore.entity.audit.enums.AuditTargetType;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROD-18. Las listas de los CHECK de {@code audit_events} son exactamente las constantes de los enums (B6). Agregar una
 * acción sin su migración no falla al compilar: falla la primera vez que alguien la registra, con el cambio de negocio
 * deshecho detrás (la auditoría es fail-closed). Esto lo adelanta al build.
 */
class AuditCatalogConsistencyTest extends IntegrationTestSupport {

    private static final Pattern QUOTED = Pattern.compile("'([A-Z_]+)'");

    static Stream<Arguments> catalogues() {
        return Stream.of(
                Arguments.of("audit_events_action_valid", AuditAction.values()),
                Arguments.of("audit_events_target_type_valid", AuditTargetType.values()),
                Arguments.of("audit_events_actor_type_valid", AuditActorType.values()),
                Arguments.of("audit_events_result_valid", AuditResult.values()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("catalogues")
    void theCheckListsExactlyTheEnum(String constraint, Enum<?>[] values) {
        String definition = jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?", String.class, constraint);

        Set<String> inDatabase = new TreeSet<>();
        Matcher matcher = QUOTED.matcher(definition);
        while (matcher.find()) {
            inDatabase.add(matcher.group(1));
        }
        Set<String> inJava = Arrays.stream(values).map(Enum::name).collect(Collectors.toCollection(TreeSet::new));

        assertThat(inDatabase).as(constraint).isEqualTo(inJava);
    }
}

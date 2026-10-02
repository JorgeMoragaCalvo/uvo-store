package org.uvo.uvostore.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.service.report.ReportZone;

import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F20. Una zona mal escrita en {@code app.reports.timezone} impide el arranque.
 *
 * <p>Unitario a propósito: lo que se comprueba es el constructor. Caer a UTC en silencio sería el peor de
 * los dos mundos — es justo lo que el arreglo quita, y el informe no avisaría de nada: solo daría cifras
 * distintas. Mismo criterio que {@code SettingValues} con los ajustes de dinero y que {@code JwtService}
 * con el secreto.
 */
class ReportZoneValidationTest {

    @Test
    @DisplayName("Una zona válida se acepta")
    void aValidZoneIsAccepted() {
        assertThat(new ReportZone("America/Santiago").zone()).isEqualTo(ZoneId.of("America/Santiago"));
        assertThat(new ReportZone("UTC").zone()).isEqualTo(ZoneId.of("UTC"));
    }

    @Test
    @DisplayName("Una zona inexistente falla nombrando la propiedad")
    void anUnknownZoneFailsNamingTheProperty() {
        assertThatThrownBy(() -> new ReportZone("America/Santiagoo"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.reports.timezone")
                .hasMessageContaining("America/Santiagoo");
    }

    @Test
    @DisplayName("Un valor que no es una zona, y el vacío, fallan igual")
    void garbageAndBlankFailToo() {
        assertThatThrownBy(() -> new ReportZone("no es una zona"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.reports.timezone");
        assertThatThrownBy(() -> new ReportZone(""))
                .isInstanceOf(IllegalStateException.class);
    }
}

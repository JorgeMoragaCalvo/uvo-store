package org.uvo.uvostore.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.uvo.uvostore.service.BusinessException;
import org.uvo.uvostore.service.settings.ChileanRut;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** GAP-18. El RUT del vendedor se guarda en una sola forma y con el dígito verificador comprobado. */
class ChileanRutTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "76.123.456-0, 76123456-0",
            "76123456-0, 76123456-0",
            "761234560, 76123456-0",
            "' 76 123 456-0 ', 76123456-0",
            "11.111.111-1, 11111111-1",
            "1.000.005-k, 1000005-K",
            "1000005K, 1000005-K",
    })
    @DisplayName("Acepta el RUT escrito de cualquier forma y lo guarda sin puntos, con guion y DV en mayúscula")
    void normalizesValidRuts(String raw, String expected) {
        assertThat(ChileanRut.normalize(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("En blanco es «sin configurar»")
    void blankIsNull(String raw) {
        assertThat(ChileanRut.normalize(raw)).isNull();
    }

    @Test
    @DisplayName("Un dígito verificador que no corresponde se rechaza")
    void rejectsAWrongCheckDigit() {
        assertThatThrownBy(() -> ChileanRut.normalize("76.123.456-7"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("dígito verificador");
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "12-3", "123456789012", "76.123.456-X"})
    @DisplayName("Lo que no tiene forma de RUT se rechaza")
    void rejectsMalformedRuts(String raw) {
        assertThatThrownBy(() -> ChileanRut.normalize(raw))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("formato");
    }
}

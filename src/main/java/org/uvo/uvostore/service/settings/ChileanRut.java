package org.uvo.uvostore.service.settings;

import org.uvo.uvostore.service.BusinessException;

/**
 * GAP-18. El RUT del vendedor que muestran las páginas legales y el pie de la tienda.
 *
 * <p>Se valida al guardar porque un RUT mal tecleado no falla en ningún otro sitio: se publica tal cual
 * en la identificación del proveedor, que es justo el dato que el comprador necesita para reclamar.
 *
 * <p>Se guarda en una sola forma, sin puntos y con guion ({@code 12345678-5}, DV en mayúscula), para que
 * el {@code CHECK} de V35 pueda comprobarla y el storefront la formatee siempre igual. El comerciante puede
 * escribirlo como quiera: con puntos, sin guion, con espacios o con la {@code k} en minúscula.
 */
public final class ChileanRut {

    private ChileanRut() {
    }

    /** El RUT normalizado, o {@code null} si llega en blanco (el campo es opcional). */
    public static String normalize(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        String compact = rawValue.replaceAll("[.\\s-]", "").toUpperCase();
        if (!compact.matches("[0-9]{7,8}[0-9K]")) {
            throw new BusinessException("El RUT '" + rawValue.trim() + "' no tiene un formato válido. Ejemplo: 76.123.456-0.");
        }
        String body = compact.substring(0, compact.length() - 1);
        char checkDigit = compact.charAt(compact.length() - 1);
        if (checkDigit(body) != checkDigit) {
            throw new BusinessException("El RUT '" + rawValue.trim() + "' no es válido: el dígito verificador no corresponde.");
        }
        return body + "-" + checkDigit;
    }

    /** Módulo 11: los dígitos de derecha a izquierda por 2, 3, 4, 5, 6, 7, 2, 3… */
    static char checkDigit(String body) {
        int sum = 0;
        int factor = 2;
        for (int i = body.length() - 1; i >= 0; i--) {
            sum += (body.charAt(i) - '0') * factor;
            factor = factor == 7 ? 2 : factor + 1;
        }
        int digit = 11 - (sum % 11);
        if (digit == 11) return '0';
        if (digit == 10) return 'K';
        return (char) ('0' + digit);
    }
}

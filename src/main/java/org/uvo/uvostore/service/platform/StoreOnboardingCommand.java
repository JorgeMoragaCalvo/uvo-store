package org.uvo.uvostore.service.platform;

/**
 * Los datos de un alta.
 *
 * <p>POS-6: ya no lleva contraseña. El dueño nace invitado y la elige él. Y {@code pos} es la conexión con
 * UvoPOS cuando el alta la pide el POS; null cuando la hace el formulario de operador.
 */
public record StoreOnboardingCommand(
        String slug,
        String domain,
        String storeName,
        String adminName,
        String adminEmail,
        PosLink pos
) {

    /**
     * POS-6. La empresa del POS y los dos secretos de la conexión, generados por el POS.
     *
     * <p>{@code toString} los oculta: un record los imprimiría tal cual en cualquier log o mensaje de excepción
     * que incluyera el comando.
     */
    public record PosLink(Long companyId, String companyName, String apiUrl, String apiKey, String webhookSecret) {

        @Override
        public String toString() {
            return "PosLink[companyId=" + companyId + ", companyName=" + companyName + ", apiUrl=" + apiUrl
                    + ", apiKey=***, webhookSecret=***]";
        }
    }
}

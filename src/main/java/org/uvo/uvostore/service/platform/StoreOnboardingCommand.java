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
     * POS-6 e IMP-08. La empresa del POS y las dos llaves de la conexión, generadas por el POS (contrato §2).
     *
     * <p>{@code toString} oculta los secretos: un record los imprimiría tal cual en cualquier log o mensaje de
     * excepción que incluyera el comando.
     */
    public record PosLink(Long companyId, String companyName, String apiUrl,
                          String outboundKeyId, String outboundSecret, String inboundKeyId, String inboundSecret) {

        @Override
        public String toString() {
            return "PosLink[companyId=" + companyId + ", companyName=" + companyName + ", apiUrl=" + apiUrl
                    + ", outboundKeyId=" + outboundKeyId + ", inboundKeyId=" + inboundKeyId + ", secrets=***]";
        }
    }
}

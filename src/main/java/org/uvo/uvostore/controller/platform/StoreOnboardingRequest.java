package org.uvo.uvostore.controller.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * El cuerpo de {@code POST /api/platform/stores}.
 *
 * <p>POS-6: sin {@code adminPassword}. Nadie elige la contraseña de otro: el dueño recibe una invitación.
 * {@code pos} va cuando el alta la pide UvoPOS al activar el módulo.
 */
public record StoreOnboardingRequest(
        @NotBlank @Pattern(regexp = "^[a-z0-9]([a-z0-9-]{1,61}[a-z0-9])?$",
                message = "El nick solo puede contener minúsculas, números y guiones") String slug,
        @Pattern(regexp = "^$|^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$",
                message = "El dominio no tiene un formato válido") String domain,
        @NotBlank String storeName,
        @NotBlank String adminName,
        @NotBlank @Email String adminEmail,
        // Sin @Valid aquí, ninguna anotación del bloque se evalúa.
        @Valid PosLinkRequest pos
) {

    /**
     * POS-6 e IMP-08. La conexión con UvoPOS, con las dos llaves que generó el POS (contrato §2): la tienda→POS, con
     * la que firma la tienda, y la POS→tienda, con la que firma el POS sus avisos. Cada una es un {@code key_id} y un
     * secreto base64 de al menos 32 bytes ({@code PosConnectionKeyService} lo comprueba).
     *
     * <p>Los mensajes no repiten el valor recibido: aquí viajan secretos, y el mensaje de validación vuelve en la
     * respuesta.
     */
    public record PosLinkRequest(
            @NotNull(message = "Falta la empresa del POS") @Positive(message = "La empresa del POS no es válida")
            Long companyId,
            @NotBlank(message = "Falta el nombre de la empresa") String companyName,
            @NotBlank(message = "Falta la URL de la API del POS")
            @Pattern(regexp = "^https?://\\S+$", message = "La URL de la API del POS no es válida") String apiUrl,
            @NotBlank(message = "Falta el key_id de la llave tienda→POS") String outboundKeyId,
            @NotBlank(message = "Falta el secreto de la llave tienda→POS") String outboundSecret,
            @NotBlank(message = "Falta el key_id de la llave POS→tienda") String inboundKeyId,
            @NotBlank(message = "Falta el secreto de la llave POS→tienda") String inboundSecret
    ) {

        @Override
        public String toString() {
            return "PosLinkRequest[companyId=" + companyId + ", companyName=" + companyName + ", apiUrl=" + apiUrl
                    + ", outboundKeyId=" + outboundKeyId + ", inboundKeyId=" + inboundKeyId + ", secrets=***]";
        }
    }
}

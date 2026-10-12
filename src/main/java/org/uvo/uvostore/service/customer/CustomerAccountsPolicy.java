package org.uvo.uvostore.service.customer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * PROD-07. El único sitio que decide si las cuentas de comprador están habilitadas.
 *
 * <p>Nace porque la bandera se lee en tres lugares que tienen que coincidir o la función queda a
 * medias: el filtro que apaga {@code /api/customer/**}, el checkout que decide si marcar al invitado
 * como invitado, y el oyente que manda el correo con el enlace de activación. Si uno de los tres se
 * desincroniza vuelve justo el estado que PROD-07 cierra — un correo que promete una cuenta que la
 * API no atiende, o una fila marcada {@code INVITED} con un token que nunca salió. Mismo criterio que
 * {@code AdminInvitationPolicy} con los plazos de invitación y {@code ReportZone} con la zona horaria.
 *
 * <p><b>Apagado por defecto y a propósito.</b> La superficie de comprador no existe en el storefront
 * (no hay ruta {@code /cuenta/activar}, ni store de sesión, ni recuperación de contraseña de cliente),
 * así que lo honesto es no ofrecerla. Encenderla exige las pantallas y los dos endpoints que faltan;
 * mientras no estén, {@code CUSTOMER_ACCOUNTS_ENABLED=true} solo sirve para probar la API.
 *
 * <p>Es de plataforma y no un ajuste por tienda: por tienda exigiría una columna de ajustes, y el
 * alcance de la primera versión comercial es el mismo para todas (igual que la divisa de F17 y la zona
 * horaria de F20).
 */
@Component
public class CustomerAccountsPolicy {

    private final boolean enabled;

    public CustomerAccountsPolicy(@Value("${app.customer-accounts.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }
}

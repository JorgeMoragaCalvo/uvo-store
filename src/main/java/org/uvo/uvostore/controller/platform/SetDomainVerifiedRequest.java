package org.uvo.uvostore.controller.platform;

import jakarta.validation.constraints.NotNull;

/**
 * PROD-04. {@code true} habilita el dominio propio de la tienda para los enlaces que salen;
 * {@code false} lo deshace sin borrar el dominio, para cuando deja de funcionar.
 *
 * <p>{@code Boolean} y {@code @NotNull} a propósito: con un {@code boolean} primitivo, un cuerpo sin el
 * campo se leería como {@code false} y una llamada mal escrita desverificaría en silencio.
 */
public record SetDomainVerifiedRequest(@NotNull Boolean verified) {
}

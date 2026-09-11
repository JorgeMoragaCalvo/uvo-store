package org.uvo.uvostore.service.pos;

import java.util.Map;

// El cuerpo ya armado más la clave de idempotencia, que viaja dos veces: dentro del cuerpo y como
// cabecera HTTP. Van juntos en un solo objeto para que PosClient no tenga que volver a derivarla
// —si la derivara por su cuenta, cuerpo y cabecera podrían dejar de coincidir.
public record PosOrderPayload(String idempotencyKey, Map<String, Object> body) {
}

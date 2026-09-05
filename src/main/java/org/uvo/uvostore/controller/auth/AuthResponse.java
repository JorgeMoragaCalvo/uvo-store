package org.uvo.uvostore.controller.auth;

/**
 * @param permissions the admin's effective permission names (A1). The panel had no way to know what
 *        the signed-in user may do — it showed every section to everyone — so it now comes back with
 *        the login. Empty for customers, who have no permission model.
 */
public record AuthResponse(String token, Long id, String name, String email, String type,
                           java.util.List<String> permissions) {
}

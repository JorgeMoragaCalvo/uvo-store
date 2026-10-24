package org.uvo.uvostore.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.uvo.uvostore.entity.security.Role;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.StoreSettingsRepository;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Store onboarding (/api/platform/**): operator-only, gated by X-Platform-Key rather than the
// per-store JWT scheme (there's no tenant yet when a store is being created). Covers the two
// tenant-resolution paths TenantResolutionFilter now supports — custom domain first, subdomain
// slug as the fallback every store keeps working under regardless.
class StoreOnboardingTest extends IntegrationTestSupport {

    // Injected rather than hardcoded: app.platform-api-key lost its committed default in C4, so
    // there is no fixed literal to assert against — surefire supplies a test-only value (pom.xml).
    @Value("${app.platform-api-key}")
    private String platformKey;

    @Autowired
    private StoreSettingsRepository storeSettingsRepository;

    @Test
    void creatingAStoreRequiresTheCorrectPlatformKey() throws Exception {
        mockMvc.perform(post("/api/platform/stores")
                        .header("X-Platform-Key", "wrong-key")
                        .contentType("application/json")
                        .content(onboardingBody("nick" + nextSeq(), "", "Tienda de prueba")))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/platform/stores")
                        .contentType("application/json")
                        .content(onboardingBody("nick" + nextSeq(), "", "Tienda de prueba")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void creatingAStoreWithoutADomainWorksAndTheAdminCanLogInBySubdomain() throws Exception {
        String slug = "onboard-" + nextSeq();
        String response = mockMvc.perform(post("/api/platform/stores")
                        .header("X-Platform-Key", platformKey)
                        .contentType("application/json")
                        .content(onboardingBody(slug, "", "Tienda de prueba")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(slug))
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertTrue(objectMapper.readTree(response).get("domain").isNull());
        String adminEmail = objectMapper.readTree(response).get("adminEmail").asText();
        activateOwner(slug);

        mockMvc.perform(post("/api/admin/auth/login")
                        .header("Host", slug + ".localhost")
                        .contentType("application/json")
                        .content("{\"email\":\"" + adminEmail + "\",\"password\":\"password123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists());
    }

    @Test
    void creatingAStoreWithADomainResolvesTenantByThatDomain() throws Exception {
        String slug = "onboard-domain-" + nextSeq();
        String domain = "tienda" + nextSeq() + ".cl";

        String response = mockMvc.perform(post("/api/platform/stores")
                        .header("X-Platform-Key", platformKey)
                        .contentType("application/json")
                        .content(onboardingBody(slug, domain, "Tienda con dominio")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain").value(domain))
                .andReturn().getResponse().getContentAsString();

        String adminEmail = objectMapper.readTree(response).get("adminEmail").asText();
        activateOwner(slug);

        // Same admin, authenticated via the CUSTOM DOMAIN instead of the subdomain — proves
        // TenantResolutionFilter's domain-match path resolves the same store.
        mockMvc.perform(post("/api/admin/auth/login")
                        .header("Host", domain)
                        .contentType("application/json")
                        .content("{\"email\":\"" + adminEmail + "\",\"password\":\"password123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists());

        // The subdomain fallback keeps working too, for the same store.
        mockMvc.perform(post("/api/admin/auth/login")
                        .header("Host", slug + ".localhost")
                        .contentType("application/json")
                        .content("{\"email\":\"" + adminEmail + "\",\"password\":\"password123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists());
    }

    @Test
    void creatingAStoreRejectsADuplicateSlug() throws Exception {
        String slug = "onboard-dup-" + nextSeq();
        mockMvc.perform(post("/api/platform/stores")
                        .header("X-Platform-Key", platformKey)
                        .contentType("application/json")
                        .content(onboardingBody(slug, "", "Primera tienda")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/platform/stores")
                        .header("X-Platform-Key", platformKey)
                        .contentType("application/json")
                        .content(onboardingBody(slug, "", "Segunda tienda")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void creatingAStoreRejectsAnInvalidSlugFormat() throws Exception {
        mockMvc.perform(post("/api/platform/stores")
                        .header("X-Platform-Key", platformKey)
                        .contentType("application/json")
                        .content(onboardingBody("Not Valid Slug!", "", "Tienda")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void domainCanBeUpdatedAfterCreationAndRejectsADuplicate() throws Exception {
        String slugA = "onboard-upd-a-" + nextSeq();
        String slugB = "onboard-upd-b-" + nextSeq();
        String domainA = "tienda-a-" + nextSeq() + ".cl";
        String domainB = "tienda-b-" + nextSeq() + ".cl";

        String responseA = mockMvc.perform(post("/api/platform/stores")
                        .header("X-Platform-Key", platformKey)
                        .contentType("application/json")
                        .content(onboardingBody(slugA, "", "Tienda A")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long storeIdA = objectMapper.readTree(responseA).get("storeId").asLong();

        mockMvc.perform(post("/api/platform/stores")
                        .header("X-Platform-Key", platformKey)
                        .contentType("application/json")
                        .content(onboardingBody(slugB, domainB, "Tienda B")))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/platform/stores/" + storeIdA + "/domain")
                        .header("X-Platform-Key", platformKey)
                        .contentType("application/json")
                        .content("{\"domain\":\"" + domainA + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain").value(domainA));

        mockMvc.perform(put("/api/platform/stores/" + storeIdA + "/domain")
                        .header("X-Platform-Key", platformKey)
                        .contentType("application/json")
                        .content("{\"domain\":\"" + domainB + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theAdminOfANewStoreCanActuallyUseThePanel() throws Exception {
        // F08. El test de alta terminaba en "el login devuelve un token", y por eso este fallo llevaba
        // desde que existe el API de plataforma sin que nadie lo viera: el alta creaba el administrador
        // SIN NINGÚN ROL, así que su token traía solo ROLE_ADMIN y todos los endpoints del panel
        // respondían 403. Peor aún, el menú se dibuja a partir de los permisos, así que el dueño veía
        // un panel sin secciones —incluida la de Roles, la única que lo habría arreglado.
        String slug = "onboard-usable-" + nextSeq();
        String response = mockMvc.perform(post("/api/platform/stores")
                        .header("X-Platform-Key", platformKey)
                        .contentType("application/json")
                        .content(onboardingBody(slug, "", "Tienda usable")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String adminEmail = objectMapper.readTree(response).get("adminEmail").asText();
        long storeId = objectMapper.readTree(response).get("storeId").asLong();
        activateOwner(slug);

        String login = mockMvc.perform(post("/api/admin/auth/login")
                        .header("Host", slug + ".localhost")
                        .contentType("application/json")
                        .content("{\"email\":\"" + adminEmail + "\",\"password\":\"password123456\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(login).get("token").asText();

        // Leer. Las aserciones sobre los endpoints van primero a propósito: son el síntoma que sufre el
        // cliente, y así un fallo se lee como "403 al abrir el panel" y no como un detalle del token.
        mockMvc.perform(get("/api/admin/products")
                        .header("Host", slug + ".localhost")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Y escribir: un permiso de lectura por sí solo no haría gestionable la tienda. Se usa un
        // endpoint con cuerpo JSON (crear un cupón exige 'coupons.manage'); el de categorías es
        // multipart y aquí lo que se comprueba es el permiso, no el formato.
        mockMvc.perform(post("/api/admin/coupons")
                        .header("Host", slug + ".localhost")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"code\":\"ALTA-" + nextSeq() + "\",\"name\":\"Cupón inicial\","
                                + "\"type\":\"FIXED\",\"value\":1000,\"active\":true}"))
                .andExpect(status().is2xxSuccessful());

        // Y lo que decide qué secciones dibuja el panel: con la lista vacía solo queda el Dashboard.
        assertThat(objectMapper.readTree(login).get("permissions")).isNotEmpty();

        // El rol es de esta tienda y trae el catálogo completo — contado, no un número fijo que se
        // quede viejo en cuanto una migración añada un permiso.
        List<Role> roles = roleRepository.findByStoreId(storeId);
        assertThat(roles).singleElement().satisfies(role -> {
            assertThat(role.getName()).isEqualTo("Administrador");
            assertThat(role.getPermissions()).hasSize((int) permissionRepository.count());
        });
    }

    // --- PROD-24a: el nombre del alta es el nombre con el que la tienda se presenta -----------------------

    @Test
    void theStorefrontOfANewStoreIsPresentedWithTheNameChosenAtSignUp() throws Exception {
        // Antes la fila de identidad nacía con el valor por defecto de la entidad y el encabezado, el pie y
        // las páginas legales de una tienda recién creada decían «UvoStore» —el nombre de la plataforma—
        // hasta que su dueño entraba a «Apariencia» y lo cambiaba. Se lee por el endpoint PÚBLICO, que es lo
        // que ve un comprador, y sin que nadie haya tocado ningún ajuste antes.
        String slug = "onboard-name-" + nextSeq();
        String name = "Librería El Ñandú";
        mockMvc.perform(post("/api/platform/stores")
                        .header("X-Platform-Key", platformKey)
                        .contentType("application/json")
                        .content(onboardingBody(slug, "", name)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/store-settings").header("Host", slug + ".localhost"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeName").value(name));
    }

    @Test
    void theGeneralSettingsOfANewStoreStartWithTheNameChosenAtSignUp() throws Exception {
        // La otra pantalla con «Nombre de la tienda»: «Ajustes generales» lee la clave `store_name` de la
        // tabla `settings`, cuyo respaldo era el mismo «UvoStore».
        String slug = "onboard-gen-" + nextSeq();
        String name = "Ferretería Los Andes";
        String response = mockMvc.perform(post("/api/platform/stores")
                        .header("X-Platform-Key", platformKey)
                        .contentType("application/json")
                        .content(onboardingBody(slug, "", name)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String adminEmail = objectMapper.readTree(response).get("adminEmail").asText();
        activateOwner(slug);

        String login = mockMvc.perform(post("/api/admin/auth/login")
                        .header("Host", slug + ".localhost")
                        .contentType("application/json")
                        .content("{\"email\":\"" + adminEmail + "\",\"password\":\"password123456\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(get("/api/admin/settings/general")
                        .header("Host", slug + ".localhost")
                        .header("Authorization", "Bearer " + objectMapper.readTree(login).get("token").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeName").value(name));
    }

    @Test
    void theSignUpWritesBothNamesItselfInsteadOfLeavingItToTheFirstRead() throws Exception {
        // Las dos pantallas crean sus filas «al primer uso» si no existen. Por eso los tests de arriba, solos,
        // no distinguen "el alta las escribió" de "la primera lectura las creó con el nombre de la tienda":
        // aquí se mira la base ANTES de que nadie lea nada.
        String slug = "onboard-rows-" + nextSeq();
        String response = mockMvc.perform(post("/api/platform/stores")
                        .header("X-Platform-Key", platformKey)
                        .contentType("application/json")
                        .content(onboardingBody(slug, "", "Tienda de las filas")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long storeId = objectMapper.readTree(response).get("storeId").asLong();

        assertThat(storeSettingsRepository.findByStoreId(storeId))
                .hasValueSatisfying(s -> assertThat(s.getStoreName()).isEqualTo("Tienda de las filas"));
        assertThat(settingRepository.findByStoreIdAndSettingKey(storeId, "store_name"))
                .hasValueSatisfying(s -> assertThat(s.getValue()).isEqualTo("Tienda de las filas"));
    }

    @Test
    void theNameChosenAtSignUpIsOnlyTheStartingPointNotAFixedValue() throws Exception {
        // La fila existe desde el alta, así que el guardado de «Apariencia» la actualiza (no crea una
        // segunda: UNIQUE(store_id)) y lo que el dueño escribe manda sobre el nombre del alta.
        String slug = "onboard-edit-" + nextSeq();
        String response = mockMvc.perform(post("/api/platform/stores")
                        .header("X-Platform-Key", platformKey)
                        .contentType("application/json")
                        .content(onboardingBody(slug, "", "Nombre del alta")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String adminEmail = objectMapper.readTree(response).get("adminEmail").asText();
        long storeId = objectMapper.readTree(response).get("storeId").asLong();
        activateOwner(slug);
        String token = objectMapper.readTree(mockMvc.perform(post("/api/admin/auth/login")
                        .header("Host", slug + ".localhost")
                        .contentType("application/json")
                        .content("{\"email\":\"" + adminEmail + "\",\"password\":\"password123456\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("token").asText();

        // Multipart con método PUT, igual que AdminSettingsCrudTest: es como lo envía el panel.
        mockMvc.perform(multipart("/api/admin/settings/store")
                        .with(req -> {
                            req.setMethod("PUT");
                            return req;
                        })
                        .header("Host", slug + ".localhost")
                        .header("Authorization", "Bearer " + token)
                        .param("storeName", "Nombre elegido por el dueño")
                        .param("primaryColor", "#123456")
                        .param("secondaryColor", "#654321")
                        .param("accentColor", "#abcdef")
                        .param("darkColor", "#111111")
                        .param("showHero", "true")
                        .param("heroAutoplaySpeed", "5000")
                        .param("showCategories", "true")
                        .param("categoriesLimit", "6")
                        .param("showNewProducts", "true")
                        .param("newProductsLimit", "12")
                        .param("newProductsDays", "30")
                        .param("showFeaturedProducts", "true")
                        .param("featuredProductsLimit", "12")
                        .param("showDeals", "true")
                        .param("dealsLimit", "12")
                        .param("showBenefits", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeName").value("Nombre elegido por el dueño"));

        assertThat(storeSettingsRepository.findByStoreId(storeId))
                .hasValueSatisfying(s -> assertThat(s.getStoreName()).isEqualTo("Nombre elegido por el dueño"));
        mockMvc.perform(get("/api/v1/store-settings").header("Host", slug + ".localhost"))
                .andExpect(jsonPath("$.storeName").value("Nombre elegido por el dueño"));
    }

    // --- POS-6: el dueño nace invitado, nadie elige su contraseña ------------------------------------------

    @Test
    void theOwnerIsInvitedInsteadOfGivenAPassword() throws Exception {
        // Antes el operador escribía la contraseña del dueño y la conocía para siempre. Ahora el dueño nace sin
        // contraseña, con una invitación de un solo uso, y no puede entrar hasta aceptarla.
        String slug = "onboard-invite-" + nextSeq();
        String response = mockMvc.perform(post("/api/platform/stores")
                        .header("X-Platform-Key", platformKey)
                        .contentType("application/json")
                        .content(onboardingBody(slug, "", "Tienda invitada")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true))
                .andReturn().getResponse().getContentAsString();
        String adminEmail = objectMapper.readTree(response).get("adminEmail").asText();

        User owner = owner(slug);
        assertThat(owner.getPassword()).isNull();
        assertThat(owner.getInvitationToken()).hasSize(64);
        assertThat(owner.getInvitationSentAt()).isNotNull();
        assertThat(owner.getInvitationAcceptedAt()).isNull();

        // Sin aceptar, ninguna contraseña entra: la que antes elegía el operador tampoco.
        mockMvc.perform(post("/api/admin/auth/login")
                        .header("Host", slug + ".localhost")
                        .contentType("application/json")
                        .content("{\"email\":\"" + adminEmail + "\",\"password\":\"password123456\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anOldClientSendingAPasswordDoesNotGetItSet() throws Exception {
        // Un cliente viejo que todavía mande adminPassword no consigue fijarla: el campo ya no existe y se
        // ignora, y el dueño queda igualmente invitado.
        String slug = "onboard-oldpwd-" + nextSeq();
        mockMvc.perform(post("/api/platform/stores")
                        .header("X-Platform-Key", platformKey)
                        .contentType("application/json")
                        .content(onboardingBody(slug, "", "Tienda vieja").replace("\"adminName\"",
                                "\"adminPassword\": \"password123456\", \"adminName\"")))
                .andExpect(status().isOk());

        assertThat(owner(slug).getPassword()).isNull();
        assertThat(owner(slug).getInvitationToken()).isNotNull();
    }

    @Test
    void aPosBlockIsRejectedWhileThePosIntegrationIsOff() throws Exception {
        // Este contexto corre con app.pos.enabled=false, el valor de despacho. Con la integración apagada,
        // /api/sync/** responde 404, así que la tienda quedaría creada y el POS no podría cargarle el catálogo:
        // un alta a medias. Se rechaza antes de escribir nada.
        String slug = "onboard-posoff-" + nextSeq();
        long companyId = 880_000_000L + nextSeq();
        String body = onboardingBody(slug, "", "Tienda POS apagado").replace("\n}", """
                ,
                  "pos": {
                    "companyId": %d,
                    "companyName": "Empresa",
                    "apiUrl": "http://pos.internal",
                    "apiKey": "uvp_%d_%s",
                    "webhookSecret": "%s"
                  }
                }""".formatted(companyId, companyId, "a".repeat(40), "s".repeat(40)));

        mockMvc.perform(post("/api/platform/stores")
                        .header("X-Platform-Key", platformKey)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("POS_ENABLED")));

        assertThat(storeRepository.existsBySlug(slug)).isFalse();
    }

    /** El dueño de la tienda con ese nick. */
    private User owner(String slug) {
        Store store = storeRepository.findBySlug(slug).orElseThrow();
        return userRepository.findById(store.getOwnerUserId()).orElseThrow();
    }

    /**
     * POS-6. El dueño acepta su invitación con {@code password123456}, como hace desde el enlace del correo. Los
     * tests que entran al panel después del alta pasan por aquí: antes el operador fijaba esa contraseña en el alta.
     */
    private void activateOwner(String slug) throws Exception {
        mockMvc.perform(post("/api/admin/auth/accept-invitation")
                        .header("Host", slug + ".localhost")
                        .contentType("application/json")
                        .content("{\"token\":\"" + owner(slug).getInvitationToken() + "\",\"password\":\"password123456\"}"))
                .andExpect(status().isOk());
    }

    private String onboardingBody(String slug, String domain, String storeName) {
        return """
                {
                  "slug": "%s",
                  "domain": "%s",
                  "storeName": "%s",
                  "adminName": "Admin de prueba",
                  "adminEmail": "admin-%d@test.local"
                }""".formatted(slug, domain, storeName, nextSeq());
    }
}

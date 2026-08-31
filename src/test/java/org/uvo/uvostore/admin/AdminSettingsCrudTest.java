package org.uvo.uvostore.admin;

import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.support.IntegrationTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Fase 2 ("Punto 2"): admin UI for home banners, general (checkout/currency/shipping/stripe/pos)
// settings, and store branding settings — all backed by pre-existing endpoints that had no admin
// UI yet. Includes a regression test for the bug this work uncovered: saving general settings used
// to fail with 400 whenever posApiToken was blank, even for stores that never configured POS.
class AdminSettingsCrudTest extends IntegrationTestSupport {

    @Test
    void generalSettingsCanBeSavedWithoutAPosToken() throws Exception {
        Store store = createStore("settings-nopos");
        User admin = createAdmin(store, "settings-nopos");
        String token = loginAdmin(store, admin);

        String body = generalSettingsBody("");

        mockMvc.perform(put("/api/admin/settings/general")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void generalSettingsRejectsAnInvalidPosToken() throws Exception {
        Store store = createStore("settings-badpos");
        User admin = createAdmin(store, "settings-badpos");
        String token = loginAdmin(store, admin);

        String body = generalSettingsBody("not-a-valid-token");

        mockMvc.perform(put("/api/admin/settings/general")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void generalSettingsAcceptsAValidPosToken() throws Exception {
        Store store = createStore("settings-goodpos");
        User admin = createAdmin(store, "settings-goodpos");
        String token = loginAdmin(store, admin);

        String body = generalSettingsBody("uvp_1_abc123XYZ");

        mockMvc.perform(put("/api/admin/settings/general")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posApiTokenConfigured").value(true));
    }

    @Test
    void generalSettingsAreIsolatedPerStore() throws Exception {
        Store storeA = createStore("settings-iso-a");
        Store storeB = createStore("settings-iso-b");
        User adminA = createAdmin(storeA, "settings-iso-a");
        User adminB = createAdmin(storeB, "settings-iso-b");
        String tokenA = loginAdmin(storeA, adminA);
        String tokenB = loginAdmin(storeB, adminB);

        mockMvc.perform(put("/api/admin/settings/general")
                        .header("Host", hostHeader(storeA))
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType("application/json")
                        .content(generalSettingsBody("").replace("\"currency\":\"CLP\"", "\"currency\":\"USD\"")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/settings/general")
                        .header("Host", hostHeader(storeB))
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("CLP"));
    }

    @Test
    void storeSettingsBrandingCanBeSavedAndReadBack() throws Exception {
        Store store = createStore("store-settings");
        User admin = createAdmin(store, "store-settings");
        String token = loginAdmin(store, admin);

        mockMvc.perform(multipart("/api/admin/settings/store")
                        .with(req -> {
                            req.setMethod("PUT");
                            return req;
                        })
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .param("storeName", "Mi Tienda")
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
                .andExpect(jsonPath("$.storeName").value("Mi Tienda"))
                .andExpect(jsonPath("$.primaryColor").value("#123456"));

        mockMvc.perform(get("/api/admin/settings/store")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeName").value("Mi Tienda"));
    }

    @Test
    void bannerCanBeCreatedToggledAndDeleted() throws Exception {
        Store store = createStore("banner-crud");
        User admin = createAdmin(store, "banner-crud");
        String token = loginAdmin(store, admin);

        org.springframework.mock.web.MockMultipartFile image =
                new org.springframework.mock.web.MockMultipartFile("newImage", "banner.jpg", "image/jpeg", "fake-image-bytes".getBytes());

        String createResponse = mockMvc.perform(multipart("/api/admin/home/banners")
                        .file(image)
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .param("title", "Bienvenida")
                        .param("ctaText", "Ver más")
                        .param("ctaLink", "/shop")
                        .param("textPosition", "left")
                        .param("textColor", "light")
                        .param("overlayOpacity", "40")
                        .param("active", "true")
                        .param("sortOrder", "1")
                        .param("ctaNewTab", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Bienvenida"))
                // Must be an absolute URL (scheme+host), not root-relative "/uploads/..." — a
                // relative path resolves against the FRONTEND's origin in the browser, not this
                // API's, and 404s/serves the SPA's HTML fallback instead of the image.
                .andExpect(jsonPath("$.image", org.hamcrest.Matchers.startsWith("http://")))
                .andReturn().getResponse().getContentAsString();
        long bannerId = objectMapper.readTree(createResponse).get("id").asLong();

        mockMvc.perform(post("/api/admin/home/banners/" + bannerId + "/toggle")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(delete("/api/admin/home/banners/" + bannerId)
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/admin/home/banners/" + bannerId)
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void bannerEndpointsRejectAnotherStoresBanner() throws Exception {
        Store storeA = createStore("banner-cross-a");
        Store storeB = createStore("banner-cross-b");
        User adminA = createAdmin(storeA, "banner-cross-a");
        User adminB = createAdmin(storeB, "banner-cross-b");
        String tokenA = loginAdmin(storeA, adminA);
        String tokenB = loginAdmin(storeB, adminB);

        org.springframework.mock.web.MockMultipartFile image =
                new org.springframework.mock.web.MockMultipartFile("newImage", "banner.jpg", "image/jpeg", "fake-image-bytes".getBytes());

        String createResponse = mockMvc.perform(multipart("/api/admin/home/banners")
                        .file(image)
                        .header("Host", hostHeader(storeB))
                        .header("Authorization", "Bearer " + tokenB)
                        .param("title", "Solo B")
                        .param("ctaText", "Ver más")
                        .param("ctaLink", "/shop")
                        .param("textPosition", "left")
                        .param("textColor", "light")
                        .param("overlayOpacity", "40")
                        .param("active", "true")
                        .param("sortOrder", "1")
                        .param("ctaNewTab", "false"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long bannerIdB = objectMapper.readTree(createResponse).get("id").asLong();

        mockMvc.perform(get("/api/admin/home/banners/" + bannerIdB)
                        .header("Host", hostHeader(storeA))
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }

    private String generalSettingsBody(String posApiToken) {
        return """
                {
                  "storeName":"Tienda","storeEmail":"a@test.local","storePhone":"+56911111111","adminEmail":"admin@test.local",
                  "currency":"CLP","currencySymbol":"$","taxRate":"19","pricesIncludeTax":false,
                  "shippingEnabled":true,"defaultShippingCost":"0","freeShippingEnabled":false,"freeShippingThreshold":"0",
                  "allowGuestCheckout":true,"requirePhone":false,"requireCompany":false,
                  "stripePublicKey":"","stripeSecretKey":"","stripeEnabled":false,
                  "posApiUrl":"","posApiToken":"%s","posWebhookSecret":"","posSyncEnabled":false,
                  "metaTitle":"","metaDescription":"","metaKeywords":"",
                  "facebookUrl":"","instagramUrl":"","twitterUrl":""
                }
                """.formatted(posApiToken);
    }
}

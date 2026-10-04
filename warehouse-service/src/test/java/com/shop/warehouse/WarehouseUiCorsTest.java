package com.shop.warehouse;

import com.shop.warehouse.delivery.WarehouseUiCors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** Verifies actual Spring MVC preflight policy, keeping diagnostic mutations and unrelated endpoints outside browser access. */
class WarehouseUiCorsTest {
    /** Configures MVC with production CORS policy and inert endpoints so no Kafka/database mock can mask origin matching. */
    @Configuration @EnableWebMvc @Import({WarehouseUiCors.class, Endpoints.class})
    static class Config { }

    /** Inert routes exercise method/path CORS matching; they never mutate application state. */
    @RestController static class Endpoints {
        /** Returns a placeholder for the supplier publication route. */
        @PostMapping("/technical/deliveries") String publish() { return "{}"; }
        /** Returns a placeholder for store-scoped receiving diagnostics. */
        @GetMapping("/stores/{store}/deliveries/{id}") String status() { return "{}"; }
        /** Returns a placeholder for a diagnostic mutation that must not be available cross-origin. */
        @PostMapping("/stores/{store}/deliveries/{id}/retry-pricing") String retry() { return "{}"; }
    }

    /** Given the default local UI origin, preflight permits JSON publication but never credentials. */
    @Test void permitsConfiguredSupplierOrigin() throws Exception {
        try (var context = context()) {
            mvc(context).perform(options("/technical/deliveries").header(HttpHeaders.ORIGIN, "http://localhost:6789")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST").header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
                    .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:6789"))
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        }
    }

    /** Given a scoped receiving read, preflight permits GET from the alternate configured loopback origin. */
    @Test void permitsStatusRead() throws Exception {
        try (var context = context()) {
            mvc(context).perform(options("/stores/S-1/deliveries/D-1").header(HttpHeaders.ORIGIN, "http://127.0.0.1:6789")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")).andExpect(status().isOk());
        }
    }

    /** Given an unlisted origin, supplier publication is rejected before a controller executes. */
    @Test void rejectsUnlistedOrigin() throws Exception {
        try (var context = context()) {
            mvc(context).perform(options("/technical/deliveries").header(HttpHeaders.ORIGIN, "https://other.example")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")).andExpect(status().isForbidden());
        }
    }

    /** Given an allowed origin, cross-origin retry-pricing is still rejected because only read access is configured on diagnostics. */
    @Test void rejectsDiagnosticMutation() throws Exception {
        try (var context = context()) {
            mvc(context).perform(options("/stores/S-1/deliveries/D-1/retry-pricing").header(HttpHeaders.ORIGIN, "http://localhost:6789")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")).andExpect(status().isForbidden());
        }
    }

    /** Given wildcards, paths or credentials, configuration fails rather than broadening CORS silently. */
    @ParameterizedTest @ValueSource(strings = {"*", "http://localhost:6789/", "https://user:secret@host", "https://host?q=1", "null"})
    void rejectsUnsafeOrigin(String origin) { assertThrows(IllegalArgumentException.class, () -> new WarehouseUiCors(origin)); }

    /** Creates an isolated MVC application using default production origin values and no external services. */
    private AnnotationConfigWebApplicationContext context() {
        var context = new AnnotationConfigWebApplicationContext(); context.setServletContext(new MockServletContext());
        context.register(Config.class); context.refresh(); return context;
    }

    /** Builds the request driver against registered production MVC mappings. */
    private MockMvc mvc(AnnotationConfigWebApplicationContext context) { return webAppContextSetup(context).build(); }
}

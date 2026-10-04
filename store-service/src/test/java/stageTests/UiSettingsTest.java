package stageTests;

import com.shop.store.shop.UiSettingsController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** Checks public browser routing configuration without introducing a warehouse dependency into STORE. */
class UiSettingsTest {
    /** Given a public proxy URL, GET exposes only the normalized warehouse URL and succeeds without credentials. */
    @Test void exposesPublicConfiguration() throws Exception {
        standaloneSetup(new UiSettingsController("https://shop.example/warehouse/")).build()
                .perform(get("/ui/config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.warehouseBaseUrl").value("https://shop.example/warehouse"));
    }

    /** Given an unsafe/non-HTTP endpoint, startup fails rather than emitting credentials or executable URLs into the browser. */
    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "http://user:secret@host", "http://host?q=secret", "http://host#fragment", "/relative"})
    void rejectsUnsafeUrl(String url) { assertThrows(IllegalArgumentException.class, () -> new UiSettingsController(url)); }
}

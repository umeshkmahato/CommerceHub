package com.commercehub.product;

import com.commercehub.product.controller.ProductController;
import com.commercehub.product.dto.ProductResponses;
import com.commercehub.product.exception.GlobalExceptionHandler;
import com.commercehub.product.exception.ProductNotFoundException;
import com.commercehub.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller-layer tests with MockMvc (no server, no DB).
 * Verifies HTTP contract: status codes, validation, authz header checks.
 */
class ProductControllerTest {

    private final ProductService service = mock(ProductService.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ProductController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void getById_returns200() throws Exception {
        when(service.getById(1L)).thenReturn(mock(ProductResponses.ProductResponse.class));
        mvc.perform(get("/api/v1/products/1"))
                .andExpect(status().isOk());
    }

    @Test
    void getById_notFound_returns404Envelope() throws Exception {
        when(service.getById(99L)).thenThrow(new ProductNotFoundException("gone"));
        mvc.perform(get("/api/v1/products/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void create_withoutAdminRole_returns403() throws Exception {
        // Body must be VALID — @Valid runs before the method, so an invalid
        // body would 400 before the authz check ever executes.
        String validBody = """
                {"sku":"SKU-X","name":"Widget","categoryId":1,"price":9.99}
                """;
        mvc.perform(post("/api/v1/admin/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody)
                        .header("X-User-Roles", "CUSTOMER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_withInvalidBody_returns400() throws Exception {
        mvc.perform(post("/api/v1/admin/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"\"}")   // missing required fields
                        .header("X-User-Roles", "ADMIN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void search_returnsPageEnvelope() throws Exception {
        when(service.search(any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(new PageImpl<>(List.of()));
        mvc.perform(get("/api/v1/products")
                        .param("q", "laptop").param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").exists());
    }
}

package com.metaarch.customerprofile.profile;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
@SpringBootTest @AutoConfigureMockMvc
class CustomerProfileControllerTest {
  @Autowired MockMvc mvc;
  @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
  @Test void customerCanSaveOwnAddressButOtherCustomerCannotChangeIt() throws Exception {
    String payload = "{\"type\":\"Home\",\"address\":\"Test address\",\"phone\":\"9876543210\",\"houseNumber\":\"4\",\"floor\":\"4\",\"landmark\":\"Test landmark\"}";
    String response = mvc.perform(post("/api/v1/profiles/me/addresses").header("Origin", "http://127.0.0.1:5180").with(jwt().jwt(j -> j.subject("address-owner"))).contentType(MediaType.APPLICATION_JSON).content(payload))
      .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    String id = mapper.readTree(response).get("id").asText();
    mvc.perform(get("/api/v1/profiles/me/addresses").with(jwt().jwt(j -> j.subject("address-owner"))))
      .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
    mvc.perform(put("/api/v1/profiles/me/addresses/" + id).with(jwt().jwt(j -> j.subject("other-customer"))).contentType(MediaType.APPLICATION_JSON).content(payload))
      .andExpect(status().isNotFound());
    mvc.perform(delete("/api/v1/profiles/me/addresses/" + id).with(jwt().jwt(j -> j.subject("other-customer"))))
      .andExpect(status().isNotFound());
    mvc.perform(post("/api/v1/profiles/me/addresses").contentType(MediaType.APPLICATION_JSON).content(payload))
      .andExpect(status().isUnauthorized());
  }
  @Test void createsAndReadsOwnProfile() throws Exception {
    mvc.perform(put("/api/v1/profiles/me").with(jwt().jwt(j -> j.subject("alice"))).contentType(MediaType.APPLICATION_JSON)
      .content("{\"name\":\"Alice Doe\",\"phone\":\"+919876543210\",\"email\":\"alice@example.com\",\"gender\":\"FEMALE\",\"dateOfBirth\":\"1994-05-12\"}"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("alice")).andExpect(jsonPath("$.name").value("Alice Doe"));
    mvc.perform(get("/api/v1/profiles/me").with(jwt().jwt(j -> j.subject("alice"))))
      .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("alice@example.com"));
  }
  @Test void missingAuthenticatedProfileReturnsEmptyProfileForAccountFallback() throws Exception {
    String customerId = java.util.UUID.randomUUID().toString();
    mvc.perform(get("/api/v1/profiles/me").with(jwt().jwt(j -> j.subject("customer-without-profile").claim("customerId", customerId))))
      .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(customerId))
      .andExpect(jsonPath("$.username").value("customer-without-profile"));
  }
  @Test void rejectsInvalidProfile() throws Exception {
    mvc.perform(put("/api/v1/profiles/me").with(jwt()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\",\"phone\":\"123\"}"))
      .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.name").exists()).andExpect(jsonPath("$.fields.phone").exists());
  }
  @Test void protectsAdminLookup() throws Exception {
    mvc.perform(get("/api/v1/profiles/alice").with(jwt())).andExpect(status().isForbidden());
  }
}

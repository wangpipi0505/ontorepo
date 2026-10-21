package cn.ontorepo.data.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** 以密文完整性、授权交集和缺分类反例验证数据源边界，不用假数据库冒充适配验收。 */
class DataBoundaryTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

  @Test
  void credentialsUseRandomNonceAndBindSourceIdentity() {
    var cipher = new CredentialCipher(KEY);
    String first = cipher.encrypt("source-one", "{\"password\":\"本地测试值\"}");
    String second = cipher.encrypt("source-one", "{\"password\":\"本地测试值\"}");
    assertNotEquals(first, second);
    assertFalse(first.contains("本地测试值"));
    assertEquals("{\"password\":\"本地测试值\"}", cipher.decrypt("source-one", first));
    assertEquals(
        "CREDENTIAL_INTEGRITY_FAILED",
        assertThrows(DataProblem.class, () -> cipher.decrypt("source-two", first)).code());
  }

  @Test
  void credentialTamperingAndMissingKeyNeverFallBackToPlaintext() {
    var cipher = new CredentialCipher(KEY);
    String encoded = cipher.encrypt("source-one", "secret");
    byte[] bytes = Base64.getUrlDecoder().decode(encoded.substring(3));
    bytes[15] ^= 1;
    String tampered = "v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    assertEquals(
        "CREDENTIAL_INTEGRITY_FAILED",
        assertThrows(DataProblem.class, () -> cipher.decrypt("source-one", tampered)).code());
    assertThrows(DataProblem.class, () -> new CredentialCipher(null));
    assertThrows(DataProblem.class, () -> new CredentialCipher("plain-key"));
    assertThrows(DataProblem.class, () -> cipher.decrypt("source-one", "secret"));
  }

  @Test
  void endpointScopeNarrowsOtherwiseBroadScopes() throws Exception {
    var all = LabelScope.parse(JSON.readTree("{\"attr\":\"region\",\"in\":[\"A\",\"B\"]}"));
    var onlyA = LabelScope.parse(JSON.readTree("{\"attr\":\"region\",\"in\":[\"A\"]}"));
    var service = LabelScope.intersect(all, onlyA, all);
    assertTrue(service.allows("region", JSON.readTree("\"A\"")));
    assertFalse(service.allows("region", JSON.readTree("\"B\"")));
    var internal = LabelScope.intersect(all, all, all);
    assertTrue(internal.allows("region", JSON.readTree("\"B\"")));
    assertEquals(internal, LabelScope.intersect(all, LabelScope.unrestricted(), all));
  }

  @Test
  void unclassifiedAndDisjointLabelsDenyEvenWhenOtherLayersUnrestricted() throws Exception {
    var a = LabelScope.parse(JSON.readTree("{\"attr\":\"region\",\"in\":[\"A\"]}"));
    var b = LabelScope.parse(JSON.readTree("{\"attr\":\"region\",\"in\":[\"B\"]}"));
    assertTrue(LabelScope.intersect(a, b).deniesAll());
    assertFalse(LabelScope.unrestricted().allows("region", JSON.nullNode()));
    assertFalse(LabelScope.unrestricted().allows("region", null));
    assertFalse(LabelScope.unrestricted().allows("region", JSON.readTree("\"\"")));
  }

  @Test
  void scopeRejectsUnknownOrMalformedExpressionsAndKeepsMultipleGuards() throws Exception {
    for (String invalid :
        new String[] {
          "{}",
          "{\"attr\":\"region\",\"in\":\"A\"}",
          "{\"attr\":true,\"in\":[\"A\"]}",
          "{\"attr\":\"region\",\"in\":[null]}",
          "{\"attr\":\"region\",\"in\":[{\"x\":1}]}",
          "{\"attr\":\"region\",\"in\":[\"A\"],\"sql\":\"1=1\"}"
        }) assertThrows(DataProblem.class, () -> LabelScope.parse(JSON.readTree(invalid)));
    var mixed =
        LabelScope.parse(
            JSON.readTree(
                "{\"all\":[{\"attr\":\"region\",\"in\":[\"A\"]},{\"attr\":\"tenant_label\",\"in\":[\"T1\"]}]}"));
    assertTrue(mixed.allows("region", JSON.readTree("\"A\"")));
    assertFalse(mixed.allows("tenant_label", JSON.readTree("\"T2\"")));
  }

  @Test
  void numericScopeValuesHaveMathematicalEqualityWithoutStringCoercion() throws Exception {
    var integer = LabelScope.parse(JSON.readTree("{\"attr\":\"level\",\"in\":[1]}"));
    var decimal = LabelScope.parse(JSON.readTree("{\"attr\":\"level\",\"in\":[1.0]}"));
    var result = LabelScope.intersect(integer, decimal);
    assertFalse(result.deniesAll());
    assertTrue(result.allows("level", JSON.readTree("1.00")));
    assertFalse(result.allows("level", JSON.readTree("\"1\"")));
  }

  @Test
  void directConstructionCannotBypassScopeValidation() throws Exception {
    assertThrows(
        DataProblem.class,
        () ->
            new LabelScope(java.util.Map.of("region", java.util.Set.of(JSON.createObjectNode()))));
    assertThrows(
        DataProblem.class,
        () ->
            new LabelScope(
                java.util.Map.of(
                    "region; DROP", java.util.Set.of(JSON.getNodeFactory().textNode("A")))));
    assertThrows(DataProblem.class, () -> LabelScope.parse(JSON.readTree("{\"all\":[null]}")));
  }
}

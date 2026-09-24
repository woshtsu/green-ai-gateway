package pe.edu.continental.greenai.gateway;

import java.util.Date;
import java.util.List;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayApplicationTests {
    static final String ISSUER = "https://auth.test/auth/v1";
    static final ECKey KEY = key();
    static ECKey key() {
        try { return new ECKeyGenerator(Curve.P_256).keyID("test").generate(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    static final DisposableServer UPSTREAM = HttpServer.create().host("127.0.0.1").port(0)
        .handle((req,res) -> {
            if (req.uri().equals("/jwks")) return res.header("Content-Type", "application/json")
                .sendString(Mono.just(new JWKSet(KEY.toPublicJWK()).toString()));
            return res.header("X-Request-Id",req.requestHeaders().get("X-Request-Id"))
                .sendString(Mono.just(req.uri()));
        }).bindNow();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("GATEWAY_MONITORING_BASE_URL", () -> "http://127.0.0.1:" + UPSTREAM.port());
        p.add("gateway.auth.issuer", () -> ISSUER);
        p.add("gateway.auth.jwks", () -> "http://127.0.0.1:" + UPSTREAM.port() + "/jwks");
    }
    @AfterAll static void stop() { UPSTREAM.disposeNow(); }
    @LocalServerPort int port;
    WebTestClient client;
    @BeforeEach void setup() { client=WebTestClient.bindToServer().baseUrl("http://127.0.0.1:"+port).build(); }
    String token(String role, String issuer, String audience, long expiry, ECKey key) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject("user-test")
            .issueTime(new Date(System.currentTimeMillis()-1000)).expirationTime(new Date(expiry))
            .claim("role","authenticated").claim("is_anonymous",false);
        if (role != null) claims.claim("user_role",role);
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("test").build(), claims.build());
        jwt.sign(new ECDSASigner(key)); return jwt.serialize();
    }
    String valid(String role) throws Exception { return token(role,ISSUER,"authenticated",System.currentTimeMillis()+300000,KEY); }
    static final String PATH="/api/monitoring/v1/metrics/current?metric=node.cpu.utilization&resourceType=node";
    @Test void requiresToken() {
        client.get().uri(PATH).exchange().expectStatus().isUnauthorized()
            .expectHeader().contentType("application/problem+json").expectHeader().exists("X-Request-Id");
    }
    @Test void operatorAndAdminReachOnlyConfirmedRoute() throws Exception {
        for (String role : List.of("OPERATOR","ADMIN")) {
            String jwt=valid(role);
            client.get().uri(PATH).headers(h -> h.setBearerAuth(jwt))
                .header("X-Request-Id","browser-42").exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Request-Id","browser-42")
                .expectBody(String.class).isEqualTo("/api/v1/metrics/current?metric=node.cpu.utilization&resourceType=node");
        }
    }
    @Test void rejectsInvalidTokens() throws Exception {
        long later=System.currentTimeMillis()+300000;
        for (String jwt : List.of("invalid", token("ADMIN","https://wrong.test","authenticated",later,KEY),
            token("ADMIN",ISSUER,"wrong",later,KEY),token("ADMIN",ISSUER,"authenticated",System.currentTimeMillis()-120000,KEY),
            token("ADMIN",ISSUER,"authenticated",later,key()))) {
            client.get().uri(PATH).headers(h -> h.setBearerAuth(jwt)).exchange().expectStatus().isUnauthorized();
        }
    }
    @Test void missingOrUnknownRoleIsForbidden() throws Exception {
        for (String jwt : List.of(valid(null),valid("ROOT")))
            client.get().uri(PATH).headers(h -> h.setBearerAuth(jwt)).exchange().expectStatus().isForbidden();
    }
    @Test void cannotPostOrProxyUnknownPaths() throws Exception {
        var jwt=valid("ADMIN");
        client.post().uri("/api/monitoring/v1/metrics/current").headers(h -> h.setBearerAuth(jwt)).exchange().expectStatus().isForbidden();
        client.get().uri("/api/processing/v1/anything").headers(h -> h.setBearerAuth(jwt)).exchange().expectStatus().isForbidden();
    }
    @Test void corsPreflightNeedsNoToken() {
        client.options().uri("/api/monitoring/v1/metrics/current")
            .header("Origin","http://127.0.0.1:3001").header("Access-Control-Request-Method","GET")
            .header("Access-Control-Request-Headers","authorization")
            .exchange().expectStatus().isOk().expectHeader().valueEquals("Access-Control-Allow-Origin","http://127.0.0.1:3001");
    }
    @Test void deniesForeignOrigin() {
        client.options().uri("/api/monitoring/v1/metrics/current").header("Origin","https://wrong.test")
            .header("Access-Control-Request-Method","GET").exchange().expectStatus().isForbidden();
    }
    @Test void healthIsPublic() { client.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk(); }
}

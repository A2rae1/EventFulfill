package cn.hm.gateway;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.assertj.core.api.Assertions.*;

/** Real Gateway/Netty against an explicit local HTTP transport fixture, no Nacos claim. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayTransportTest {
 static final AtomicInteger posts=new AtomicInteger();static final HttpServer backend;
 static {try{backend=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  backend.createContext("/api/echo",exchange->{String key=exchange.getRequestHeaders().getFirst("Idempotency-Key");byte[] body=exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().add("X-Received-Key",key==null?"missing":key);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});
  backend.createContext("/api/fail",exchange->{posts.incrementAndGet();byte[] body="upstream unavailable".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(503,body.length);exchange.getResponseBody().write(body);exchange.close();});backend.start();
 }catch(Exception error){throw new ExceptionInInitializerError(error);}}
 @DynamicPropertySource static void settings(DynamicPropertyRegistry registry){registry.add("gateway.java-fallback",()->"http://127.0.0.1:"+backend.getAddress().getPort());}
 @Autowired WebTestClient web;
 @AfterAll static void close(){backend.stop(0);}
 @Test void postBodyAndOriginalKeyReachBackend(){web.post().uri("/api/echo").header("Idempotency-Key","stable-fixture-key").contentType(org.springframework.http.MediaType.TEXT_PLAIN).bodyValue("one request").exchange().expectStatus().isOk().expectHeader().valueEquals("X-Received-Key","stable-fixture-key").expectBody(String.class).isEqualTo("one request");}
 @Test void failedPostIsNeverAutomaticallyRetried(){int before=posts.get();web.post().uri("/api/fail").header("Idempotency-Key","do-not-duplicate").exchange().expectStatus().isEqualTo(503).expectBody().jsonPath("$.code").isEqualTo("UPSTREAM_UNAVAILABLE");assertThat(posts.get()-before).isEqualTo(1);}
}

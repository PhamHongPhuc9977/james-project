/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/
package org.apache.james.jmap;

import static com.jayway.restassured.RestAssured.given;
import static com.jayway.restassured.RestAssured.with;
import static com.jayway.restassured.config.EncoderConfig.encoderConfig;
import static com.jayway.restassured.config.RestAssuredConfig.newConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.servlet.Filter;

import org.apache.james.http.jetty.Configuration;
import org.apache.james.http.jetty.JettyHttpServer;
import org.apache.james.jmap.api.AccessTokenManager;
import org.apache.james.jmap.api.access.AccessToken;
import org.apache.james.jmap.crypto.AccessTokenManagerImpl;
import org.apache.james.jmap.crypto.JamesSignatureHandlerProvider;
import org.apache.james.jmap.crypto.SignedContinuationTokenManager;
import org.apache.james.jmap.memory.access.MemoryAccessTokenRepository;
import org.apache.james.jmap.model.ContinuationToken;
import org.apache.james.jmap.utils.ZonedDateTimeProvider;
import org.apache.james.user.api.UsersRepository;
import org.apache.james.user.api.UsersRepositoryException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.common.base.Charsets;
import com.jayway.restassured.RestAssured;
import com.jayway.restassured.http.ContentType;

public class JMAPAuthenticationTest {

    private static final ZonedDateTime oldDate = ZonedDateTime.parse("2011-12-03T10:15:30+01:00", DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    private static final ZonedDateTime newDate = ZonedDateTime.parse("2011-12-03T10:16:30+01:00", DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    private static final ZonedDateTime afterExpirationDate = ZonedDateTime.parse("2011-12-03T10:30:31+01:00", DateTimeFormatter.ISO_OFFSET_DATE_TIME);

    private JettyHttpServer server;

    private UsersRepository mockedUsersRepository;
    private ZonedDateTimeProvider mockedZonedDateTimeProvider;
    private AccessTokenManager accessTokenManager;
    private SignedContinuationTokenManager continuationTokenManager;

    @Before
    public void setup() throws Exception {
        mockedUsersRepository = mock(UsersRepository.class);
        mockedZonedDateTimeProvider = mock(ZonedDateTimeProvider.class);
        accessTokenManager = new AccessTokenManagerImpl(new MemoryAccessTokenRepository(TimeUnit.MILLISECONDS.convert(1, TimeUnit.HOURS)));
        continuationTokenManager = new SignedContinuationTokenManager(new JamesSignatureHandlerProvider().provide(), mockedZonedDateTimeProvider);
        
        AuthenticationServlet authenticationServlet = new AuthenticationServlet(mockedUsersRepository, continuationTokenManager, accessTokenManager);


        AuthenticationFilter authenticationFilter = new AuthenticationFilter(accessTokenManager);
        Filter getAuthenticationFilter = new BypassOnPostFilter(authenticationFilter);
        
        server = JettyHttpServer.create(
                Configuration.builder()
                .filter("/*")
                .with(getAuthenticationFilter)
                .serve("/*")
                .with(authenticationServlet)
                .randomPort()
                .build());

        
        server.start();
        RestAssured.port = server.getPort();
        RestAssured.config = newConfig().encoderConfig(encoderConfig().defaultContentCharset(Charsets.UTF_8));
    }
    
    @After
    public void teardown() throws Exception {
        server.stop();
    }
    
    @Test
    public void mustReturnMalformedRequestWhenContentTypeIsMissing() {
        given()
            .accept(ContentType.JSON)
        .when()
            .post("/authentication")
        .then()
            .statusCode(400);
    }

    @Test
    public void mustReturnMalformedRequestWhenContentTypeIsNotJson() {
        given()
            .contentType(ContentType.XML)
            .accept(ContentType.JSON)
        .when()
            .post("/authentication")
        .then()
            .statusCode(400);
    }

    @Test
    public void mustReturnMalformedRequestWhenAcceptIsMissing() {
        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/authentication")
        .then()
            .statusCode(400);
    }

    @Test
    public void mustReturnMalformedRequestWhenAcceptIsNotJson() {
        given()
            .contentType(ContentType.JSON)
            .accept(ContentType.XML)
        .when()
            .post("/authentication")
        .then()
            .statusCode(400);
    }

    @Test
    public void mustReturnMalformedRequestWhenCharsetIsNotUTF8() {
        given()
            .contentType("application/json; charset=ISO-8859-1")
            .accept(ContentType.JSON)
        .when()
            .post("/authentication")
        .then()
            .statusCode(400);
    }

    @Test
    public void mustReturnMalformedRequestWhenBodyIsEmpty() {
        given()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
        .when()
            .post("/authentication")
        .then()
            .statusCode(400);
    }

    @Test
    public void mustReturnMalformedRequestWhenBodyIsNotAcceptable() {
        given()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .body("{\"badAttributeName\": \"value\"}")
        .when()
            .post("/authentication")
        .then()
            .statusCode(400);
    }

    @Test
    public void mustReturnJsonResponse() throws Exception {
        when(mockedZonedDateTimeProvider.get())
            .thenReturn(oldDate);

        given()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .body("{\"username\": \"user@domain.tld\", \"clientName\": \"Mozilla Thunderbird\", \"clientVersion\": \"42.0\", \"deviceName\": \"Joe Blogg’s iPhone\"}")
        .when()
            .post("/authentication")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON);
    }

    @Test
    public void methodShouldContainPasswordWhenValidResquest() throws Exception {
        when(mockedZonedDateTimeProvider.get())
            .thenReturn(oldDate);

        given()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .body("{\"username\": \"user@domain.tld\", \"clientName\": \"Mozilla Thunderbird\", \"clientVersion\": \"42.0\", \"deviceName\": \"Joe Blogg’s iPhone\"}")
        .when()
            .post("/authentication")
        .then()
            .statusCode(200)
            .body("methods", hasItem("password"));
    }

    @Test
    public void mustReturnContinuationTokenWhenValidResquest() throws Exception {
        when(mockedZonedDateTimeProvider.get())
            .thenReturn(oldDate);

        given()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .body("{\"username\": \"user@domain.tld\", \"clientName\": \"Mozilla Thunderbird\", \"clientVersion\": \"42.0\", \"deviceName\": \"Joe Blogg’s iPhone\"}")
        .when()
            .post("/authentication")
        .then()
            .statusCode(200)
            .body("continuationToken", isA(String.class));
    }

    @Test
    public void mustReturnAuthenticationFailedWhenBadPassword() throws Exception {
        when(mockedZonedDateTimeProvider.get())
            .thenReturn(oldDate);

        String continuationToken = fromGoodContinuationTokenRequest();

        given()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .body("{\"token\": \"" + continuationToken + "\", \"method\": \"password\", \"password\": \"badpassword\"}")
        .when()
            .post("/authentication")
        .then()
            .statusCode(401);
    }

    @Test
    public void mustReturnAuthenticationFailedWhenContinuationTokenIsRejectedByTheContinuationTokenManager() throws Exception {
        ContinuationToken badContinuationToken = new ContinuationToken("user@domain.tld", newDate, "badSignature");
        
        when(mockedUsersRepository.test("user@domain.tld", "password"))
            .thenReturn(true);
        when(mockedZonedDateTimeProvider.get())
            .thenReturn(oldDate);

        given()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .body("{\"token\": \"" + badContinuationToken.serialize() + "\", \"method\": \"password\", \"password\": \"password\"}")
        .when()
            .post("/authentication")
        .then()
            .statusCode(401);
    }

    @Test
    public void mustReturnRestartAuthenticationWhenContinuationTokenIsExpired() throws Exception {
        when(mockedZonedDateTimeProvider.get())
            .thenReturn(oldDate);

        String continuationToken = fromGoodContinuationTokenRequest();
        
        when(mockedUsersRepository.test("user@domain.tld", "password"))
            .thenReturn(true);
        when(mockedZonedDateTimeProvider.get())
            .thenReturn(afterExpirationDate);

        given()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .body("{\"token\": \"" + continuationToken + "\", \"method\": \"password\", \"password\": \"password\"}")
        .when()
            .post("/authentication")
        .then()
            .statusCode(403);
    }

    @Test
    public void mustReturnAuthenticationFailedWhenUsersRepositoryException() throws Exception {
        when(mockedZonedDateTimeProvider.get())
            .thenReturn(oldDate);

        String continuationToken = fromGoodContinuationTokenRequest();

        when(mockedUsersRepository.test("user@domain.tld", "password"))
            .thenThrow(new UsersRepositoryException("test"));

        given()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .body("{\"token\": \"" + continuationToken + "\", \"method\": \"password\", \"password\": \"password\"}")
        .when()
            .post("/authentication")
        .then()
            .statusCode(401);
    }

    @Test
    public void mustReturnCreatedWhenGoodPassword() throws Exception {
        when(mockedZonedDateTimeProvider.get())
            .thenReturn(oldDate);

        String continuationToken = fromGoodContinuationTokenRequest();

        when(mockedUsersRepository.test("user@domain.tld", "password"))
            .thenReturn(true);
        when(mockedZonedDateTimeProvider.get())
            .thenReturn(newDate);

        given()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .body("{\"token\": \"" + continuationToken + "\", \"method\": \"password\", \"password\": \"password\"}")
        .when()
            .post("/authentication")
        .then()
            .statusCode(201);
    }

    @Test
    public void mustSendJsonContainingAccessTokenWhenGoodPassword() throws Exception {
        when(mockedZonedDateTimeProvider.get())
            .thenReturn(oldDate);

        String continuationToken = fromGoodContinuationTokenRequest();

        when(mockedUsersRepository.test("user@domain.tld", "password"))
            .thenReturn(true);
        when(mockedZonedDateTimeProvider.get())
            .thenReturn(newDate);

        given()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .body("{\"token\": \"" + continuationToken + "\", \"method\": \"password\", \"password\": \"password\"}")
        .when()
            .post("/authentication")
        .then()
            .body("accessToken", isA(String.class));
    }
    
    @Test
    public void getMustReturnUnauthorizedWithoutAuthroizationHeader() throws Exception {
        given()
        .when()
            .get("/authentication")
        .then()
            .statusCode(401);
    }

    @Test
    public void getMustReturnUnauthorizedWithoutAValidAuthroizationHeader() throws Exception {
        given()
            .header("Authorization", UUID.randomUUID())
        .when()
            .get("/authentication")
        .then()
            .statusCode(401);
    }

    @Test
    public void getMustReturnEndpointsWhenValidAuthorizationHeader() throws Exception {
        AccessToken token = accessTokenManager.grantAccessToken("username");
        given()
            .header("Authorization", token.serialize())
        .when()
            .get("/authentication")
        .then()
            .statusCode(200)
            .body("api", isA(String.class));
    }

    @Test
    public void getMustReturnEndpointsWhenCorrectAuthentication() throws Exception {
        when(mockedZonedDateTimeProvider.get())
            .thenReturn(oldDate);

        String continuationToken = fromGoodContinuationTokenRequest();
    
        when(mockedUsersRepository.test("user@domain.tld", "password"))
            .thenReturn(true);
        when(mockedZonedDateTimeProvider.get())
            .thenReturn(newDate);
    
        String accessToken = fromGoodAccessTokenRequest(continuationToken);
    
        given()
            .header("Authorization", accessToken)
        .when()
            .get("/authentication")
        .then()
            .statusCode(200)
            .body("api", isA(String.class));
    }
    
    @Test
    public void deleteMustReturnUnauthenticatedWithoutAuthorizationHeader() throws Exception {
        given()
        .when()
            .delete("/authentication")
        .then()
            .statusCode(401);
    }

    @Test
    public void deleteMustReturnUnauthenticatedWithoutAValidAuthroizationHeader() throws Exception {
        given()
            .header("Authorization", UUID.randomUUID())
        .when()
            .delete("/authentication")
        .then()
            .statusCode(401);
    }
    
    @Test
    public void deleteMustReturnOKNoContentOnValidAuthorizationToken() throws Exception {
        AccessToken token = accessTokenManager.grantAccessToken("username");
        given()
            .header("Authorization", token.serialize())
        .when()
            .delete("/authentication")
        .then()
            .statusCode(204);
    }

    @Test
    public void deleteMustInvalidTokenOnValidAuthorizationToken() throws Exception {
        AccessToken token = accessTokenManager.grantAccessToken("username");
        with()
            .header("Authorization", token.serialize())
            .delete("/authentication");
        assertThat(accessTokenManager.isValid(token)).isFalse();
    }

    @Test
    public void deleteMustInvalidAuthorizationOnCorrectAuthorization() throws Exception {
        when(mockedZonedDateTimeProvider.get())
            .thenReturn(oldDate);

        String continuationToken = fromGoodContinuationTokenRequest();
    
        when(mockedUsersRepository.test("user@domain.tld", "password"))
            .thenReturn(true);
        when(mockedZonedDateTimeProvider.get())
            .thenReturn(newDate);
    
        String accessToken = fromGoodAccessTokenRequest(continuationToken);
        
        goodDeleteAccessTokenRequest(accessToken);
    
        given()
            .header("Authorization", accessToken)
        .when()
            .get("/authentication")
        .then()
            .statusCode(401);
    }

    private String fromGoodContinuationTokenRequest() {
        return with()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .body("{\"username\": \"user@domain.tld\", \"clientName\": \"Mozilla Thunderbird\", \"clientVersion\": \"42.0\", \"deviceName\": \"Joe Blogg’s iPhone\"}")
        .post("/authentication")
            .body()
            .path("continuationToken")
            .toString();
    }

    private String fromGoodAccessTokenRequest(String continuationToken) {
        return with()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .body("{\"token\": \"" + continuationToken + "\", \"method\": \"password\", \"password\": \"password\"}")
        .post("/authentication")
            .path("accessToken")
            .toString();
    }

    private void goodDeleteAccessTokenRequest(String accessToken) {
        with()
            .header("Authorization", accessToken)
            .delete("/authentication");
    }
}
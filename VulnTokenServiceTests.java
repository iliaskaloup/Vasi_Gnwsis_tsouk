/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.authc;

import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.NoShardAvailableActionException;
import org.elasticsearch.action.get.GetAction;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetRequestBuilder;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexAction;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.action.update.UpdateAction;
import org.elasticsearch.action.update.UpdateRequestBuilder;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.io.stream.OutputStreamStreamOutput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.node.Node;
import org.elasticsearch.test.ClusterServiceUtils;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.EqualsHashCodeTestUtils;
import org.elasticsearch.threadpool.FixedExecutorBuilder;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.core.XPackSettings;
import org.elasticsearch.xpack.core.security.authc.Authentication;
import org.elasticsearch.xpack.core.security.authc.Authentication.AuthenticationType;
import org.elasticsearch.xpack.core.security.authc.Authentication.RealmRef;
import org.elasticsearch.xpack.core.security.authc.TokenMetaData;
import org.elasticsearch.xpack.core.security.authc.support.TokensInvalidationResult;
import org.elasticsearch.xpack.core.security.user.User;
import org.elasticsearch.xpack.core.watcher.watch.ClockMock;
import org.elasticsearch.xpack.security.support.SecurityIndexManager;
import org.junit.After;
import org.elasticsearch.xpack.security.test.SecurityMocks;
import org.hamcrest.Matchers;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;

import static java.time.Clock.systemUTC;
import static org.elasticsearch.repositories.ESBlobStoreTestCase.randomBytes;
import static org.elasticsearch.test.ClusterServiceUtils.setState;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TokenServiceTests extends ESTestCase {

    private static ThreadPool threadPool;
    private static final Settings settings = Settings.builder().put(Node.NODE_NAME_SETTING.getKey(), "TokenServiceTests")
            .put(XPackSettings.TOKEN_SERVICE_ENABLED_SETTING.getKey(), true).build();

    private Client client;
    private SecurityIndexManager securityMainIndex;
    private SecurityIndexManager securityTokensIndex;
    private ClusterService clusterService;
    private DiscoveryNode oldNode;
    private Settings tokenServiceEnabledSettings = Settings.builder()
        .put(XPackSettings.TOKEN_SERVICE_ENABLED_SETTING.getKey(), true).build();
    private XPackLicenseState licenseState;

    @Before
    public void setupClient() {
        client = mock(Client.class);
        when(client.threadPool()).thenReturn(threadPool);
        when(client.settings()).thenReturn(settings);
        doAnswer(invocationOnMock -> {
            GetRequestBuilder builder = new GetRequestBuilder(client, GetAction.INSTANCE);
            builder.setIndex((String) invocationOnMock.getArguments()[0])
                    .setType((String) invocationOnMock.getArguments()[1])
                    .setId((String) invocationOnMock.getArguments()[2]);
            return builder;
        }).when(client).prepareGet(anyString(), anyString(), anyString());
        when(client.prepareIndex(any(String.class), any(String.class), any(String.class)))
                .thenReturn(new IndexRequestBuilder(client, IndexAction.INSTANCE));
        when(client.prepareUpdate(any(String.class), any(String.class), any(String.class)))
                .thenReturn(new UpdateRequestBuilder(client, UpdateAction.INSTANCE));
        doAnswer(invocationOnMock -> {
            ActionListener<IndexResponse> responseActionListener = (ActionListener<IndexResponse>) invocationOnMock.getArguments()[2];
            responseActionListener.onResponse(new IndexResponse(new ShardId(".security", UUIDs.randomBase64UUID(), randomInt()), "_doc",
                    randomAlphaOfLength(4), randomNonNegativeLong(), randomNonNegativeLong(), randomNonNegativeLong(), true));
            return null;
        }).when(client).execute(eq(IndexAction.INSTANCE), any(IndexRequest.class), any(ActionListener.class));

        // setup lifecycle service
        this.securityMainIndex = SecurityMocks.mockSecurityIndexManager();
        this.securityTokensIndex = SecurityMocks.mockSecurityIndexManager();
        this.clusterService = ClusterServiceUtils.createClusterService(threadPool);

        // License state (enabled by default)
        licenseState = mock(XPackLicenseState.class);
        when(licenseState.isTokenServiceAllowed()).thenReturn(true);

        // version 7.2 was an "inflection" point in the Token Service development (access_tokens as UUIDS, multiple concurrent refreshes,
        // tokens docs on a separate index), let's test the TokenService works in a mixed cluster with nodes with versions prior to these
        // developments
        if (randomBoolean()) {
            oldNode = addAnotherDataNodeWithVersion(this.clusterService, randomFrom(Version.V_6_7_0, Version.V_7_0_0));
        }
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
        clusterService.close();
    }

    @BeforeClass
    public static void startThreadPool() throws IOException {
        threadPool = new ThreadPool(settings,
                new FixedExecutorBuilder(settings, TokenService.THREAD_POOL_NAME, 1, 1000, "xpack.security.authc.token.thread_pool"));
        new Authentication(new User("foo"), new RealmRef("realm", "type", "node"), null).writeToContext(threadPool.getThreadContext());
    }

    @AfterClass
    public static void shutdownThreadpool() throws InterruptedException {
        terminate(threadPool);
        threadPool = null;
    }

    public void testAttachAndGetToken() throws Exception {
        TokenService tokenService = createTokenService(tokenServiceEnabledSettings, systemUTC());
        Authentication authentication = new Authentication(new User("joe", "admin"), new RealmRef("native_realm", "native", "node1"), null);
        PlainActionFuture<Tuple<UserToken, String>> tokenFuture = new PlainActionFuture<>();
        tokenService.createOAuth2Tokens(authentication, authentication, Collections.emptyMap(), true, tokenFuture);
        final UserToken token = tokenFuture.get().v1();
        assertNotNull(token);
        mockGetTokenFromId(token, false);
        authentication = token.getAuthentication();

        ThreadContext requestContext = new ThreadContext(Settings.EMPTY);
        requestContext.putHeader("Authorization", randomFrom("Bearer ", "BEARER ", "bearer ") + tokenService.getAccessTokenAsString(token));

        try (ThreadContext.StoredContext ignore = requestContext.newStoredContext(true)) {
            PlainActionFuture<UserToken> future = new PlainActionFuture<>();
            tokenService.getAndValidateToken(requestContext, future);
            UserToken serialized = future.get();
            assertAuthentication(authentication, serialized.getAuthentication());
        }

        try (ThreadContext.StoredContext ignore = requestContext.newStoredContext(true)) {
            // verify a second separate token service with its own salt can also verify
            TokenService anotherService = createTokenService(tokenServiceEnabledSettings, systemUTC());
            anotherService.refreshMetaData(tokenService.getTokenMetaData());
            PlainActionFuture<UserToken> future = new PlainActionFuture<>();
            anotherService.getAndValidateToken(requestContext, future);
            UserToken fromOtherService = future.get();
            assertAuthentication(authentication, fromOtherService.getAuthentication());
        }
    }

    public void testInvalidAuthorizationHeader() throws Exception {
        TokenService tokenService = createTokenService(tokenServiceEnabledSettings, systemUTC());
        ThreadContext requestContext = new ThreadContext(Settings.EMPTY);
        String token = randomFrom("", "          ");
        String authScheme = randomFrom("Bearer ", "BEARER ", "bearer ", "Basic ");
        requestContext.putHeader("Authorization", authScheme + token);

        try (ThreadContext.StoredContext ignore = requestContext.newStoredContext(true)) {
            PlainActionFuture<UserToken> future = new PlainActionFuture<>();
            tokenService.getAndValidateToken(requestContext, future);
            UserToken serialized = future.get();
            assertThat(serialized, nullValue());
        }
    }

    public void testRotateKey() throws Exception {
        TokenService tokenService = createTokenService(tokenServiceEnabledSettings, systemUTC());
        Authentication authentication = new Authentication(new User("joe", "admin"), new RealmRef("native_realm", "native", "node1"), null);
        PlainActionFuture<Tuple<UserToken, String>> tokenFuture = new PlainActionFuture<>();
        tokenService.createOAuth2Tokens(authentication, authentication, Collections.emptyMap(), true, tokenFuture);
        final UserToken token = tokenFuture.get().v1();
        assertNotNull(token);
        mockGetTokenFromId(token, false);
        authentication = token.getAuthentication();

        ThreadContext requestContext = new ThreadContext(Settings.EMPTY);
        storeTokenHeader(requestContext, getDeprecatedAccessTokenString(tokenService, token));

        try (ThreadContext.StoredContext ignore = requestContext.newStoredContext(true)) {
            PlainActionFuture<UserToken> future = new PlainActionFuture<>();
            tokenService.getAndValidateToken(requestContext, future);
            UserToken serialized = future.get();
            assertAuthentication(authentication, serialized.getAuthentication());
        }
        rotateKeys(tokenService);

        try (ThreadContext.StoredContext ignore = requestContext.newStoredContext(true)) {
            PlainActionFuture<UserToken> future = new PlainActionFuture<>();
            tokenService.getAndValidateToken(requestContext, future);
            UserToken serialized = future.get();
            assertAuthentication(authentication, serialized.getAuthentication());
        }

        PlainActionFuture<Tuple<UserToken, String>> newTokenFuture = new PlainActionFuture<>();
        tokenService.createOAuth2Tokens(authentication, authentication, Collections.emptyMap(), true, newTokenFuture);
        final UserToken newToken = newTokenFuture.get().v1();
        assertNotNull(newToken);
        assertNotEquals(getDeprecatedAccessTokenString(tokenService, newToken), getDeprecatedAccessTokenString(tokenService, token));

        requestContext = new ThreadContext(Settings.EMPTY);
        storeTokenHeader(requestContext, getDeprecatedAccessTokenString(tokenService, newToken));
        mockGetTokenFromId(newToken, false);

        try (ThreadContext.StoredContext ignore = requestContext.newStoredContext(true)) {
            PlainActionFuture<UserToken> future = new PlainActionFuture<>();
            tokenService.getAndValidateToken(requestContext, future);
            UserToken serialized = future.get();
            assertAuthentication(authentication, serialized.getAuthentication());
        }
    }

    private void rotateKeys(TokenService tokenService) {
        TokenMetaData tokenMetaData = tokenService.generateSpareKey();
        tokenService.refreshMetaData(tokenMetaData);
        tokenMetaData = tokenService.rotateToSpareKey();
        tokenService.refreshMetaData(tokenMetaData);
    }

    public void testKeyExchange() throws Exception {
        TokenService tokenService = createTokenService(tokenServiceEnabledSettings, systemUTC());
        int numRotations = randomIntBetween(1, 5);
        for (int i = 0; i < numRotations; i++) {
            rotateKeys(tokenService);
        }
        TokenService otherTokenService = createTokenService(tokenServiceEnabledSettings, systemUTC());
        otherTokenService.refreshMetaData(tokenService.getTokenMetaData());
        Authentication authentication = new Authentication(new User("joe", "admin"), new RealmRef("native_realm", "native", "node1"), null);
        PlainActionFuture<Tuple<UserToken, String>> tokenFuture = new PlainActionFuture<>();
        tokenService.createOAuth2Tokens(authentication, authentication, Collections.emptyMap(), true, tokenFuture);
        final UserToken token = tokenFuture.get().v1();
        assertNotNull(token);
        mockGetTokenFromId(token, false);
        authentication = token.getAuthentication();

        ThreadContext requestContext = new ThreadContext(Settings.EMPTY);
        storeTokenHeader(requestContext, getDeprecatedAccessTokenString(tokenService, token));
        try (ThreadContext.StoredContext ignore = requestContext.newStoredContext(true)) {
            PlainActionFuture<UserToken> future = new PlainActionFuture<>();
            otherTokenService.getAndValidateToken(requestContext, future);
            UserToken serialized = future.get();
            assertEquals(authentication, serialized.getAuthentication());
        }

        rotateKeys(tokenService);

        otherTokenService.refreshMetaData(tokenService.getTokenMetaData());

        try (ThreadContext.StoredContext ignore = requestContext.newStoredContext(true)) {
            PlainActionFuture<UserToken> future = new PlainActionFuture<>();
            otherTokenService.getAndValidateToken(requestContext, future);
            UserToken serialized = future.get();
            assertEquals(authentication, serialized.getAuthentication());
        }
    }

    public void testPruneKeys() throws Exception {
        TokenService tokenService = createTokenService(tokenServiceEnabledSettings, systemUTC());
        Authentication authentication = new Authentication(new User("joe", "admin"), new RealmRef("native_realm", "native", "node1"), null);
        PlainActionFuture<Tuple<UserToken, String>> tokenFuture = new PlainActionFuture<>();
        tokenService.createOAuth2Tokens(authentication, authentication, Collections.emptyMap(), true, tokenFuture);
        final UserToken token = tokenFuture.get().v1();
        assertNotNull(token);
        mockGetTokenFromId(token, false);
        authentication = token.getAuthentication();

        ThreadContext requestContext = new ThreadContext(Settings.EMPTY);
        storeTokenHeader(requestContext, getDeprecatedAccessTokenString(tokenService, token));

        try (ThreadContext.StoredContext ignore = requestContext.newStoredContext(true)) {
            PlainActionFuture<UserToken> future = new PlainActionFuture<>();
            tokenService.getAndValidateToken(requestContext, future);
            UserToken serialized = future.get();
            assertAuthentication(authentication, serialized.getAuthentication());
        }
        TokenMetaData metaData = tokenService.pruneKeys(randomIntBetween(0, 100));
        tokenService.refreshMetaData(metaData);

        int numIterations = scaledRandomIntBetween(1, 5);
        for (int i = 0; i < numIterations; i++) {
            rotateKeys(tokenService);
        }

        try (ThreadContext.StoredContext ignore = requestContext.newStoredContext(true)) {
            PlainActionFuture<UserToken> future = new PlainActionFuture<>();
            tokenService.getAndValidateToken(requestContext, future);
            UserToken serialized = future.get();
            assertAuthentication(authentication, serialized.getAuthentication());
        }

        PlainActionFuture<Tuple<UserToken, String>> newTokenFuture = new PlainActionFuture<>();
        tokenService.createOAuth2Tokens(authentication, authentication, Collections.emptyMap(), true, newTokenFuture);
        final UserToken newToken = newTokenFuture.get().v1();
        assertNotNull(newToken);
        assertNotEquals(getDeprecatedAccessTokenString(tokenService, newToken), getDeprecatedAccessTokenString(tokenService, token));

        metaData = tokenService.pruneKeys(1);
        tokenService.refreshMetaData(metaData);

        try (ThreadContext.StoredContext ignore = requestContext.newStoredContext(true)) {
            PlainActionFuture<UserToken> future = new PlainActionFuture<>();
            tokenService.getAndValidateToken(requestContext, future);
            UserToken serialized = future.get();
            assertNull(serialized);
        }

        requestContext = new ThreadContext(Settings.EMPTY);
        storeTokenHeader(requestContext, getDeprecatedAccessTokenString(tokenService, newToken));
        mockGetTokenFromId(newToken, false);
        try (ThreadContext.StoredContext ignore = requestContext.newStoredContext(true)) {
            PlainActionFuture<UserToken> future = new PlainActionFuture<>();
            tokenService.getAndValidateToken(requestContext, future);
            UserToken serialized = future.get();
            assertAuthentication(authentication, serialized.getAuthentication());
        }

    }

    public void testPassphraseWorks() throws Exception {
        TokenService tokenService = createTokenService(tokenServiceEnabledSettings, systemUTC());
        Authentication authentication = new Authentication(new User("joe", "admin"), new RealmRef("native_realm", "native", "node1"), null);
        PlainActionFuture<Tuple<UserToken, String>> tokenFuture = new PlainActionFuture<>();
        tokenService.createOAuth2Tokens(authentication, authentication, Collections.emptyMap(), true, tokenFuture);
        final UserToken token = tokenFuture.get().v1();
        assertNotNull(token);
        mockGetTokenFromId(token, false);
        authentication = token.getAuthentication();

        ThreadContext requestContext = new ThreadContext(Settings.EMPTY);
        storeTokenHeader(requestContext, getDeprecatedAccessTokenString(tokenService, token));

        try (ThreadContext.StoredContext ignore = requestContext.newStoredContext(true)) {
            PlainActionFuture<UserToken> future = new PlainActionFuture<>();
            tokenService.getAndValidateToken(requestContext, future);
            UserToken serialized = future.get();
            assertAuthentication(authentication, serialized.getAuthentication());
        }

        try (ThreadContext.StoredContext ignore = requestContext.newStoredContext(true)) {
            // verify a second separate token service with its own passphrase cannot verify
            TokenService anotherService = createTokenService(tokenServiceEnabledSettings, systemUTC());
            PlainActionFuture<UserToken> future = new PlainActionFuture<>();
            anotherService.getAndValidateToken(requestContext, future);
            assertNull(future.get());
        }
    }

    public void testGetTokenWhenKeyCacheHasExpired() throws Exception {
        TokenService tokenService = createTokenService(tokenServiceEnabledSettings, systemUTC());
        Authentication authentication = new Authentication(new User("joe", "admin"), new RealmRef("native_realm", "native", "node1"), null);

        PlainActionFuture<Tuple<UserToken, String>> tokenFuture = new PlainActionFuture<>();
        tokenService.createOAuth2Tokens(authentication, authentication, Collections.emptyMap(), true, tokenFuture);
        UserToken token = tokenFuture.get().v1();
        assertThat(getDeprecatedAccessTokenString(tokenService, token), notNullValue());

        tokenService.clearActiveKeyCache();
        assertThat(getDeprecatedAccessTokenString(tokenService, token), notNullValue());
    }

    public void testInvalidatedToken() throws Exception {
        when(securityMainIndex.indexExists()).thenReturn(true);
        TokenService tokenService = createTokenService(tokenServiceEnabledSettings, systemUTC());
        Authentication authentication = new Authentication(new User("joe", "admin"), new RealmRef("native_realm", "native", "node1"), null);
        PlainActionFuture<Tuple<UserToken, String>> tokenFuture = new PlainActionFuture<>();
        tokenService.createOAuth2Tokens(authentication, authentication, Collections.emptyMap(), true, tokenFuture);
        final UserToken token = tokenFuture.get().v1();
        assertNotNull(token);
        mockGetTokenFromId(token, true);

        ThreadContext requestContext = new ThreadContext(Settings.EMPTY);
        storeTokenHeader(requestContext, tokenService.getAccessTokenAsString(token));

        try (ThreadContext.StoredContext ignore = requestContext.newStoredContext(true)) {
            PlainActionFuture<UserToken> future = new PlainActionFuture<>();
            tokenService.getAndValidateToken(requestContext, future);
            ElasticsearchSecurityException e = expectThrows(ElasticsearchSecurityException.class, future::actionGet);
            final String headerValue = e.getHeader("WWW-Authenticate").get(0);
            assertThat(headerValue, containsString("Bearer realm="));
            assertThat(headerValue, containsString("expired"));
        }
    }

    private void storeTokenHeader(ThreadContext requestContext, String tokenString) throws IOException, GeneralSecurityException {
        requestContext.putHeader("Authorization", "Bearer " + tokenString);
    }

    public void testComputeSecretKeyIsConsistent() throws Exception {
        byte[] saltArr = new byte[32];
        random().nextBytes(saltArr);
        SecretKey key = TokenService.computeSecretKey("some random passphrase".toCharArray(), saltArr);
        SecretKey key2 = TokenService.computeSecretKey("some random passphrase".toCharArray(), saltArr);
        assertArrayEquals(key.getEncoded(), key2.getEncoded());
    }

    public void testTokenExpiryConfig() {
        TimeValue expiration =  TokenService.TOKEN_EXPIRATION.get(tokenServiceEnabledSettings);
        assertThat(expiration, equalTo(TimeValue.timeValueMinutes(20L)));
        // Configure Minimum expiration
        tokenServiceEnabledSettings = Settings.builder().put(TokenService.TOKEN_EXPIRATION.getKey(), "1s").build();
        expiration =  TokenService.TOKEN_EXPIRATION.get(tokenServiceEnabledSettings);
        assertThat(expiration, equalTo(TimeValue.timeValueSeconds(1L)));
        // Configure Maximum expiration
        tokenServiceEnabledSettings = Settings.builder().put(TokenService.TOKEN_EXPIRATION.getKey(), "60m").build();
        expiration =  TokenService.TOKEN_EXPIRATION.get(tokenServiceEnabledSettings);
        assertThat(expiration, equalTo(TimeValue.timeValueHours(1L)));
        // Outside range should fail
        tokenServiceEnabledSettings = Settings.builder().put(TokenService.TOKEN_EXPIRATION.getKey(), "1ms").build();
        IllegalArgumentException ile = expectThrows(IllegalArgumentException.class,
                () -> TokenService.TOKEN_EXPIRATION.get(tokenServiceEnabledSettings));
        assertThat(ile.getMessage(),
                containsString("failed to parse value [1ms] for setting [xpack.security.authc.token.timeout], must be >= [1s]"));
        tokenServiceEnabledSettings = Settings.builder().put(TokenService.TOKEN_EXPIRATION.getKey(), "120m").build();
        ile = expectThrows(IllegalArgumentException.class, () -> TokenService.TOKEN_EXPIRATION.get(tokenServiceEnabledSettings));
        assertThat(ile.getMessage(),
                containsString("failed to parse value [120m] for setting [xpack.security.authc.token.timeout], must be <= [1h]"));
    }

    public void testTokenExpiry() throws Exception {
        ClockMock clock = ClockMock.frozen();
        TokenService tokenService = createTokenService(tokenServiceEnabledSettings, clock);
        Authentication authentication = new Authentication(new User("joe", "admin"), new RealmRef("native_realm", "native", "node1"), null);
        PlainActionFuture<Tuple<UserToken, String>> tokenFuture = new PlainActionFuture<>();
        tokenService.createOAuth2Tokens(authentication, authentication, Collections.emptyMap(), true, tokenFuture);
        final UserToken token = tokenFuture.get().v1();
        mockGetTokenFromId(token, false);
        authentication = token.getAuthentication();

        ThreadContext requestContext = new ThreadContext(Settings.EMPTY);
        storeTokenHeader(requestContext, tokenService.getAccessTokenAsString(token));

        try (ThreadContext.StoredContext ignore = requestContext.newStoredContext(true)) {
            // the clock is still frozen, so the cookie should be valid
            PlainActionFuture<UserToken> future = new PlainActionFuture<>();
            tokenService.getAndValidateToken(requestContext, future);
            assertAuthentication(authentication, future.get().getAuthentication());
        }

        final TimeValue defaultExpiration = TokenService.TOKEN_EXPIRATION.get(Settings.EMPTY);
        final int fastForwardAmount = randomIntBetween(1, Math.toIntExact(defaultExpiration.getSeconds()) - 5);
        try (ThreadContext.StoredContext ignore = requestContext.newStoredContext(true)) {
            // move the clock forward but don't go to expiry
            clock.fastForwardSeconds(fastForwardAmount);
            PlainActionFuture<UserToken> future = new PlainActionFuture<>();
            tokenService.getAndValidateToken(requestContext, future);
            assertAuthentication(authentication, future.get().getAuthentication());
        }

        try (ThreadContext.StoredContext ignore = requestContext.newStoredContext(true)) {
            // move to expiry
            clock.fastForwardSeconds(Math.toIntExact(defaultExpiration.getSeconds()) - fastForwardAmount);
            clock.rewind(TimeValue.timeValueNanos(clock.instant().getNano())); // trim off nanoseconds since don't store them in the index
            PlainActionFuture<UserToken> future = new PlainActionFuture<>();
            tokenService.getAndValidateToken(requestContext, future);
            assertAuthentication(authentication, future.get().getAuthentication());
        }

        try (ThreadContext.StoredContext ignore = requestContext.newStoredContext(true)) {
            // move one second past expiry
            clock.fastForwardSeconds(1);
            PlainActionFuture<UserToken> future = new PlainActionFuture<>();
            tokenService.getAndValidateToken(requestContext, future);
            ElasticsearchSecurityException e = expectThrows(ElasticsearchSecurityException.class, future::actionGet);
            final String headerValue = e.getHeader("WWW-Authenticate").get(0);
            assertThat(headerValue, containsString("Bearer realm="));
            assertThat(headerValue, containsString("expired"));
        }
    }

    public void testTokenServiceDisabled() throws Exception {
        TokenService tokenService = new TokenService(Settings.builder()
                .put(XPackSettings.TOKEN_SERVICE_ENABLED_SETTING.getKey(), false)
                .build(),
                Clock.systemUTC(), client, licenseState, securityMainIndex, securityTokensIndex, clusterService);
        IllegalStateException e = expectThrows(IllegalStateException.class,
            () -> tokenService.createOAuth2Tokens(null, null, null, true, null));
        assertEquals("security tokens are not enabled", e.getMessage());

        PlainActionFuture<UserToken> future = new PlainActionFuture<>();
        tokenService.getAndValidateToken(null, future);
        assertNull(future.get());

        e = expectThrows(IllegalStateException.class, () -> {
            PlainActionFuture<TokensInvalidationResult> invalidateFuture = new PlainActionFuture<>();
            tokenService.invalidateAccessToken((String) null, invalidateFuture);
            invalidateFuture.actionGet();
        });
        assertEquals("security tokens are not enabled", e.getMessage());
    }

    public void testBytesKeyEqualsHashCode() {
        final int dataLength = randomIntBetween(2, 32);
        final byte[] data = randomBytes(dataLength);
        BytesKey bytesKey = new BytesKey(data);
        EqualsHashCodeTestUtils.checkEqualsAndHashCode(bytesKey, (b) -> new BytesKey(b.bytes.clone()), (b) -> {
            final byte[] copy = b.bytes.clone();
            final int randomlyChangedValue = randomIntBetween(0, copy.length - 1);
            final byte original = copy[randomlyChangedValue];
            boolean loop;
            do {
                byte value = randomByte();
                if (value == original) {
                    loop = true;
                } else {
                    loop = false;
                    copy[randomlyChangedValue] = value;
                }
            } while (loop);
            return new BytesKey(copy);
        });
    }

    public void testMalformedToken() throws Exception {
        final int numBytes = randomIntBetween(1, TokenService.MINIMUM_BYTES + 32);
        final byte[] randomBytes = new byte[numBytes];
        random().nextBytes(randomBytes);
        TokenService tokenService = createTokenService(Settings.EMPTY, systemUTC());

        ThreadContext requestContext = new ThreadContext(Settings.EMPTY);
        storeTokenHeader(requestContext, Base64.getEncoder().encodeToString(randomBytes));

        try (ThreadContext.StoredContext ignore = requestContext.newStoredContext(true)) {
            PlainActionFuture<UserToken> future = new PlainActionFuture<>();
            tokenService.getAndValidateToken(requestContext, future);
            assertNull(future.get());
        }
    }

    public void testIndexNotAvailable() throws Exception {
        TokenService tokenService = createTokenService(tokenServiceEnabledSettings, systemUTC());
        Authentication authentication = new Authentication(new User("joe", "admin"), new RealmRef("native_realm", "native", "node1"), null);
        PlainActionFuture<Tuple<UserToken, String>> tokenFuture = new PlainActionFuture<>();
        tokenService.createOAuth2Tokens(authentication, authentication, Collections.emptyMap(), true, tokenFuture);
        final UserToken token = tokenFuture.get().v1();
        assertNotNull(token);
        //mockGetTokenFromId(token, false);

        ThreadContext requestContext = new ThreadContext(Settings.EMPTY);
        storeTokenHeader(requestContext, tokenService.getAccessTokenAsString(token));

        doAnswer(invocationOnMock -> {
            ActionListener<GetResponse> listener = (ActionListener<GetResponse>) invocationOnMock.getArguments()[1];
            listener.onFailure(new NoShardAvailableActionException(new ShardId(new Index("foo", "uuid"), 0), "shard oh shard"));
            return Void.TYPE;
        }).when(client).get(any(GetRequest.class), any(ActionListener.class));

        final SecurityIndexManager tokensIndex;
        if (oldNode != null) {
            tokensIndex = securityMainIndex;
            when(securityTokensIndex.isAvailable()).thenReturn(false);
            when(securityTokensIndex.indexExists()).thenReturn(false);
        } else {
            tokensIndex = securityTokensIndex;
            when(securityMainIndex.isAvailable()).thenReturn(false);
            when(securityMainIndex.indexExists()).thenReturn(false);
        }
        try (ThreadContext.StoredContext ignore = requestContext.newStoredContext(true)) {
            PlainActionFuture<UserToken> future = new PlainActionFuture<>();
            tokenService.getAndValidateToken(requestContext, future);
            assertNull(future.get());

            when(tokensIndex.isAvailable()).thenReturn(false);
            when(tokensIndex.indexExists()).thenReturn(true);
            future = new PlainActionFuture<>();
            tokenService.getAndValidateToken(requestContext, future);
            assertNull(future.get());

            when(tokensIndex.indexExists()).thenReturn(false);
            future = new PlainActionFuture<>();
            tokenService.getAndValidateToken(requestContext, future);
            assertNull(future.get());

            when(tokensIndex.isAvailable()).thenReturn(true);
            when(tokensIndex.indexExists()).thenReturn(true);
            mockGetTokenFromId(token, false);
            future = new PlainActionFuture<>();
            tokenService.getAndValidateToken(requestContext, future);
            assertEquals(future.get().getAuthentication(), token.getAuthentication());
        }
    }

    public void testGetAuthenticationWorksWithExpiredUserToken() throws Exception {
        TokenService tokenService = createTokenService(tokenServiceEnabledSettings, Clock.systemUTC());
        Authentication authentication = new Authentication(new User("joe", "admin"), new RealmRef("native_realm", "native", "node1"), null);
        UserToken expired = new UserToken(authentication, Instant.now().minus(3L, ChronoUnit.DAYS));
        mockGetTokenFromId(expired, false);
        String userTokenString = tokenService.getAccessTokenAsString(expired);
        PlainActionFuture<Tuple<Authentication, Map<String, Object>>> authFuture = new PlainActionFuture<>();
        tokenService.getAuthenticationAndMetaData(userTokenString, authFuture);
        Authentication retrievedAuth = authFuture.actionGet().v1();
        assertEquals(authentication, retrievedAuth);
    }

    public void testCannotValidateTokenIfLicenseDoesNotAllowTokens() throws Exception {
        when(licenseState.isTokenServiceAllowed()).thenReturn(true);
        TokenService tokenService = createTokenService(tokenServiceEnabledSettings, Clock.systemUTC());
        Authentication authentication = new Authentication(new User("joe", "admin"), new RealmRef("native_realm", "native", "node1"), null);
        UserToken token = new UserToken(authentication, Instant.now().plusSeconds(180));
        mockGetTokenFromId(token, false);

        final ThreadContext threadContext = new ThreadContext(Settings.EMPTY);
        storeTokenHeader(threadContext, tokenService.getAccessTokenAsString(token));

        PlainActionFuture<UserToken> authFuture = new PlainActionFuture<>();
        when(licenseState.isTokenServiceAllowed()).thenReturn(false);
        tokenService.getAndValidateToken(threadContext, authFuture);
        UserToken authToken = authFuture.actionGet();
        assertThat(authToken, Matchers.nullValue());
    }

    private TokenService createTokenService(Settings settings, Clock clock) throws GeneralSecurityException {
        return new TokenService(settings, clock, client, licenseState, securityMainIndex, securityTokensIndex, clusterService);
    }

    private void mockGetTokenFromId(UserToken userToken, boolean isExpired) {
        mockGetTokenFromId(userToken, isExpired, client);
    }

    public static void mockGetTokenFromId(UserToken userToken, boolean isExpired, Client client) {
        doAnswer(invocationOnMock -> {
            GetRequest request = (GetRequest) invocationOnMock.getArguments()[0];
            ActionListener<GetResponse> listener = (ActionListener<GetResponse>) invocationOnMock.getArguments()[1];
            GetResponse response = mock(GetResponse.class);
            if (userToken.getId().equals(request.id().replace("token_", ""))) {
                when(response.isExists()).thenReturn(true);
                Map<String, Object> sourceMap = new HashMap<>();
                try (XContentBuilder builder = XContentBuilder.builder(XContentType.JSON.xContent())) {
                    userToken.toXContent(builder, ToXContent.EMPTY_PARAMS);
                    Map<String, Object> accessTokenMap = new HashMap<>();
                    accessTokenMap.put("user_token",
                        XContentHelper.convertToMap(XContentType.JSON.xContent(), Strings.toString(builder), false));
                    accessTokenMap.put("invalidated", isExpired);
                    sourceMap.put("access_token", accessTokenMap);
                }
                when(response.getSource()).thenReturn(sourceMap);
            }
            listener.onResponse(response);
            return Void.TYPE;
        }).when(client).get(any(GetRequest.class), any(ActionListener.class));
    }

    public static void assertAuthentication(Authentication result, Authentication expected) {
        assertEquals(expected.getUser(), result.getUser());
        assertEquals(expected.getAuthenticatedBy(), result.getAuthenticatedBy());
        assertEquals(expected.getLookedUpBy(), result.getLookedUpBy());
        assertEquals(expected.getMetadata(), result.getMetadata());
        assertEquals(AuthenticationType.TOKEN, result.getAuthenticationType());
    }

    protected String getDeprecatedAccessTokenString(TokenService tokenService, UserToken userToken) throws IOException,
        GeneralSecurityException {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream(TokenService.MINIMUM_BASE64_BYTES);
             OutputStream base64 = Base64.getEncoder().wrap(os);
             StreamOutput out = new OutputStreamStreamOutput(base64)) {
            out.setVersion(Version.V_7_0_0);
            TokenService.KeyAndCache keyAndCache = tokenService.getActiveKeyCache();
            Version.writeVersion(Version.V_7_0_0, out);
            out.writeByteArray(keyAndCache.getSalt().bytes);
            out.writeByteArray(keyAndCache.getKeyHash().bytes);
            final byte[] initializationVector = tokenService.getNewInitializationVector();
            out.writeByteArray(initializationVector);
            try (CipherOutputStream encryptedOutput =
                     new CipherOutputStream(out, tokenService.getEncryptionCipher(initializationVector, keyAndCache, Version.V_7_0_0));
                 StreamOutput encryptedStreamOutput = new OutputStreamStreamOutput(encryptedOutput)) {
                encryptedStreamOutput.setVersion(Version.V_7_0_0);
                encryptedStreamOutput.writeString(userToken.getId());
                encryptedStreamOutput.close();
                return new String(os.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }

    private DiscoveryNode addAnotherDataNodeWithVersion(ClusterService clusterService, Version version) {
        final ClusterState currentState = clusterService.state();
        final DiscoveryNodes.Builder discoBuilder = DiscoveryNodes.builder(currentState.getNodes());
        final DiscoveryNode anotherDataNode = new DiscoveryNode("another_data_node#" + version, buildNewFakeTransportAddress(),
                Collections.emptyMap(), Collections.singleton(DiscoveryNode.Role.DATA), version);
        discoBuilder.add(anotherDataNode);
        final ClusterState.Builder newStateBuilder = ClusterState.builder(currentState);
        newStateBuilder.nodes(discoBuilder);
        setState(clusterService, newStateBuilder.build());
        return anotherDataNode;
    }

}
/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.audit.index;

import java.net.InetAddress;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.elasticsearch.action.Action;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionRequestBuilder;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.MockTransportClient;
import org.elasticsearch.transport.TransportMessage;
import org.elasticsearch.xpack.security.InternalClient;
import org.elasticsearch.xpack.security.audit.index.IndexAuditTrail.State;
import org.elasticsearch.xpack.security.authc.AuthenticationToken;
import org.elasticsearch.xpack.security.transport.filter.SecurityIpFilterRule;
import org.elasticsearch.xpack.security.user.SystemUser;
import org.elasticsearch.xpack.security.user.User;
import org.junit.After;
import org.junit.Before;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

public class IndexAuditTrailMutedTests extends ESTestCase {

    private InternalClient client;
    private TransportClient transportClient;
    private ThreadPool threadPool;
    private ClusterService clusterService;
    private IndexAuditTrail auditTrail;

    private AtomicBoolean messageEnqueued;
    private AtomicBoolean clientCalled;

    @Before
    public void setup() {
        DiscoveryNode localNode = mock(DiscoveryNode.class);
        when(localNode.getHostAddress()).thenReturn(buildNewFakeTransportAddress().toString());
        clusterService = mock(ClusterService.class);
        when(clusterService.localNode()).thenReturn(localNode);

        threadPool = new TestThreadPool("index audit trail tests");
        transportClient = new MockTransportClient(Settings.EMPTY);
        clientCalled = new AtomicBoolean(false);
        class IClient extends InternalClient {
           IClient(Client transportClient){
                super(Settings.EMPTY, null, transportClient, null);
           }
            @Override
            protected <Request extends ActionRequest, Response extends ActionResponse, RequestBuilder extends
                    ActionRequestBuilder<Request, Response, RequestBuilder>> void doExecute(
                    Action<Request, Response, RequestBuilder> action, Request request, ActionListener<Response> listener) {
                clientCalled.set(true);
            }
        }
        client = new IClient(transportClient);
        messageEnqueued = new AtomicBoolean(false);
    }

    @After
    public void stop() {
        if (auditTrail != null) {
            auditTrail.stop();
        }
        if (transportClient != null) {
            transportClient.close();
        }
        threadPool.shutdown();
    }

    public void testAnonymousAccessDeniedMutedTransport() {
        createAuditTrail(new String[] { "anonymous_access_denied" });
        TransportMessage message = mock(TransportMessage.class);
        auditTrail.anonymousAccessDenied("_action", message);
        assertThat(messageEnqueued.get(), is(false));
        assertThat(clientCalled.get(), is(false));

        verifyZeroInteractions(message);
    }

    public void testAnonymousAccessDeniedMutedRest() {
        createAuditTrail(new String[] { "anonymous_access_denied" });
        RestRequest restRequest = mock(RestRequest.class);
        auditTrail.anonymousAccessDenied(restRequest);
        assertThat(messageEnqueued.get(), is(false));
        assertThat(clientCalled.get(), is(false));

        verifyZeroInteractions(restRequest);
    }

    public void testAuthenticationFailedMutedTransport() {
        createAuditTrail(new String[] { "authentication_failed" });
        TransportMessage message = mock(TransportMessage.class);
        AuthenticationToken token = mock(AuthenticationToken.class);

        // without realm
        auditTrail.authenticationFailed(token, "_action", message);
        assertThat(messageEnqueued.get(), is(false));
        assertThat(clientCalled.get(), is(false));

        // without the token
        auditTrail.authenticationFailed("_action", message);
        assertThat(messageEnqueued.get(), is(false));
        assertThat(clientCalled.get(), is(false));

        verifyZeroInteractions(token, message);
    }

    public void testAuthenticationFailedMutedRest() {
        createAuditTrail(new String[] { "authentication_failed" });
        RestRequest restRequest = mock(RestRequest.class);
        AuthenticationToken token = mock(AuthenticationToken.class);

        // without the realm
        auditTrail.authenticationFailed(token, restRequest);
        assertThat(messageEnqueued.get(), is(false));
        assertThat(clientCalled.get(), is(false));

        // without the token
        auditTrail.authenticationFailed(restRequest);
        assertThat(messageEnqueued.get(), is(false));
        assertThat(clientCalled.get(), is(false));

        verifyZeroInteractions(token, restRequest);
    }

    public void testAuthenticationFailedRealmMutedTransport() {
        createAuditTrail(new String[] { "realm_authentication_failed" });
        TransportMessage message = mock(TransportMessage.class);
        AuthenticationToken token = mock(AuthenticationToken.class);

        // with realm
        auditTrail.authenticationFailed(randomAsciiOfLengthBetween(2, 10), token, "_action", message);
        assertThat(messageEnqueued.get(), is(false));
        assertThat(clientCalled.get(), is(false));

        verifyZeroInteractions(token, message);
    }

    public void testAuthenticationFailedRealmMutedRest() {
        createAuditTrail(new String[]{"realm_authentication_failed"});
        RestRequest restRequest = mock(RestRequest.class);
        AuthenticationToken token = mock(AuthenticationToken.class);

        // with realm
        auditTrail.authenticationFailed(randomAsciiOfLengthBetween(2, 10), token, restRequest);
        assertThat(messageEnqueued.get(), is(false));
        assertThat(clientCalled.get(), is(false));
        verifyZeroInteractions(token, restRequest);
    }

    public void testAccessGrantedMuted() {
        createAuditTrail(new String[] { "access_granted" });
        TransportMessage message = mock(TransportMessage.class);
        User user = mock(User.class);
        auditTrail.accessGranted(user, randomAsciiOfLengthBetween(6, 40), message);
        assertThat(messageEnqueued.get(), is(false));
        assertThat(clientCalled.get(), is(false));

        verifyZeroInteractions(message, user);
    }

    public void testSystemAccessGrantedMuted() {
        createAuditTrail(randomFrom(new String[] { "access_granted" }, null));
        TransportMessage message = mock(TransportMessage.class);
        User user = SystemUser.INSTANCE;
        auditTrail.accessGranted(user, "internal:foo", message);
        assertThat(messageEnqueued.get(), is(false));
        assertThat(clientCalled.get(), is(false));

        verifyZeroInteractions(message);
    }

    public void testAccessDeniedMuted() {
        createAuditTrail(new String[] { "access_denied" });
        TransportMessage message = mock(TransportMessage.class);
        User user = mock(User.class);
        auditTrail.accessDenied(user, randomAsciiOfLengthBetween(6, 40), message);
        assertThat(messageEnqueued.get(), is(false));
        assertThat(clientCalled.get(), is(false));

        verifyZeroInteractions(message, user);
    }

    public void testTamperedRequestMuted() {
        createAuditTrail(new String[] { "tampered_request" });
        TransportMessage message = mock(TransportMessage.class);
        User user = mock(User.class);

        // with user
        auditTrail.tamperedRequest(user, randomAsciiOfLengthBetween(6, 40), message);
        assertThat(messageEnqueued.get(), is(false));
        assertThat(clientCalled.get(), is(false));

        // without user
        auditTrail.tamperedRequest(randomAsciiOfLengthBetween(6, 40), message);
        assertThat(messageEnqueued.get(), is(false));
        assertThat(clientCalled.get(), is(false));

        verifyZeroInteractions(message, user);
    }

    public void testConnectionGrantedMuted() {
        createAuditTrail(new String[] { "connection_granted" });
        InetAddress address = mock(InetAddress.class);
        SecurityIpFilterRule rule = mock(SecurityIpFilterRule.class);

        auditTrail.connectionGranted(address, randomAsciiOfLengthBetween(1, 12), rule);
        assertThat(messageEnqueued.get(), is(false));
        assertThat(clientCalled.get(), is(false));

        verifyZeroInteractions(address, rule);
    }

    public void testConnectionDeniedMuted() {
        createAuditTrail(new String[] { "connection_denied" });
        InetAddress address = mock(InetAddress.class);
        SecurityIpFilterRule rule = mock(SecurityIpFilterRule.class);

        auditTrail.connectionDenied(address, randomAsciiOfLengthBetween(1, 12), rule);
        assertThat(messageEnqueued.get(), is(false));
        assertThat(clientCalled.get(), is(false));

        verifyZeroInteractions(address, rule);
    }

    public void testRunAsGrantedMuted() {
        createAuditTrail(new String[] { "run_as_granted" });
        TransportMessage message = mock(TransportMessage.class);
        User user = mock(User.class);

        auditTrail.runAsGranted(user, randomAsciiOfLengthBetween(6, 40), message);
        assertThat(messageEnqueued.get(), is(false));
        assertThat(clientCalled.get(), is(false));

        verifyZeroInteractions(message, user);
    }

    public void testRunAsDeniedMuted() {
        createAuditTrail(new String[] { "run_as_denied" });
        TransportMessage message = mock(TransportMessage.class);
        User user = mock(User.class);

        auditTrail.runAsDenied(user, randomAsciiOfLengthBetween(6, 40), message);
        assertThat(messageEnqueued.get(), is(false));
        assertThat(clientCalled.get(), is(false));

        verifyZeroInteractions(message, user);
    }

    public void testAuthenticationSuccessRest() {
        createAuditTrail(new String[] { "authentication_success" });
        RestRequest restRequest = mock(RestRequest.class);
        User user = mock(User.class);
        String realm = "_realm";

        auditTrail.authenticationSuccess(realm, user, restRequest);
        assertThat(messageEnqueued.get(), is(false));
        assertThat(clientCalled.get(), is(false));

        verifyZeroInteractions(restRequest);
    }

    public void testAuthenticationSuccessTransport() {
        createAuditTrail(new String[] { "authentication_success" });
        TransportMessage message = mock(TransportMessage.class);
        User user = mock(User.class);
        String realm = "_realm";
        auditTrail.authenticationSuccess(realm, user, randomAsciiOfLengthBetween(6, 40), message);
        assertThat(messageEnqueued.get(), is(false));
        assertThat(clientCalled.get(), is(false));

        verifyZeroInteractions(message, user);
    }

    IndexAuditTrail createAuditTrail(String[] excludes) {
        Settings settings = IndexAuditTrailTests.levelSettings(null, excludes);
        auditTrail = new IndexAuditTrail(settings, client, threadPool, clusterService) {
            @Override
            void putTemplate(Settings settings, ActionListener<Void> listener) {
                // make this a no-op so we don't have to stub out unnecessary client activities
                listener.onResponse(null);
            }

            @Override
            BlockingQueue<Message> createQueue(int maxQueueSize) {
                return new LinkedBlockingQueue<Message>(maxQueueSize) {
                    @Override
                    public boolean offer(Message message) {
                        messageEnqueued.set(true);
                        return super.offer(message);
                    }
                };
            }
        };
        auditTrail.start(true);
        assertThat(auditTrail.state(), is(State.STARTED));
        return auditTrail;
    }
}
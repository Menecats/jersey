/*
 * Copyright (c) 2022 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.jersey.apache5.connector;

import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.logging.Logger;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientRequestFilter;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.DynamicFeature;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.FeatureContext;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.logging.LoggingFeature;
import org.glassfish.jersey.server.ClientBinding;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.Uri;
import org.glassfish.jersey.test.JerseyTest;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * Jersey programmatic managed client test
 *
 * @author Marek Potociar
 */
public class ManagedClientTest extends JerseyTest {

    private static final Logger LOGGER = Logger.getLogger(ManagedClientTest.class.getName());

    /**
     * Managed client configuration for client A.
     *
     * @author Marek Potociar (marek.potociar at oracle.com)
     */
    @ClientBinding(configClass = MyClientAConfig.class)
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.PARAMETER})
    public static @interface ClientA {
    }

    /**
     * Managed client configuration for client B.
     *
     * @author Marek Potociar (marek.potociar at oracle.com)
     */
    @ClientBinding(configClass = MyClientBConfig.class)
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.PARAMETER})
    public @interface ClientB {
    }

    /**
     * Dynamic feature that appends a properly configured {@link CustomHeaderFilter} instance
     * to every method that is annotated with {@link Require &#64;Require} internal feature
     * annotation.
     *
     * @author Marek Potociar
     */
    public static class CustomHeaderFeature implements DynamicFeature {

        /**
         * A method annotation to be placed on those resource methods to which a validating
         * {@link CustomHeaderFilter} instance should be added.
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Documented
        @Target(ElementType.METHOD)
        public static @interface Require {

            /**
             * Expected custom header name to be validated by the {@link CustomHeaderFilter}.
             */
            public String headerName();

            /**
             * Expected custom header value to be validated by the {@link CustomHeaderFilter}.
             */
            public String headerValue();
        }

        @Override
        public void configure(ResourceInfo resourceInfo, FeatureContext context) {
            final Require va = resourceInfo.getResourceMethod().getAnnotation(Require.class);
            if (va != null) {
                context.register(new CustomHeaderFilter(va.headerName(), va.headerValue()));
            }
        }
    }

    /**
     * A filter for appending and validating custom headers.
     * <p>
     * On the client side, appends a new custom request header with a configured name and value to each outgoing request.
     * </p>
     * <p>
     * On the server side, validates that each request has a custom header with a configured name and value.
     * If the validation fails a HTTP 403 response is returned.
     * </p>
     *
     * @author Marek Potociar (marek.potociar at oracle.com)
     */
    public static class CustomHeaderFilter implements ContainerRequestFilter, ClientRequestFilter {

        private final String headerName;
        private final String headerValue;

        public CustomHeaderFilter(String headerName, String headerValue) {
            if (headerName == null || headerValue == null) {
                throw new IllegalArgumentException("Header name and value must not be null.");
            }
            this.headerName = headerName;
            this.headerValue = headerValue;
        }

        @Override
        public void filter(ContainerRequestContext ctx) throws IOException { // validate
            if (!headerValue.equals(ctx.getHeaderString(headerName))) {
                ctx.abortWith(Response.status(Response.Status.FORBIDDEN)
                        .type(MediaType.TEXT_PLAIN)
                        .entity(String
                                .format("Expected header '%s' not present or value not equal to '%s'", headerName, headerValue))
                        .build());
            }
        }

        @Override
        public void filter(ClientRequestContext ctx) throws IOException { // append
            ctx.getHeaders().putSingle(headerName, headerValue);
        }
    }

    /**
     * Internal resource accessed from the managed client resource.
     *
     * @author Marek Potociar (marek.potociar at oracle.com)
     */
    @Path("internal")
    public static class InternalResource {

        @GET
        @Path("a")
        @CustomHeaderFeature.Require(headerName = "custom-header", headerValue = "a")
        public String getA() {
            return "a";
        }

        @GET
        @Path("b")
        @CustomHeaderFeature.Require(headerName = "custom-header", headerValue = "b")
        public String getB() {
            return "b";
        }
    }

    /**
     * A resource that uses managed clients to retrieve values of internal
     * resources 'A' and 'B', which are protected by a {@link CustomHeaderFilter}
     * and require a specific custom header in a request to be set to a specific value.
     * <p>
     * Properly configured managed clients have a {@code CustomHeaderFilter} instance
     * configured to insert the {@link CustomHeaderFeature.Require required} custom header
     * with a proper value into the outgoing client requests.
     * </p>
     *
     * @author Marek Potociar (marek.potociar at oracle.com)
     */
    @Path("public")
    public static class PublicResource {

        @Uri("a")
        @ClientA // resolves to <base>/internal/a
        private WebTarget targetA;

        @GET
        @Produces("text/plain")
        @Path("a")
        public String getTargetA() {
            return targetA.request(MediaType.TEXT_PLAIN).get(String.class);
        }

        @GET
        @Produces("text/plain")
        @Path("b")
        public Response getTargetB(@Uri("internal/b") @ClientB WebTarget targetB) {
            return targetB.request(MediaType.TEXT_PLAIN).get();
        }
    }

    @Override
    protected Application configure() {
        ResourceConfig config = new ResourceConfig(PublicResource.class, InternalResource.class, CustomHeaderFeature.class)
                .property(ClientA.class.getName() + ".baseUri", this.getBaseUri().toString() + "internal");
        config.register(new LoggingFeature(LOGGER, LoggingFeature.Verbosity.PAYLOAD_ANY));
        return config;
    }

    public static class MyClientAConfig extends ClientConfig {

        public MyClientAConfig() {
            this.register(new CustomHeaderFilter("custom-header", "a"));
        }
    }

    public static class MyClientBConfig extends ClientConfig {

        public MyClientBConfig() {
            this.register(new CustomHeaderFilter("custom-header", "b"));
        }
    }

    @Override
    protected void configureClient(ClientConfig config) {
        config.connectorProvider(new Apache5ConnectorProvider());
    }

    /**
     * Test that a connection via managed clients works properly.
     *
     * @throws Exception in case of test failure.
     */
    @Test
    public void testManagedClient() throws Exception {
        final WebTarget resource = target().path("public").path("{name}");
        Response response;

        response = resource.resolveTemplate("name", "a").request(MediaType.TEXT_PLAIN).get();
        assertEquals(200, response.getStatus());
        assertEquals("a", response.readEntity(String.class));

        response = resource.resolveTemplate("name", "b").request(MediaType.TEXT_PLAIN).get();
        assertEquals(200, response.getStatus());
        assertEquals("b", response.readEntity(String.class));
    }

}

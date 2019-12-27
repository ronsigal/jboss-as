/*
 * Copyright (C) 2019 Red Hat, inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301  USA
 */
package org.jboss.as.test.integration.jaxrs.cfg;

import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation.Builder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.arquillian.api.ServerSetup;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.SimpleAttributeDefinition;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.as.jaxrs.JaxrsAttribute;
import org.jboss.as.test.integration.jaxrs.packaging.war.WebXml;
import org.jboss.as.test.integration.management.base.AbstractMgmtServerSetupTask;
import org.jboss.as.test.integration.management.base.ContainerResourceMgmtTestBase;
import org.jboss.as.test.integration.management.util.MgmtOperationException;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;
import org.jboss.resteasy.plugins.providers.StringTextStar;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.staxmapper.XMLElementReader;
import org.jboss.staxmapper.XMLElementWriter;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for setting RESTEasy context parameters using the Wildfly management model.
 *
 * @author <a href="rsigal@redhat.com">Ron Sigal</a>
 */
@RunWith(Arquillian.class)
@RunAsClient
@ServerSetup(ResteasyAttributeTestCase.AttributeTestCaseDeploymentSetup.class)
public class ResteasyAttributeTestCase extends ContainerResourceMgmtTestBase {

    private static ModelControllerClient modelControllerClient;
    private static final Map<SimpleAttributeDefinition, ModelNode> expectedValues = new HashMap<SimpleAttributeDefinition, ModelNode>();
    private static final Map<ModelType, Class<?>> modelTypeMap = new HashMap<ModelType, Class<?>>();
    private static Client jaxrsClient;

    @ArquillianResource
    private static URL url;

    static {
        modelTypeMap.put(ModelType.BOOLEAN, Boolean.class);
        modelTypeMap.put(ModelType.INT, Integer.class);
        modelTypeMap.put(ModelType.STRING, String.class);
    }

    private static final ModelNode ADDRESS = Operations.createAddress("subsystem", "jaxrs");
    private static final String OUTCOME = "outcome";
    private static final String SUCCESS = "success";
    private static final String FAILED = "failed";
    private static final String RESTEASY_MEDIA_TYPE_PARAM_MAPPING_CONTEXT_VALUE = "resteasy.media.type.param.mapping.context.value";

    static class AttributeTestCaseDeploymentSetup extends AbstractMgmtServerSetupTask {

        @Override
        public void doSetup(final ManagementClient managementClient) throws Exception {
            modelControllerClient = getModelControllerClient();
            setAttributeValues();
        }

        @Override
        public void tearDown(final ManagementClient managementClient, final String containerId) throws Exception {
            resetAttributeValues();
        }
    }
    /**
     * Define the deployment
     *
     * @return The deployment archive
     */
    @Deployment
    public static Archive<?> createDeployment() throws Exception {
        WebArchive war = ShrinkWrap.create(WebArchive.class, "jaxrsnoap.war");
        war.addClasses(Foo.class, Bar.class, Bar5.class, Bar6.class, ResteasyAttributeResource.class);
        war.addClasses(Provider1.class, Provider2.class, Provider5.class, Provider6.class);
        war.addClasses(EJB_Resource1.class, EJB_Resource2.class);
        war.addClasses(Resource1.class, Resource2.class);
        war.addClass(JaxrsAttribute.class);
        war.addClasses(XMLElementReader.class, XMLElementWriter.class);
        war.addPackage(MgmtOperationException.class.getPackage());
        war.addPackage(AttributeTestCaseDeploymentSetup.class.getPackage());
        war.addPackage(AbstractMgmtServerSetupTask.class.getPackage());
        war.addPackage(ContainerResourceMgmtTestBase.class.getPackage());
        war.addAsManifestResource(new StringAsset("Dependencies: org.jboss.as.jaxrs, org.jboss.as.controller, org.jboss.as.controller-client,org.jboss.dmr,org.jboss.as.cli,javax.inject.api,org.jboss.as.connector\n"), "MANIFEST.MF");

        // Put "resteasy.media.type.param.mapping" in web.xml to verify that it overrides value set in Wildfly management model.
        war.addAsWebInfResource(WebXml.get(
                        "<context-param>\n" +
                        "    <param-name>resteasy.media.type.param.mapping</param-name>\n" +
                        "    <param-value>" + RESTEASY_MEDIA_TYPE_PARAM_MAPPING_CONTEXT_VALUE + "</param-value>\n" +
                        "</context-param>" +
                        "<servlet-mapping>\n" +
                        "    <servlet-name>javax.ws.rs.core.Application</servlet-name>\n" +
                        "        <url-pattern>/myjaxrs/*</url-pattern>\n" +
                        "    </servlet-mapping>\n" +
                "\n"), "web.xml");
        return war;
    }

    @BeforeClass
    public static void beforeClass() throws IOException {
        jaxrsClient = ClientBuilder.newClient();
    }

    @AfterClass
    public static void afterClass() throws IOException {
        jaxrsClient.close();
    }

    //////////////////////////////////////////////////////////////////////////////////////
    /////////////////////            Configuration methods            ////////////////////
    //////////////////////////////////////////////////////////////////////////////////////
    /*
     * Set attributes to testable values.
     */
    static void setAttributeValues() throws IOException {
        setAttributeValue(JaxrsAttribute.JAXRS_2_0_REQUEST_MATCHING, changeBoolean(JaxrsAttribute.JAXRS_2_0_REQUEST_MATCHING.getDefaultValue()));
        setAttributeValue(JaxrsAttribute.RESTEASY_ADD_CHARSET, changeBoolean(JaxrsAttribute.RESTEASY_ADD_CHARSET.getDefaultValue()));
        setAttributeValue(JaxrsAttribute.RESTEASY_BUFFER_EXCEPTION_ENTITY, changeBoolean(JaxrsAttribute.RESTEASY_BUFFER_EXCEPTION_ENTITY.getDefaultValue()));
        setAttributeValue(JaxrsAttribute.RESTEASY_DISABLE_HTML_SANITIZER, changeBoolean(JaxrsAttribute.RESTEASY_DISABLE_HTML_SANITIZER.getDefaultValue()));
        setAttributeValue(JaxrsAttribute.RESTEASY_DISABLE_PROVIDERS, new ModelNode(Provider1.class.getName() + "," + Provider2.class.getName()));
        setAttributeValue(JaxrsAttribute.RESTEASY_DOCUMENT_EXPAND_ENTITY_REFERENCES, changeBoolean(JaxrsAttribute.RESTEASY_DOCUMENT_EXPAND_ENTITY_REFERENCES.getDefaultValue()));
        setAttributeValue(JaxrsAttribute.RESTEASY_DOCUMENT_SECURE_DISABLE_DTDS, changeBoolean(JaxrsAttribute.RESTEASY_DOCUMENT_SECURE_DISABLE_DTDS.getDefaultValue()));
        setAttributeValue(JaxrsAttribute.RESTEASY_DOCUMENT_SECURE_PROCESSING_FEATURE, changeBoolean(JaxrsAttribute.RESTEASY_DOCUMENT_SECURE_PROCESSING_FEATURE.getDefaultValue()));
        setAttributeValue(JaxrsAttribute.RESTEASY_GZIP_MAX_INPUT, changeInteger(JaxrsAttribute.RESTEASY_GZIP_MAX_INPUT.getDefaultValue()));
//        setAttributeValue(JaxrsAttribute.RESTEASY_JNDI_RESOURCES, new ModelNode("java:global/jaxrsnoap/" + EJB_Resource1.class.getSimpleName() + ", java:global/jaxrsnoap/" + EJB_Resource2.class.getSimpleName() + ""));
        setAttributeValue(JaxrsAttribute.RESTEASY_LANGUAGE_MAPPINGS, new ModelNode("en : en-US , es : es, fr : fr"));
        setAttributeValue(JaxrsAttribute.RESTEASY_MEDIA_TYPE_MAPPINGS, new ModelNode("unusual : text/unusual, xml : application/xml"));
        setAttributeValue(JaxrsAttribute.RESTEASY_MEDIA_TYPE_PARAM_MAPPING, new ModelNode("abyz"));
        setAttributeValue(JaxrsAttribute.RESTEASY_PROVIDERS, new ModelNode(Provider5.class.getName() + ", " + Provider6.class.getName() + " , " + StringTextStar.class.getName()));
        setAttributeValue(JaxrsAttribute.RESTEASY_RESOURCES, new ModelNode(Resource1.class.getName() + "," + Resource2.class.getName()));
        setAttributeValue(JaxrsAttribute.RESTEASY_RFC7232_PRECONDITIONS, changeBoolean(JaxrsAttribute.RESTEASY_RFC7232_PRECONDITIONS.getDefaultValue()));
        setAttributeValue(JaxrsAttribute.RESTEASY_ROLE_BASED_SECURITY, changeBoolean(JaxrsAttribute.RESTEASY_ROLE_BASED_SECURITY.getDefaultValue()));
        setAttributeValue(JaxrsAttribute.RESTEASY_SECURE_RANDOM_MAX_USE, changeInteger(JaxrsAttribute.RESTEASY_SECURE_RANDOM_MAX_USE.getDefaultValue()));
        setAttributeValue(JaxrsAttribute.RESTEASY_USE_BUILTIN_PROVIDERS, changeBoolean(JaxrsAttribute.RESTEASY_USE_BUILTIN_PROVIDERS.getDefaultValue()));
        setAttributeValue(JaxrsAttribute.RESTEASY_USE_CONTAINER_FORM_PARAMS, changeBoolean(JaxrsAttribute.RESTEASY_USE_CONTAINER_FORM_PARAMS.getDefaultValue()));
        setAttributeValue(JaxrsAttribute.RESTEASY_WIDER_REQUEST_MATCHING, changeBoolean(JaxrsAttribute.RESTEASY_WIDER_REQUEST_MATCHING.getDefaultValue()));
    }

    /*
     * Reset attributes to default values.
     */
    static void resetAttributeValues() throws IOException {
        setAttributeValue(JaxrsAttribute.JAXRS_2_0_REQUEST_MATCHING, JaxrsAttribute.JAXRS_2_0_REQUEST_MATCHING.getDefaultValue());
        setAttributeValue(JaxrsAttribute.RESTEASY_ADD_CHARSET, JaxrsAttribute.RESTEASY_ADD_CHARSET.getDefaultValue());
        setAttributeValue(JaxrsAttribute.RESTEASY_BUFFER_EXCEPTION_ENTITY, JaxrsAttribute.RESTEASY_BUFFER_EXCEPTION_ENTITY.getDefaultValue());
        setAttributeValue(JaxrsAttribute.RESTEASY_DISABLE_HTML_SANITIZER, JaxrsAttribute.RESTEASY_DISABLE_HTML_SANITIZER.getDefaultValue());
        setAttributeValue(JaxrsAttribute.RESTEASY_DISABLE_PROVIDERS, new ModelNode(""));
        setAttributeValue(JaxrsAttribute.RESTEASY_DOCUMENT_EXPAND_ENTITY_REFERENCES, JaxrsAttribute.RESTEASY_DOCUMENT_EXPAND_ENTITY_REFERENCES.getDefaultValue());
        setAttributeValue(JaxrsAttribute.RESTEASY_DOCUMENT_SECURE_DISABLE_DTDS, JaxrsAttribute.RESTEASY_DOCUMENT_SECURE_DISABLE_DTDS.getDefaultValue());
        setAttributeValue(JaxrsAttribute.RESTEASY_DOCUMENT_SECURE_PROCESSING_FEATURE, JaxrsAttribute.RESTEASY_DOCUMENT_SECURE_PROCESSING_FEATURE.getDefaultValue());
        setAttributeValue(JaxrsAttribute.RESTEASY_GZIP_MAX_INPUT, JaxrsAttribute.RESTEASY_GZIP_MAX_INPUT.getDefaultValue());
        setAttributeValue(JaxrsAttribute.RESTEASY_JNDI_RESOURCES, new ModelNode(""));
        setAttributeValue(JaxrsAttribute.RESTEASY_LANGUAGE_MAPPINGS, new ModelNode(""));
        setAttributeValue(JaxrsAttribute.RESTEASY_MEDIA_TYPE_MAPPINGS, new ModelNode(""));
        setAttributeValue(JaxrsAttribute.RESTEASY_MEDIA_TYPE_PARAM_MAPPING, new ModelNode(" "));
        setAttributeValue(JaxrsAttribute.RESTEASY_PROVIDERS, new ModelNode(""));
        setAttributeValue(JaxrsAttribute.RESTEASY_RESOURCES, new ModelNode(""));
        setAttributeValue(JaxrsAttribute.RESTEASY_RFC7232_PRECONDITIONS, JaxrsAttribute.RESTEASY_RFC7232_PRECONDITIONS.getDefaultValue());
        setAttributeValue(JaxrsAttribute.RESTEASY_ROLE_BASED_SECURITY, JaxrsAttribute.RESTEASY_ROLE_BASED_SECURITY.getDefaultValue());
        setAttributeValue(JaxrsAttribute.RESTEASY_SECURE_RANDOM_MAX_USE, JaxrsAttribute.RESTEASY_SECURE_RANDOM_MAX_USE.getDefaultValue());
        setAttributeValue(JaxrsAttribute.RESTEASY_USE_BUILTIN_PROVIDERS, JaxrsAttribute.RESTEASY_USE_BUILTIN_PROVIDERS.getDefaultValue());
        setAttributeValue(JaxrsAttribute.RESTEASY_USE_CONTAINER_FORM_PARAMS, JaxrsAttribute.RESTEASY_USE_CONTAINER_FORM_PARAMS.getDefaultValue());
        setAttributeValue(JaxrsAttribute.RESTEASY_WIDER_REQUEST_MATCHING, JaxrsAttribute.RESTEASY_WIDER_REQUEST_MATCHING.getDefaultValue());
    }

    static void setAttributeValue(SimpleAttributeDefinition attribute, ModelNode value) throws IOException {
        ModelNode op = Operations.createWriteAttributeOperation(ADDRESS, attribute.getName(), value);
        ModelNode result = modelControllerClient.execute(op);
        Assert.assertEquals(SUCCESS, result.get(OUTCOME).asString());
        expectedValues.put(attribute, value);
    }

    static ModelNode changeBoolean(ModelNode modelNode) {
        return new ModelNode(!(modelNode.asBoolean()));
    }

    static ModelNode changeInteger(ModelNode modelNode) {
        return new ModelNode(modelNode.asInt() + 17);
    }

    //////////////////////////////////////////////////////////////////////////////////////
    /////////////////////            Test methods                     ////////////////////
    //////////////////////////////////////////////////////////////////////////////////////
    @Test
    public void testAttributes() throws IOException {
        WebTarget target = jaxrsClient.target(url.toString() + "myjaxrs/attribute");
        for (SimpleAttributeDefinition attribute : JaxrsAttribute.attributes) {
            if (JaxrsAttribute.RESTEASY_JNDI_RESOURCES.equals(attribute)) {
                continue;
            }
            testAttribute(target, attribute);
        }
    }

    void testAttribute(WebTarget target, SimpleAttributeDefinition attribute) {
        Response response = target.path(changeHyphensToDots(attribute.getName())).request().accept("text/plain").get();
        Assert.assertEquals(200, response.getStatus());
        Object result = response.readEntity(modelTypeMap.get(attribute.getType()));

        switch (attribute.getType()) {

            case BOOLEAN:
                Assert.assertEquals(expectedValues.get(attribute).asBoolean(), result);
                return;

            case INT:
                Assert.assertEquals(expectedValues.get(attribute).asInt(), result);
                return;

            case STRING:
                Assert.assertEquals(expectedValues.get(attribute).asString(), result);
                return;

            case LIST:
                Assert.assertEquals(expectedValues.get(attribute).asString(), result);
                return;

            default:
                Assert.fail("Unexpected ModelNode type");
        }
     }

    String changeHyphensToDots(String attribute) {
        return attribute.replace("-", ".");
    }

    /**
     * Test list syntax using attribute "resteasy.providers" (which includes provider StringTextStar).
     */
    @Test
    public void testProvidersAdded() throws Exception {
        {
            WebTarget base = jaxrsClient.target(url.toString() + "myjaxrs/attribute/bar5");
            base.register(Provider5.class);
            Builder builder = base.request().accept("text/plain");
            Response response = builder.post(Entity.entity(new Bar5("sending"), "application/bar"));
            Assert.assertEquals(200, response.getStatus());
            Assert.assertEquals("provider5", response.readEntity(String.class));
        }
        {
            WebTarget base = jaxrsClient.target(url.toString() + "myjaxrs/attribute/bar6");
            base.register(Provider6.class);
            Builder builder = base.request().accept("text/plain");
            Response response = builder.post(Entity.entity(new Bar6("sending"), "application/bar"));
            Assert.assertEquals(200, response.getStatus());
            Assert.assertEquals("provider6", response.readEntity(String.class));
        }
    }

    /**
     * Test map syntax using attribute "resteasy.media.type.mappings" (which includes mapping "unusual : text/unusual").
     */
    @Test
    public void testMap() throws Exception {
        WebTarget base = jaxrsClient.target(url.toString() + "myjaxrs/attribute/accept.unusual");
        Builder builder = base.request().accept("text/plain");
        Response response = builder.get();
        Assert.assertEquals(200, response.getStatus());
        Assert.assertEquals("unusual", response.readEntity(String.class));
    }

    /**
     * Verify that syntactically incorrect values get kicked out.
     */
    @Test
    public void testBadSyntax() throws Exception {
        for (SimpleAttributeDefinition attribute : JaxrsAttribute.attributes) {
            // RESTEasy accepts any string for "resteasy-media-type-param-mapping".
            if (JaxrsAttribute.RESTEASY_MEDIA_TYPE_PARAM_MAPPING.equals(attribute)) {
                continue;
            }
            ModelNode op = Operations.createWriteAttributeOperation(ADDRESS, attribute.getName(), mangleAttribute(attribute));
            ModelNode result = modelControllerClient.execute(op);
            Assert.assertEquals(FAILED, result.get(OUTCOME).asString());
        }
    }

    static String mangleAttribute(SimpleAttributeDefinition attribute) {
        switch (attribute.getType()) {

            case BOOLEAN:
                return "pqr";

            case INT:
                return "xyz";

            // Either a list or a map or a "resteasy-media-type-param-mapping" query parameter
            case STRING:
                return "123";

            default:
                throw new RuntimeException("Unexpected ModelNode type: " + attribute.getType());
        }
    }

    @Test
    public void testExistingDeploymentsUnchanged() throws IOException {

        // Get current value of "resteasy.add.charset".
        WebTarget base = jaxrsClient.target(url.toString() + "myjaxrs/attribute");
        Builder builder = base.path(changeHyphensToDots(JaxrsAttribute.RESTEASY_ADD_CHARSET.getName())).request();
        Response response = builder.get();
        Assert.assertEquals(200, response.getStatus());
        boolean oldValue = response.readEntity(boolean.class);

        // Update "resteasy.add.charset" value to a new value.
        ModelNode op = Operations.createWriteAttributeOperation(ADDRESS, JaxrsAttribute.RESTEASY_ADD_CHARSET.getName(), !oldValue);
        ModelNode result = modelControllerClient.execute(op);
        Assert.assertEquals(SUCCESS, result.get(OUTCOME).asString());

        // Verify that value of "resteasy.add.charset" hasn't changed in existing deployment.
        response = builder.get();
        Assert.assertEquals(200, response.getStatus());
        Assert.assertEquals(oldValue, response.readEntity(boolean.class));

        // Reset "resteasy.add.charset" to original value.
        op = Operations.createWriteAttributeOperation(ADDRESS, JaxrsAttribute.RESTEASY_ADD_CHARSET.getName(), oldValue);
        result = modelControllerClient.execute(op);
        Assert.assertEquals(SUCCESS, result.get(OUTCOME).asString());
    }
}

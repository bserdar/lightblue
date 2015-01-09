package com.redhat.lightblue.test.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.redhat.lightblue.metadata.DataStore;
import com.redhat.lightblue.metadata.parser.DataStoreParser;
import com.redhat.lightblue.metadata.parser.Extensions;
import com.redhat.lightblue.metadata.parser.JSONMetadataParser;
import com.redhat.lightblue.metadata.parser.MetadataParser;
import com.redhat.lightblue.metadata.types.DefaultTypes;
import com.redhat.lightblue.util.JsonUtils;
import org.json.JSONException;
import org.junit.Test;

import com.redhat.lightblue.Response;
import com.redhat.lightblue.metadata.EntityInfo;
import com.redhat.lightblue.metadata.EntityMetadata;
import org.skyscreamer.jsonassert.JSONAssert;

import java.io.IOException;

public class FakeMetadataTest {

    @Test
    public void testEntityInfo_VersionDoesNotExist(){
        assertFalse(new FakeMetadata().checkVersionExists("fake", "1.0.0"));
    }

    @Test
    public void testEntityInfo_VersionDoesExist(){
        String entityName = "fake";
        String version1 = "1.0.0";

        FakeMetadata metadata = new FakeMetadata();

        EntityInfo entityInfo = new EntityInfo(entityName);
        metadata.setEntityInfo(entityInfo);

        assertFalse(metadata.checkVersionExists(entityName, version1));

        metadata.setEntityMetadata(entityName, version1, new EntityMetadata("fake EntityMetadata"));

        assertTrue(metadata.checkVersionExists(entityName, version1));
    }

    @Test
    public void testGetJSONSchemaNoFields(){
        String entityName = "fake";
        String version1 = "1.0.0";

        FakeMetadata metadata = new FakeMetadata();

        EntityInfo entityInfo = new EntityInfo(entityName);
        metadata.setEntityInfo(entityInfo);
        metadata.setEntityMetadata(entityName, version1, new EntityMetadata("fake EntityMetadata"));

        JsonNode jsonSchema = metadata.getJSONSchema(entityName, version1);
        String actual = jsonSchema.toString();
        assertEquals("{\"$schema\":\"http://json-schema.org/draft-04/schema#\",\"type\":\"object\",\"description\":\"JSON schema for entity 'fake' version '1.0.0'\"}", actual);
    }


    @Test
    public void testGetJSONSchemaWithFields() throws IOException, JSONException {
        String entityName = "user";
        String version1 = "1.0.0";

        FakeMetadata metadata = new FakeMetadata();

        EntityInfo entityInfo = new EntityInfo(entityName);
        metadata.setEntityInfo(entityInfo);
        Extensions<JsonNode> extensions = new Extensions<>();
        extensions.addDefaultExtensions();
        extensions.registerDataStoreParser("mongo", new DataStoreParser<JsonNode>() {
            @Override
            public String getDefaultName() {
                return "mongo";
            }

            @Override
            public DataStore parse(String name, MetadataParser<JsonNode> p, JsonNode node) {
                return new DataStore() {
                    @Override
                    public String getBackend() {
                        return "mongo";
                    }
                };
            }

            @Override
            public void convert(MetadataParser<JsonNode> p, JsonNode emptyNode, DataStore object) {
            }
        });
        JSONMetadataParser parser = new JSONMetadataParser(extensions, new DefaultTypes(), JsonNodeFactory.withExactBigDecimals(false));
        JsonNode node = JsonUtils.json(getClass().getResourceAsStream("/usermd.json"));
        EntityMetadata entityMetadata = parser.parseEntityMetadata(node);
        metadata.setEntityMetadata(entityName, version1, entityMetadata);

        JsonNode jsonSchema = metadata.getJSONSchema(entityName, version1);
        String actual = jsonSchema.toString();
        String expected = "{\"$schema\":\"http://json-schema.org/draft-04/schema#\",\"type\":\"object\",\"description\":\"JSON schema for entity 'user' version '1.0.0'\",\"properties\":{\"uid\":{\"type\":\"uid\"},\"nonrequid\":{\"type\":\"uid\",\"required\":\"Field not required constraint\"},\"iduid\":{\"type\":\"uid\",\"identity\":\"Field is part of identity constraint\"},\"personalInfo\":{\"type\":\"object\",\"lastName\":{\"type\":\"string\"},\"nonrequid\":{\"type\":\"uid\",\"required\":\"Field not required constraint\"},\"greeting\":{\"type\":\"string\"},\"department\":{\"type\":\"string\"},\"locale\":{\"type\":\"string\"},\"suffix\":{\"type\":\"string\"},\"title\":{\"type\":\"string\"},\"timezone\":{\"type\":\"string\"},\"faxNumber\":{\"type\":\"string\"},\"phoneNumber\":{\"type\":\"string\",\"matches\":\"^\\\\d{3}\\\\-\\\\d{4}\\\\ \\\\d{4}$\"},\"email\":{\"type\":\"string\"},\"company\":{\"type\":\"string\"},\"firstName\":{\"type\":\"string\"},\"requid\":{\"type\":\"uid\",\"required\":\"Field required constraint\"},\"emailConfirmed\":{\"type\":\"boolean\"}},\"updatedDate\":{\"type\":\"date\"},\"password\":{\"type\":\"object\",\"hashed\":{\"type\":\"string\"},\"salt\":{\"type\":\"string\"}},\"contactPermissions\":{\"type\":\"object\",\"allowMailContact\":{\"type\":\"boolean\"},\"allowThirdPartyContact\":{\"type\":\"boolean\"},\"allowPhoneContact\":{\"type\":\"boolean\"},\"allowFaxContact\":{\"type\":\"boolean\"},\"allowEmailContact\":{\"type\":\"boolean\"}},\"_id\":{\"type\":\"string\",\"identity\":\"Field is part of identity constraint\"},\"active\":{\"type\":\"boolean\"},\"login\":{\"type\":\"string\",\"maxLength\":\"64\",\"minLength\":\"1\",\"required\":\"Field required constraint\"},\"objectType\":{\"type\":\"string\"},\"requid\":{\"type\":\"uid\",\"description\":\"test\",\"required\":\"Field required constraint\"},\"createdDate\":{\"type\":\"date\"},\"required\":[\"login\",\"personalInfo.requid\",\"requid\"]}}";
        JSONAssert.assertEquals(expected, actual, false);
    }


    @Test(expected = IllegalStateException.class)
    public void testEntityInfo_DoesNotExist(){
        String entityName = "fake";
        String version1 = "1.0.0";

        FakeMetadata metadata = new FakeMetadata();

        metadata.setEntityMetadata(entityName, version1, new EntityMetadata("fake EntityMetadata"));
    }

    @Test
    public void testRemoveEntity(){
        String entityName = "fake";

        FakeMetadata metadata = new FakeMetadata();

        EntityInfo entityInfo = new EntityInfo(entityName);
        metadata.setEntityInfo(entityInfo);

        assertNotNull(metadata.getEntityInfo(entityName));

        metadata.removeEntity(entityName);

        assertNull(metadata.getEntityInfo(entityName));
    }

    @Test
    public void testRemoveEntity_ButDoesNotExist(){
        FakeMetadata metadata = new FakeMetadata();

        metadata.removeEntity("fake");

        //Nothing should happen and no exception should be thrown.
    }

    @Test
    public void testUpdateEntityInfo(){
        String entityName = "fake";

        FakeMetadata metadata = new FakeMetadata();

        EntityInfo entityInfo1 = new EntityInfo(entityName);
        metadata.setEntityInfo(entityInfo1);

        assertEquals(entityInfo1, metadata.getEntityInfo(entityName));

        EntityInfo entityInfo2 = new EntityInfo(entityName);
        metadata.updateEntityInfo(entityInfo2);

        assertEquals(entityInfo2, metadata.getEntityInfo(entityName));
    }

    @Test(expected = IllegalStateException.class)
    public void testUpdateEntityInfo_ThatDoesNotExist(){
        FakeMetadata metadata = new FakeMetadata();

        metadata.updateEntityInfo(new EntityInfo("fake"));
    }

    @Test
    public void testEntityMetadata(){
        String entityName = "fake";
        String version1 = "1.0.0";
        String version2 = "2.0.0";

        FakeMetadata metadata = new FakeMetadata();

        EntityInfo entityInfo = new EntityInfo(entityName);
        metadata.setEntityInfo(entityInfo);

        assertEquals(entityInfo, metadata.getEntityInfo(entityName));

        EntityMetadata entityMetadata = new EntityMetadata("fake EntityMetadata");
        metadata.setEntityMetadata(entityName, version1, entityMetadata);

        assertEquals(entityMetadata, metadata.getEntityMetadata(entityName, version1));
        assertNull(metadata.getEntityMetadata(entityName, version2));
    }

    @Test
    public void testDependencies(){
        String entityName = "fake";
        String version1 = "1.0.0";
        String version2 = "2.0.0";

        FakeMetadata metadata = new FakeMetadata();

        EntityInfo entityInfo = new EntityInfo(entityName);
        metadata.setEntityInfo(entityInfo);

        assertEquals(entityInfo, metadata.getEntityInfo(entityName));

        Response dependencies = new Response(null);
        metadata.setDependencies(entityName, version1, dependencies);

        assertEquals(dependencies, metadata.getDependencies(entityName, version1));
        assertNull(metadata.getDependencies(entityName, version2));
    }

    @Test
    public void testAccess(){
        String entityName = "fake";
        String version1 = "1.0.0";
        String version2 = "2.0.0";

        FakeMetadata metadata = new FakeMetadata();

        EntityInfo entityInfo = new EntityInfo(entityName);
        metadata.setEntityInfo(entityInfo);

        assertEquals(entityInfo, metadata.getEntityInfo(entityName));

        Response access = new Response(null);
        metadata.setAccess(entityName, version1, access);

        assertEquals(access, metadata.getAccess(entityName, version1));
        assertNull(metadata.getAccess(entityName, version2));
    }

}

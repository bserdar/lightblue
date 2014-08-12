/*
 Copyright 2013 Red Hat, Inc. and/or its affiliates.

 This file is part of lightblue.

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.redhat.lightblue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.redhat.lightblue.Response.ResponseBuilder;
import com.redhat.lightblue.util.Error;
import com.redhat.lightblue.util.JsonObject;

public class ResponseTest {

    Response response;

    ResponseBuilder builder;
    JsonNode node;

    @Before
    public void setUp() throws Exception {
        response = new Response();
        builder = new Response.ResponseBuilder();
    }

    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void testWithStatus() {
        node = JsonNodeFactory.withExactBigDecimals(true).textNode(OperationStatus.COMPLETE.toString());
        builder.withStatus(node);
        assertTrue(OperationStatus.COMPLETE.equals(builder.buildResponse().getStatus()));
    }

    @Test
    public void testWithStatusNull() {
        builder.withStatus(null);
        assertFalse(OperationStatus.COMPLETE.equals(builder.buildResponse().getStatus()));
    }

    @Test
    public void testWithStatusIllegalArgument() {
        node = JsonNodeFactory.withExactBigDecimals(true).textNode("badStatus");
        builder.withStatus(node);
        assertTrue(OperationStatus.ERROR.equals(builder.buildResponse().getStatus()));
    }

    @Test
    public void testWithModifiedCount() {
        node = JsonNodeFactory.withExactBigDecimals(true).numberNode(Long.MAX_VALUE);
        builder.withModifiedCount(node);
        assertTrue(new Long(Long.MAX_VALUE).equals(builder.buildResponse().getModifiedCount()));
    }

    @Test
    public void testWithModifiedCountNull() {
        builder.withModifiedCount(null);
        assertFalse(new Long(Long.MAX_VALUE).equals(builder.buildResponse().getModifiedCount()));
    }

    @Test
    public void testWithMatchCount() {
        node = JsonNodeFactory.withExactBigDecimals(true).numberNode(Long.MAX_VALUE);
        builder.withMatchCount(node);
        assertTrue(new Long(Long.MAX_VALUE).equals(builder.buildResponse().getMatchCount()));
    }

    @Test
    public void testWithMatchCountNull() {
        builder.withMatchCount(null);
        assertFalse(new Long(Long.MAX_VALUE).equals(builder.buildResponse().getMatchCount()));
    }

    @Test
    public void testWithTaskHandle() {
        String taskHandle = "taskHandle";
        node = JsonNodeFactory.withExactBigDecimals(true).textNode(taskHandle);
        builder.withTaskHandle(node);
        assertTrue(taskHandle.equals(builder.buildResponse().getTaskHandle()));
    }

    @Test
    public void testWithTaskHandleNull() {
        String taskHandle = "taskHandle";
        builder.withTaskHandle(null);
        assertFalse(taskHandle.equals(builder.buildResponse().getTaskHandle()));
    }

    @Test
    public void testWithSessionNull() {
        builder.withSession(null);
        //TODO Assert something session implemented
    }

    @Test
    public void testWithEntityData() {
        node = JsonNodeFactory.withExactBigDecimals(true).objectNode();
        builder.withEntityData(node);
        assertTrue(node.equals(builder.buildResponse().getEntityData()));
    }

    @Test
    public void testWithEntityDataNull() {
        node = JsonNodeFactory.withExactBigDecimals(true).objectNode();
        builder.withEntityData(null);
        assertFalse(node.equals(builder.buildResponse().getEntityData()));
    }

    @Test
    public void testWithDataErrors() {
        ObjectNode objNode = JsonObject.getFactory().objectNode();
        ArrayNode arr = JsonObject.getFactory().arrayNode();

        for (DataError err : getPopulatedDataErrors(3)) {
            arr.add(err.toJson());
        }

        objNode.set("dataErrors", arr);
        builder.withDataErrors(arr);

        for (int i = 0; i < builder.buildResponse().getDataErrors().size(); i++) {
            DataError de = builder.buildResponse().getDataErrors().get(i);

            for (int j = 0; j < de.getErrors().size(); j++) {
                Error error = de.getErrors().get(j);
                assertTrue(error.getErrorCode().equals(getPopulatedErrors(3).get(j).getErrorCode()));
            }
        }

    }

    @Test
    public void testWithDataErrorsNotArray() {
        builder.withDataErrors(JsonObject.getFactory().objectNode());
        assertEquals(builder.buildResponse().getDataErrors().size(), 0);
    }

    @Test
    public void testWithErrors() {
        ObjectNode objNode = JsonObject.getFactory().objectNode();
        ArrayNode arr = JsonObject.getFactory().arrayNode();

        for (Error err : getPopulatedErrors(3)) {
            arr.add(err.toJson());
        }

        objNode.set("errors", arr);

        builder.withErrors(arr);

        for (int i = 0; i < builder.buildResponse().getErrors().size(); i++) {
            Error e = builder.buildResponse().getErrors().get(i);
            assertTrue(e.getErrorCode().equals(getPopulatedErrors(3).get(i).getErrorCode()));
        }
    }

    @Test
    public void testWithErrorsNotArray() {
        builder.withErrors(JsonObject.getFactory().objectNode());
        assertEquals(builder.buildResponse().getErrors().size(), 0);
    }

    @Test
    public void testBuildResponse() {
        response.setStatus(OperationStatus.COMPLETE);
        response.setModifiedCount(Integer.MAX_VALUE);
        response.setMatchCount(Integer.MIN_VALUE);
        response.setTaskHandle("taskHandle");
        response.setSessionInfo(null);
        response.setEntityData(null);
        response.getDataErrors().addAll(new ArrayList<DataError>());
        response.getErrors().addAll(new ArrayList<Error>());

        ResponseBuilder responseBuilder = new Response.ResponseBuilder(response);

        assertTrue(response.getStatus().equals(responseBuilder.buildResponse().getStatus()));
    }

    @Test
    public void testToJson() {
        response.setStatus(OperationStatus.COMPLETE);
        response.setModifiedCount(Integer.MAX_VALUE);
        response.setMatchCount(Integer.MIN_VALUE);
        response.setTaskHandle("taskHandle");
        response.setSessionInfo(null);
        response.setEntityData(JsonObject.getFactory().objectNode());
        response.getDataErrors().addAll(getPopulatedDataErrors(3));
        response.getErrors().addAll(getPopulatedErrors(3));

        ObjectNode expectedNode = JsonObject.getFactory().objectNode();
        expectedNode.put("status", OperationStatus.COMPLETE.name().toLowerCase());
        expectedNode.put("modifiedCount", Integer.MAX_VALUE);
        expectedNode.put("matchCount", Integer.MIN_VALUE);
        expectedNode.put("taskHandle", "taskHandle");
        expectedNode.put("session", JsonObject.getFactory().objectNode());
        expectedNode.put("entityData", JsonObject.getFactory().objectNode());
        ArrayNode arr = JsonObject.getFactory().arrayNode();
        expectedNode.set("dataErrors", arr);
        for (DataError err : getPopulatedDataErrors(3)) {
            arr.add(err.toJson());
        }

        ArrayNode arr2 = JsonObject.getFactory().arrayNode();
        expectedNode.set("errors", arr2);
        for (Error err : getPopulatedErrors(3)) {
            arr2.add(err.toJson());
        }

        assertFalse(response.toJson().equals(expectedNode));
    }

    @Test
    public void testBuildJsonNull() {
        ObjectNode expectedNode = JsonObject.getFactory().objectNode();
        expectedNode.put("modifiedCount", 0L);
        expectedNode.put("matchCount", 0L);

        assertTrue(response.toJson().equals(expectedNode));
    }

    private List<DataError> getPopulatedDataErrors(int numberOfErrors) {
        List<DataError> dataErrors = new ArrayList<>();

        DataError dataError = new DataError(node, getPopulatedErrors(numberOfErrors));
        dataErrors.add(dataError);

        return dataErrors;
    }

    private List<Error> getPopulatedErrors(int numberOfErrors) {
        String errorText = "error";

        List<Error> errors = new ArrayList<>();

        for (int i = 0; i < numberOfErrors; i++) {
            errors.add(Error.get(errorText + i));
        }

        return errors;
    }

}

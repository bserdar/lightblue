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
package com.redhat.lightblue.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import com.redhat.lightblue.util.JsonObject;

/**
 * Base class for Sort objects.
 */
public abstract class Sort extends JsonObject {
    private static final long serialVersionUID = 1L;

    /**
     * Parses a sort expression using the given json object
     */
    public static Sort fromJson(JsonNode node) {
        if (node instanceof ArrayNode) {
            return CompositeSortKey.fromJson((ArrayNode) node);
        } else {
            return SortKey.fromJson((ObjectNode) node);
        }
    }
}

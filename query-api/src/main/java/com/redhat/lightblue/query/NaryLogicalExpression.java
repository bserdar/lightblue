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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.redhat.lightblue.util.Error;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Represents a query of the form
 * <pre>
 * { nary_logical_operator : [ query_expression,...] }
 * </pre>
 */
public class NaryLogicalExpression extends LogicalExpression {

    private static final long serialVersionUID = 1L;

    private final NaryLogicalOperator op;
    private final List<QueryExpression> queries;

    /**
     * Ctor with the given values
     */
    public NaryLogicalExpression(NaryLogicalOperator op,
                                 List<QueryExpression> queries) {
        this.op = op;
        this.queries = queries;
    }

    /**
     * Ctor with the given values
     */
    public NaryLogicalExpression(NaryLogicalOperator op,
                                 QueryExpression... queries) {
        this(op, new ArrayList<QueryExpression>(queries.length));
        for (QueryExpression q : queries) {
            this.queries.add(q);
        }
    }

    /**
     * The operator
     */
    public NaryLogicalOperator getOp() {
        return this.op;
    }

    /**
     * The nested queries
     */
    public List<QueryExpression> getQueries() {
        return this.queries;
    }

    /**
     * Returns a json representation of this query
     */
    @Override
    public JsonNode toJson() {
        ArrayNode arr = getFactory().arrayNode();
        for (QueryExpression x : queries) {
            arr.add(x.toJson());
        }
        return getFactory().objectNode().set(op.toString(), arr);
    }

    /**
     * Parses an n-ary logical expression from the given json object
     */
    public static NaryLogicalExpression fromJson(ObjectNode node) {
        if (node.size() != 1) {
            throw Error.get(QueryConstants.ERR_INVALID_LOGICAL_EXPRESSION, node.toString());
        }
        String fieldName = node.fieldNames().next();
        NaryLogicalOperator op = NaryLogicalOperator.fromString(fieldName);
        if (op == null) {
            throw Error.get(QueryConstants.ERR_INVALID_LOGICAL_EXPRESSION, node.toString());
        }
        JsonNode x = node.get(fieldName);
        if (x instanceof ArrayNode) {
            ArrayList<QueryExpression> list
                    = new ArrayList<>(((ArrayNode) x).size());
            for (Iterator<JsonNode> itr = ((ArrayNode) x).elements();
                    itr.hasNext();) {
                list.add(QueryExpression.fromJson(itr.next()));
            }
            return new NaryLogicalExpression(op, list);
        } else {
            throw Error.get(QueryConstants.ERR_INVALID_LOGICAL_EXPRESSION, node.toString());
        }
    }
}

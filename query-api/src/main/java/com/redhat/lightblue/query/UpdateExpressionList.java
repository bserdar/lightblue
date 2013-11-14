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

import java.util.List;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Iterator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class UpdateExpressionList extends UpdateExpression {

    private List<UpdateExpression> list;

    public UpdateExpressionList() {
    }

    public UpdateExpressionList(List<UpdateExpression> items) {
        this.list=items;
    }

    public UpdateExpressionList(UpdateExpression... i) {
        this(Arrays.asList(i));
    }

    public List<UpdateExpression> getList() {
        return list;
    }

    public void setList(List<UpdateExpression> l) {
        list=l;
    }

    public JsonNode toJson() {
        ArrayNode arr=factory.arrayNode();
        for(UpdateExpression x:list)
            arr.add(x.toJson());
        return arr;
    }

    public static UpdateExpressionList fromJson(ArrayNode node) {
        ArrayList<UpdateExpression> list=
            new ArrayList<UpdateExpression>(node.size());
        for(Iterator<JsonNode> itr=node.elements();
            itr.hasNext();)
            list.add(PartialUpdateExpression.fromJson((ObjectNode)itr.next()));
        return new UpdateExpressionList(list);
    }
}

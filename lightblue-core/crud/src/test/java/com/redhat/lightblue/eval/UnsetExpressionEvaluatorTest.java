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
package com.redhat.lightblue.eval;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.redhat.lightblue.metadata.EntityMetadata;
import com.redhat.lightblue.query.UpdateExpression;
import com.redhat.lightblue.util.Path;
import com.redhat.lightblue.util.test.AbstractJsonNodeTest;

public class UnsetExpressionEvaluatorTest extends AbstractJsonNodeTest {

    private EntityMetadata md;

    @Before
    public void setUp() throws Exception {
        jsonDoc = EvalTestContext.getDoc("./sample1.json");
        md = EvalTestContext.getMd("./testMetadata.json");
    }

    @Test
    public void unset() throws Exception {
        UpdateExpression expr = EvalTestContext.updateExpressionFromJson("{'$unset' : [ 'field1', 'field6.nf2', 'field6.nf6.1','field7.1'] }");

        Updater updater = Updater.getInstance(JSON_NODE_FACTORY, md, expr);
        Assert.assertTrue(updater.update(jsonDoc, md.getFieldTreeRoot(), new Path()));
        Assert.assertNull(jsonDoc.get(new Path("field1")));
        Assert.assertNull(jsonDoc.get(new Path("field6.nf2")));
        Assert.assertEquals("three", jsonDoc.get(new Path("field6.nf6.1")).asText());
        Assert.assertEquals(3, jsonDoc.get(new Path("field6.nf6#")).asInt());
        Assert.assertEquals(3, jsonDoc.get(new Path("field6.nf6")).size());
        Assert.assertEquals("elvalue2_1", jsonDoc.get(new Path("field7.1.elemf1")).asText());
        Assert.assertEquals(3, jsonDoc.get(new Path("field7#")).asInt());
        Assert.assertEquals(3, jsonDoc.get(new Path("field7")).size());
    }

    @Test
    public void one_$parent_unset() throws Exception {
        UpdateExpression expr = EvalTestContext.updateExpressionFromJson("{'$unset' : [ 'field2.$parent.field1', 'field2.$parent.field6.nf2', 'field2.$parent.field6.nf6.1','field2.$parent.field7.1'] }");

        Updater updater = Updater.getInstance(JSON_NODE_FACTORY, md, expr);
        Assert.assertTrue(updater.update(jsonDoc, md.getFieldTreeRoot(), new Path()));
        Assert.assertNull(jsonDoc.get(new Path("field1")));
        Assert.assertNull(jsonDoc.get(new Path("field6.nf2")));
        Assert.assertEquals("three", jsonDoc.get(new Path("field6.nf6.1")).asText());
        Assert.assertEquals(3, jsonDoc.get(new Path("field6.nf6#")).asInt());
        Assert.assertEquals(3, jsonDoc.get(new Path("field6.nf6")).size());
        Assert.assertEquals("elvalue2_1", jsonDoc.get(new Path("field7.1.elemf1")).asText());
        Assert.assertEquals(3, jsonDoc.get(new Path("field7#")).asInt());
        Assert.assertEquals(3, jsonDoc.get(new Path("field7")).size());
    }

}

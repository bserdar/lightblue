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
package com.redhat.lightblue.metadata.constraints;

import com.redhat.lightblue.metadata.types.StringType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class EnumConstraintTest {

    EnumConstraint constraint;

    @Before
    public void setUp() throws Exception {
        constraint = new EnumConstraint();
    }

    @After
    public void tearDown() throws Exception {

    }

    @Test
    public void testGetType() {
        assertTrue(constraint.getType().equals(EnumConstraint.ENUM));
    }

    @Test
    public void testIsValidForFieldType() {
        assertTrue(constraint.isValidForFieldType(StringType.TYPE));
    }

    @Test
    public void testGetName() {
        constraint.setName("not null");
        assertNotNull(constraint.getName());
    }

    @Test
    public void testSetName() {
        String name = "3";
        constraint.setName(name);
        assertTrue(name.equals(constraint.getName()));
    }

    @Test
    public void testSetValuesNull() {
        constraint.setName(null);
        assertTrue(null == constraint.getName());
    }
}

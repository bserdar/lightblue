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
package com.redhat.lightblue.metadata.parser;

import java.util.Map;

public abstract class PropertyParser<T> implements Parser<T, Object> {
    public void parseProperty(MetadataParser<T> MetadataParser, String name, Map<String, Object> properties, T objectProperty) {
        final Object obj = parse(name, MetadataParser, objectProperty);
        properties.put(name, obj);
    }
}

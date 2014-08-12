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
package com.redhat.lightblue.metadata;

import java.util.ArrayList;
import java.util.Iterator;

import com.redhat.lightblue.util.Path;
import com.redhat.lightblue.util.Error;
import com.redhat.lightblue.metadata.types.ArrayType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ArrayField extends Field {
    private static final Logger LOGGER = LoggerFactory.getLogger(ArrayField.class);

    private static final long serialVersionUID = 1L;

    private ArrayElement element;

    public ArrayField(String name) {
        super(name, ArrayType.TYPE);
    }

    public ArrayField(String name, ArrayElement el) {
        super(name, ArrayType.TYPE);
        element = el;
        element.setParent(this);
    }

    public ArrayElement getElement() {
        return element;
    }

    public void setElement(ArrayElement el) {
        element = el;
        element.setParent(this);
    }

    @Override
    public boolean hasChildren() {
        return true;
    }

    @Override
    public Iterator<? extends FieldTreeNode> getChildren() {
        if (element != null) {
            ArrayList<FieldTreeNode> l = new ArrayList<>(1);
            l.add(element);
            return l.iterator();
        }
        return FieldTreeNode.EMPTY;
    }

    @Override
    public FieldTreeNode resolve(Path p, int level) {
        int l = p.numSegments() - level;
        if (l == 0) {
            return this;
        } else {
            Error.push(p.head(level));
            try {
                if (p.isIndex(level) || p.head(level).equals(Path.ANY)) {
                    return element.resolve(p, level + 1);
                } else if (Path.PARENT.equals(p.head(level))) {
                    return this.getParent().resolve(p, level + 1);
                } else {
                    throw Error.get(MetadataConstants.ERR_INVALID_ARRAY_REFERENCE);
                }
            } catch (Error e) {
                // rethrow lightblue error
                throw e;
            } catch (Exception e) {
                // throw new Error (preserves current error context)
                LOGGER.error(e.getMessage(), e);
                throw Error.get(MetadataConstants.ERR_ILL_FORMED_METADATA, e.getMessage());
            } finally {
                Error.pop();
            }
        }
    }

}

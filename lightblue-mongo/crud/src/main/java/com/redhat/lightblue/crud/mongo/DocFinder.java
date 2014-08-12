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
package com.redhat.lightblue.crud.mongo;

import com.mongodb.DBCollection;
import com.mongodb.DBObject;

import com.redhat.lightblue.crud.CRUDOperationContext;

/**
 * Finds documents, sorts them, and limits the result set.
 */
public interface DocFinder {

    /**
     * Finds documents.
     *
     * @param ctx The operation context. The found documents are stored in the
     * context.
     * @param coll The collection on which the find operation will be performed
     * @param mongoQuery The MongoDB query
     * @param mongoSort Optional sort
     * @param from Optional from index, starting from 0
     * @param to Optional to index, starting from 0
     *
     * The find implementation should search for documents in the given
     * collection using the search criteria. If a sort is given, the results
     * should be sorted, and optionally, a subset of the result set should be
     * returned.
     *
     * @return Total number of objects found with given query before applying
     * from and to conditions
     */
    long find(CRUDOperationContext ctx,
              DBCollection coll,
              DBObject mongoQuery,
              DBObject mongoSort,
              Long from,
              Long to);
}

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
package com.redhat.lightblue.metadata.mongo;

import com.redhat.lightblue.metadata.MetadataConstants;
import com.redhat.lightblue.metadata.DataStore;
import com.redhat.lightblue.metadata.parser.DataStoreParser;
import com.redhat.lightblue.metadata.parser.MetadataParser;

import com.redhat.lightblue.common.mongo.MongoDataStore;

import com.redhat.lightblue.util.Error;

public class MongoDataStoreParser<T> implements DataStoreParser<T> {

    public static final String COLLECTION_REQUIRED = "COLLECTION_REQUIRED";

    public static final String NAME = "mongo";

    @Override
    public DataStore parse(String name, MetadataParser<T> p, T node) {
        if (!NAME.equals(name)) {
            throw Error.get(MetadataConstants.ERR_ILL_FORMED_METADATA, name);
        }

        MongoDataStore ds = new MongoDataStore();
        ds.setDatabaseName(p.getStringProperty(node, "database"));
        ds.setDatasourceName(p.getStringProperty(node, "datasource"));
        ds.setCollectionName(p.getStringProperty(node, "collection"));
        if (ds.getCollectionName() == null
                || ds.getCollectionName().length() == 0) {
            throw Error.get(COLLECTION_REQUIRED, "datastore");
        }
        return ds;
    }

    @Override
    public void convert(MetadataParser<T> p, T emptyNode, DataStore object) {
        MongoDataStore ds = (MongoDataStore) object;
        if (ds.getDatabaseName() != null) {
            p.putString(emptyNode, "database", ds.getDatabaseName());
        }
        if (ds.getDatasourceName() != null) {
            p.putString(emptyNode, "datasource", ds.getDatasourceName());
        }
        if (ds.getCollectionName() != null) {
            p.putString(emptyNode, "collection", ds.getCollectionName());
        }
    }

    @Override
    public String getDefaultName() {
        return NAME;
    }
}

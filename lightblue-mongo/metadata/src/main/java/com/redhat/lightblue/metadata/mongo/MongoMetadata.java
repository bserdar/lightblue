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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Pattern;

import org.bson.BSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.MongoException;
import com.mongodb.WriteConcern;
import com.mongodb.WriteResult;
import com.redhat.lightblue.DataError;
import com.redhat.lightblue.OperationStatus;
import com.redhat.lightblue.Response;
import com.redhat.lightblue.common.mongo.DBResolver;
import com.redhat.lightblue.common.mongo.MongoDataStore;
import com.redhat.lightblue.metadata.AbstractMetadata;
import com.redhat.lightblue.metadata.DataStore;
import com.redhat.lightblue.metadata.EntityAccess;
import com.redhat.lightblue.metadata.EntityInfo;
import com.redhat.lightblue.metadata.EntityMetadata;
import com.redhat.lightblue.metadata.EntitySchema;
import com.redhat.lightblue.metadata.Field;
import com.redhat.lightblue.metadata.FieldAccess;
import com.redhat.lightblue.metadata.FieldCursor;
import com.redhat.lightblue.metadata.FieldTreeNode;
import com.redhat.lightblue.metadata.MetadataConstants;
import com.redhat.lightblue.metadata.MetadataStatus;
import com.redhat.lightblue.metadata.PredefinedFields;
import com.redhat.lightblue.metadata.StatusChange;
import com.redhat.lightblue.metadata.TypeResolver;
import com.redhat.lightblue.metadata.Version;
import com.redhat.lightblue.metadata.VersionInfo;
import com.redhat.lightblue.metadata.parser.Extensions;
import com.redhat.lightblue.metadata.parser.MetadataParser;
import com.redhat.lightblue.crud.Factory;
import com.redhat.lightblue.mongo.hystrix.FindCommand;
import com.redhat.lightblue.mongo.hystrix.FindOneCommand;
import com.redhat.lightblue.mongo.hystrix.InsertCommand;
import com.redhat.lightblue.mongo.hystrix.RemoveCommand;
import com.redhat.lightblue.mongo.hystrix.UpdateCommand;
import com.redhat.lightblue.mongo.hystrix.DistinctCommand;
import com.redhat.lightblue.util.Error;
import com.redhat.lightblue.util.Path;

public class MongoMetadata extends AbstractMetadata {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = LoggerFactory.getLogger(MongoMetadata.class);

    public static final String DEFAULT_METADATA_COLLECTION = "metadata";

    private static final String LITERAL_ID = "_id";
    private static final String LITERAL_ENTITY_NAME = "entityName";
    private static final String LITERAL_VERSION = "version";
    private static final String LITERAL_STATUS = "status";
    private static final String LITERAL_STATUS_VALUE = "status.value";
    private static final String LITERAL_NAME = "name";

    private final transient DBCollection collection;
    private final transient DBResolver dbResolver;
    private final transient BSONParser mdParser;
    private final transient Factory factory;

    public MongoMetadata(DB db,
                         String metadataCollection,
                         DBResolver dbResolver,
                         Extensions<BSONObject> parserExtensions,
                         TypeResolver typeResolver,
                         Factory factory) {
        this.collection = db.getCollection(metadataCollection);
        this.mdParser = new BSONParser(parserExtensions, typeResolver);
        this.dbResolver = dbResolver;
        this.factory = factory;
    }

    public MongoMetadata(DB db,
                         DBResolver dbResolver,
                         Extensions<BSONObject> parserExtensions,
                         TypeResolver typeResolver,
                         Factory factory) {
        this(db, DEFAULT_METADATA_COLLECTION, dbResolver, parserExtensions, typeResolver, factory);
    }

    @Override
    public EntityMetadata getEntityMetadata(String entityName,
                                            String version) {
        if (entityName == null || entityName.length() == 0) {
            throw new IllegalArgumentException(LITERAL_ENTITY_NAME);
        }

        Error.push("getEntityMetadata(" + entityName + ":" + version + ")");
        try {
            EntityInfo info = getEntityInfo(entityName);
            if (version == null || version.length() == 0) {
                if (info.getDefaultVersion() == null || info.getDefaultVersion().length() == 0) {
                    throw new IllegalArgumentException(LITERAL_VERSION);
                } else {
                    version = info.getDefaultVersion();
                }
            }

            EntitySchema schema;

            BasicDBObject query = new BasicDBObject(LITERAL_ID, entityName + BSONParser.DELIMITER_ID + version);
            DBObject es = new FindOneCommand(collection, query).execute();
            if (es != null) {
                schema = mdParser.parseEntitySchema(es);
            } else {
                throw Error.get(MongoMetadataConstants.ERR_UNKNOWN_VERSION, entityName + ":" + version);
            }
            return new EntityMetadata(info, schema);
        } catch (Error | IllegalArgumentException e) {
            // rethrow lightblue error or illegao arg exception
            throw e;
        } catch (Exception e) {
            // throw new Error (preserves current error context)
            LOGGER.error(e.getMessage(), e);
            throw Error.get(MetadataConstants.ERR_ILL_FORMED_METADATA, e.getMessage());
        } finally {
            Error.pop();
        }
    }

    @Override
    public EntityInfo getEntityInfo(String entityName) {
        if (entityName == null || entityName.length() == 0) {
            throw new IllegalArgumentException(LITERAL_ENTITY_NAME);
        }

        Error.push("getEntityInfo(" + entityName + ")");
        try {
            BasicDBObject query = new BasicDBObject(LITERAL_ID, entityName + BSONParser.DELIMITER_ID);
            DBObject ei = new FindOneCommand(collection, query).execute();
            if (ei != null) {
                return mdParser.parseEntityInfo(ei);
            } else {
                return null;
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

    @SuppressWarnings("rawtypes")
    @Override
    public String[] getEntityNames(MetadataStatus... statuses) {
        LOGGER.debug("getEntityNames({})", statuses);
        Error.push("getEntityNames");
        Set<MetadataStatus> statusSet = new HashSet<>();
        for (MetadataStatus x : statuses) {
            if (x != null) {
                statusSet.add(x);
            }
        }
        try {
            if (statusSet.isEmpty()
                    || (statusSet.contains(MetadataStatus.ACTIVE)
                    && statusSet.contains(MetadataStatus.DEPRECATED)
                    && statusSet.contains(MetadataStatus.DISABLED))) {
                List l = new DistinctCommand(collection, LITERAL_NAME).execute();
                String[] arr = new String[l.size()];
                int i = 0;
                for (Object x : l) {
                    arr[i++] = x.toString();
                }
                return arr;
            } else {
                LOGGER.debug("Requested statuses:{}", statusSet);
                List<String> list = new ArrayList<>(statusSet.size());
                for (MetadataStatus x : statusSet) {
                    list.add(MetadataParser.toString(x));
                }
                BasicDBObject query = new BasicDBObject(LITERAL_STATUS_VALUE, new BasicDBObject("$in", list));
                List l = new DistinctCommand(collection, LITERAL_NAME, query).execute();
                String[] arr = new String[l.size()];
                int i = 0;
                for (Object x : l) {
                    arr[i++] = x.toString();
                }
                return arr;
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

    @Override
    public VersionInfo[] getEntityVersions(String entityName) {
        if (entityName == null || entityName.length() == 0) {
            throw new IllegalArgumentException(LITERAL_ENTITY_NAME);
        }
        Error.push("getEntityVersions(" + entityName + ")");
        try {
            // Get the default version
            BasicDBObject query = new BasicDBObject(LITERAL_ID, entityName + BSONParser.DELIMITER_ID);
            DBObject ei = new FindOneCommand(collection, query).execute();
            String defaultVersion = ei == null ? null : (String) ei.get("defaultVersion");

            // query by name but only return documents that have a version
            query = new BasicDBObject(LITERAL_NAME, entityName)
                    .append(LITERAL_VERSION, new BasicDBObject("$exists", 1));
            DBObject project = new BasicDBObject(LITERAL_VERSION, 1).
                    append(LITERAL_STATUS, 1).
                    append(LITERAL_ID, 0);
            DBCursor cursor = new FindCommand(collection, query, project).execute();
            int n = cursor.count();
            VersionInfo[] ret = new VersionInfo[n];
            int i = 0;
            while (cursor.hasNext()) {
                DBObject object = cursor.next();
                ret[i] = new VersionInfo();
                Version v = mdParser.parseVersion((BSONObject) object.get(LITERAL_VERSION));
                ret[i].setValue(v.getValue());
                ret[i].setExtendsVersions(v.getExtendsVersions());
                ret[i].setChangelog(v.getChangelog());
                ret[i].setStatus(MetadataParser.statusFromString((String) ((DBObject) object.get(LITERAL_STATUS)).get("value")));
                if (defaultVersion != null && defaultVersion.equals(ret[i].getValue())) {
                    ret[i].setDefault(true);
                }
                i++;
            }
            return ret;
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

    @Override
    public void createNewMetadata(EntityMetadata md) {
        LOGGER.debug("createNewMetadata: begin");
        checkMetadataHasName(md);
        checkMetadataHasFields(md);
        checkDataStoreIsValid(md);
        Version ver = checkVersionIsValid(md);
        LOGGER.debug("createNewMetadata: version {}", ver);

        Error.push("createNewMetadata(" + md.getName() + ")");

        // write info and schema as separate docs!
        try {
            if (md.getEntityInfo().getDefaultVersion() != null) {
                if (!md.getEntityInfo().getDefaultVersion().equals(ver.getValue())) {
                    validateDefaultVersion(md.getEntityInfo());
                }
                if (md.getStatus() == MetadataStatus.DISABLED) {
                    throw Error.get(MongoMetadataConstants.ERR_DISABLED_DEFAULT_VERSION, md.getName() + ":" + md.getEntityInfo().getDefaultVersion());
                }
            }
            LOGGER.debug("createNewMetadata: Default version validated");
            PredefinedFields.ensurePredefinedFields(md);
            DBObject infoObj = (DBObject) mdParser.convert(md.getEntityInfo());
            DBObject schemaObj = (DBObject) mdParser.convert(md.getEntitySchema());

            Error.push("writeEntity");
            try {
                try {
                    WriteResult result = new InsertCommand(collection, infoObj, WriteConcern.SAFE).execute();
                    LOGGER.debug("Inserted entityInfo");
                    String error = result.getError();
                    if (error != null) {
                        LOGGER.error("createNewMetadata: error in createInfo: {}" + error);
                        throw Error.get(MongoMetadataConstants.ERR_DB_ERROR, error);
                    }
                } catch (MongoException.DuplicateKey dke) {
                    LOGGER.error("createNewMetadata: duplicateKey {}", dke);
                    throw Error.get(MongoMetadataConstants.ERR_DUPLICATE_METADATA, ver.getValue());
                }
                try {
                    WriteResult result = new InsertCommand(collection, schemaObj, WriteConcern.SAFE).execute();
                    String error = result.getError();
                    if (error != null) {
                        LOGGER.error("createNewMetadata: error in createSchema: {}" + error);
                        new RemoveCommand(collection, new BasicDBObject(LITERAL_ID, infoObj.get(LITERAL_ID))).execute();
                        throw Error.get(MongoMetadataConstants.ERR_DB_ERROR, error);
                    }

                    factory.getCRUDController(md.getEntityInfo().getDataStore().getBackend()).
                            newSchema(this, md);

                } catch (MongoException.DuplicateKey dke) {
                    LOGGER.error("createNewMetadata: duplicateKey {}", dke);
                    new RemoveCommand(collection, new BasicDBObject(LITERAL_ID, infoObj.get(LITERAL_ID))).execute();
                    throw Error.get(MongoMetadataConstants.ERR_DUPLICATE_METADATA, ver.getValue());
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
        } catch (Error e) {
            // rethrow lightblue error
            throw e;
        } catch (Exception e) {
            LOGGER.error("createNewMetadata", e);
            // throw new Error (preserves current error context)
            LOGGER.error(e.getMessage(), e);
            throw Error.get(MetadataConstants.ERR_ILL_FORMED_METADATA, e.getMessage());
        } finally {
            Error.pop();
        }
        LOGGER.debug("createNewMetadata: end");
    }

    @Override
    protected boolean checkVersionExists(String entityName, String version) {
        BasicDBObject query = new BasicDBObject(LITERAL_ID, entityName + BSONParser.DELIMITER_ID + version);
        DBObject es = collection.findOne(query);
        return (es != null);
    }

    @Override
    public void updateEntityInfo(EntityInfo ei) {
        checkMetadataHasName(ei);
        checkDataStoreIsValid(ei);
        Error.push("updateEntityInfo(" + ei.getName() + ")");
        try {
            // Verify entity info exists
            EntityInfo old = getEntityInfo(ei.getName());
            if (null == old) {
                throw Error.get(MongoMetadataConstants.ERR_MISSING_ENTITY_INFO, ei.getName());
            }
            if (!Objects.equals(old.getDefaultVersion(), ei.getDefaultVersion())) {
                validateDefaultVersion(ei);
            }

            try {
                collection.update(new BasicDBObject(LITERAL_ID, ei.getName() + BSONParser.DELIMITER_ID),
                        (DBObject) mdParser.convert(ei));
                factory.getCRUDController(ei.getDataStore().getBackend()).
                        updateEntityInfo(this, ei);

            } catch (Exception e) {
                LOGGER.error("updateEntityInfo", e);
                throw Error.get(MongoMetadataConstants.ERR_DB_ERROR, e.toString());
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

    /**
     * Creates a new schema (versioned data) for an existing metadata.
     *
     * @param md
     */
    @Override
    public void createNewSchema(EntityMetadata md) {
        checkMetadataHasName(md);
        checkMetadataHasFields(md);
        checkDataStoreIsValid(md);
        Version ver = checkVersionIsValid(md);

        Error.push("createNewSchema(" + md.getName() + ")");

        try {
            // verify entity info exists
            EntityInfo info = getEntityInfo(md.getName());

            if (null == info) {
                throw Error.get(MongoMetadataConstants.ERR_MISSING_ENTITY_INFO, md.getName());
            }

            PredefinedFields.ensurePredefinedFields(md);
            DBObject schemaObj = (DBObject) mdParser.convert(md.getEntitySchema());

            WriteResult result = new InsertCommand(collection, schemaObj, WriteConcern.SAFE).execute();
            String error = result.getError();
            if (error != null) {
                throw Error.get(MongoMetadataConstants.ERR_DB_ERROR, error);
            }
        } catch (MongoException.DuplicateKey dke) {
            throw Error.get(MongoMetadataConstants.ERR_DUPLICATE_METADATA, ver.getValue());
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

    @Override
    protected void checkDataStoreIsValid(EntityInfo md) {
        DataStore store = md.getDataStore();
        if (!(store instanceof MongoDataStore)) {
            throw new IllegalArgumentException(MongoMetadataConstants.ERR_INVALID_DATASTORE);
        }
    }

    @Override
    public void setMetadataStatus(String entityName,
                                  String version,
                                  MetadataStatus newStatus,
                                  String comment) {

        if (entityName == null || entityName.length() == 0) {
            throw new IllegalArgumentException(LITERAL_ENTITY_NAME);
        }
        if (version == null || version.length() == 0) {
            throw new IllegalArgumentException(LITERAL_VERSION);
        }
        if (newStatus == null) {
            throw new IllegalArgumentException(MongoMetadataConstants.ERR_NEW_STATUS_IS_NULL);
        }
        BasicDBObject query = new BasicDBObject(LITERAL_ID, entityName + BSONParser.DELIMITER_ID + version);
        Error.push("setMetadataStatus(" + entityName + ":" + version + ")");
        try {
            DBObject md = new FindOneCommand(collection, query).execute();
            if (md == null) {
                throw Error.get(MongoMetadataConstants.ERR_UNKNOWN_VERSION, entityName + ":" + version);
            }

            EntityInfo info = getEntityInfo(entityName);
            if (info.getDefaultVersion() != null && info.getDefaultVersion().contentEquals(version) && newStatus == MetadataStatus.DISABLED) {
                throw Error.get(MongoMetadataConstants.ERR_DISABLED_DEFAULT_VERSION, entityName + ":" + version);
            }

            EntitySchema schema = mdParser.parseEntitySchema(md);
            StatusChange newLog = new StatusChange();
            newLog.setDate(new Date());
            newLog.setStatus(schema.getStatus());
            newLog.setComment(comment);
            List<StatusChange> slog = schema.getStatusChangeLog();
            slog.add(newLog);
            schema.setStatusChangeLog(slog);
            schema.setStatus(newStatus);

            query = new BasicDBObject(LITERAL_ID, md.get(LITERAL_ID));
            WriteResult result = new UpdateCommand(collection, query, (DBObject) mdParser.convert(schema), false, false).execute();
            String error = result.getError();
            if (error != null) {
                throw Error.get(MongoMetadataConstants.ERR_DB_ERROR, error);
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

    @Override
    public void removeEntity(String entityName) {
        if (entityName == null || entityName.length() == 0) {
            throw new IllegalArgumentException(LITERAL_ENTITY_NAME);
        }
        // All versions must be disabled. Search for a schema that is not disabled
        DBObject query = new BasicDBObject(LITERAL_NAME, entityName).
                append(LITERAL_VERSION, new BasicDBObject("$exists", 1)).
                append(LITERAL_STATUS_VALUE, new BasicDBObject("$ne", MetadataParser.toString(MetadataStatus.DISABLED)));
        LOGGER.debug("Checking if there are entity versions that are not disabled: {}", query);

        DBObject result = new FindOneCommand(collection, query).execute();
        if (result != null) {
            LOGGER.debug("There is at least one enabled version {}", result);
            throw Error.get(MongoMetadataConstants.ERR_CANNOT_DELETE, entityName);
        }

        LOGGER.warn("All versions of {} are disabled, deleting {}", entityName, entityName);
        query = new BasicDBObject(LITERAL_ID, Pattern.compile(entityName + "\\" + BSONParser.DELIMITER_ID + ".*"));
        LOGGER.debug("Removal query:{}", query);
        try {
            WriteResult r = new RemoveCommand(collection, query).execute();
            LOGGER.debug("Removal result:{}", r);
            String error = r.getError();
            if (error != null) {
                throw Error.get(MongoMetadataConstants.ERR_DB_ERROR, error);
            }
        } catch (Exception e) {
            LOGGER.error("Error during delete", e);
            throw Error.get(MongoMetadataConstants.ERR_DB_ERROR, e.toString());
        }
    }

    @Override
    public Response getDependencies(String entityName, String version) {
        // NOTE do not implement until entity references are moved from fields to entity info! (TS3)
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public Response getAccess(String entityName, String version) {
        List<String> entityNames = new ArrayList<>();
        // accessMap: <role, <operation, List<path>>>
        Map<String, Map<String, List<String>>> accessMap = new HashMap<>();

        if (null != entityName && !entityName.isEmpty()) {
            entityNames.add(entityName);
        } else {
            // force version to be null
            version = null;
            entityNames.addAll(Arrays.asList(getEntityNames()));
        }

        // initialize response, assume will be completely successful
        Response response = new Response();
        response.setStatus(OperationStatus.COMPLETE);

        // for each name get metadata
        for (String name : entityNames) {
            EntityMetadata metadata;
            try {
                metadata = getEntityMetadata(name, version);
            } catch (Exception e) {
                response.setStatus(OperationStatus.PARTIAL);
                // construct error data
                ObjectNode obj = new ObjectNode(JsonNodeFactory.instance);
                obj.put(LITERAL_NAME, name);
                if (null != version) {
                    obj.put("version", version);
                }
                List<Error> errors = new ArrayList<>();
                errors.add(Error.get("ERR_NO_METADATA", "Could not get metadata for given input. Error message: " + e.getMessage()));
                DataError error = new DataError(obj, errors);
                response.getDataErrors().add(error);
                // skip to next entity name
                continue;
            }

            EntityAccess ea = metadata.getAccess();
            Map<FieldAccess, Path> fa = new HashMap<>();
            FieldCursor fc = metadata.getFieldCursor();
            // collect field access
            while (fc.next()) {
                FieldTreeNode ftn = fc.getCurrentNode();
                if (ftn instanceof Field) {
                    // add access if there is anything to extract later
                    Field f = (Field) ftn;
                    if (!f.getAccess().getFind().isEmpty()
                            || !f.getAccess().getInsert().isEmpty()
                            || !f.getAccess().getUpdate().isEmpty()) {
                        fa.put(f.getAccess(), f.getFullPath());
                    }
                }
            }

            // key is role, value is all associated paths.
            // accessMap: <role, <operation, List<path>>>
            // collect entity access
            helperAddRoles(ea.getDelete().getRoles(), "delete", name, accessMap);
            helperAddRoles(ea.getFind().getRoles(), "find", name, accessMap);
            helperAddRoles(ea.getInsert().getRoles(), "insert", name, accessMap);
            helperAddRoles(ea.getUpdate().getRoles(), "update", name, accessMap);

            // collect field access
            for (Map.Entry<FieldAccess, Path> entry : fa.entrySet()) {
                FieldAccess access = entry.getKey();
                String pathString = name + "." + entry.getValue().toString();
                helperAddRoles(access.getFind().getRoles(), "find", pathString, accessMap);
                helperAddRoles(access.getInsert().getRoles(), "insert", pathString, accessMap);
                helperAddRoles(access.getUpdate().getRoles(), "update", pathString, accessMap);
            }
        }

        // finally, populate response with valid output
        if (!accessMap.isEmpty()) {
            ArrayNode root = new ArrayNode(JsonNodeFactory.instance);
            response.setEntityData(root);
            for (Map.Entry<String, Map<String, List<String>>> entry : accessMap.entrySet()) {
                String role = entry.getKey();
                Map<String, List<String>> opPathMap = entry.getValue();

                ObjectNode roleJson = new ObjectNode(JsonNodeFactory.instance);
                root.add(roleJson);

                roleJson.put("role", role);

                for (Map.Entry<String, List<String>> operationMap : opPathMap.entrySet()) {
                    String operation = operationMap.getKey();
                    List<String> paths = opPathMap.get(operation);
                    ArrayNode pathNode = new ArrayNode(JsonNodeFactory.instance);
                    for (String path : paths) {
                        pathNode.add(path);
                    }
                    roleJson.put(operation, pathNode);
                }
            }
        } else {
            // nothing successful! set status to error
            response.setStatus(OperationStatus.ERROR);
        }

        return response;
    }

}

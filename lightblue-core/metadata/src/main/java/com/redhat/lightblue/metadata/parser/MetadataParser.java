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

import com.google.common.collect.Sets;
import com.redhat.lightblue.metadata.*;
import com.redhat.lightblue.metadata.Enum;
import com.redhat.lightblue.metadata.types.ArrayType;
import com.redhat.lightblue.metadata.types.DateType;
import com.redhat.lightblue.metadata.types.ObjectType;
import com.redhat.lightblue.metadata.types.ReferenceType;
import com.redhat.lightblue.query.Projection;
import com.redhat.lightblue.query.QueryExpression;
import com.redhat.lightblue.query.Sort;
import com.redhat.lightblue.query.SortKey;
import com.redhat.lightblue.util.Error;
import com.redhat.lightblue.util.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.ParseException;
import java.util.*;
import java.util.Map.Entry;

/**
 * Base class for converting metadata to/from json/bson and potentially other
 * formats represented as a tree.
 *
 * The metadata parser is thread safe.
 */
public abstract class MetadataParser<T> {

    private static final String STR_ID = "_id";
    private static final String STR_NAME = "name";
    private static final String STR_VALUE = "value";
    private static final String STR_VERSION = "version";
    private static final String STR_DEFAULT_VERSION = "defaultVersion";
    private static final String STR_FIND = "find";
    private static final String STR_STATUS = "status";
    private static final String STR_CONSTRAINTS = "constraints";
    private static final String STR_ACCESS = "access";
    private static final String STR_UPDATE = "update";
    private static final String STR_FIELDS = "fields";
    private static final String STR_VALUES = "values";
    private static final String STR_ITEMS = "items";
    private static final String STR_UNIQUE = "unique";
    private static final String STR_INDEXES = "indexes";
    private static final String STR_TYPE = "type";
    private static final String STR_HOOKS = "hooks";
    private static final String STR_ENUMS = "enums";
    private static final String STR_ENTITY_INFO = "entityInfo";
    private static final String STR_SCHEMA = "schema";
    private static final String STR_DATASTORE = "datastore";
    private static final String STR_BACKEND = "backend";
    private static final String STR_EXTEND_VERSIONS = "extendVersions";
    private static final String STR_CHANGELOG = "changelog";
    private static final String STR_LOG = "log";
    private static final String STR_DATE = "date";
    private static final String STR_COMMENT = "comment";
    private static final String STR_DELETE = "delete";
    private static final String STR_INSERT = "insert";
    private static final String STR_ENTITY = "entity";
    private static final String STR_VERSION_VALUE = "versionValue";
    private static final String STR_PROJECTION = "projection";
    private static final String STR_ACTIONS = "actions";
    private static final String STR_QUERY = "query";
    private static final String STR_SORT = "sort";
    private static final String STR_ACTIVE = "active";
    private static final String STR_DEPRECATED = "deprecated";
    private static final String STR_DISABLED = "disabled";
    private static final String STR_CONFIGURATION = "configuration";

    private static final Logger LOGGER = LoggerFactory.getLogger(MetadataParser.class);
    private static final Set<String> ENTITY_INFO_FIELDS = Sets.newHashSet(STR_ID, STR_NAME, STR_DEFAULT_VERSION, STR_INDEXES, STR_ENUMS, STR_HOOKS, STR_DATASTORE);

    private final Extensions<T> extensions;
    private final TypeResolver typeResolver;

    private interface ArrCb<S, D> {
        D parse(S child);
    }

    private final ArrCb<T, Index> parseIndex = new ArrCb<T, Index>() {
        @Override
        public Index parse(T child) {
            return parseIndex(child);
        }
    };

    private final ArrCb<T, Enum> parseEnum = new ArrCb<T, Enum>() {
        @Override
        public Enum parse(T child) {
            return parseEnum(child);
        }
    };

    private final ArrCb<T, Hook> parseHook = new ArrCb<T, Hook>() {
        @Override
        public Hook parse(T child) {
            return parseHook(child);
        }
    };

    public MetadataParser(Extensions<T> ex, TypeResolver typeResolver) {
        this.extensions = ex;
        this.typeResolver = typeResolver;
    }

    public DataStoreParser<T> getDataStoreParser(String backendName) {
        return extensions.getDataStoreParser(backendName);
    }

    public EntityConstraintParser<T> getEntityConstraintParser(String constraintName) {
        return extensions.getEntityConstraintParser(constraintName);
    }

    public FieldConstraintParser<T> getFieldConstraintParser(String constraintName) {
        return extensions.getFieldConstraintParser(constraintName);
    }

    /**
     * Entry point for entity metadata parser. Expects an Object corresponding
     * to the EntityMetadata object.
     *
     * @throws ParseException
     */
    public EntityMetadata parseEntityMetadata(T object) {
        Error.push("parseEntityMetadata");
        try {
            EntityInfo info = parseEntityInfo(getRequiredObjectProperty(object, STR_ENTITY_INFO));
            EntitySchema schema = parseEntitySchema(getRequiredObjectProperty(object, STR_SCHEMA));

            EntityMetadata md = new EntityMetadata(info, schema);

            return md;
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
     * Entry point for entity info parser. Expects an Object corresponding to
     * the EntityInfo object.
     *
     * @throws ParseException
     */
    public EntityInfo parseEntityInfo(T object) {
        Error.push("parseEntityInfo");
        try {
            String name = getRequiredStringProperty(object, STR_NAME);

            EntityInfo info = new EntityInfo(name);

            info.setDefaultVersion(getStringProperty(object, STR_DEFAULT_VERSION));
            info.getIndexes().setIndexes(parseArr(getObjectProperty(object, STR_INDEXES), parseIndex));
            info.getEnums().setEnums(parseArr(getObjectProperty(object, STR_ENUMS), parseEnum));
            info.getHooks().setHooks(parseArr(getObjectProperty(object, STR_HOOKS), parseHook));

            T backend = getRequiredObjectProperty(object, STR_DATASTORE);
            info.setDataStore(parseDataStore(backend));

            parsePropertyParser(object, info.getProperties());

            return info;
        } finally {
            Error.pop();
        }
    }

    public void parsePropertyParser(T object, Map<String, Object> properties) {
        Error.push("parsePropertyParser");
        try {

            Set<String> propertyFields = findFieldsNotIn(object, ENTITY_INFO_FIELDS);
            for (String property : propertyFields) {
                PropertyParser p = extensions.getPropertyParser(property);
                if (p != null) {
                    p.parseProperty(this, property, properties, getObjectProperty(object, property));
                }
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

    private <I> List<I> parseArr(T object, ArrCb<T, I> cb) {
        Error.push("parseArray");
        try {
            if (object != null) {
                List<T> children = getObjectList(object);

                List<I> list = new ArrayList<>();

                for (T child : children) {
                    list.add(cb.parse(child));
                }

                return list;
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

    public Index parseIndex(T object) {
        Error.push("parseIndex");
        try {
            if (object != null) {
                Index index = new Index();

                String name = getStringProperty(object, STR_NAME);
                Object unique = getValueProperty(object, STR_UNIQUE);
                List<T> fields = getObjectList(object, STR_FIELDS);

                if (null != name) {
                    index.setName(name);
                }

                if (null != unique) {
                    index.setUnique(Boolean.parseBoolean(unique.toString()));
                }

                if (null != fields && !fields.isEmpty()) {
                    List<SortKey> f = new ArrayList<>();

                    for (T s : fields) {
                        String fld = getRequiredStringProperty(s, "field");
                        String dir = getStringProperty(s, "dir");
                        if (dir == null) {
                            dir = "$asc";
                        }
                        SortKey sort = new SortKey(new Path(fld), "$desc".equals(dir));
                        f.add(sort);
                    }
                    index.setFields(f);
                } else {
                    throw Error.get(MetadataConstants.ERR_PARSE_MISSING_ELEMENT, STR_FIELDS);
                }

                return index;
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

    public Enum parseEnum(T object) {
        Error.push("parseEnum");
        try {
            if (object != null) {
                String name = getStringProperty(object, STR_NAME);
                if (name == null) {
                    throw Error.get(MetadataConstants.ERR_PARSE_MISSING_ELEMENT, STR_NAME);
                }
                Enum e = new Enum(name);
                List<String> values = getStringList(object, STR_VALUES);
                if (null != values && !values.isEmpty()) {
                    e.setValues(values);
                } else {
                    throw Error.get(MetadataConstants.ERR_PARSE_MISSING_ELEMENT, STR_VALUES);
                }

                return e;
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

    public Hook parseHook(T object) {
        Error.push("parseHook");
        try {
            if (object != null) {
                String name = getStringProperty(object, STR_NAME);
                if (name == null) {
                    throw Error.get(MetadataConstants.ERR_PARSE_MISSING_ELEMENT, STR_NAME);
                }
                Hook hook = new Hook(name);
                T x = getObjectProperty(object, STR_PROJECTION);
                if (x != null) {
                    hook.setProjection(parseProjection(x));
                }
                List<String> values = getStringList(object, STR_ACTIONS);
                if (values != null) {
                    hook.setInsert(values.contains(STR_INSERT));
                    hook.setUpdate(values.contains(STR_UPDATE));
                    hook.setDelete(values.contains(STR_DELETE));
                    hook.setFind(values.contains(STR_FIND));
                }
                T cfg = getObjectProperty(object, STR_CONFIGURATION);
                if (cfg != null) {
                    HookConfigurationParser<T> parser = extensions.getHookConfigurationParser(name);
                    if (parser == null) {
                        throw Error.get(MetadataConstants.ERR_INVALID_HOOK, name);
                    }
                    hook.setConfiguration(parser.parse(name, this, cfg));
                }
                return hook;
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
        return null;
    }

    /**
     * Entry point for entity schema parser. Expects an Object corresponding to
     * the EntitySchema object.
     *
     * @throws ParseException
     */
    public EntitySchema parseEntitySchema(T object) {
        Error.push("parseEntitySchema");
        try {
            String name = getRequiredStringProperty(object, STR_NAME);

            EntitySchema schema = new EntitySchema(name);
            T version = getRequiredObjectProperty(object, STR_VERSION);
            schema.setVersion(parseVersion(version));

            T status = getRequiredObjectProperty(object, STR_STATUS);
            parseStatus(schema, status);

            // TODO hooks
            T access = getObjectProperty(object, STR_ACCESS);
            if (access != null) {
                parseEntityAccess(schema.getAccess(), access);
            }

            T fields = getRequiredObjectProperty(object, STR_FIELDS);
            parseFields(schema.getFields(), fields);

            List<T> constraints = getObjectList(object, STR_CONSTRAINTS);
            parseEntityConstraints(schema, constraints);

            parsePropertyParser(object, schema.getProperties());

            return schema;
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
     * Parses metadata version
     *
     * @param object Object corresponding to the version element
     *
     * @return The parsed version
     */
    public Version parseVersion(T object) {
        Error.push(STR_VERSION);
        try {
            if (object != null) {
                Version v = new Version();
                v.setValue(getRequiredStringProperty(object, STR_VALUE));
                List<String> l = getStringList(object, STR_EXTEND_VERSIONS);
                if (l != null) {
                    v.setExtendsVersions(l.toArray(new String[l.size()]));
                }
                v.setChangelog(getRequiredStringProperty(object, STR_CHANGELOG));
                return v;
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

    /**
     * Parses metadata status, and populates metadata
     *
     * @throws ParseException
     */
    public void parseStatus(EntitySchema schema, T object) {
        Error.push(STR_STATUS);
        try {
            schema.setStatus(statusFromString(getRequiredStringProperty(object, STR_VALUE)));
            List<T> logList = getObjectList(object, STR_LOG);
            List<StatusChange> list = new ArrayList<>();
            if (logList != null) {
                for (T log : logList) {
                    StatusChange item = new StatusChange();
                    String d = getRequiredStringProperty(log, STR_DATE);
                    try {
                        item.setDate(DateType.getDateFormat().parse(d));
                    } catch (ParseException e) {
                        throw Error.get(MetadataConstants.ERR_ILL_FORMED_METADATA, d);
                    }
                    item.setStatus(statusFromString(getRequiredStringProperty(log, STR_VALUE)));
                    item.setComment(getRequiredStringProperty(log, STR_COMMENT));
                    list.add(item);
                }
                schema.setStatusChangeLog(list);
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
     * Parses metadata entity access
     *
     * @param access The entity access object to be initialized
     * @param object The object corresponding to the entity access element
     */
    public void parseEntityAccess(EntityAccess access,
                                  T object) {
        Error.push(STR_ACCESS);
        try {
            if (object != null) {
                parseAccess(access.getFind(), getStringList(object, STR_FIND));
                parseAccess(access.getUpdate(), getStringList(object, STR_UPDATE));
                parseAccess(access.getDelete(), getStringList(object, STR_DELETE));
                parseAccess(access.getInsert(), getStringList(object, STR_INSERT));
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
     * Parses entity constraints using the registered entity constraint parsers.
     *
     * Entity constraints are an object array where each object contains only
     * one field, the constraint name. The constraint data can be a simple
     * value, an array, or an object.
     */
    public void parseEntityConstraints(EntitySchema schema,
                                       List<T> constraintList) {
        if (constraintList != null) {
            List<EntityConstraint> entityConstraintList = new ArrayList<>();
            for (T x : constraintList) {
                // The constraint object must contain a single field
                String name = getSingleFieldName(x, MetadataConstants.ERR_INVALID_CONSTRAINT);
                Error.push(name);
                try {
                    EntityConstraintParser<T> parser = getEntityConstraintParser(name);
                    if (parser == null) {
                        throw Error.get(MetadataConstants.ERR_INVALID_CONSTRAINT, name);
                    }
                    EntityConstraint constraint = parser.parse(name, this, x);
                    entityConstraintList.add(constraint);
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
            if (!entityConstraintList.isEmpty()) {
                schema.setConstraints(entityConstraintList);
            }
        }
    }

    public void parseFieldConstraints(Field field,
                                      T fieldConstraints) {
        if (fieldConstraints != null) {
            // The constraint object must contain a single field
            Set<String> childNames = getChildNames(fieldConstraints);
            List<FieldConstraint> constraints = new ArrayList<>();
            for (String name : childNames) {
                Error.push(name);
                try {
                    FieldConstraintParser<T> parser = getFieldConstraintParser(name);
                    if (parser == null) {
                        throw Error.get(MetadataConstants.ERR_INVALID_CONSTRAINT, name);
                    }
                    // for each FieldConstraint call parse on the parent object
                    FieldConstraint constraint = parser.parse(name, this, fieldConstraints);
                    constraints.add(constraint);
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
            if (!constraints.isEmpty()) {
                field.setConstraints(constraints);
            }
        }
    }

    /**
     * Parses field access
     *
     * @param access The field access object to be initialized
     * @param object The object corresponding to the field access element
     */
    public void parseFieldAccess(FieldAccess access,
                                 T object) {
        Error.push(STR_ACCESS);
        try {
            if (object != null) {
                parseAccess(access.getFind(), getStringList(object, STR_FIND));
                parseAccess(access.getUpdate(), getStringList(object, STR_UPDATE));
                parseAccess(access.getInsert(), getStringList(object, STR_INSERT));
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

    private void parseAccess(Access access, List<String> roles) {
        if (roles != null) {
            access.setRoles(roles);
        }
    }

    /**
     * Parses and initializes fields
     *
     * @param fields The destination object to be initialized
     * @param object The object corresponding to the fields element
     */
    public void parseFields(Fields fields, T object) {
        Error.push(STR_FIELDS);
        try {
            if (object != null) {
                Set<String> names = getChildNames(object);
                for (String name : names) {
                    T fieldObject = getObjectProperty(object, name);
                    Field field = parseField(name, fieldObject);
                    fields.addNew(field);
                }
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
     * Parses a backend using a registered backend parser
     *
     * @param object The object for the backend element. The object must contain
     * only one object field whose name is used to resolve the backend parser
     *
     * @return The parsed backend. Returns null if object is null.
     */
    public DataStore parseDataStore(T object) {
        if (object != null) {
            LOGGER.debug("parseDataStore {}", object);
            String name = getRequiredStringProperty(object, STR_BACKEND);
            LOGGER.debug("Backend:{}", name);
            DataStoreParser<T> p = getDataStoreParser(name);
            LOGGER.debug("parser: {}", p);
            if (p == null) {
                throw Error.get(MetadataConstants.ERR_UNKNOWN_BACKEND, name);
            }
            return p.parse(name, this, object);
        } else {
            return null;
        }
    }

    /**
     * Returns the single field name contained in the object. If the object
     * contains more fields or no fields, throws an error with the given error
     * code.
     */
    private String getSingleFieldName(T object, String errorCode) {
        Set<String> names = getChildNames(object);
        if (names.size() != 1) {
            throw Error.get(errorCode, names.toString());
        }
        return names.iterator().next();
    }

    private Field parseField(String name, T object) {
        Field field;
        Error.push(name);
        try {
            if (object != null) {
                String type = getRequiredStringProperty(object, STR_TYPE);
                if (type.equals(ArrayType.TYPE.getName())) {
                    field = parseArrayField(name, object);
                } else if (type.equals(ObjectType.TYPE.getName())) {
                    field = parseObjectField(name, object);
                } else if (type.equals(ReferenceType.TYPE.getName())) {
                    field = parseReferenceField(name, object);
                } else {
                    field = parseSimpleField(name, type);
                }
                parseFieldAccess(field.getAccess(),
                        getObjectProperty(object, STR_ACCESS));
                parseFieldConstraints(field,
                        getObjectProperty(object, STR_CONSTRAINTS));

                parsePropertyParser(object, field.getProperties());
            } else {
                field = null;
            }
            return field;
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

    private Field parseSimpleField(String name,
                                   String type) {
        SimpleField field = new SimpleField(name);
        Type t = typeResolver.getType(type);
        if (t == null) {
            throw Error.get(MetadataConstants.ERR_INVALID_TYPE, type);
        }
        field.setType(t);
        return field;
    }

    private Field parseReferenceField(String name,
                                      T object) {
        ReferenceField field = new ReferenceField(name);
        field.setEntityName(getRequiredStringProperty(object, STR_ENTITY));
        field.setVersionValue(getRequiredStringProperty(object, STR_VERSION_VALUE));
        field.setProjection(parseProjection(getRequiredObjectProperty(object, STR_PROJECTION)));
        field.setQuery(parseQuery(getRequiredObjectProperty(object, STR_QUERY)));
        field.setSort(parseSort(getObjectProperty(object, STR_SORT)));
        return field;
    }

    private Field parseObjectField(String name, T object) {
        ObjectField field = new ObjectField(name);
        T fields = getRequiredObjectProperty(object, STR_FIELDS);
        parseFields(field.getFields(), fields);
        return field;
    }

    private Field parseArrayField(String name, T object) {
        ArrayField field = new ArrayField(name);
        T items = getRequiredObjectProperty(object, STR_ITEMS);
        field.setElement(parseArrayItem(items));
        return field;
    }

    private ArrayElement parseArrayItem(T items) {
        String type = getRequiredStringProperty(items, STR_TYPE);

        if (type.equals(ObjectType.TYPE.getName())) {
            T fields = getRequiredObjectProperty(items, STR_FIELDS);
            ObjectArrayElement ret = new ObjectArrayElement();
            ret.setType(ObjectType.TYPE);
            parseFields(ret.getFields(), fields);
            return ret;
        } else if (type.equals(ArrayType.TYPE.getName())
                || type.equals(ReferenceType.TYPE.getName())) {
            throw Error.get(MetadataConstants.ERR_INVALID_ARRAY_ELEMENT_TYPE, type);
        } else {
            SimpleArrayElement ret = new SimpleArrayElement();
            Type t = typeResolver.getType(type);
            if (t == null) {
                throw Error.get(MetadataConstants.ERR_INVALID_TYPE, type);
            }
            ret.setType(t);
            return ret;
        }
    }

    /**
     * Converts the entity metadata to T
     */
    public T convert(EntityMetadata md) {
        Error.push("convert[metadata]");
        try {
            T ret = newNode();
            putObject(ret, STR_ENTITY_INFO, convert(md.getEntityInfo()));
            putObject(ret, STR_SCHEMA, convert(md.getEntitySchema()));
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

    /**
     * Converts the entity metadata to T
     */
    public T convert(EntityInfo info) {
        Error.push("convert[info]");
        try {
            T ret = newNode();
            if (info.getName() != null) {
                putString(ret, STR_NAME, info.getName());
            }
            if (info.getDefaultVersion() != null) {
                putString(ret, STR_DEFAULT_VERSION, info.getDefaultVersion());
            }
            if (info.getIndexes() != null && !info.getIndexes().isEmpty()) {
                // indexes is an array directly on the entity info, so do not create a new node ere, let conversion handle it
                convertIndexes(ret, info.getIndexes());
            }
            if (info.getEnums() != null && !info.getEnums().isEmpty()) {
                // enumsis an array directly on the entity info, so do not create a new node ere, let conversion handle it
                convertEnums(ret, info.getEnums());
            }
            if (info.getDataStore() != null) {
                T dsNode = newNode();
                convertDataStore(dsNode, info.getDataStore());
                putObject(ret, STR_DATASTORE, dsNode);
            }
            if (info.getProperties() != null && !info.getProperties().isEmpty()) {
                for (String s : info.getProperties().keySet()) {
                    PropertyParser p = extensions.getPropertyParser(s);
                    if (p != null) {
                        T node = newNode();
                        p.convert(this, node, info.getProperties().get(s));
                        putObject(ret, s, node);
                    }
                }
            }
            convertPropertyParser(ret, info.getProperties());
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

    /**
     * Converts the entity metadata to T
     */
    public T convert(EntitySchema schema) {
        Error.push("convert[schema]");
        try {
            T ret = newNode();
            if (schema.getName() != null) {
                putString(ret, STR_NAME, schema.getName());
            }
            putObject(ret, STR_VERSION, convert(schema.getVersion()));
            putObject(ret, STR_STATUS, convert(schema.getStatus(), schema.getStatusChangeLog()));
            putObject(ret, STR_ACCESS, convert(schema.getAccess()));
            putObject(ret, STR_FIELDS, convert(schema.getFields()));
            convertPropertyParser(ret, schema.getProperties());
            convertEntityConstraints(ret, schema.getConstraints());
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

    public void convertPropertyParser(T object, Map<String, Object> properties) {
        Error.push("convertPropertyParser");
        try {

            for (Entry entry : properties.entrySet()) {
                String key = (String) entry.getKey();
                PropertyParser p = extensions.getPropertyParser(key);
                if (p != null) {
                    p.convert(this, object, entry.getValue());
                }
            }
        } finally {
            Error.pop();
        }
    }

    /**
     * Converts metadata version to T
     */
    public T convert(Version v) {
        if (v != null) {
            Error.push(STR_VERSION);
            try {
                T obj = newNode();
                if (v.getValue() != null) {
                    putString(obj, STR_VALUE, v.getValue());
                }
                String[] ex = v.getExtendsVersions();
                if (ex != null && ex.length > 0) {
                    Object arr = newArrayField(obj, STR_EXTEND_VERSIONS);
                    for (String x : ex) {
                        addStringToArray(arr, x);
                    }
                }
                if (v.getChangelog() != null) {
                    putString(obj, STR_CHANGELOG, v.getChangelog());
                }
                return obj;
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
        } else {
            return null;
        }
    }

    public T convert(MetadataStatus status, List<StatusChange> changeLog) {
        if (status != null && changeLog != null) {
            Error.push(STR_STATUS);
            try {
                T obj = newNode();
                putString(obj, STR_VALUE, toString(status));

                // only create log if you have a value for status, else isn't schema compliant
                if (!changeLog.isEmpty()) {
                    Object logArray = newArrayField(obj, STR_LOG);
                    for (StatusChange x : changeLog) {
                        T log = newNode();
                        if (x.getDate() != null) {
                            putString(log, STR_DATE, DateType.getDateFormat().format(x.getDate()));
                        }
                        if (x.getStatus() != null) {
                            putString(log, STR_VALUE, toString(x.getStatus()));
                        }
                        if (x.getComment() != null) {
                            putString(log, STR_COMMENT, x.getComment());
                        }
                        addObjectToArray(logArray, log);
                    }
                }
                return obj;
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
        return null;
    }

    /**
     * Converts entity access to T
     */
    public T convert(EntityAccess access) {
        if (access != null) {
            Error.push(STR_ACCESS);
            try {
                T ret = newNode();
                convertRoles(ret, STR_INSERT, access.getInsert());
                convertRoles(ret, STR_UPDATE, access.getUpdate());
                convertRoles(ret, STR_FIND, access.getFind());
                convertRoles(ret, STR_DELETE, access.getDelete());
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
        } else {
            return null;
        }
    }

    /**
     * Converts field access to T
     */
    public T convert(FieldAccess access) {
        if (access != null && (access.getFind().getRoles().size() > 0 || access.getUpdate().getRoles().size() > 0)) {
            Error.push(STR_ACCESS);
            try {
                T ret = newNode();
                if (access.getFind().getRoles().size() > 0) {
                    convertRoles(ret, STR_FIND, access.getFind());
                }
                if (access.getUpdate().getRoles().size() > 0) {
                    convertRoles(ret, STR_UPDATE, access.getUpdate());
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
        } else {
            return null;
        }
    }

    /**
     * Converts fields to T
     */
    public T convert(Fields fields) {
        T ret = newNode();
        for (Iterator<Field> itr = fields.getFields(); itr.hasNext();) {
            Field field = itr.next();
            T fieldObject = newNode();
            Error.push(field.getName());
            try {
                putObject(ret, field.getName(), fieldObject);
                putString(fieldObject, STR_TYPE, field.getType().getName());
                if (field instanceof ArrayField) {
                    convertArrayField((ArrayField) field, fieldObject);
                } else if (field instanceof ObjectField) {
                    convertObjectField((ObjectField) field, fieldObject);
                } else if (field instanceof ReferenceField) {
                    convertReferenceField((ReferenceField) field, fieldObject);
                }
                T access = convert(field.getAccess());
                if (access != null) {
                    putObject(fieldObject, STR_ACCESS, access);
                }
                convertFieldConstraints(fieldObject, field.getConstraints());
                convertPropertyParser(ret, field.getProperties());
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
        convertPropertyParser(ret, fields.getProperties());
        return ret;
    }

    /**
     * Creates a STR_CONSTRAINTS array in <code>parent</code> and fills it up
     * with constraints
     */
    public void convertFieldConstraints(T parent, List<FieldConstraint> constraints) {
        if (constraints != null && !constraints.isEmpty()) {
            Error.push(STR_CONSTRAINTS);
            try {
                T constraintNode = newNode();
                putObject(parent, STR_CONSTRAINTS, constraintNode);
                for (FieldConstraint constraint : constraints) {
                    String constraintType = constraint.getType();
                    FieldConstraintParser<T> parser = getFieldConstraintParser(constraintType);
                    if (parser == null) {
                        throw Error.get(MetadataConstants.ERR_INVALID_CONSTRAINT, constraintType);
                    }
                    parser.convert(this, constraintNode, constraint);
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

    /**
     * Creates a STR_CONSTRAINTS array in <code>parent</code> and fills it up
     * with constraints
     */
    public void convertEntityConstraints(T parent, List<EntityConstraint> constraints) {
        if (constraints != null && !constraints.isEmpty()) {
            Error.push(STR_CONSTRAINTS);
            try {
                Object arr = newArrayField(parent, STR_CONSTRAINTS);
                for (EntityConstraint constraint : constraints) {
                    String constraintType = constraint.getType();
                    EntityConstraintParser<T> parser = getEntityConstraintParser(constraintType);
                    if (parser == null) {
                        throw Error.get(MetadataConstants.ERR_INVALID_CONSTRAINT, constraintType);
                    }
                    T constraintNode = newNode();
                    parser.convert(this, constraintNode, constraint);
                    addObjectToArray(arr, constraintNode);
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

    public void convertIndexes(T parent, Indexes indexes) {
        Error.push(STR_INDEXES);
        try {
            if (indexes != null && !indexes.isEmpty()) {
                // create array node for indexes
                Object array = newArrayField(parent, STR_INDEXES);

                // for each index, add it to array
                for (Index i : indexes.getIndexes()) {
                    T node = newNode();
                    addObjectToArray(array, node);
                    putString(node, STR_NAME, i.getName());
                    // assume that if is not unique we don't need to set the flag
                    if (i.isUnique()) {
                        putValue(node, STR_UNIQUE, Boolean.TRUE);
                    }

                    // for each field, add to a new fields array
                    Object indexObj = newArrayField(node, STR_FIELDS);
                    for (SortKey p : i.getFields()) {
                        T node2 = newNode();
                        putString(node2, "field", p.getField().toString());
                        putString(node2, "dir", p.isDesc() ? "$desc" : "$asc");
                        addObjectToArray(indexObj, node2);
                    }
                }
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

    public void convertEnums(T parent, Enums enums) {
        Error.push(STR_INDEXES);
        try {
            if (enums != null && !enums.isEmpty()) {
                // create array node for enums
                Object array = newArrayField(parent, STR_ENUMS);

                // for each enum, add it to array
                for (Enum e : enums.getEnums().values()) {
                    T node = newNode();
                    addObjectToArray(array, node);
                    putString(node, STR_NAME, e.getName());

                    // for each value, add to a new values array
                    Object indexObj = newArrayField(node, STR_VALUES);
                    for (String v : e.getValues()) {
                        addStringToArray(indexObj, v);
                    }
                }
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
     * Adds the description of backend to parent as a field named by the type of
     * the backend
     */
    public void convertDataStore(T dsNode, DataStore store) {
        Error.push("convertDataStore");
        try {
            String type = store.getBackend();
            DataStoreParser<T> parser = getDataStoreParser(type);
            if (parser == null) {
                throw Error.get(MetadataConstants.ERR_UNKNOWN_BACKEND, type);
            }
            parser.convert(this, dsNode, store);
            putString(dsNode, STR_BACKEND, type);
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

    private void convertObjectField(ObjectField field, T fieldObject) {
        putObject(fieldObject, STR_FIELDS, convert(field.getFields()));
    }

    private void convertArrayField(ArrayField field, T fieldObject) {
        ArrayElement el = field.getElement();
        T items = newNode();
        putObject(fieldObject, STR_ITEMS, items);
        putString(items, STR_TYPE, el.getType().getName());
        if (el instanceof ObjectArrayElement) {
            convertObjectArrayElement((ObjectArrayElement) el, items);
        }
    }

    private void convertReferenceField(ReferenceField field, T fieldObject) {
        putString(fieldObject, STR_ENTITY, field.getEntityName());
        putString(fieldObject, STR_VERSION_VALUE, field.getVersionValue());
        if (field.getProjection() != null) {
            putString(fieldObject, STR_PROJECTION, field.getProjection().toString());
        }
        if (field.getQuery() != null) {
            putString(fieldObject, STR_QUERY, field.getQuery().toString());
        }
        if (field.getSort() != null) {
            putString(fieldObject, STR_SORT, field.getSort().toString());
        }
    }

    private void convertObjectArrayElement(ObjectArrayElement el, T items) {
        putObject(items, STR_FIELDS, convert(el.getFields()));
    }

    private void convertRoles(T node, String name, Access roles) {
        if (roles != null) {
            Object arr = newArrayField(node, name);
            Set<String> r = roles.getRoles();
            for (String x : r) {
                addStringToArray(arr, x);
            }
        }
    }

    public static String toString(MetadataStatus status) {
        switch (status) {
            case ACTIVE:
                return STR_ACTIVE;
            case DEPRECATED:
                return STR_DEPRECATED;
            case DISABLED:
                return STR_DISABLED;
        }
        return null;
    }

    public static MetadataStatus statusFromString(String status) {
        switch (status) {
            case STR_ACTIVE:
                return MetadataStatus.ACTIVE;
            case STR_DEPRECATED:
                return MetadataStatus.DEPRECATED;
            case STR_DISABLED:
                return MetadataStatus.DISABLED;
            default:
                throw Error.get(MetadataConstants.ERR_PARSE_INVALID_STATUS, status);
        }
    }

    /**
     * Returns a string child property
     *
     * @param object The object containing the property
     * @param name Name of the property to return
     *
     * If the property is not a string, should throw exception
     *
     * @return The string property requested, or null if property does not exist
     */
    public abstract String getStringProperty(T object, String name);

    /**
     * Returns a string child property, fail if the child property is not found.
     *
     * @param object The object containing the property
     * @param name Name of the property to return
     *
     * If the property is not a string, should throw exception
     *
     * @return The string property requested, or null if property does not exist
     */
    public String getRequiredStringProperty(T object, String name) {
        Error.push("getRequiredStringProperty");
        Error.push(name);
        try {
            String property = getStringProperty(object, name);
            if (property == null || property.trim().length() == 0) {
                throw Error.get(MetadataConstants.ERR_PARSE_MISSING_ELEMENT, name);
            }
            return property;
        } catch (Error e) {
            // rethrow lightblue error
            throw e;
        } catch (Exception e) {
            // throw new Error (preserves current error context)
            LOGGER.error(e.getMessage(), e);
            throw Error.get(MetadataConstants.ERR_ILL_FORMED_METADATA, e.getMessage());
        } finally {
            Error.pop();
            Error.pop();
        }
    }

    /**
     * Returns an object child property
     *
     * @param object The object containing the property
     * @param name Name of the property to return
     *
     * If the property is not an object, should throw an exception
     *
     * @return The property requested, or null if property does not exist
     */
    public abstract T getObjectProperty(T object, String name);

    /**
     * Returns an object child property, fail if the child property is not
     * found.
     *
     * @param object The object containing the property
     * @param name Name of the property to return
     *
     * If the property is not an object, should throw an exception
     *
     * @return The property requested, or null if property does not exist
     */
    public T getRequiredObjectProperty(T object, String name) {
        Error.push("getRequiredObjectProperty");
        Error.push(name);
        try {
            T property = getObjectProperty(object, name);
            if (property == null) {
                throw Error.get(MetadataConstants.ERR_PARSE_MISSING_ELEMENT, name);
            }
            return property;
        } catch (Error e) {
            // rethrow lightblue error
            throw e;
        } catch (Exception e) {
            // throw new Error (preserves current error context)
            LOGGER.error(e.getMessage(), e);
            throw Error.get(MetadataConstants.ERR_ILL_FORMED_METADATA, e.getMessage());
        } finally {
            Error.pop();
            Error.pop();
        }
    }

    /**
     * Returns a property that is a simple value
     *
     * @param object The object containing the property
     * @param name Name of the property to return
     *
     * If the property is not a simple java value, should throw exception
     *
     * @return The property value requested (String, Number, Boolean, etc), or
     * null if property does not exist
     */
    public abstract Object getValueProperty(T object, String name);

    /**
     * Returns a string list child property
     *
     * @param object The object containing the property
     * @param name Name of the string list
     *
     * @return The string list property, or null if property does not exist
     */
    public abstract List<String> getStringList(T object, String name);

    /**
     * Returns an object list of child property
     *
     * @param object The object containing the property
     * @param name Name of the property
     *
     * @return Object list property, or null if property does not exist
     */
    public abstract List<T> getObjectList(T object, String name);

    /**
     * Returns an object list of all children
     *
     * @param object The parent object.
     *
     * @return Object list of all children, or null if there are no children
     */
    public abstract List<T> getObjectList(T object);

    /**
     * Returns the names of the child elements
     *
     * @param object The object
     *
     * @return The names of child elements
     */
    public abstract Set<String> getChildNames(T object);

    /**
     * Parse and return a projection
     */
    public abstract Projection parseProjection(T object);

    /**
     * Parse and return a query
     */
    public abstract QueryExpression parseQuery(T object);

    /**
     * Parse and return a sort
     */
    public abstract Sort parseSort(T object);

    /**
     * Creates a new node
     */
    public abstract T newNode();

    /**
     * Adds a new string field to the object.
     */
    public abstract void putString(T object, String name, String value);

    /**
     * Adds a new object field to the object.
     */
    public abstract void putObject(T object, String name, Object value);

    /**
     * Adds a simple value field
     */
    public abstract void putValue(T object, String name, Object value);

    /**
     * Creates a new array field
     */
    public abstract Object newArrayField(T object, String name);

    /**
     * Adds an element to the array
     */
    public abstract void addStringToArray(Object array, String value);

    /**
     * Adds an element to the array
     */
    public abstract void addObjectToArray(Object array, Object value);

    public abstract Set<String> findFieldsNotIn(T elements, Set<String> removeAllFields);

}

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.redhat.lightblue.metadata.ArrayElement;
import com.redhat.lightblue.metadata.ArrayField;
import com.redhat.lightblue.metadata.EntityMetadata;
import com.redhat.lightblue.metadata.FieldTreeNode;
import com.redhat.lightblue.metadata.ObjectField;
import com.redhat.lightblue.metadata.SimpleArrayElement;
import com.redhat.lightblue.metadata.SimpleField;
import com.redhat.lightblue.metadata.ResolvedReferenceField;
import com.redhat.lightblue.query.ArrayQueryMatchProjection;
import com.redhat.lightblue.query.ArrayRangeProjection;
import com.redhat.lightblue.query.FieldProjection;
import com.redhat.lightblue.query.Projection;
import com.redhat.lightblue.query.ProjectionList;
import com.redhat.lightblue.util.JsonDoc;
import com.redhat.lightblue.util.JsonNodeCursor;
import com.redhat.lightblue.util.Path;

/**
 * This class evaluates a Projection. 
 *
 * This is a stateful class. It retains state from the last execution
 * that gets overwritten every time project() is called.
 *
 * This is how a document is projected: all the elements in the
 * document is traversed in a depth first manner. For each field, the
 * projection is evaluated.  If the projection evaluated to
 * <code>true</code>, the field is included, and projection continues
 * to the subtree under that field. If the projection evaluates to
 * <code>false</code>, that subtree is excluded. If the projection for
 * that field cannot be decided, the a warning is logged, and field is
 * excluded. Array fields can have nested projections to project their
 * array elements.
 *
 * Recursive inclusion projections don't cross entity boundaries
 * (i.e. references) unless there is an explicit inclusion projection
 * for the referenced entity, or a field under that entity.
 */
public abstract class Projector {

    private static final Logger LOGGER = LoggerFactory.getLogger(Projector.class);

    private final FieldTreeNode rootMdNode;
    private final Path rootMdPath;

    protected Projector(Path ctxPath, FieldTreeNode ctx) {
        this.rootMdNode = ctx;
        this.rootMdPath = ctxPath;
    }

    /**
     * Returns the nested projector for this path *only if*
     * <code>project</code> returns true. Nested projector is used to
     * project array elements. When a nested projector exists,
     * projection operation should use the nested projector to project
     * array elements.  May return null, which means to continue using
     * existing projector (this).
     */
    public abstract Projector getNestedProjector();

    /**
     * If <code>project</code> returns true or false (not null),
     * <code>exactMatch</code> returns whether the decision
     * about the inclusion or exclusion of the current field is given
     * by an exact match, or by a recursive include/exclude. If
     * returns false, then the field is a descendant of another field,
     * and the projection rule is a recursive inclusion of that
     * descendant. If this call returns true, then there exists an
     * inclusion or exclusion explicitly for this field.
     */
    public abstract boolean exactMatch();

    /**
     * Is the projection is an inclusion or an exclusion, then returns the projector
     * that decided to include or exclude this field.
     */
    public abstract Projector getDecidingProjector();

    /**
     * Returns true, false, or null if the result cannot be determined.
     *
     * @param p The absolute field path
     * @param ctx Query evaluation context
     */
    public abstract Boolean project(Path p, QueryEvaluationContext ctx);

    /**
     * Builds a projector using the given projection and entity metadata
     */
    public static Projector getInstance(Projection projection, EntityMetadata md) {
        return getInstance(projection, Path.EMPTY, md.getFieldTreeRoot());
    }

    /**
     * Builds a (potentially nested) projection based on the given projection,
     * and the location in the metadata field tree.
     */
    public static Projector getInstance(Projection projection, Path ctxPath, FieldTreeNode ctx) {
        if (projection instanceof FieldProjection) {
            return new FieldProjector((FieldProjection) projection, ctxPath, ctx);
        } else if (projection instanceof ProjectionList) {
            return new ListProjector((ProjectionList) projection, ctxPath, ctx);
        } else if (projection instanceof ArrayRangeProjection) {
            return new ArrayRangeProjector((ArrayRangeProjection) projection, ctxPath, ctx);
        } else {
            return new ArrayQueryProjector((ArrayQueryMatchProjection) projection, ctxPath, ctx);
        }
    }

    /**
     * Projects a document
     */
    public JsonDoc project(JsonDoc doc,
                           JsonNodeFactory factory) {
        JsonNodeCursor cursor = doc.cursor();
        cursor.firstChild();

        ObjectNode root = projectObject(this,
                factory,
                rootMdNode,
                rootMdPath,
                cursor,
                new QueryEvaluationContext(doc.getRoot()));
        return new JsonDoc(root);
    }

    private ObjectNode projectObject(Projector projector,
                                     JsonNodeFactory factory,
                                     FieldTreeNode mdContext,
                                     Path contextPath,
                                     JsonNodeCursor cursor,
                                     QueryEvaluationContext ctx) {
        ObjectNode ret = factory.objectNode();
        do {
            Path fieldPath = cursor.getCurrentPath();
            // The context path *is* a prefix of the field path 
            Path contextRelativePath = contextPath.isEmpty() ? fieldPath : fieldPath.suffix(-contextPath.numSegments());
            JsonNode fieldNode = cursor.getCurrentNode();
            LOGGER.debug("projectObject context={} fieldPath={} contextRelativePath={}", contextPath, fieldPath, contextRelativePath);
            FieldTreeNode fieldMd = mdContext.resolve(contextRelativePath);
            if (fieldMd != null) {
                LOGGER.debug("Projecting {} in context {}", contextRelativePath, contextPath);
                Boolean result = projector.project(fieldPath, ctx);
                if (result != null) {
                    if (result) {
                        LOGGER.debug("Projection includes {} md={}", fieldPath,fieldMd);
                        if (fieldMd instanceof ObjectField) {
                            projectObjectField(fieldNode, ret, fieldPath, cursor, projector, mdContext, contextPath, factory, ctx);
                        } else if (fieldMd instanceof SimpleField) {
                            projectSimpleField(fieldNode, ret, fieldPath);
                        } else if(fieldMd instanceof ResolvedReferenceField) {
                            // The decision to recurse into a resolved
                            // reference is made by the existence of
                            // an exact matching projection
                            if(projector.exactMatch()) {
                                projectArrayField(projector,factory,fieldMd,ret,fieldPath,fieldNode,cursor,ctx);
                            } else {
                                LOGGER.debug("Projection excludes {} because it crosses entity boundary with no explicit projection", fieldPath);
                            }
                        } else if (fieldMd instanceof ArrayField) {
                            projectArrayField(projector, factory, fieldMd, ret, fieldPath, fieldNode, cursor, ctx);
                        }
                    } else {
                        LOGGER.debug("Projection excludes {}", fieldPath);
                    }
                } else {
                    LOGGER.debug("No projection match for {}", fieldPath);
                }
            } else {
                LOGGER.warn("Unknown field {}", fieldPath);
            }
        } while (cursor.nextSibling());
        return ret;
    }

    private JsonNode projectObjectField(JsonNode fieldNode, ObjectNode ret, Path fieldPath, JsonNodeCursor cursor, Projector projector, FieldTreeNode mdContext, Path contextPath, JsonNodeFactory factory, QueryEvaluationContext ctx) {
        if (fieldNode instanceof ObjectNode) {
            LOGGER.debug("projecting object node {}",fieldPath);
            if (cursor.firstChild()) {
                ObjectNode newNode = projectObject(projector, factory, mdContext, contextPath, cursor, ctx);
                ret.set(fieldPath.tail(0), newNode);
                cursor.parent();
            } else {
                ret.set(fieldPath.tail(0), factory.objectNode());
            }
        } else {
            LOGGER.warn("Expecting object node, found {} for {}", fieldNode.getClass().getName(), fieldPath);
        }
        return null;
    }

    private JsonNode projectSimpleField(JsonNode fieldNode, ObjectNode ret, Path fieldPath) {
        if (fieldNode.isValueNode()) {
            LOGGER.debug("Projection value node {}",fieldPath);
            ret.set(fieldPath.tail(0), fieldNode);
        } else {
            LOGGER.warn("Expecting value node, found {} for {}", fieldNode.getClass().getName(), fieldPath);
        }
        return null;
    }

    private JsonNode projectArrayField(Projector projector,
                                       JsonNodeFactory factory,
                                       FieldTreeNode fieldMd,
                                       ObjectNode ret,
                                       Path fieldPath,
                                       JsonNode fieldNode,
                                       JsonNodeCursor cursor,
                                       QueryEvaluationContext ctx) {

        if (fieldNode instanceof ArrayNode) {
            LOGGER.debug("Projecting array field {}",fieldPath);
            Projector deciding=projector.getDecidingProjector();
            ArrayNode newNode = factory.arrayNode();
            if (cursor.firstChild()) {
                do {
                    JsonNode node = projectArrayElement(projector,
                            factory,
                            ((ArrayField) fieldMd).getElement(),
                            fieldPath,
                            cursor,
                            ctx);
                    if (node != null) {
                        newNode.add(node);
                    }
                } while (cursor.nextSibling());
                cursor.parent();
                if(deciding instanceof ArrayProjector&&
                   ((ArrayProjector)deciding).getSort()!=null) {
                    LOGGER.debug("Sorting array elements using {}",((ArrayProjector)deciding).getSort());
                    newNode=((ArrayProjector)deciding).sortArray(newNode,factory);
                }
            }            
            ret.set(fieldPath.tail(0), newNode);
        } else {
            LOGGER.warn("Expecting array node, found {} for {}", fieldNode.getClass().getName(), fieldPath);
        }
        return null;
    }


    private JsonNode projectArrayElement(Projector projector,
                                         JsonNodeFactory factory,
                                         ArrayElement mdContext,
                                         Path contextPath,
                                         JsonNodeCursor cursor,
                                         QueryEvaluationContext ctx) {
        Path elemPath = cursor.getCurrentPath();
        LOGGER.debug("Project array element {}  context {}", elemPath, contextPath);
        Boolean result = projector.project(elemPath, ctx);
        if (result != null) {
            if (result) {
                Projector nestedProjector = projector.getNestedProjector();
                if (nestedProjector == null) {
                    nestedProjector = projector;
                }
                LOGGER.debug("Projection includes {}", elemPath);
                if (mdContext instanceof SimpleArrayElement) {
                    return cursor.getCurrentNode();
                } else {
                    if (cursor.firstChild()) {
                        // Object array element
                        JsonNode ret = projectObject(nestedProjector, factory, mdContext, elemPath, cursor, ctx);
                        cursor.parent();
                        return ret;
                    } else {
                        return factory.objectNode();
                    }
                }
            } else {
                LOGGER.debug("Projection excludes {}", elemPath);
            }
        } else {
            LOGGER.debug("No projection match for {}", elemPath);
        }
        return null;
    }
}

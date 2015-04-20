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

import java.util.List;
import java.util.ListIterator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.redhat.lightblue.metadata.ArrayElement;
import com.redhat.lightblue.metadata.ArrayField;
import com.redhat.lightblue.metadata.EntityMetadata;
import com.redhat.lightblue.metadata.FieldTreeNode;
import com.redhat.lightblue.metadata.ObjectField;
import com.redhat.lightblue.metadata.SimpleArrayElement;
import com.redhat.lightblue.metadata.ObjectArrayElement;
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
        
        ObjectNode root=(ObjectNode)project(factory,
                                            rootMdPath,
                                            rootMdNode,
                                            cursor,
                                            new QueryEvaluationContext(doc.getRoot()),
                                            false);
        if(root==null)
            root=factory.objectNode();
        return new JsonDoc(root);
    }

    private JsonNode project(JsonNodeFactory factory,
                             Path contextPath,
                             FieldTreeNode contextNode,
                             JsonNodeCursor cursor,
                             QueryEvaluationContext ctx,
                             boolean processingArray) {
        JsonNode parentNode=null;
        do {
            Path fieldPath=cursor.getCurrentPath();
            Path contextRelativePath = contextPath.isEmpty() ? fieldPath : fieldPath.suffix(-contextPath.numSegments());
            JsonNode fieldNode = cursor.getCurrentNode();
            LOGGER.debug("project context={} fieldPath={} contextRelativePath={} isArray={}", contextPath, fieldPath, contextRelativePath, processingArray);
            FieldTreeNode fieldMd = contextNode.resolve(contextRelativePath);
            if(fieldMd!=null) {
                Boolean result=project(fieldPath,ctx);
                LOGGER.debug("Projecting '{}' in context '{}': {}", contextRelativePath, contextPath, result);
                if(result==null) {
                    // Projection is undecisive. Recurse into array/object/reference nodes and see if anything is projected there
                    if(!(fieldNode instanceof NullNode)) {
                        if(fieldMd instanceof ObjectField ||
                           fieldMd instanceof ArrayField ||
                           fieldMd instanceof ObjectArrayElement ||
                           fieldMd instanceof ResolvedReferenceField) {
                            JsonNode newNode;
                            if(cursor.firstChild()) {
                                newNode=(getNestedProjector()==null?this:getNestedProjector()).
                                    project(factory,contextPath,contextNode,cursor,ctx,!(fieldMd instanceof ObjectField ||
                                                                                         fieldMd instanceof ObjectArrayElement));
                                cursor.parent();
                            } else {
                                if(fieldMd instanceof ObjectField)
                                    newNode=factory.objectNode();
                                else
                                    newNode=factory.arrayNode();
                            }
                            if(newNode!=null) {
                                if(newNode instanceof ArrayNode)
                                    newNode=sort(factory,this,(ArrayNode)newNode,fieldPath);
                                if(parentNode==null)
                                    parentNode=processingArray?factory.arrayNode():factory.objectNode();
                                if(parentNode instanceof ArrayNode)
                                    ((ArrayNode)parentNode).add(newNode);
                                else
                                    ((ObjectNode)parentNode).set(fieldPath.tail(0), newNode);
                            }
                        }
                    }
                } else if(result) {
                    // Field is included
                    if(fieldNode instanceof NullNode) {
                        if(parentNode==null)
                            parentNode=processingArray?factory.arrayNode():factory.objectNode();
                        if(parentNode instanceof ArrayNode)
                            ((ArrayNode)parentNode).add(fieldNode);
                        else
                            ((ObjectNode)parentNode).set(fieldPath.tail(0), fieldNode);
                    } else {
                        JsonNode newNode=null;
                        if(fieldMd instanceof ObjectField||
                           fieldMd instanceof ObjectArrayElement) {
                            if(cursor.firstChild()) {
                                newNode=(getNestedProjector()==null?this:getNestedProjector()).
                                    project(factory,contextPath,contextNode,cursor,ctx,false);
                                cursor.parent();
                                if(newNode==null)
                                    newNode=factory.objectNode();
                            }
                        } else if(fieldMd instanceof ArrayField||
                                  fieldMd instanceof ResolvedReferenceField) {
                            if(cursor.firstChild()) {
                                newNode=(getNestedProjector()==null?this:getNestedProjector()).
                                    project(factory,contextPath,contextNode,cursor,ctx,true);
                                cursor.parent();
                                if(newNode==null)
                                    newNode=factory.arrayNode();
                            }                            
                        } else if(fieldMd instanceof SimpleField) {
                            newNode=fieldNode;
                        } else if(fieldMd instanceof SimpleArrayElement) {
                            newNode=fieldNode;
                        } 
                        if(newNode!=null) {
                            if(newNode instanceof ArrayNode)
                                newNode=sort(factory,this,(ArrayNode)newNode,fieldPath);
                            if(parentNode==null)
                                parentNode=processingArray?factory.arrayNode():factory.objectNode();
                            if(parentNode instanceof ArrayNode)
                                ((ArrayNode)parentNode).add(newNode);
                            else
                                ((ObjectNode)parentNode).set(fieldPath.tail(0), newNode);
                        }
                    }
                }
            } else {
                LOGGER.warn("Unknown field:{}",fieldPath);
            }
        } while (cursor.nextSibling());
        return parentNode;
    }

    private static ArrayNode sort(JsonNodeFactory factory,Projector projector,ArrayNode node,Path nodePath) {
        ArrayProjector p=findArrayProjectorForField(projector,nodePath);
        if(p!=null && p.getSort()!=null) {
            LOGGER.debug("Sorting array elements using {}",p.getSort());
            return p.sortArray(node,factory);
        } 
        return node;
    }

    private static ArrayProjector findArrayProjectorForField(Projector p,Path field) {
        if(p instanceof ListProjector) {
            List<Projector> items=((ListProjector)p).getItems();
            ListIterator<Projector> itemsItr=items.listIterator(items.size());
            while (itemsItr.hasPrevious()) {
                Projector projector=itemsItr.previous();
                ArrayProjector x=findArrayProjectorForField(projector,field);
                if(x!=null)
                    return x;
            }
        } else if(p instanceof ArrayProjector) {
            if( field.matches( ((ArrayProjector)p).getArrayFieldPattern() ) ) {
                return (ArrayProjector)p;
            }
            return findArrayProjectorForField( p.getNestedProjector(),field );
        }
        return null;
    }
}

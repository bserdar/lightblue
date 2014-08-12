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

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.WriteResult;
import com.redhat.lightblue.interceptor.InterceptPoint;
import com.redhat.lightblue.crud.CRUDOperationContext;
import com.redhat.lightblue.crud.CRUDUpdateResponse;
import com.redhat.lightblue.crud.ConstraintValidator;
import com.redhat.lightblue.crud.CrudConstants;
import com.redhat.lightblue.crud.DocCtx;
import com.redhat.lightblue.crud.Operation;
import com.redhat.lightblue.eval.FieldAccessRoleEvaluator;
import com.redhat.lightblue.eval.Projector;
import com.redhat.lightblue.eval.Updater;
import com.redhat.lightblue.metadata.EntityMetadata;
import com.redhat.lightblue.metadata.PredefinedFields;
import com.redhat.lightblue.mongo.hystrix.FindCommand;
import com.redhat.lightblue.mongo.hystrix.SaveCommand;
import com.redhat.lightblue.util.Error;
import com.redhat.lightblue.util.Path;

/**
 * Non-atomic updater that evaluates the query, and updates the documents one by
 * one.
 */
public class IterateAndUpdate implements DocUpdater {

    private static final Logger LOGGER = LoggerFactory.getLogger(IterateAndUpdate.class);

    private final JsonNodeFactory nodeFactory;
    private final ConstraintValidator validator;
    private final FieldAccessRoleEvaluator roleEval;
    private final Translator translator;
    private final Updater updater;
    private final Projector projector;
    private final Projector errorProjector;

    public IterateAndUpdate(JsonNodeFactory nodeFactory,
                            ConstraintValidator validator,
                            FieldAccessRoleEvaluator roleEval,
                            Translator translator,
                            Updater updater,
                            Projector projector,
                            Projector errorProjector) {
        this.nodeFactory = nodeFactory;
        this.validator = validator;
        this.roleEval = roleEval;
        this.translator = translator;
        this.updater = updater;
        this.projector = projector;
        this.errorProjector = errorProjector;
    }

    @Override
    public void update(CRUDOperationContext ctx,
                       DBCollection collection,
                       EntityMetadata md,
                       CRUDUpdateResponse response,
                       DBObject query) {
        LOGGER.debug("iterateUpdate: start");
        LOGGER.debug("Computing the result set for {}", query);
        DBCursor cursor = null;
        int docIndex = 0;
        int numFailed = 0;
        try {
            ctx.getFactory().getInterceptors().callInterceptors(InterceptPoint.PRE_CRUD_UPDATE_RESULTSET, ctx);
            cursor = new FindCommand(collection, query, null).execute();
            LOGGER.debug("Found {} documents", cursor.count());
            ctx.getFactory().getInterceptors().callInterceptors(InterceptPoint.POST_CRUD_UPDATE_RESULTSET, ctx);
            // read-update-write
            while (cursor.hasNext()) {
                DBObject document = cursor.next();
                boolean hasErrors = false;
                LOGGER.debug("Retrieved doc {}", docIndex);
                DocCtx doc = ctx.addDocument(translator.toJson(document));
                doc.setOutputDocument(doc.copy());
                // From now on: doc contains the old copy, and doc.getOutputDocument contains the new copy
                if (updater.update(doc.getOutputDocument(), md.getFieldTreeRoot(), Path.EMPTY)) {
                    LOGGER.debug("Document {} modified, updating", docIndex);
                    PredefinedFields.updateArraySizes(nodeFactory, doc.getOutputDocument());
                    LOGGER.debug("Running constraint validations");
                    validator.clearErrors();
                    validator.validateDoc(doc.getOutputDocument());
                    List<Error> errors = validator.getErrors();
                    if (errors != null && !errors.isEmpty()) {
                        ctx.addErrors(errors);
                        hasErrors = true;
                        LOGGER.debug("Doc has errors");
                    }
                    errors = validator.getDocErrors().get(doc.getOutputDocument());
                    if (errors != null && !errors.isEmpty()) {
                        doc.addErrors(errors);
                        hasErrors = true;
                        LOGGER.debug("Doc has data errors");
                    }
                    if (!hasErrors) {
                        List<Path> paths = roleEval.getInaccessibleFields_Update(doc.getOutputDocument(), doc);
                        LOGGER.debug("Inaccesible fields during update={}" + paths);
                        if (paths != null && !paths.isEmpty()) {
                            doc.addError(Error.get("update", CrudConstants.ERR_NO_FIELD_UPDATE_ACCESS, paths.toString()));
                            hasErrors = true;
                        }
                    }
                    if (!hasErrors) {
                        try {
                            ctx.getFactory().getInterceptors().callInterceptors(InterceptPoint.PRE_CRUD_UPDATE_DOC, ctx, doc);
                            DBObject updatedObject = translator.toBson(doc.getOutputDocument());
                            translator.addInvisibleFields(document, updatedObject, md);
                            WriteResult result = new SaveCommand(collection, updatedObject).execute();
                            doc.setOperationPerformed(Operation.UPDATE);
                            LOGGER.debug("Number of rows affected : ", result.getN());
                            ctx.getFactory().getInterceptors().callInterceptors(InterceptPoint.POST_CRUD_UPDATE_DOC, ctx, doc);
                        } catch (Exception e) {
                            LOGGER.warn("Update exception for document {}: {}", docIndex, e);
                            doc.addError(Error.get(MongoCrudConstants.ERR_UPDATE_ERROR, e.toString()));
                            hasErrors = true;
                        }
                    }
                } else {
                    LOGGER.debug("Document {} was not modified", docIndex);
                }
                if (hasErrors) {
                    LOGGER.debug("Document {} has errors", docIndex);
                    numFailed++;
                    doc.setOutputDocument(errorProjector.project(doc.getOutputDocument(), nodeFactory));
                } else {
                    if (projector != null) {
                        LOGGER.debug("Projecting document {}", docIndex);
                        doc.setOutputDocument(projector.project(doc.getOutputDocument(), nodeFactory));
                    }
                }
                docIndex++;
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        response.setNumUpdated(docIndex);
        response.setNumFailed(numFailed);
    }

}

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
package com.redhat.lightblue.mediator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.jcabi.aspects.Loggable;
import com.redhat.lightblue.OperationStatus;
import com.redhat.lightblue.Request;
import com.redhat.lightblue.Response;
import com.redhat.lightblue.DataError;
import com.redhat.lightblue.ResultMetadata;
import com.redhat.lightblue.crud.BulkRequest;
import com.redhat.lightblue.crud.BulkResponse;
import com.redhat.lightblue.crud.CRUDController;
import com.redhat.lightblue.crud.CRUDDeleteResponse;
import com.redhat.lightblue.crud.CRUDInsertionResponse;
import com.redhat.lightblue.crud.CRUDFindResponse;
import com.redhat.lightblue.crud.CRUDSaveResponse;
import com.redhat.lightblue.crud.CRUDOperation;
import com.redhat.lightblue.crud.CRUDUpdateResponse;
import com.redhat.lightblue.crud.ConstraintValidator;
import com.redhat.lightblue.crud.CrudConstants;
import com.redhat.lightblue.crud.DeleteRequest;
import com.redhat.lightblue.crud.DocCtx;
import com.redhat.lightblue.crud.Factory;
import com.redhat.lightblue.crud.FindRequest;
import com.redhat.lightblue.crud.InsertionRequest;
import com.redhat.lightblue.crud.SaveRequest;
import com.redhat.lightblue.crud.UpdateRequest;
import com.redhat.lightblue.crud.DocumentStream;
import com.redhat.lightblue.crud.WithQuery;
import com.redhat.lightblue.crud.WithRange;
import com.redhat.lightblue.crud.WithIfCurrent;
import com.redhat.lightblue.assoc.AnalyzeQuery;
import com.redhat.lightblue.assoc.QueryFieldInfo;
import com.redhat.lightblue.eval.FieldAccessRoleEvaluator;
import com.redhat.lightblue.interceptor.InterceptPoint;
import com.redhat.lightblue.metadata.CompositeMetadata;
import com.redhat.lightblue.metadata.DocId;
import com.redhat.lightblue.metadata.DocIdExtractor;
import com.redhat.lightblue.metadata.EntityMetadata;
import com.redhat.lightblue.metadata.Metadata;
import com.redhat.lightblue.metadata.PredefinedFields;
import com.redhat.lightblue.query.BinaryComparisonOperator;
import com.redhat.lightblue.query.FieldProjection;
import com.redhat.lightblue.query.NaryLogicalExpression;
import com.redhat.lightblue.query.NaryLogicalOperator;
import com.redhat.lightblue.query.Projection;
import com.redhat.lightblue.query.ProjectionList;
import com.redhat.lightblue.query.QueryExpression;
import com.redhat.lightblue.query.Value;
import com.redhat.lightblue.query.ValueComparisonExpression;
import com.redhat.lightblue.util.Error;
import com.redhat.lightblue.util.JsonDoc;
import com.redhat.lightblue.util.Path;

import com.redhat.lightblue.assoc.CompositeFindImpl;

/**
 * The mediator looks at a request, performs basic validation, and passes the
 * operation to one or more of the controllers based on the request attributes.
 */
public class Mediator {

    public static final String CTX_QPLAN = "meditor:qplan";

    public static final String CRUD_MSG_PREFIX = "CRUD controller={}";

    private static final Logger LOGGER = LoggerFactory.getLogger(Mediator.class);
    private static final Logger METRICS = LoggerFactory.getLogger("metrics."+Mediator.class.getName());

    private static final Path OBJECT_TYPE_PATH = new Path("objectType");

    public final Metadata metadata;
    public final Factory factory;

    public Mediator(Metadata md,
                    Factory factory) {
        this.metadata = md;
        this.factory = factory;
    }
    
    /**
     * Inserts data
     *
     * @param req Insertion request
     *
     * Mediator performs constraint and role validation, and passes documents
     * that pass the validation to the CRUD implementation for that entity. CRUD
     * implementation can perform further validations.
     */
    @Loggable(limit=5, unit=TimeUnit.SECONDS, trim=false, skipResult=true, name="stopwatch.com.redhat.lightblue.mediator.Mediator")
    public Response insert(InsertionRequest req) {
        LOGGER.debug("insert {}", req.getEntityVersion());
        Error.push("insert(" + req.getEntityVersion().toString() + ")");
        Response response = new Response(factory.getNodeFactory());
        OperationContext ctx=null;
        try {
            ctx = newCtx(req, CRUDOperation.INSERT);
            ctx.measure.begin("insert");
            response.setEntity(ctx.getTopLevelEntityName(),ctx.getTopLevelEntityVersion());
            EntityMetadata md = ctx.getTopLevelEntityMetadata();
            if (!md.getAccess().getInsert().hasAccess(ctx.getCallerRoles())) {
                ctx.setStatus(OperationStatus.ERROR);
                ctx.addError(Error.get(CrudConstants.ERR_NO_ACCESS, "insert " + ctx.getTopLevelEntityName()));
            } else {
                factory.getInterceptors().callInterceptors(InterceptPoint.PRE_MEDIATOR_INSERT, ctx);
                CRUDController controller = factory.getCRUDController(md);
                updatePredefinedFields(ctx, controller, md.getName());
                runBulkConstraintValidation(ctx);
                if (!ctx.hasErrors() && ctx.hasInputDocumentsWithoutErrors()) {
                    LOGGER.debug(CRUD_MSG_PREFIX, controller.getClass().getName());
                    CRUDInsertionResponse ir=controller.insert(ctx, req.getReturnFields());
                    ctx.getHookManager().queueMediatorHooks(ctx);
                    ctx.measure.begin("postProcessInsertedDocs");
                    response.setModifiedCount(ir.getNumInserted());
                    List<DataError> dataErrors=setResponseResults(ctx,req,response);
                    response.getDataErrors().addAll(dataErrors);
                    ctx.measure.begin("postProcessInsertedDocs");
                    if (!ctx.hasErrors() && dataErrors.isEmpty() && ctx.getInputDocuments().size()==ir.getNumInserted()) {
                        ctx.setStatus(OperationStatus.COMPLETE);
                    } else if (ir.getNumInserted()>0) {
                        ctx.setStatus(OperationStatus.PARTIAL);
                    } else {
                        ctx.setStatus(OperationStatus.ERROR);
                    }
                } else {
                    List<DataError> dataErrors=setResponseResults(ctx,req,response);
                    response.getDataErrors().addAll(dataErrors);
                    ctx.setStatus(OperationStatus.ERROR);
                }
            }
            response.getErrors().addAll(ctx.getErrors());
            response.setStatus(ctx.getStatus());
            if (response.getStatus() != OperationStatus.ERROR) {
                ctx.getHookManager().callQueuedHooks();
            }
        } catch (Error e) {
            response.getErrors().add(e);
            response.setStatus(OperationStatus.ERROR);
        } catch (Exception e) {
            response.getErrors().add(Error.get(CrudConstants.ERR_CRUD, e));
            response.setStatus(OperationStatus.ERROR);
        } finally {
            if(ctx!=null) {
                ctx.measure.end("insert");
                METRICS.debug("insert: {}",ctx.measure);
            }
            Error.pop();
        }
        return response;
    }

    /**
     * Saves data. Documents in the DB that match the ID of the documents in the
     * request are rewritten. If a document does not exist in the DB and
     * upsert=true, the document is inserted.
     *
     * @param req Save request
     *
     * Mediator performs constraint validation, and passes documents that pass
     * the validation to the CRUD implementation for that entity. CRUD
     * implementation can perform further validations.
     *
     */
    @Loggable(limit=5, unit=TimeUnit.SECONDS, trim=false, skipResult=true, name="stopwatch.com.redhat.lightblue.mediator.Mediator")
    public Response save(SaveRequest req) {
        LOGGER.debug("save {}", req.getEntityVersion());
        Error.push("save(" + req.getEntityVersion().toString() + ")");
        Response response = new Response(factory.getNodeFactory());
        OperationContext ctx=null;
        try {
            ctx = newCtx(req, CRUDOperation.SAVE);
            ctx.measure.begin("save");
            response.setEntity(ctx.getTopLevelEntityName(),ctx.getTopLevelEntityVersion());
            EntityMetadata md = ctx.getTopLevelEntityMetadata();
            if (!md.getAccess().getUpdate().hasAccess(ctx.getCallerRoles())
                    || (req.isUpsert() && !md.getAccess().getInsert().hasAccess(ctx.getCallerRoles()))) {
                ctx.setStatus(OperationStatus.ERROR);
                ctx.addError(Error.get(CrudConstants.ERR_NO_ACCESS, "insert/update " + ctx.getTopLevelEntityName()));
            } else {
                factory.getInterceptors().callInterceptors(InterceptPoint.PRE_MEDIATOR_SAVE, ctx);
                CRUDController controller = factory.getCRUDController(md);
                updatePredefinedFields(ctx, controller, md.getName());
                runBulkConstraintValidation(ctx);
                if (!ctx.hasErrors() && ctx.hasInputDocumentsWithoutErrors()) {
                    LOGGER.debug(CRUD_MSG_PREFIX, controller.getClass().getName());
                    CRUDSaveResponse sr=controller.save(ctx, req.isUpsert(), req.getReturnFields());
                    ctx.getHookManager().queueMediatorHooks(ctx);
                    ctx.measure.begin("postProcessSavedDocs");
                    response.setModifiedCount(sr.getNumSaved());
                    List<DataError> dataErrors=setResponseResults(ctx,req,response);
                    response.getDataErrors().addAll(dataErrors);
                    ctx.measure.end("postProcessSavedDocs");
                    if (!ctx.hasErrors() && dataErrors.isEmpty() && ctx.getInputDocuments().size()==sr.getNumSaved()) {
                        ctx.setStatus(OperationStatus.COMPLETE);
                    } else if (sr.getNumSaved()>0) {
                        ctx.setStatus(OperationStatus.PARTIAL);
                    } else {
                        ctx.setStatus(OperationStatus.ERROR);
                    }
                } else {
                    List<DataError> dataErrors=setResponseResults(ctx,req,response);
                    response.getDataErrors().addAll(dataErrors);
                }
            }
            response.getErrors().addAll(ctx.getErrors());
            response.setStatus(ctx.getStatus());
            if (response.getStatus() != OperationStatus.ERROR) {
                ctx.getHookManager().callQueuedHooks();
            }
        } catch (Error e) {
            response.getErrors().add(e);
            response.setStatus(OperationStatus.ERROR);
        } catch (Exception e) {
            response.getErrors().add(Error.get(CrudConstants.ERR_CRUD, e));
            response.setStatus(OperationStatus.ERROR);
        } finally {
            if(ctx!=null) {
                ctx.measure.end("save");
                METRICS.debug("save: {}",ctx.measure);
            }
            Error.pop();
        }
        return response;
    }

    /**
     * Updates documents that match the given search criteria
     *
     * @param req Update request
     *
     * All documents matching the search criteria are updated using the update
     * expression given in the request. Then, the updated document is projected
     * and returned in the response.
     *
     * The mediator does not perform any constraint validation. The CRUD
     * implementation must perform all constraint validations and process only
     * the documents that pass those validations.
     */
    @Loggable(limit=5, unit=TimeUnit.SECONDS, trim=false, skipResult=true, name="stopwatch.com.redhat.lightblue.mediator.Mediator")
    public Response update(UpdateRequest req) {
        LOGGER.debug("update {}", req.getEntityVersion());
        Error.push("update(" + req.getEntityVersion().toString() + ")");
        Response response = new Response(factory.getNodeFactory());
        OperationContext ctx=null;
        try {
            ctx = newCtx(req, CRUDOperation.UPDATE);
            ctx.measure.begin("update");
            response.setEntity(ctx.getTopLevelEntityName(),ctx.getTopLevelEntityVersion());
            CompositeMetadata md = ctx.getTopLevelEntityMetadata();
            if (!md.getAccess().getUpdate().hasAccess(ctx.getCallerRoles())) {
                ctx.setStatus(OperationStatus.ERROR);
                ctx.addError(Error.get(CrudConstants.ERR_NO_ACCESS, "update " + ctx.getTopLevelEntityName()));
            } else if (checkQueryAccess(ctx, req.getQuery())) {
                factory.getInterceptors().callInterceptors(InterceptPoint.PRE_MEDIATOR_UPDATE, ctx);
                CRUDController controller = factory.getCRUDController(md);
                LOGGER.debug(CRUD_MSG_PREFIX, controller.getClass().getName());
                CRUDUpdateResponse updateResponse;
                if (ctx.isSimple()) {
                    updateResponse = controller.update(ctx,
                            req.getQuery(),
                            req.getUpdateExpression(),
                            req.getReturnFields());
                } else {
                    LOGGER.debug("Composite search required for update");
                    QueryExpression q = rewriteUpdateQueryForCompositeSearch(md, ctx);
                    LOGGER.debug("New query:{}", q);
                    if (q != null) {
                        updateResponse = controller.update(ctx, q, req.getUpdateExpression(), req.getReturnFields());
                    } else {
                        updateResponse = new CRUDUpdateResponse();
                        updateResponse.setNumUpdated(0);
                        updateResponse.setNumFailed(0);
                        updateResponse.setNumMatched(0);
                    }
                }
                ctx.getHookManager().queueMediatorHooks(ctx);
                ctx.measure.begin("postProcessUpdatedDocs");
                LOGGER.debug("# Updated", updateResponse.getNumUpdated());                
                response.setModifiedCount(updateResponse.getNumUpdated());
                response.setMatchCount(updateResponse.getNumMatched());
                List<DataError> dataErrors=setResponseResults(ctx,req,response);
                response.getDataErrors().addAll(dataErrors);
                ctx.measure.end("postProcessUpdatedDocs");
                if (ctx.hasErrors()) {
                    ctx.setStatus(OperationStatus.ERROR);
                } else if (!dataErrors.isEmpty()) {
                    ctx.setStatus(OperationStatus.PARTIAL);
                } else {
                    ctx.setStatus(OperationStatus.COMPLETE);
                }
            }
            response.getErrors().addAll(ctx.getErrors());
            response.setStatus(ctx.getStatus());
            if (response.getStatus() != OperationStatus.ERROR) {
                ctx.getHookManager().callQueuedHooks();
            }
        } catch (Error e) {
            response.getErrors().add(e);
            response.setStatus(OperationStatus.ERROR);
        } catch (Exception e) {
            response.getErrors().add(Error.get(CrudConstants.ERR_CRUD, e));
            response.setStatus(OperationStatus.ERROR);
        } finally {
             if(ctx!=null) {
                ctx.measure.end("update");
                METRICS.debug("update: {}",ctx.measure);
            }
           Error.pop();
        }
        return response;
    }

    @Loggable(limit=5, unit=TimeUnit.SECONDS, trim=false, skipResult=true, name="stopwatch.com.redhat.lightblue.mediator.Mediator")
    public Response delete(DeleteRequest req) {
        LOGGER.debug("delete {}", req.getEntityVersion());
        Error.push("delete(" + req.getEntityVersion().toString() + ")");
        Response response = new Response(factory.getNodeFactory());
        OperationContext ctx=null;
        try {
            ctx = newCtx(req, CRUDOperation.DELETE);
            ctx.measure.begin("delete");
            response.setEntity(ctx.getTopLevelEntityName(),ctx.getTopLevelEntityVersion());
            CompositeMetadata md = ctx.getTopLevelEntityMetadata();
            if (!md.getAccess().getDelete().hasAccess(ctx.getCallerRoles())) {
                ctx.setStatus(OperationStatus.ERROR);
                ctx.addError(Error.get(CrudConstants.ERR_NO_ACCESS, "delete " + ctx.getTopLevelEntityName()));
            } else if (checkQueryAccess(ctx, req.getQuery())) {
                factory.getInterceptors().callInterceptors(InterceptPoint.PRE_MEDIATOR_DELETE, ctx);
                CRUDController controller = factory.getCRUDController(md);
                LOGGER.debug(CRUD_MSG_PREFIX, controller.getClass().getName());

                CRUDDeleteResponse result;
                if (ctx.isSimple()) {
                    result = controller.delete(ctx, req.getQuery());
                } else {
                    LOGGER.debug("Composite search required for delete");
                    QueryExpression q = rewriteUpdateQueryForCompositeSearch(md, ctx);
                    LOGGER.debug("New query:{}", q);
                    if (q != null) {
                        result = controller.delete(ctx, q);
                    } else {
                        result = new CRUDDeleteResponse();
                        result.setNumDeleted(0);
                    }
                }

                ctx.getHookManager().queueMediatorHooks(ctx);
                response.setModifiedCount(result == null ? 0 : result.getNumDeleted());
                if (ctx.hasErrors()) {
                    ctx.setStatus(OperationStatus.ERROR);
                } else {
                    ctx.setStatus(OperationStatus.COMPLETE);
                }
            }
            response.getErrors().addAll(ctx.getErrors());
            response.setStatus(ctx.getStatus());
            if (response.getStatus() != OperationStatus.ERROR) {
                ctx.getHookManager().callQueuedHooks();
            }
        } catch (Error e) {
            response.getErrors().add(e);
            response.setStatus(OperationStatus.ERROR);
        } catch (Exception e) {
            response.getErrors().add(Error.get(CrudConstants.ERR_CRUD, e));
            response.setStatus(OperationStatus.ERROR);
        } finally {
            if(ctx!=null) {
                ctx.measure.end("delete");
                METRICS.debug("delete: {}",ctx.measure);
            }
            Error.pop();
        }
        return response;
    }

    private QueryExpression rewriteUpdateQueryForCompositeSearch(CompositeMetadata md, OperationContext ctx) {
        // Construct a new find request with the composite query
        // Retrieve only the identities
        // This fails if the entity doesn't have identities
        DocIdExtractor docIdx = new DocIdExtractor(md);
        // Identity fields also contains the objectType, we'll filter that out while writing the query
        Path[] identityFields = docIdx.getIdentityFields();

        FindRequest freq = new FindRequest();
        freq.setEntityVersion(ctx.getRequest().getEntityVersion());
        freq.setClientId(ctx.getRequest().getClientId());
        freq.setExecution(ctx.getRequest().getExecution());
        freq.setQuery(((WithQuery) ctx.getRequest()).getQuery());
        // Project the identity fields
        List<Projection> pl = new ArrayList<>(identityFields.length);
        for (Path field : identityFields) {
            pl.add(new FieldProjection(field, true, false));
        }
        freq.setProjection(new ProjectionList(pl));
        LOGGER.debug("Query:{} projection:{}", freq.getQuery(), freq.getProjection());

        OperationContext findCtx = new OperationContext(freq, CRUDOperation.FIND, ctx);
        CompositeFindImpl finder = new CompositeFindImpl(md);
        finder.setParallelism(9);
        CRUDFindResponse response = finder.find(findCtx, freq.getCRUDFindRequest());
        if(findCtx.hasErrors()) {
            ctx.addErrors(findCtx.getErrors());
        } else {
            DocumentStream<DocCtx> docStream = findCtx.getDocumentStream();
            
            // Now write a query
            List<QueryExpression> orq = new ArrayList<>();
            for (;docStream.hasNext();) {
                DocCtx doc=docStream.next();
                if(!doc.hasErrors()) {
                    DocId id = docIdx.getDocId(doc);
                    List<QueryExpression> idList = new ArrayList<>(identityFields.length);
                    for (int ix = 0; ix < identityFields.length; ix++) {
                        if (!identityFields[ix].equals(PredefinedFields.OBJECTTYPE_PATH)) {
                            Object value = id.getValue(ix);
                            idList.add(new ValueComparisonExpression(identityFields[ix],
                                                                     BinaryComparisonOperator._eq,
                                                                     new Value(value)));
                        }
                    }
                    QueryExpression idq;
                    if (idList.size() == 1) {
                        idq = idList.get(0);
                    } else {
                        idq = new NaryLogicalExpression(NaryLogicalOperator._and, idList);
                    }
                    orq.add(idq);
                }
            }
            docStream.close();
            if (orq.isEmpty()) {
                return null;
            } else if (orq.size() == 1) {
                return orq.get(0);
            } else {
                return new NaryLogicalExpression(NaryLogicalOperator._or, orq);
            }
        }
        return null;
    }

    /**
     * Finds documents
     *
     * @param req Find request
     *
     * The implementation passes the request to the back-end.
     */
    @Loggable(limit=5, unit=TimeUnit.SECONDS, trim=false, skipResult=true, name="stopwatch.com.redhat.lightblue.mediator.Mediator")
    public Response find(FindRequest req) {
        LOGGER.debug("find {}", req.getEntityVersion());
        Error.push("find(" + req.getEntityVersion().toString() + ")");
        Response response = new Response(factory.getNodeFactory());
        response.setStatus(OperationStatus.ERROR);
        OperationContext ctx=null;
        try {
            ctx = newCtx(req, CRUDOperation.FIND);
            ctx.measure.begin("find");
            response.setEntity(ctx.getTopLevelEntityName(),ctx.getTopLevelEntityVersion());
            CompositeMetadata md = ctx.getTopLevelEntityMetadata();
            if (!md.getAccess().getFind().hasAccess(ctx.getCallerRoles())) {
                ctx.setStatus(OperationStatus.ERROR);
                LOGGER.debug("No access");
                ctx.addError(Error.get(CrudConstants.ERR_NO_ACCESS, "find " + ctx.getTopLevelEntityName()));
            } else if (checkQueryAccess(ctx, req.getQuery())) {
                factory.getInterceptors().callInterceptors(InterceptPoint.PRE_MEDIATOR_FIND, ctx);
                Finder finder;
                if (ctx.isSimple()) {
                    LOGGER.debug("Simple entity");
                    finder = new SimpleFindImpl(md, factory);
                } else {
                    LOGGER.debug("Composite entity");
                    finder = new CompositeFindImpl(md);
                    // This can be read from a configuration
                    ((CompositeFindImpl) finder).setParallelism(9);
                }

                ctx.measure.begin("finder.find");
                CRUDFindResponse result = finder.find(ctx, req.getCRUDFindRequest());
                ctx.measure.end("finder.find");

                if(!ctx.hasErrors()) {
                    ctx.measure.begin("postProcessFound");
                    DocumentStream<DocCtx> docStream=ctx.getDocumentStream();
                    List<ResultMetadata> rmd=new ArrayList<>();
                    response.setEntityData(factory.getNodeFactory().arrayNode());
                    for(;docStream.hasNext();) {
                        DocCtx doc=docStream.next();
                        if(!doc.hasErrors()) {          
                            response.addEntityData(doc.getOutputDocument().getRoot());
                            rmd.add(doc.getResultMetadata());
                    } else {
                            DataError error=doc.getDataError();
                            if(error!=null)
                                response.getDataErrors().add(error);
                        }
                    }
                    docStream.close();
                    response.setResultMetadata(rmd);
                    ctx.measure.end("postProcessFound");
                    ctx.setStatus(OperationStatus.COMPLETE);
                } else {
                    ctx.setStatus(OperationStatus.ERROR);
                }
                response.setMatchCount(result == null ? 0 : result.getSize());
            }
            // call any queued up hooks (regardless of status)
            ctx.getHookManager().queueMediatorHooks(ctx);
            response.setStatus(ctx.getStatus());
            response.getErrors().addAll(ctx.getErrors());
            if (response.getStatus() != OperationStatus.ERROR) {
                ctx.getHookManager().callQueuedHooks();
            }
        } catch (Error e) {
            LOGGER.debug("Error during find:{}", e);
            response.getErrors().add(e);
        } catch (Exception e) {
            LOGGER.debug("Exception during find:{}", e);
            response.getErrors().add(Error.get(CrudConstants.ERR_CRUD, e));
        } finally {
            if(ctx!=null) {
                ctx.measure.end("find");
                METRICS.debug("find: {}",ctx.measure);
            }
            Error.pop();
        }
        return response;
    }

    /**
     * Explains the query. Part of the implementation is done here at
     * the core level, and then passed to the backend to fill in
     * back-end specifics
     */
    @Loggable(limit=5, unit=TimeUnit.SECONDS, trim=false, skipResult=true, name="stopwatch.com.redhat.lightblue.mediator.Mediator")
    public Response explain(FindRequest req) {
        LOGGER.debug("explain {}", req.getEntityVersion());
        Error.push("explain(" + req.getEntityVersion().toString() + ")");
        Response response = new Response(factory.getNodeFactory());
        response.setStatus(OperationStatus.ERROR);
        try {
            OperationContext ctx = newCtx(req, CRUDOperation.FIND);
            response.setEntity(ctx.getTopLevelEntityName(),ctx.getTopLevelEntityVersion());
            CompositeMetadata md = ctx.getTopLevelEntityMetadata();
            Finder finder;
            if (ctx.isSimple()) {
                LOGGER.debug("Simple entity");
                finder = new SimpleFindImpl(md, factory);
            } else {
                LOGGER.debug("Composite entity");
                finder = new CompositeFindImpl(md);
                // This can be read from a configuration
                ((CompositeFindImpl) finder).setParallelism(9);
            }
            
            finder.explain(ctx, req.getCRUDFindRequest());
            
            DocumentStream<DocCtx> documentStream = ctx.getDocumentStream();
            if(documentStream!=null&&documentStream.hasNext()) {
                ctx.setStatus(OperationStatus.COMPLETE);
                List<JsonDoc> resultList = new ArrayList<>();
                while(documentStream.hasNext()) {
                    resultList.add(documentStream.next().getOutputDocument());
                }
                response.setMatchCount(resultList.size());
                response.setEntityData(JsonDoc.listToDoc(resultList, factory.getNodeFactory()));
            } else {
                ctx.setStatus(OperationStatus.ERROR);
            }
            
            response.setStatus(ctx.getStatus());
            response.getErrors().addAll(ctx.getErrors());
        } catch (Error e) {
            LOGGER.debug("Error during explain:{}", e);
            response.getErrors().add(e);
        } catch (Exception e) {
            LOGGER.debug("Exception during explain:{}", e);
            response.getErrors().add(Error.get(CrudConstants.ERR_CRUD, e));
        } finally {
            Error.pop();
        }
        return response;        
    }

    public static class BulkExecutionContext {
        final Future<Response>[] futures;
        final Response[] responses;

        public BulkExecutionContext(int size) {
            futures = new Future[size];
            responses = new Response[size];
        }
    }

    protected void wait(BulkExecutionContext ctx) {
        for (int i = 0; i < ctx.futures.length; i++) {
            if (ctx.futures[i] != null) {
                try {
                    LOGGER.debug("Waiting for a find request to complete");
                    ctx.responses[i] = ctx.futures[i].get();
                } catch (Exception e) {
                    LOGGER.debug("Find request wait failed", e);
                }
            }
        }
    }

    protected Callable<Response> getFutureRequest(final Request req) {
        return new Callable<Response>() {
            @Override
            public Response call() {
                LOGGER.debug("Starting a future {} request",req.getOperation());
                switch (req.getOperation()) {
                    case FIND:
                        return find((FindRequest) req);
                    case INSERT:
                        return insert((InsertionRequest) req);
                    case DELETE:
                        return delete((DeleteRequest) req);
                    case UPDATE:
                        return update((UpdateRequest) req);
                    case SAVE:
                        return save((SaveRequest) req);
                    default:
                        throw new UnsupportedOperationException("CRUD operation '"+req.getOperation()+"' is not supported!");
                }
            }
        };
    }

    public BulkResponse bulkRequest(BulkRequest requests) {
        LOGGER.debug("Bulk request start");
        Error.push("bulk operation");
        ExecutorService executor = Executors.newFixedThreadPool(factory.getBulkParallelExecutions());
        try {
            LOGGER.debug("Executing up to {} requests in parallel, ordered = {}", factory.getBulkParallelExecutions(), requests.isOrdered());
            List<Request> requestList = requests.getEntries();
            int n = requestList.size();
            BulkExecutionContext ctx = new BulkExecutionContext(n);

            for (int i = 0; i < n; i++) {
                Request req = requestList.get(i);
                if (requests.isOrdered()) {
                    // ordered - only consecutive finds in parallel
                    if (req.getOperation() == CRUDOperation.FIND) {
                        ctx.futures[i] = executor.submit(getFutureRequest(req));
                    } else {
                        wait(ctx);
                        switch (req.getOperation()) {
                            case INSERT:
                                ctx.responses[i] = insert((InsertionRequest) req);
                                break;
                            case DELETE:
                                ctx.responses[i] = delete((DeleteRequest) req);
                                break;
                            case UPDATE:
                                ctx.responses[i] = update((UpdateRequest) req);
                                break;
                            case SAVE:
                                ctx.responses[i] = save((SaveRequest) req);
                                break;
                        }
                    }
                } else {
                    LOGGER.debug("Scheduling a future operation");
                    // unordered - do them all in parallel
                    ctx.futures[i] = executor.submit(getFutureRequest(req));
                }
            }

            wait(ctx);
            LOGGER.debug("Bulk execution completed");
            BulkResponse response = new BulkResponse();
            response.setEntries(ctx.responses);
            Error.pop();
            return response;
        } finally {
            executor.shutdown();
        }
    }

    protected OperationContext newCtx(Request request, CRUDOperation CRUDOperation) {
        OperationContext ctx=new OperationContext(request, metadata, factory, CRUDOperation);
        if(request instanceof WithIfCurrent) {
            WithIfCurrent wif=(WithIfCurrent)request;
            if(wif.isIfCurrentOnly()) {
                ctx.setUpdateIfCurrent(true);
                List<String> list=wif.getDocumentVersions();
                if(list!=null)
                    ctx.getUpdateDocumentVersions().addAll(list);
            }
        }
        return ctx;
    }

    /**
     * Runs constraint validation
     */
    private void runBulkConstraintValidation(OperationContext ctx) {
        LOGGER.debug("Bulk constraint validation");
        ctx.measure.begin("runBulkConstraintValidation");
        EntityMetadata md = ctx.getTopLevelEntityMetadata();
        ConstraintValidator constraintValidator = factory.getConstraintValidator(md);
        List<DocCtx> docs = ctx.getInputDocumentsWithoutErrors();
        if(docs!=null) {
            constraintValidator.validateDocs(docs);
            Map<JsonDoc, List<Error>> docErrors = constraintValidator.getDocErrors();
            for (Map.Entry<JsonDoc, List<Error>> entry : docErrors.entrySet()) {
                JsonDoc doc = entry.getKey();
                List<Error> errors = entry.getValue();
                if (errors != null && !errors.isEmpty()) {
                    ((DocCtx) doc).addErrors(errors);
                }
            }
            List<Error> errors = constraintValidator.getErrors();
            if (errors != null && !errors.isEmpty()) {
                ctx.addErrors(errors);
            }
        }
        LOGGER.debug("Constraint validation complete");
        ctx.measure.end("runBulkConstraintValidation");
    }

    private void updatePredefinedFields(OperationContext ctx, CRUDController controller, String entity) {
        ctx.measure.begin("updatePredefinedFields");
        for (JsonDoc doc : ctx.getInputDocuments()) {
            PredefinedFields.updateArraySizes(ctx.getTopLevelEntityMetadata(), factory.getNodeFactory(), doc);
            JsonNode node = doc.get(OBJECT_TYPE_PATH);
            if (node == null) {
                doc.modify(OBJECT_TYPE_PATH, factory.getNodeFactory().textNode(entity), false);
            } else if (!node.asText().equals(entity)) {
                throw Error.get(CrudConstants.ERR_INVALID_ENTITY, node.asText());
            }
            controller.updatePredefinedFields(ctx, doc);
        }
        ctx.measure.end("updatePredefinedFields");
    }

    /**
     * Checks if the caller has access to all the query fields. Returns false if
     * not, and sets the error status in ctx
     */
    private boolean checkQueryAccess(OperationContext ctx, QueryExpression query) {
        boolean ret = true;
        if (query != null) {
            CompositeMetadata md = ctx.getTopLevelEntityMetadata();
            FieldAccessRoleEvaluator eval = new FieldAccessRoleEvaluator(md, ctx.getCallerRoles());
            AnalyzeQuery analyzer=new AnalyzeQuery(md,null);
            analyzer.iterate(query,Path.EMPTY);
            List<QueryFieldInfo> fields=analyzer.getFieldInfo();
            LOGGER.debug("Checking access for query fields {}", fields);
            for (QueryFieldInfo field : fields) {
                LOGGER.debug("Access checking field {}", field.getFullFieldName());
                if (eval.hasAccess(field.getFullFieldName(), FieldAccessRoleEvaluator.Operation.find)) {
                    LOGGER.debug("Field {} is readable", field.getFullFieldName());
                } else {
                    LOGGER.debug("Field {} is not readable", field.getFullFieldName());
                    ctx.addError(Error.get(CrudConstants.ERR_NO_ACCESS, field.getFullFieldName().toString()));
                    ctx.setStatus(OperationStatus.ERROR);
                    ret = false;
                }
            }
        }
        return ret;
    }
    
    private List<DataError> setResponseResults(OperationContext ctx,
                                               WithRange requestWithRange,
                                               Response response) {
        List<DataError> dataErrors=new ArrayList<>();
        Long from = requestWithRange.getFrom();
        Long to = (requestWithRange.getTo() == null) ? null : requestWithRange.getTo();
        int f=from==null?0:from.intValue();
        int t=to==null?Integer.MAX_VALUE:to.intValue();
        int ix=0;
        DocumentStream<DocCtx> docStream=ctx.getDocumentStream();
        if(docStream!=null) {
            List<ResultMetadata> rmd=new ArrayList<>();
            for(;docStream.hasNext();) {
                DocCtx doc=docStream.next();
                if(!doc.hasErrors()) {                
                    if(ix>=f&&ix<=t) {                
                        response.addEntityData(doc.getOutputDocument().getRoot());
                        rmd.add(doc.getResultMetadata());
                    }
                    ix++;
                } else {
                    DataError error=doc.getDataError();
                    if(error!=null)
                        dataErrors.add(error);
                }
            }
            docStream.close();
            response.setResultMetadata(rmd);
        }
        return dataErrors;
    }
}

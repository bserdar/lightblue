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

import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;


import com.redhat.lightblue.query.QueryExpression;
import com.redhat.lightblue.query.FieldInfo;
import com.redhat.lightblue.query.Projection;

import com.redhat.lightblue.crud.CRUDFindRequest;
import com.redhat.lightblue.crud.CRUDFindResponse;
import com.redhat.lightblue.crud.Factory;
import com.redhat.lightblue.crud.DocCtx;

import com.redhat.lightblue.eval.FieldAccessRoleEvaluator;
import com.redhat.lightblue.eval.Projector;

import com.redhat.lightblue.assoc.QueryPlan;
import com.redhat.lightblue.assoc.QueryPlanNode;
import com.redhat.lightblue.assoc.QueryPlanChooser;
import com.redhat.lightblue.assoc.ResultDoc;
import com.redhat.lightblue.assoc.DocReference;
import com.redhat.lightblue.assoc.ChildDocReference;
import com.redhat.lightblue.assoc.SortAndLimit;

import com.redhat.lightblue.assoc.scorers.SimpleScorer;
import com.redhat.lightblue.assoc.scorers.IndexedFieldScorer;
import com.redhat.lightblue.assoc.iterators.First;
import com.redhat.lightblue.assoc.iterators.BruteForceQueryPlanIterator;

import com.redhat.lightblue.metadata.CompositeMetadata;
import com.redhat.lightblue.metadata.DocId;

import com.redhat.lightblue.util.Path;
import com.redhat.lightblue.util.Error;

public class CompositeFindImpl implements Finder {

    private static final Logger LOGGER=LoggerFactory.getLogger(CompositeFindImpl.class);

    private final CompositeMetadata root;
    private final Factory factory;

    private final List<Error> errors=new ArrayList<>();

    public CompositeFindImpl(CompositeMetadata md,
                             Factory factory) {
        this.root=md;
        this.factory=factory;
    }


    private void init(QueryPlan qplan) {
        // We need two separate for loops below. First associates an
        // QueryPlanNodeExecutor to each query plan node. Second adds
        // the edges between those execution data. Setting up the
        // edges requires all execution information readily available.
        
        //  Setup execution data for each node
        for(QueryPlanNode x:qplan.getAllNodes())
            x.setProperty(QueryPlanNodeExecutor.class,new QueryPlanNodeExecutor(x,factory,root));
        
        // setup edges between execution data
        for(QueryPlanNode x:qplan.getAllNodes()) {
            x.getProperty(QueryPlanNodeExecutor.class).init(qplan);
        }
    }

    /**
     * Determine which entities are required to evaluate the given query
     */
    private Set<CompositeMetadata> findMinimalSetOfQueryEntities(QueryExpression query,
                                                                 CompositeMetadata md) {
        Set<CompositeMetadata> entities=new HashSet<>();
        if(query!=null) {
            List<FieldInfo> lfi=query.getQueryFields();
            for(FieldInfo fi:lfi) {
                CompositeMetadata e=md.getEntityOfPath(fi.getFieldName());
                if(e!=md)
                    entities.add(e);
            }
            // All entities on the path from every entity to the root
            // should also be included
            for(CompositeMetadata x:entities.toArray(new CompositeMetadata[entities.size()])) {
                CompositeMetadata trc=x.getParent();
                while(trc!=null) {
                    entities.add(trc);
                    trc=trc.getParent();
                }
            }
        }
        // At this point, entities contains all required entities, but maybe not the root
        entities.add(md);
        return entities;
    }

    /**
     * The operation starts by evaluating source nodes, and
     * moves on by going to the destination nodes.
     */
    @Override
    public CRUDFindResponse find(OperationContext ctx,
                                 CRUDFindRequest req) {
        LOGGER.debug("Composite find: start");

        // First: determine a minimal entity tree containing the nodes
        // sufficient to evaluate the query. Then, retrieve using the
        // complete set of entities.
        Set<CompositeMetadata> minimalTree=findMinimalSetOfQueryEntities(req.getQuery(),
                                                                         ctx.getTopLevelEntityMetadata());

        LOGGER.debug("Minimal find tree size={}",minimalTree.size());
        QueryPlan searchQPlan=null;
        QueryPlanNode searchQPlanRoot=null;
        SortAndLimit sortAndLimit=new SortAndLimit(root,null,null,null);
        // Need to keep the matchCount, because the root node evaluator will only return the documents in the range
        CRUDFindResponse rootResponse=null;

        if(minimalTree.size()>1) {
            // The query depends on several entities. so, we query first, and then retrieve
            QueryPlanChooser qpChooser=new QueryPlanChooser(root,
                                                            new BruteForceQueryPlanIterator(),
                                                            new IndexedFieldScorer(),
                                                            req.getQuery(),
                                                            minimalTree);
            searchQPlan=qpChooser.choose();
            LOGGER.debug("Chosen query plan:{}",searchQPlan);
            ctx.setProperty(Mediator.CTX_QPLAN,searchQPlan);
            init(searchQPlan);
            // At this stage, we have Execution objects assigned to query plan nodes
            
            // Put the executions in order
            QueryPlanNode[] nodeOrdering=searchQPlan.getBreadthFirstNodeOrdering();
            // Execute nodes.
            for(QueryPlanNode node:nodeOrdering) {
                LOGGER.debug("Composite find: {}",node.getName());
                QueryPlanNodeExecutor exec=node.getProperty(QueryPlanNodeExecutor.class);
                if(node.getMetadata().getParent()==null) {
                    searchQPlanRoot=node;
                    boolean hasSources=node.getSources().length>0;
                    if(hasSources) {
                        sortAndLimit=new SortAndLimit(root,req.getSort(),req.getFrom(),req.getTo());
                    } else {
                        if (req.getTo() != null && req.getFrom() != null) {
                            exec.setRange(req.getFrom(), req.getTo());
                        }
                    }
                    if(hasSources) {
                    	exec.execute(ctx,req.getSort());
                    } else {
                    	rootResponse=exec.execute(ctx, req.getSort());
                    }
                } else {
                    exec.execute(ctx,null);
                }
            }
            LOGGER.debug("Composite find: search complete");
        }
        
        LOGGER.debug("Composite find: retrieving documents");

        // Create a new query plan for retrieval. This one will have
        // the root document at the root.
        QueryPlan retrievalQPlan;
        if(searchQPlan==null) {
            // No search was performed. We have to search now.
            retrievalQPlan=new QueryPlanChooser(root,new First(),new SimpleScorer(),req.getQuery(),null).choose();
            ctx.setProperty(Mediator.CTX_QPLAN,retrievalQPlan);
        } else {
            retrievalQPlan=new QueryPlanChooser(root,new First(),new SimpleScorer(),null,null).choose();
        }
        init(retrievalQPlan);
        // This query plan has only one source
        QueryPlanNode retrievalQPlanRoot=retrievalQPlan.getSources()[0];
        // Now execute rest of the retrieval plan
        QueryPlanNode[] nodeOrdering=retrievalQPlan.getBreadthFirstNodeOrdering();

        
        List<ResultDoc> rootDocs=null;
        for(int i=0;i<nodeOrdering.length;i++) {
            if(nodeOrdering[i].getMetadata().getParent()==null) {
                // This is the root node. If we know the result docs, assign them, otherwise, search
                LOGGER.debug("Retrieving root node documents");
                if(searchQPlan!=null) {
                    LOGGER.debug("Retrieving root node documents from previous search");
                    rootDocs=searchQPlanRoot.getProperty(QueryPlanNodeExecutor.class).getDocs();
                    // Filter duplicates, recreate ResultDoc objects
                    Set<DocId> ids=new HashSet<>();
                    List<ResultDoc> filteredDocs=new ArrayList<>(rootDocs.size());
                    for(ResultDoc doc:rootDocs) {
                        if(!ids.contains(doc.getId())) {
                            ids.add(doc.getId());
                            filteredDocs.add(new ResultDoc(doc.getDoc(),doc.getId(),nodeOrdering[i]));
                        }
                    }
                    LOGGER.debug("Retrieving {} docs",filteredDocs.size());
                    nodeOrdering[i].getProperty(QueryPlanNodeExecutor.class).setDocs(filteredDocs);
                    if(rootResponse==null) {
                        rootResponse=new CRUDFindResponse();
                        rootResponse.setSize(filteredDocs.size());
                    }
                    rootDocs=sortAndLimit.process(filteredDocs);
                } else {
                    LOGGER.debug("Performing search for retrieval");
                    // Perform search
                    QueryPlanNodeExecutor exec=nodeOrdering[i].getProperty(QueryPlanNodeExecutor.class);
                    if (req.getTo() != null && req.getFrom() != null) {
                        exec.setRange(req.getFrom(), req.getTo());
                    }                    
                    rootResponse=exec.execute(ctx,req.getSort());
                    rootDocs=exec.getDocs();
                }
            } else {
                LOGGER.debug("Composite retrieval: {}",nodeOrdering[i].getName());
                QueryPlanNodeExecutor exec=nodeOrdering[i].getProperty(QueryPlanNodeExecutor.class);
                exec.execute(ctx,null);
            }
        }

        if(rootDocs != null && ctx.hasErrors())
            rootDocs.clear();
        LOGGER.debug("Root docs:{}",(rootDocs == null ? 0 : rootDocs.size()));
        List<DocCtx> resultDocuments=new ArrayList<>((rootDocs == null ? 0 : rootDocs.size()));
        if (rootDocs != null) {
            for(ResultDoc dgd:rootDocs) {
                retrieveFragments(dgd);
                DocCtx dctx=new DocCtx(dgd.getDoc());
                dctx.setOutputDocument(dgd.getDoc());
                resultDocuments.add(dctx);
            }
        }
        CRUDFindResponse response=new CRUDFindResponse();

        response.setSize(rootResponse == null ? 0 : rootResponse.getSize());
        ctx.setDocuments(projectResults(ctx,resultDocuments,req.getProjection()));
        ctx.addErrors(errors);
        
        LOGGER.debug("Composite find: end");
        return response;
    }

    private List<DocCtx> projectResults(OperationContext ctx,
                                List<DocCtx> resultDocuments,
                                Projection projection) {
        // Project results
        LOGGER.debug("Projecting association result using {}",projection.toString());
        FieldAccessRoleEvaluator roleEval = new FieldAccessRoleEvaluator(root, ctx.getCallerRoles());
        Projector projector = Projector.getInstance(Projection.add(projection, 
                                                                   roleEval.getExcludedFields(FieldAccessRoleEvaluator.Operation.find)), root);
        for (DocCtx document : resultDocuments) {
            LOGGER.debug("Projecting {}",document);
            document.setOutputDocument(projector.project(document, ctx.getFactory().getNodeFactory()));
            LOGGER.debug("Result:{}",document.getOutputDocument());
        }
        return resultDocuments;
    }

    private void retrieveFragments(ResultDoc doc) {
        for(List<DocReference> children:doc.getChildren().values()) {
            if(children!=null) {
                for(DocReference cref:children) {
                    if(cref instanceof ChildDocReference) {
                        ChildDocReference ref=(ChildDocReference)cref;
                        Path insertInto=ref.getReferenceField();
                        JsonNode insertionNode=doc.getDoc().get(insertInto);
                        LOGGER.debug("Inserting reference {} to {} with {} docs",ref,insertInto,ref.getChildren().size());
                        if(insertionNode==null || insertionNode instanceof NullNode) {
                            doc.getDoc().modify(insertInto,insertionNode=factory.getNodeFactory().arrayNode(),true);
                        }
                        for(ResultDoc child:ref.getChildren()) {
                            ((ArrayNode)insertionNode).add(child.getDoc().getRoot());
                            retrieveFragments(child);
                        }
                    }
                }
            }
        }
    }

}

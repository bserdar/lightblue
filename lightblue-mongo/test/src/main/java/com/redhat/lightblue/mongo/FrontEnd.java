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
package com.redhat.lightblue.mongo;

import java.io.FileReader;

import com.mongodb.MongoClient;
import com.mongodb.Mongo;
import com.mongodb.DB;
import org.bson.BSONObject;

import de.flapdoodle.embed.mongo.MongodExecutable;
import de.flapdoodle.embed.mongo.MongodStarter;
import de.flapdoodle.embed.mongo.config.MongodConfig;
import de.flapdoodle.embed.process.runtime.Network;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;/*
 import com.redhat.lightblue.DeleteRequest;
 import com.redhat.lightblue.FindRequest;
 import com.redhat.lightblue.InsertionRequest;
 import com.redhat.lightblue.SaveRequest;
 import com.redhat.lightblue.UpdateRequest;

 import com.redhat.lightblue.util.JsonUtils;

 import com.redhat.lightblue.metadata.Metadata;
 import com.redhat.lightblue.metadata.MetadataStatus;
 import com.redhat.lightblue.metadata.mongo.MongoMetadata;
 import com.redhat.lightblue.metadata.mongo.MongoDataStore;
 import com.redhat.lightblue.metadata.types.DefaultTypes;

 import com.redhat.lightblue.crud.Factory;
 import com.redhat.lightblue.crud.mongo.MongoCRUDController;
 import com.redhat.lightblue.crud.mongo.DBResolver;
 import com.redhat.lightblue.crud.validator.DefaultFieldConstraintValidators;

 import com.redhat.lightblue.mediator.Mediator;
 import com.redhat.lightblue.metadata.mongo.MongoDataStoreParser;
 import com.redhat.lightblue.metadata.parser.Extensions;
 import com.redhat.lightblue.metadata.parser.JSONMetadataParser;
 import java.io.IOException;

 /**
 * Simple test front-end for metadata and mediator that works with one DB
 **/


public class FrontEnd {
    /**
     * private final DB db;
     *
     * private static final String MONGO_HOST = "localhost"; private static
     * final int MONGO_PORT = 27777; private static final String
     * IN_MEM_CONNECTION_URL = MONGO_HOST + ":" + MONGO_PORT;
     *
     * private static final JsonNodeFactory NODE_FACTORY =
     * JsonNodeFactory.withExactBigDecimals(true);
     *
     * private final DBResolver simpleDBResolver = new DBResolver() {
     *
     * @Override public DB get(MongoDataStore s) { return db; } };
     *
     * public FrontEnd(DB db) { this.db = db; }
     *
     * public Metadata getMetadata() { Extensions<BSONObject> parserExtensions =
     * new Extensions<>(); parserExtensions.addDefaultExtensions();
     * parserExtensions.registerDataStoreParser("mongo", new
     * MongoDataStoreParser<BSONObject>()); DefaultTypes typeResolver = new
     * DefaultTypes(); return new MongoMetadata(db, parserExtensions,
     * typeResolver); }
     *
     * public Mediator getMediator() { Factory factory = new Factory();
     * factory.addFieldConstraintValidators(new
     * DefaultFieldConstraintValidators()); MongoCRUDController
     * mongoCRUDController = new MongoCRUDController(NODE_FACTORY,
     * simpleDBResolver); factory.addCRUDController("mongo",
     * mongoCRUDController); return new Mediator(getMetadata(), factory); }
     *
     * public static void main(String[] args) { try { String dbHost =
     * arg("dbHost", args, true); String dbPort = arg("dbPort", args, true);
     * String dbName = arg("dbName", args); DB db; if (dbHost != null || dbPort
     * != null) { if (dbHost == null) { dbHost = "localhost"; } if (dbPort ==
     * null) { dbPort = "27017"; } MongoClient client = new MongoClient(dbHost,
     * Integer.valueOf(dbPort)); db = client.getDB(dbName); } else {
     * MongodStarter runtime = MongodStarter.getDefaultInstance();
     * MongodExecutable mongodExe = runtime.prepare(new
     * MongodConfig(de.flapdoodle.embed.mongo.distribution.Version.V2_6_0,
     * MONGO_PORT, Network.localhostIsIPv6())); try { mongod =
     * mongodExe.start(); } catch (Throwable t) { // try again, could be killed
     * breakpoint in IDE mongod = mongodExe.start(); } Mongo mongo = new
     * Mongo(IN_MEM_CONNECTION_URL); db = mongo.getDB(dbName); } FrontEnd fe =
     * new FrontEnd(db); runCmd(fe, arg("cmd", args), args); } catch
     * (IOException | NumberFormatException e) { e.printStackTrace();
     * printHelp(); } }
     *
     * private static void runCmd(FrontEnd fe, String cmd, String[] args) throws
     * IOException { Metadata md = fe.getMetadata(); Mediator mediator =
     * fe.getMediator(); Extensions<JsonNode> extensions = new Extensions<>();
     * extensions.addDefaultExtensions();
     * extensions.registerDataStoreParser("mongo", new
     * MongoDataStoreParser<JsonNode>()); JSONMetadataParser parser = new
     * JSONMetadataParser(extensions, new DefaultTypes(), NODE_FACTORY); switch
     * (cmd) { case "getEntityMetadata":
     * System.out.println(JsonUtils.prettyPrint(parser.convert(md.
     * getEntityMetadata(arg("entityName", args), arg("version", args),true))));
     * break; case "getEntityNames": printArr(md.getEntityNames()); break; case
     * "getEntityVersions": printArr(md.getEntityVersions(arg("entityName",
     * args))); break; case "createNewMetadata":
     * md.createNewMetadata(parser.parseEntityMetadata(fileOrJson("md", args)));
     * break; case "setMetadataStatus": md.setMetadataStatus(arg("entityName",
     * args), arg("version", args), MetadataStatus.valueOf(arg("newStatus",
     * args)), arg("comment", args)); break; case "insert":
     * System.out.println(JsonUtils.prettyPrint(mediator.insert(InsertionRequest.fromJson((ObjectNode)
     * fileOrJson("req", args))).toJson())); break; case "save":
     * System.out.println(JsonUtils.prettyPrint(mediator.save(SaveRequest.fromJson((ObjectNode)
     * fileOrJson("req", args))).toJson())); break; case "delete":
     * System.out.println(JsonUtils.prettyPrint(mediator.delete(DeleteRequest.fromJson((ObjectNode)
     * fileOrJson("req", args))).toJson())); break; case "update":
     * System.out.println(JsonUtils.prettyPrint(mediator.update(UpdateRequest.fromJson((ObjectNode)
     * fileOrJson("req", args))).toJson())); break; case "find":
     * System.out.println(JsonUtils.prettyPrint(mediator.find(FindRequest.fromJson((ObjectNode)
     * fileOrJson("req", args))).toJson())); break; default: throw new
     * RuntimeException("Unknown cmd:" + cmd); } }
     *
     * private static void printHelp() { System.out.println("Optional:
     * dbHost=host dbPort=port\n" + "dbName=name\n" + "cmd=getEntityMetadata
     * entityName=?? version=??\n" + "cmd=getEntityNames\n" +
     * "cmd=getEntityVersions entityName=??\n" + "cmd=createNewMetadata md=@file
     * or md=jsonStr\n" + "cmd=setMetadataStatus entityName=?? version=??
     * newStatus=(ACTIVE|DEPRECATED|DISABLED) comment=??\n" + "cmd=insert
     * req=@file or req=jsonStr\n" + "cmd=save req=@file or req=jsonStr\n" +
     * "cmd=update req=#file or req=jsonStr\n" + "cmd=delete req=@file or
     * req=jsonStr\n" + "cmd=find req=@file or req=jsonStr"); }
     *
     * private static void printArr(Object[] arr) { if (arr != null) { for
     * (Object x : arr) { System.out.println(x); } } }
     *
     * private static String arg(String argName, String[] args, boolean
     * optional) { for (String x : args) { if (x.startsWith(argName + "=")) {
     * return x.substring(argName.length() + 1); } } if (!optional) { throw new
     * RuntimeException("Required:" + argName); } return null; }
     *
     * private static String arg(String argName, String[] args) { return
     * arg(argName, args, false); }
     *
     * private static JsonNode fileOrJson(String argName, String[] args) throws
     * IOException { String arg = arg(argName, args); if (arg.startsWith("@")) {
     * arg = arg.substring(1); StringBuilder str = new StringBuilder(); try
     * (FileReader reader = new FileReader(arg)) { int c; while ((c =
     * reader.read()) >= 0) { str.append((char) c); } } return
     * JsonUtils.json(str.toString()); } else { return JsonUtils.json(arg); } }*
     */
}

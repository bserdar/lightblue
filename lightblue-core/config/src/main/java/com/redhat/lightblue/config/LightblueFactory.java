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
package com.redhat.lightblue.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;

import java.lang.reflect.InvocationTargetException;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import com.redhat.lightblue.crud.CRUDController;
import com.redhat.lightblue.crud.CrudConstants;
import com.redhat.lightblue.crud.Factory;
import com.redhat.lightblue.crud.interceptors.UIDInterceptor;
import com.redhat.lightblue.crud.validator.DefaultFieldConstraintValidators;

import com.redhat.lightblue.metadata.Metadata;
import com.redhat.lightblue.metadata.MetadataConstants;
import com.redhat.lightblue.metadata.parser.Extensions;
import com.redhat.lightblue.metadata.parser.JSONMetadataParser;
import com.redhat.lightblue.metadata.parser.DataStoreParser;

import com.redhat.lightblue.metadata.types.DefaultTypes;

import com.redhat.lightblue.mediator.Mediator;
import com.redhat.lightblue.util.JsonUtils;

/**
 * Manager class that creates instances of Mediator, Factory, Metadata, etc.
 * based on configuration.
 */
public final class LightblueFactory implements Serializable {

    private static final long serialVersionUID = 1l;

    private static final Logger LOGGER = LoggerFactory.getLogger(LightblueFactory.class);

    private final DataSourcesConfiguration datasources;

    private static final JsonNodeFactory NODE_FACTORY = JsonNodeFactory.withExactBigDecimals(true);

    private volatile Metadata metadata = null;
    private volatile JSONMetadataParser parser = null;
    private volatile Mediator mediator = null;
    private volatile Factory factory;

    private static LightblueFactory instance;

    /**
     * Sudo-singleton, get the last instantiated instance of LightblueFactory.
     * In practice expect only one instance to ever exist.
     *
     * @return an instance of LightblueFactory
     */
    public static LightblueFactory getInstance() {
        return instance;
    }

    public LightblueFactory(DataSourcesConfiguration datasources) {
        instance = this;
        this.datasources = datasources;
    }

    private synchronized void initializeParser()
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException, IOException, InstantiationException {
        if (parser == null) {
            Extensions<JsonNode> extensions = new Extensions<>();
            extensions.addDefaultExtensions();

            Map<String, DataSourceConfiguration> ds = datasources.getDataSources();
            for (Map.Entry<String, DataSourceConfiguration> entry : ds.entrySet()) {
                Class<DataStoreParser> tempParser = entry.getValue().getMetadataDataStoreParser();
                DataStoreParser backendParser = tempParser.newInstance();
                extensions.registerDataStoreParser(backendParser.getDefaultName(), backendParser);
            }

            parser = new JSONMetadataParser(extensions, new DefaultTypes(), NODE_FACTORY);
        }
    }

    private synchronized void initializeMediator()
            throws ClassNotFoundException, IllegalAccessException, InvocationTargetException, IOException, NoSuchMethodException, InstantiationException {
        if (mediator == null) {
            mediator = new Mediator(getMetadata(), getFactory());
        }
    }

    private synchronized void initializeFactory()
            throws ClassNotFoundException, IllegalAccessException, InvocationTargetException, IOException, NoSuchMethodException, InstantiationException {
        if (factory == null) {
            JsonNode root;
            try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(CrudConfiguration.FILENAME)) {
                root = JsonUtils.json(is);
            }

            // convert root to Configuration object
            CrudConfiguration configuration = new CrudConfiguration();
            configuration.initializeFromJson(root);

            Factory f = new Factory();
            f.addFieldConstraintValidators(new DefaultFieldConstraintValidators());

            // Add default interceptors
            new UIDInterceptor().register(f.getInterceptors());

            // validate
            if (!configuration.isValid()) {
                throw new IllegalStateException(CrudConstants.ERR_CONFIG_NOT_VALID + " - " + CrudConfiguration.FILENAME);
            }

            for (ControllerConfiguration x : configuration.getControllers()) {
                ControllerFactory cfactory = x.getControllerFactory().newInstance();
                CRUDController controller = cfactory.createController(x, datasources);
                f.addCRUDController(x.getBackend(), controller);
            }
            // Make sure we assign factory after it is initialized. (factory is volatile, there's a memory barrier here)
            factory = f;
        }
    }

    private synchronized void initializeMetadata() throws IOException, ClassNotFoundException, NoSuchMethodException, IllegalAccessException, InvocationTargetException, InstantiationException {
        if (metadata == null) {
            LOGGER.debug("Initializing metadata");

            JsonNode root;
            try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(MetadataConfiguration.FILENAME)) {
                root = JsonUtils.json(is);
            }
            LOGGER.debug("Config root:{}", root);

            JsonNode cfgClass = root.get("type");
            if (cfgClass == null) {
                throw new IllegalStateException(MetadataConstants.ERR_CONFIG_NOT_FOUND + " - type");
            }

            MetadataConfiguration cfg = (MetadataConfiguration) Class.forName(cfgClass.asText()).newInstance();
            cfg.initializeFromJson(root);

            metadata = cfg.createMetadata(datasources, getJSONParser(), this);
        }
    }

    public Metadata getMetadata()
            throws IOException, ClassNotFoundException, NoSuchMethodException, IllegalAccessException, InvocationTargetException, InstantiationException {
        if (metadata == null) {
            initializeMetadata();
        }

        return metadata;
    }

    public JSONMetadataParser getJSONParser()
            throws ClassNotFoundException, NoSuchMethodException, IOException, IllegalAccessException, InvocationTargetException, InstantiationException {
        if (parser == null) {
            initializeParser();
        }

        return parser;
    }

    public Factory getFactory()
            throws ClassNotFoundException, IllegalAccessException, InvocationTargetException, IOException, NoSuchMethodException, InstantiationException {
        if (factory == null) {
            initializeFactory();
        }
        return factory;
    }

    public Mediator getMediator()
            throws ClassNotFoundException, IllegalAccessException, InvocationTargetException, IOException, NoSuchMethodException, InstantiationException {
        if (mediator == null) {
            initializeMediator();
        }

        return mediator;
    }
}

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
package com.redhat.lightblue.rest.metadata.hystrix;

import javax.servlet.http.HttpServletRequest;

import org.jboss.resteasy.spi.ResteasyProviderFactory;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;
import com.redhat.lightblue.ClientIdentification;
import com.redhat.lightblue.Request;
import com.redhat.lightblue.metadata.Metadata;
import com.redhat.lightblue.metadata.parser.JSONMetadataParser;
import com.redhat.lightblue.rest.RestConfiguration;
import com.redhat.lightblue.rest.metadata.RestMetadataConstants;
import com.redhat.lightblue.util.Error;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Note that passing a Metadata in the constructor is optional. If not provided,
 * it is fetched from MetadataManager object.
 *
 * @author nmalik
 */
public abstract class AbstractRestCommand extends HystrixCommand<String> {
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractRestCommand.class);

    protected static final JsonNodeFactory NODE_FACTORY = JsonNodeFactory.withExactBigDecimals(true);

    private final Metadata metadata;
    private final HttpServletRequest httpServletRequest;

    public AbstractRestCommand(Class commandClass, String clientKey, Metadata metadata) {
        super(HystrixCommand.Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey(commandClass.getSimpleName()))
                .andCommandKey(HystrixCommandKey.Factory.asKey(clientKey == null ? commandClass.getSimpleName() : clientKey)));
        this.metadata = metadata;
        this.httpServletRequest = ResteasyProviderFactory.getContextData(HttpServletRequest.class);
    }

    /**
     * Returns the metadata. If no metadata is set on the command uses
     * MetadataManager#getMetadata() method.
     *
     * @return
     * @throws Exception
     */
    protected Metadata getMetadata() {
        Metadata m = null;
        try {
            if (null != metadata) {
                m = metadata;
            } else {
                m = RestConfiguration.getFactory().getMetadata();
            }
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
            throw Error.get(RestMetadataConstants.ERR_CANT_GET_METADATA, e.getMessage());
        }
        return m;
    }

    protected JSONMetadataParser getJSONParser() {
        JSONMetadataParser parser = null;
        try {
            parser = RestConfiguration.getFactory().getJSONParser();
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
            throw Error.get(RestMetadataConstants.ERR_CANT_GET_PARSER, e.getMessage());
        }
        return parser;
    }

    protected void addCallerId(Request req) {
        req.setClientId(new ClientIdentification() {
            @Override
            public boolean isUserInRole(String role) {
                return httpServletRequest == null ? false : httpServletRequest.isUserInRole(role);
            }
        });
    }
}

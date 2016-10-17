/*
 * Copyright 2013-2015 Hewlett-Packard Development Company, L.P.
 * Licensed under the MIT License (the "License"); you may not use this file except in compliance with the License.
 */
package com.hp.autonomy.frontend.configuration.server;

import com.autonomy.aci.client.services.AciService;
import com.autonomy.aci.client.services.Processor;
import com.autonomy.aci.client.transport.AciServerDetails;
import com.autonomy.aci.client.util.AciParameters;
import com.autonomy.nonaci.ServerDetails;
import com.autonomy.nonaci.indexing.IndexingException;
import com.autonomy.nonaci.indexing.IndexingService;
import com.autonomy.nonaci.indexing.impl.IndexCommandImpl;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.hp.autonomy.frontend.configuration.ConfigException;
import com.hp.autonomy.frontend.configuration.validation.OptionalConfigurationComponent;
import com.hp.autonomy.frontend.configuration.validation.ValidationResult;
import com.hp.autonomy.types.idol.marshalling.ProcessorFactory;
import com.hp.autonomy.types.idol.responses.GetChildrenResponseData;
import com.hp.autonomy.types.idol.responses.GetStatusResponseData;
import com.hp.autonomy.types.idol.responses.GetVersionResponseData;
import com.hp.autonomy.types.requests.idol.actions.general.GeneralActions;
import com.hp.autonomy.types.requests.idol.actions.status.StatusActions;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.experimental.Accessors;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Configuration for an ACI server, which can also include index and service ports.
 */
@Data
@JsonDeserialize(builder = ServerConfig.Builder.class)
public class ServerConfig implements OptionalConfigurationComponent<ServerConfig> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerConfig.class);

    private final AciServerDetails.TransportProtocol protocol;
    private final String host;
    private final int port;

    private final ServerDetails.TransportProtocol indexProtocol;
    private final int indexPort;
    private final AciServerDetails.TransportProtocol serviceProtocol;
    private final int servicePort;

    /**
     * @return The producttypecsv of the server, used for validation
     */
    private final Set<ProductType> productType;

    /**
     * @return The error message to expect when testing the index port of the server.  If not defined it is assumed that
     * this server does not support indexing.
     */
    private final String indexErrorMessage;

    /**
     * @return A Pattern used to match the product type. Useful for connectors.
     */
    private final Pattern productTypeRegex;

    private ServerConfig(final Builder builder) {
        protocol = builder.getProtocol();
        host = builder.getHost();
        port = builder.getPort();
        indexPort = builder.getIndexPort();
        indexProtocol = builder.getIndexProtocol();
        servicePort = builder.getServicePort();
        serviceProtocol = builder.getServiceProtocol();
        productType = builder.getProductType();
        indexErrorMessage = builder.getIndexErrorMessage();

        if (builder.productTypeRegex == null) {
            productTypeRegex = null;
        } else {
            productTypeRegex = Pattern.compile(builder.productTypeRegex);
        }
    }

    /**
     * Merges this ServerConfig with another ServerConfig.
     *
     * @param serverConfig The ServerConfig to merge with.
     * @return A new ServerConfig whose settings replace the fields in this that are null with those from serverConfig
     */
    public ServerConfig merge(final ServerConfig serverConfig) {
        if (serverConfig != null) {
            final Builder builder = new Builder();

            builder.setProtocol(protocol == null ? serverConfig.protocol : protocol);
            builder.setHost(host == null ? serverConfig.host : host);
            builder.setPort(port == 0 ? serverConfig.port : port);
            builder.setIndexPort(indexPort == 0 ? serverConfig.indexPort : indexPort);
            builder.setIndexProtocol(indexProtocol == null ? serverConfig.indexProtocol : indexProtocol);
            builder.setServicePort(servicePort == 0 ? serverConfig.servicePort : servicePort);
            builder.setServiceProtocol(serviceProtocol == null ? serverConfig.serviceProtocol : serviceProtocol);
            builder.setProductType(productType == null ? serverConfig.productType : productType);
            builder.setIndexErrorMessage(indexErrorMessage == null ? serverConfig.indexErrorMessage : indexErrorMessage);

            // we use Pattern here, but Builder takes String
            builder.setProductTypeRegex(Objects.toString(productTypeRegex == null ? serverConfig.productTypeRegex : productTypeRegex, null));

            return builder.build();
        }

        return this;
    }

    /**
     * Creates a new ServerConfig with the given ServerDetails for indexing
     *
     * @param serverDetails The ServerDetails to use
     * @return A new ServerConfig with the supplied indexing details
     */
    public ServerConfig withIndexServer(final ServerDetails serverDetails) {
        final Builder builder = new Builder();

        builder.setProtocol(protocol);
        builder.setHost(host);
        builder.setPort(port);
        builder.setIndexProtocol(serverDetails.getProtocol());
        builder.setIndexPort(serverDetails.getPort());
        builder.setServiceProtocol(serviceProtocol);
        builder.setServicePort(servicePort);

        return builder.build();
    }

    /**
     * Fetches the index and service ports from the component
     *
     * @param aciService      The {@link AciService} used to discover the ports.
     * @param indexingService The {@link IndexingService} used to test the index port. This can be null if
     * @return A new ServerConfig with its indexing and service details filled in.
     */
    public ServerConfig fetchServerDetails(final AciService aciService, final IndexingService indexingService, final ProcessorFactory processorFactory) {
        final Builder builder = new Builder();

        builder.setProtocol(protocol);
        builder.setHost(host);
        builder.setPort(port);

        final Ports ports = determinePorts(aciService, processorFactory);

        if (ports.indexPort != null) {
            final ServerDetails indexDetails = new ServerDetails();
            indexDetails.setHost(host);
            indexDetails.setPort(ports.indexPort);
            boolean isIndexPortValid = false;

            for (final ServerDetails.TransportProtocol protocol : Arrays.asList(ServerDetails.TransportProtocol.HTTP, ServerDetails.TransportProtocol.HTTPS)) {
                indexDetails.setProtocol(protocol);

                if (testIndexingConnection(indexDetails, indexingService, indexErrorMessage)) {
                    // test http first. If the server is https, it will give an error (quickly),
                    // whereas the timeout when doing https to a http server takes a really long time
                    builder.setIndexProtocol(protocol);
                    builder.setIndexPort(ports.indexPort);

                    isIndexPortValid = true;
                    break;
                }
            }

            if (!isIndexPortValid) {
                throw new IllegalArgumentException("Server does not have a valid index port");
            }
        }

        final int servicePort = ports.servicePort;
        final AciServerDetails servicePortDetails = new AciServerDetails();
        servicePortDetails.setHost(host);
        servicePortDetails.setPort(servicePort);

        for (final AciServerDetails.TransportProtocol protocol : Arrays.asList(AciServerDetails.TransportProtocol.HTTP, AciServerDetails.TransportProtocol.HTTPS)) {
            servicePortDetails.setProtocol(protocol);
            servicePortDetails.setPort(servicePort);

            if (testServicePortConnection(servicePortDetails, aciService, processorFactory)) {
                // test http first. If the server is https, it will give an error (quickly),
                // whereas the timeout when doing https to a http server takes a really long time
                builder.setServiceProtocol(protocol);
                builder.setServicePort(servicePort);

                //Both index and service ports are valid
                return builder.build();
            }
        }

        //Index port valid but service port invalid
        throw new IllegalArgumentException("Server does not have a valid service port");
    }

    private Ports determinePorts(final AciService aciService, final ProcessorFactory processorFactory) {
        try {
            // getStatus doesn't always return ports, but does when an index port is used
            final boolean useGetStatusToDeterminePorts = indexErrorMessage != null;
            if (useGetStatusToDeterminePorts) {
                final Processor<GetStatusResponseData> processor = processorFactory.getResponseDataProcessor(GetStatusResponseData.class);
                final GetStatusResponseData getStatusResponseData = aciService.executeAction(toAciServerDetails(), new AciParameters(StatusActions.GetStatus.name()), processor);
                return new Ports(getStatusResponseData.getAciport(), getStatusResponseData.getIndexport(), getStatusResponseData.getServiceport());
            } else {
                final Processor<GetChildrenResponseData> processor = processorFactory.getResponseDataProcessor(GetChildrenResponseData.class);
                final GetChildrenResponseData responseData = aciService.executeAction(toAciServerDetails(), new AciParameters(GeneralActions.GetChildren.name()), processor);
                return new Ports(responseData.getPort(), null, responseData.getServiceport());
            }
        } catch (final RuntimeException e) {
            throw new IllegalArgumentException("Unable to connect to ACI server", e);
        }
    }

    private boolean testServicePortConnection(final AciServerDetails serviceDetails, final AciService aciService, final ProcessorFactory processorFactory) {
        try {
            aciService.executeAction(serviceDetails, new AciParameters("getstatus"), processorFactory.getVoidProcessor());
            return true;
        } catch (final Exception e) {
            return false;
        }
    }

    private boolean testIndexingConnection(final ServerDetails indexDetails, final IndexingService indexingService, final String errorMessage) {
        try {
            indexingService.executeCommand(indexDetails, new IndexCommandImpl("test"));
        } catch (final IndexingException e) {
            // we got back a response from the index port
            return e.getMessage().contains(errorMessage);
        } catch (final RuntimeException e) {
            // any other kind of exception is bad
        }

        return false;
    }

    /**
     * @return A representation of this server as an {@link AciServerDetails}
     */
    public AciServerDetails toAciServerDetails() {
        return new AciServerDetails(getProtocol(), getHost(), getPort());
    }

    /**
     * @return A representation of this server as an {@link ServerDetails}
     */
    public ServerDetails toServerDetails() {
        final ServerDetails serverDetails = new ServerDetails();

        serverDetails.setHost(getHost());
        serverDetails.setPort(getIndexPort());
        serverDetails.setProtocol(indexProtocol);

        return serverDetails;
    }

    /**
     * Validates that the required settings are supplied and that the target server is responding
     *
     * @param aciService       The {@link AciService} to use for validation
     * @param indexingService  The {@link IndexingService} to use for validation. If the server does not support indexing
     *                         this may be null
     * @param processorFactory The {@link ProcessorFactory}
     * @return A {@link ValidationResult} which will be
     * <ul>
     * <li>Valid if the server config is valid</li>
     * <li>If it is not valid because the given server is not of the require type, the data will be a {@link IncorrectServerType},
     * containing a list of valid server types</li>
     * <li>If it is invalid for any other reason, the data will be a {@link ServerConfig.Validation}</li>
     * </ul>
     */
    public ValidationResult<?> validate(final AciService aciService, final IndexingService indexingService, final ProcessorFactory processorFactory) {
        // if the host is blank further testing is futile
        try {
            // string doesn't matter here as we swallow the exception
            basicValidate(null);
        } catch (final ConfigException e) {
            return new ValidationResult<>(false, Validation.REQUIRED_FIELD_MISSING);
        }

        final boolean isCorrectVersion;

        try {
            isCorrectVersion = testServerVersion(aciService, processorFactory);
        } catch (final RuntimeException e) {
            LOGGER.debug("Error validating server version for {}", productType);
            LOGGER.debug("", e);
            return new ValidationResult<>(false, Validation.CONNECTION_ERROR);
        }

        if (!isCorrectVersion) {
            if (productTypeRegex == null) {
                final List<String> friendlyNames = new ArrayList<>();

                for (final ProductType productType : this.productType) {
                    friendlyNames.add(productType.getFriendlyName());
                }

                return new ValidationResult<>(false, new IncorrectServerType(friendlyNames));
            } else {
                // can't use friendly names for regex
                return new ValidationResult<Object>(false, Validation.REGULAR_EXPRESSION_MATCH_ERROR);
            }
        }

        try {
            final ServerConfig serverConfig = fetchServerDetails(aciService, indexingService, processorFactory);

            final boolean result = serverConfig.getServicePort() > 0;

            if (indexErrorMessage == null) {
                return new ValidationResult<>(result, Validation.SERVICE_PORT_ERROR);
            } else {
                return new ValidationResult<>(result && serverConfig.getIndexPort() > 0,
                        Validation.SERVICE_OR_INDEX_PORT_ERROR);
            }

        } catch (final RuntimeException e) {
            LOGGER.debug("Error validating config", e);
            return new ValidationResult<>(false, Validation.FETCH_PORT_ERROR);
        }
    }

    /**
     * @param component The name of the configuration section, to be used in case of failure
     * @return true if all the required settings exist
     * @throws ConfigException If the ServerConfig is invalid
     */
    @Override
    public void basicValidate(final String component) throws ConfigException {
        if (getPort() <= 0 || getPort() > 65535) {
            throw new ConfigException(component,
                    component + ": port number must be between 1 and 65535.");
        } else if (StringUtils.isBlank(getHost())) {
            throw new ConfigException(component,
                    component + ": host name must not be blank.");
        }
    }

    private boolean testServerVersion(final AciService aciService, final ProcessorFactory processorFactory) {
        // Community's ProductName is just IDOL, so we need to check the product type
        final GetVersionResponseData versionResponseData = aciService
                .executeAction(toAciServerDetails(),
                        new AciParameters(GeneralActions.GetVersion.name()),
                        processorFactory.getResponseDataProcessor(GetVersionResponseData.class));

        final Collection<String> serverProductTypes = new HashSet<>(Arrays.asList(versionResponseData.getProducttypecsv().split(",")));

        return productTypeRegex == null
                ? productType.stream().anyMatch(p -> serverProductTypes.contains(p.name()))
                : serverProductTypes.stream().anyMatch(serverProductType -> productTypeRegex.matcher(serverProductType).matches());
    }

    /**
     * @return The service port details of this ServerConfig as an {@link AciServerDetails}
     */
    public AciServerDetails toServiceServerDetails() {
        return new AciServerDetails(getServiceProtocol(), getHost(), getServicePort());
    }

    @Override
    @JsonIgnore
    public boolean isEnabled() {
        return true;
    }

    public enum Validation {
        REQUIRED_FIELD_MISSING,
        CONNECTION_ERROR,
        SERVICE_PORT_ERROR,
        SERVICE_OR_INDEX_PORT_ERROR,
        FETCH_PORT_ERROR,
        INCORRECT_SERVER_TYPE,
        REGULAR_EXPRESSION_MATCH_ERROR
    }

    @Data
    public static class IncorrectServerType {
        private final Validation validation = Validation.INCORRECT_SERVER_TYPE;
        private final List<String> friendlyNames;

        IncorrectServerType(final List<String> friendlyNames) {
            this.friendlyNames = friendlyNames;
        }
    }

    @Data
    @Accessors(chain = true)
    @JsonPOJOBuilder(withPrefix = "set")
    @JsonIgnoreProperties(ignoreUnknown = true) // for compatibility with old AciServerDetails config files
    public static class Builder {
        private AciServerDetails.TransportProtocol protocol = AciServerDetails.TransportProtocol.HTTP;
        private AciServerDetails.TransportProtocol serviceProtocol = AciServerDetails.TransportProtocol.HTTP;
        private ServerDetails.TransportProtocol indexProtocol = ServerDetails.TransportProtocol.HTTP;
        private String host;
        private int port;
        private int indexPort;
        private int servicePort;
        private Set<ProductType> productType;
        private String indexErrorMessage;
        private String productTypeRegex;

        public ServerConfig build() {
            return new ServerConfig(this);
        }
    }

    @AllArgsConstructor
    private static class Ports {
        final int aciPort;
        final Integer indexPort;
        final int servicePort;
    }
}

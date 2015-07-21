package org.vertexium.elasticsearch;

import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.stats.IndexStats;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.hppc.ObjectContainer;
import org.elasticsearch.common.hppc.cursors.ObjectCursor;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.vertexium.*;
import org.vertexium.id.NameSubstitutionStrategy;
import org.vertexium.property.StreamingPropertyValue;
import org.vertexium.query.*;
import org.vertexium.search.SearchIndex;
import org.vertexium.search.SearchIndexWithVertexPropertyCountByValue;
import org.vertexium.type.GeoCircle;
import org.vertexium.type.GeoPoint;
import org.vertexium.util.Preconditions;
import org.vertexium.util.VertexiumLogger;
import org.vertexium.util.VertexiumLoggerFactory;

import java.io.IOException;
import java.util.*;

public abstract class ElasticSearchSearchIndexBase implements SearchIndex, SearchIndexWithVertexPropertyCountByValue {
    private static final VertexiumLogger LOGGER = VertexiumLoggerFactory.getLogger(ElasticSearchSearchIndexBase.class);
    protected static final VertexiumLogger MUTATION_LOGGER = VertexiumLoggerFactory.getMutationLogger(SearchIndex.class);
    public static final String ELEMENT_TYPE = "element";
    public static final String ELEMENT_TYPE_FIELD_NAME = "__elementType";
    public static final String VISIBILITY_FIELD_NAME = "__visibility";
    public static final String EXACT_MATCH_PROPERTY_NAME_SUFFIX = "_e";
    public static final String GEO_PROPERTY_NAME_SUFFIX = "_g";
    public static final int MAX_BATCH_COUNT = 25000;
    public static final long MAX_BATCH_SIZE = 15 * 1024 * 1024;
    private final TransportClient client;
    private final ElasticSearchSearchIndexConfiguration config;
    private final Map<String, IndexInfo> indexInfos = new HashMap<>();
    private int indexInfosLastSize = 0; // Used to prevent creating a index name array each time
    private String[] indexNamesAsArray;
    private NameSubstitutionStrategy nameSubstitutionStrategy;
    private IndexSelectionStrategy indexSelectionStrategy;

    protected ElasticSearchSearchIndexBase(GraphConfiguration config) {
        this.config = new ElasticSearchSearchIndexConfiguration(config);
        nameSubstitutionStrategy = this.config.getNameSubstitutionStrategy();
        indexSelectionStrategy = this.config.getIndexSelectionStrategy();

        ImmutableSettings.Builder settingsBuilder = ImmutableSettings.settingsBuilder();
        if (getConfig().getClusterName() != null) {
            settingsBuilder.put("cluster.name", getConfig().getClusterName());
        }
        client = new TransportClient(settingsBuilder.build());
        for (String esLocation : getConfig().getEsLocations()) {
            String[] locationSocket = esLocation.split(":");
            String hostname;
            int port;
            if (locationSocket.length == 2) {
                hostname = locationSocket[0];
                port = Integer.parseInt(locationSocket[1]);
            } else if (locationSocket.length == 1) {
                hostname = locationSocket[0];
                port = getConfig().getPort();
            } else {
                throw new VertexiumException("Invalid elastic search location: " + esLocation);
            }
            client.addTransportAddress(new InetSocketTransportAddress(hostname, port));
        }

        loadIndexInfos();
        loadPropertyDefinitions();
    }

    protected boolean isStoreSourceData() {
        return getConfig().isStoreSourceData();
    }

    protected void loadIndexInfos() {
        Map<String, IndexStats> indices = getExistingIndexNames();
        for (String indexName : indices.keySet()) {
            if (!indexSelectionStrategy.isIncluded(this, indexName)) {
                LOGGER.debug("skipping index %s, not in indicesToQuery", indexName);
                continue;
            }

            IndexInfo indexInfo = indexInfos.get(indexName);
            if (indexInfo != null) {
                continue;
            }

            LOGGER.debug("loading index info for %s", indexName);
            indexInfo = createIndexInfo(indexName);
            indexInfos.put(indexName, indexInfo);
        }
    }

    private Map<String, IndexStats> getExistingIndexNames() {
        return client.admin().indices().prepareStats().execute().actionGet().getIndices();
    }

    protected IndexInfo ensureIndexCreatedAndInitialized(String indexName, boolean storeSourceData) {
        IndexInfo indexInfo = indexInfos.get(indexName);
        if (indexInfo != null && indexInfo.isElementTypeDefined()) {
            return indexInfo;
        }

        synchronized (indexInfos) {
            if (indexInfo == null) {
                if (!client.admin().indices().prepareExists(indexName).execute().actionGet().isExists()) {
                    try {
                        createIndex(indexName, storeSourceData);
                    } catch (IOException e) {
                        throw new VertexiumException("Could not create index: " + indexName, e);
                    }
                }

                indexInfo = createIndexInfo(indexName);
                indexInfos.put(indexName, indexInfo);
            }

            ensureMappingsCreated(indexInfo);

            return indexInfo;
        }
    }

    protected IndexInfo createIndexInfo(String indexName) {
        return new IndexInfo(indexName);
    }

    protected void ensureMappingsCreated(IndexInfo indexInfo) {
        if (!indexInfo.isElementTypeDefined()) {
            try {
                XContentBuilder mappingBuilder = XContentFactory.jsonBuilder()
                        .startObject()
                        .startObject("_source").field("enabled", isStoreSourceData()).endObject()
                        .startObject("properties");
                createIndexAddFieldsToElementType(mappingBuilder);
                XContentBuilder mapping = mappingBuilder.endObject()
                        .endObject();

                client.admin().indices().preparePutMapping(indexInfo.getIndexName())
                        .setType(ELEMENT_TYPE)
                        .setSource(mapping)
                        .execute()
                        .actionGet();
                indexInfo.setElementTypeDefined(true);
            } catch (IOException e) {
                throw new VertexiumException("Could not add mappings to index: " + indexInfo.getIndexName(), e);
            }
        }
    }

    @SuppressWarnings("unused")
    protected void createIndex(String indexName, boolean storeSourceData) throws IOException {
        int shards = getConfig().getNumberOfShards();
        CreateIndexResponse createResponse = client.admin().indices().prepareCreate(indexName)
                .setSettings(ImmutableSettings.settingsBuilder().put("number_of_shards", shards))
                .execute().actionGet();

        ClusterHealthResponse health = client.admin().cluster().prepareHealth(indexName)
                .setWaitForGreenStatus()
                .execute().actionGet();
        LOGGER.debug("Index status: %s", health.toString());
        if (health.isTimedOut()) {
            LOGGER.warn("timed out waiting for green index status, for index: %s", indexName);
        }
    }

    protected void createIndexAddFieldsToElementType(XContentBuilder builder) throws IOException {
        builder
                .startObject(ELEMENT_TYPE_FIELD_NAME).field("type", "string").field("store", "true").endObject()
                .startObject(VISIBILITY_FIELD_NAME).field("type", "string").field("analyzer", "keyword").field("index", "not_analyzed").field("store", "true").endObject()
        ;
        getConfig().getScoringStrategy().addFieldsToElementType(builder);
    }

    public synchronized void loadPropertyDefinitions() {
        for (String indexName : indexInfos.keySet()) {
            loadPropertyDefinitions(indexName);
        }
    }

    private void loadPropertyDefinitions(String indexName) {
        IndexInfo indexInfo = indexInfos.get(indexName);
        Map<String, String> propertyTypes = getPropertyTypesFromServer(indexName);
        for (Map.Entry<String, String> property : propertyTypes.entrySet()) {
            PropertyDefinition propertyDefinition = createPropertyDefinition(property, propertyTypes);
            indexInfo.addPropertyDefinition(nameSubstitutionStrategy.deflate(propertyDefinition.getPropertyName()), propertyDefinition);
        }
    }

    private PropertyDefinition createPropertyDefinition(Map.Entry<String, String> property, Map<String, String> propertyTypes) {
        String propertyName = nameSubstitutionStrategy.deflate(property.getKey());
        Class dataType = elasticSearchTypeToClass(property.getValue());
        Set<TextIndexHint> indexHints = new HashSet<>();

        if (dataType == String.class) {
            if (propertyTypes.containsKey(propertyName + GEO_PROPERTY_NAME_SUFFIX)) {
                dataType = GeoPoint.class;
                indexHints.add(TextIndexHint.FULL_TEXT);
            } else if (propertyName.endsWith(EXACT_MATCH_PROPERTY_NAME_SUFFIX)) {
                indexHints.add(TextIndexHint.EXACT_MATCH);
                if (propertyTypes.containsKey(propertyName.substring(0, propertyName.length() - EXACT_MATCH_PROPERTY_NAME_SUFFIX.length()))) {
                    indexHints.add(TextIndexHint.FULL_TEXT);
                }
            } else {
                indexHints.add(TextIndexHint.FULL_TEXT);
                if (propertyTypes.containsKey(propertyName + EXACT_MATCH_PROPERTY_NAME_SUFFIX)) {
                    indexHints.add(TextIndexHint.EXACT_MATCH);
                }
            }
        } else if (dataType == GeoPoint.class) {
            indexHints.add(TextIndexHint.FULL_TEXT);
        }

        return new PropertyDefinition(propertyName, dataType, indexHints);
    }

    private Class elasticSearchTypeToClass(String typeName) {
        if ("string".equals(typeName)) {
            return String.class;
        }
        if ("float".equals(typeName)) {
            return Float.class;
        }
        if ("double".equals(typeName)) {
            return Double.class;
        }
        if ("byte".equals(typeName)) {
            return Byte.class;
        }
        if ("short".equals(typeName)) {
            return Short.class;
        }
        if ("integer".equals(typeName)) {
            return Integer.class;
        }
        if ("date".equals(typeName)) {
            return Date.class;
        }
        if ("long".equals(typeName)) {
            return Long.class;
        }
        if ("boolean".equals(typeName)) {
            return Boolean.class;
        }
        if ("geo_point".equals(typeName)) {
            return GeoPoint.class;
        }
        throw new VertexiumException("Unhandled type: " + typeName);
    }

    private Map<String, String> getPropertyTypesFromServer(String indexName) {
        Map<String, String> propertyTypes = new HashMap<>();
        try {
            ImmutableOpenMap<String, ImmutableOpenMap<String, MappingMetaData>> imd = client.admin().indices().prepareGetMappings(indexName).execute().actionGet().getMappings();
            for (ObjectCursor<ImmutableOpenMap<String, MappingMetaData>> m : imd.values()) {
                ObjectContainer<MappingMetaData> mappingMetaDatas = m.value.values();
                for (ObjectCursor<MappingMetaData> mappingMetaData : mappingMetaDatas) {
                    Map<String, Object> sourceAsMap = mappingMetaData.value.getSourceAsMap();
                    Map properties = (Map) sourceAsMap.get("properties");
                    for (Object propertyObj : properties.entrySet()) {
                        Map.Entry property = (Map.Entry) propertyObj;
                        String propertyName = nameSubstitutionStrategy.inflate((String) property.getKey());
                        try {
                            Map propertyAttributes = (Map) property.getValue();
                            String propertyType = (String) propertyAttributes.get("type");
                            if (propertyType != null) {
                                propertyTypes.put(propertyName, propertyType);
                                continue;
                            }

                            Map subProperties = (Map) propertyAttributes.get("properties");
                            if (subProperties != null) {
                                if (subProperties.containsKey("lat") && subProperties.containsKey("lon")) {
                                    propertyTypes.put(propertyName, "geo_point");
                                    continue;
                                }
                            }

                            throw new VertexiumException("Failed to parse property type on property could not determine property type: " + propertyName);
                        } catch (Exception ex) {
                            throw new VertexiumException("Failed to parse property type on property: " + propertyName);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            throw new VertexiumException("Could not get current properties from elastic search for index " + indexName, ex);
        }
        return propertyTypes;
    }

    @Override
    public abstract void addElement(Graph graph, Element element, Authorizations authorizations);

    @Override
    public abstract void deleteElement(Graph graph, Element element, Authorizations authorizations);

    @Override
    public void deleteProperty(Graph graph, Element element, Property property, Authorizations authorizations) {
        deleteProperty(graph, element, property.getKey(), property.getName(), property.getVisibility(), authorizations);
    }

    @Override
    public void deleteProperty(Graph graph, Element element, String propertyKey, String propertyName, Visibility propertyVisibility, Authorizations authorizations) {
        addElement(graph, element, authorizations);
    }

    @Override
    public void addElements(Graph graph, Iterable<? extends Element> elements, Authorizations authorizations) {
        int totalCount = 0;
        Map<IndexInfo, BulkRequest> bulkRequests = new HashMap<>();
        for (Element element : elements) {
            String indexName = getIndexName(element);
            IndexInfo indexInfo = ensureIndexCreatedAndInitialized(indexName, isStoreSourceData());
            BulkRequest bulkRequest = bulkRequests.get(indexInfo);
            if (bulkRequest == null) {
                bulkRequest = new BulkRequest();
                bulkRequests.put(indexInfo, bulkRequest);
            }

            if (bulkRequest.numberOfActions() >= MAX_BATCH_COUNT || bulkRequest.estimatedSizeInBytes() > MAX_BATCH_SIZE) {
                LOGGER.debug("adding elements... %d (est size %d)", bulkRequest.numberOfActions(), bulkRequest.estimatedSizeInBytes());
                totalCount += bulkRequest.numberOfActions();
                doBulkRequest(bulkRequest);
                bulkRequest = new BulkRequest();
                bulkRequests.put(indexInfo, bulkRequest);
            }
            addElementToBulkRequest(graph, bulkRequest, indexInfo, element, authorizations);

            getConfig().getScoringStrategy().addElement(this, graph, bulkRequest, indexInfo, element, authorizations);
        }
        for (BulkRequest bulkRequest : bulkRequests.values()) {
            if (bulkRequest.numberOfActions() > 0) {
                LOGGER.debug("adding elements... %d (est size %d)", bulkRequest.numberOfActions(), bulkRequest.estimatedSizeInBytes());
                totalCount += bulkRequest.numberOfActions();
                doBulkRequest(bulkRequest);
            }
        }
        LOGGER.debug("added %d elements", totalCount);

        if (getConfig().isAutoFlush()) {
            flush();
        }
    }

    @Override
    public abstract SearchIndexSecurityGranularity getSearchIndexSecurityGranularity();

    @Override
    public abstract GraphQuery queryGraph(Graph graph, String queryString, Authorizations authorizations);

    @Override
    public MultiVertexQuery queryGraph(Graph graph, String[] vertexIds, String queryString, Authorizations authorizations) {
        return new DefaultMultiVertexQuery(graph, vertexIds, queryString, getAllPropertyDefinitions(), authorizations);
    }

    @Override
    public abstract VertexQuery queryVertex(Graph graph, Vertex vertex, String queryString, Authorizations authorizations);

    @Override
    public boolean isQuerySimilarToTextSupported() {
        return true;
    }

    @Override
    public abstract SimilarToGraphQuery querySimilarTo(Graph graph, String[] similarToFields, String similarToText, Authorizations authorizations);

    public Map<String, PropertyDefinition> getAllPropertyDefinitions() {
        Map<String, PropertyDefinition> allPropertyDefinitions = new HashMap<>();
        for (IndexInfo indexInfo : this.indexInfos.values()) {
            allPropertyDefinitions.putAll(indexInfo.getPropertyDefinitions());
        }
        return allPropertyDefinitions;
    }

    @Override
    public void flush() {
        client.admin().indices().prepareRefresh(getIndexNamesAsArray()).execute().actionGet();
    }

    protected String[] getIndexNamesAsArray() {
        if (indexInfos.size() == indexInfosLastSize) {
            return indexNamesAsArray;
        }
        synchronized (this) {
            Set<String> keys = indexInfos.keySet();
            indexNamesAsArray = keys.toArray(new String[keys.size()]);
            indexInfosLastSize = indexInfos.size();
            return indexNamesAsArray;
        }
    }

    @Override
    public void shutdown() {
        client.close();
    }

    @Override
    public void addPropertyDefinition(PropertyDefinition propertyDefinition) throws IOException {
        LOGGER.debug("adding property definition: %s", propertyDefinition.toString());
        String propertyName = nameSubstitutionStrategy.deflate(propertyDefinition.getPropertyName());

        for (String indexName : getIndexNames(propertyDefinition)) {
            IndexInfo indexInfo = ensureIndexCreatedAndInitialized(indexName, isStoreSourceData());

            if (propertyDefinition.getDataType() == String.class) {
                if (propertyDefinition.getTextIndexHints().contains(TextIndexHint.EXACT_MATCH)) {
                    addPropertyToIndex(indexInfo, propertyName + EXACT_MATCH_PROPERTY_NAME_SUFFIX, String.class, false, propertyDefinition.getBoost());
                }
                if (propertyDefinition.getTextIndexHints().contains(TextIndexHint.FULL_TEXT)) {
                    addPropertyToIndex(indexInfo, propertyName, String.class, true, propertyDefinition.getBoost());
                }
            } else if (propertyDefinition.getDataType() == GeoPoint.class
                    || propertyDefinition.getDataType() == GeoCircle.class) {
                addPropertyToIndex(indexInfo, propertyName + GEO_PROPERTY_NAME_SUFFIX, propertyDefinition.getDataType(), true, propertyDefinition.getBoost());
                addPropertyToIndex(indexInfo, propertyName, String.class, true, propertyDefinition.getBoost());
            } else {
                addPropertyToIndex(indexInfo, propertyDefinition);
            }

            indexInfo.addPropertyDefinition(propertyName, propertyDefinition);
        }
    }

    @SuppressWarnings("unused")
    protected String[] getIndexNames(PropertyDefinition propertyDefinition) {
        return indexSelectionStrategy.getIndexNames(this, propertyDefinition);
    }

    @SuppressWarnings("unused")
    protected String getIndexName(Element element) {
        return indexSelectionStrategy.getIndexName(this, element);
    }

    protected String[] getIndicesToQuery() {
        return indexSelectionStrategy.getIndicesToQuery(this);
    }

    @Override
    public boolean isFieldBoostSupported() {
        return true;
    }

    public IndexInfo addPropertiesToIndex(Element element, Iterable<Property> properties) {
        try {
            String indexName = getIndexName(element);
            IndexInfo indexInfo = ensureIndexCreatedAndInitialized(indexName, isStoreSourceData());
            for (Property property : properties) {
                addPropertyToIndex(indexInfo, property);
            }
            return indexInfo;
        } catch (IOException e) {
            throw new VertexiumException("Could not add properties to index", e);
        }
    }

    private void addPropertyToIndex(IndexInfo indexInfo, String propertyName, Class dataType, boolean analyzed) throws IOException {
        addPropertyToIndex(indexInfo, propertyName, dataType, analyzed, null);
    }

    private void addPropertyToIndex(IndexInfo indexInfo, PropertyDefinition propertyDefinition) throws IOException {
        addPropertyToIndex(indexInfo, nameSubstitutionStrategy.deflate(propertyDefinition.getPropertyName()), propertyDefinition.getDataType(), true, propertyDefinition.getBoost());
    }

    public void addPropertyToIndex(IndexInfo indexInfo, Property property) throws IOException {
        String propertyName = nameSubstitutionStrategy.deflate(property.getName());

        if (indexInfo.isPropertyDefined(propertyName)) {
            return;
        }

        Class dataType;
        Object propertyValue = property.getValue();
        if (propertyValue instanceof StreamingPropertyValue) {
            StreamingPropertyValue streamingPropertyValue = (StreamingPropertyValue) propertyValue;
            if (!streamingPropertyValue.isSearchIndex()) {
                return;
            }
            dataType = streamingPropertyValue.getValueType();
            addPropertyToIndex(indexInfo, propertyName, dataType, true);
        } else if (propertyValue instanceof String) {
            dataType = String.class;
            addPropertyToIndex(indexInfo, propertyName + EXACT_MATCH_PROPERTY_NAME_SUFFIX, dataType, false);
            addPropertyToIndex(indexInfo, propertyName, dataType, true);
        } else if (propertyValue instanceof GeoPoint) {
            addPropertyToIndex(indexInfo, propertyName + GEO_PROPERTY_NAME_SUFFIX, GeoPoint.class, true);
            addPropertyToIndex(indexInfo, propertyName, String.class, true);
        } else if (propertyValue instanceof GeoCircle) {
            addPropertyToIndex(indexInfo, propertyName + GEO_PROPERTY_NAME_SUFFIX, GeoCircle.class, true);
            addPropertyToIndex(indexInfo, propertyName, String.class, true);
        } else {
            Preconditions.checkNotNull(propertyValue, "property value cannot be null for property: " + propertyName);
            dataType = propertyValue.getClass();
            addPropertyToIndex(indexInfo, propertyName, dataType, true);
        }
    }

    protected abstract void addPropertyToIndex(IndexInfo indexInfo, String propertyName, Class dataType, boolean analyzed, Double boost) throws IOException;

    protected boolean shouldIgnoreType(Class dataType) {
        return dataType == byte[].class;
    }

    public TransportClient getClient() {
        return client;
    }

    public ElasticSearchSearchIndexConfiguration getConfig() {
        return config;
    }

    protected void addTypeToMapping(XContentBuilder mapping, String propertyName, Class dataType, boolean analyzed, Double boost) throws IOException {
        if (dataType == String.class) {
            LOGGER.debug("Registering string type for %s", propertyName);
            mapping.field("type", "string");
            if (!analyzed) {
                mapping.field("index", "not_analyzed");
            }
        } else if (dataType == Float.class) {
            LOGGER.debug("Registering float type for %s", propertyName);
            mapping.field("type", "float");
        } else if (dataType == Double.class) {
            LOGGER.debug("Registering double type for %s", propertyName);
            mapping.field("type", "double");
        } else if (dataType == Byte.class) {
            LOGGER.debug("Registering byte type for %s", propertyName);
            mapping.field("type", "byte");
        } else if (dataType == Short.class) {
            LOGGER.debug("Registering short type for %s", propertyName);
            mapping.field("type", "short");
        } else if (dataType == Integer.class) {
            LOGGER.debug("Registering integer type for %s", propertyName);
            mapping.field("type", "integer");
        } else if (dataType == Date.class || dataType == DateOnly.class) {
            LOGGER.debug("Registering date type for %s", propertyName);
            mapping.field("type", "date");
        } else if (dataType == Long.class) {
            LOGGER.debug("Registering long type for %s", propertyName);
            mapping.field("type", "long");
        } else if (dataType == Boolean.class) {
            LOGGER.debug("Registering boolean type for %s", propertyName);
            mapping.field("type", "boolean");
        } else if (dataType == GeoPoint.class) {
            LOGGER.debug("Registering geo_point type for %s", propertyName);
            mapping.field("type", "geo_point");
        } else if (dataType == GeoCircle.class) {
            LOGGER.debug("Registering geo_shape type for %s", propertyName);
            mapping.field("type", "geo_shape");
            mapping.field("tree", "quadtree");
            mapping.field("precision", "100m");
        } else if (Number.class.isAssignableFrom(dataType)) {
            LOGGER.debug("Registering double type for %s", propertyName);
            mapping.field("type", "double");
        } else {
            throw new VertexiumException("Unexpected value type for property \"" + propertyName + "\": " + dataType.getName());
        }

        if (boost != null) {
            mapping.field("boost", boost.doubleValue());
        }
    }

    protected void doBulkRequest(BulkRequest bulkRequest) {
        BulkResponse response = getClient().bulk(bulkRequest).actionGet();
        if (response.hasFailures()) {
            for (BulkItemResponse bulkResponse : response) {
                if (bulkResponse.isFailed()) {
                    LOGGER.error("Failed to index %s (message: %s)", bulkResponse.getId(), bulkResponse.getFailureMessage());
                }
            }
            throw new VertexiumException("Could not add element.");
        }
    }

    @Override
    public synchronized void clearData() {
        Set<String> indexInfosSet = indexInfos.keySet();
        for (String indexName : indexInfosSet) {
            try {
                DeleteIndexRequest deleteRequest = new DeleteIndexRequest(indexName);
                getClient().admin().indices().delete(deleteRequest).actionGet();
                indexInfos.remove(indexName);
            } catch (Exception ex) {
                throw new VertexiumException("Could not delete index " + indexName, ex);
            }
            ensureIndexCreatedAndInitialized(indexName, isStoreSourceData());
        }
        loadPropertyDefinitions();
    }

    public abstract void addElementToBulkRequest(Graph graph, BulkRequest bulkRequest, IndexInfo indexInfo, Element element, Authorizations authorizations);

    @SuppressWarnings("unchecked")
    protected void addPropertyValueToPropertiesMap(Map<String, Object> propertiesMap, String propertyName, Object propertyValue) {
        Object existingValue = propertiesMap.get(propertyName);
        if (existingValue == null) {
            propertiesMap.put(propertyName, propertyValue);
            return;
        }

        if (existingValue instanceof List) {
            ((List) existingValue).add(propertyValue);
            return;
        }

        List list = new ArrayList();
        list.add(existingValue);
        list.add(propertyValue);
        propertiesMap.put(propertyName, list);
    }

    protected void convertGeoPoint(XContentBuilder jsonBuilder, Property property, GeoPoint geoPoint) throws IOException {
        Map<String, Object> propertyValueMap = new HashMap<>();
        propertyValueMap.put("lat", geoPoint.getLatitude());
        propertyValueMap.put("lon", geoPoint.getLongitude());
        jsonBuilder.field(property.getName() + GEO_PROPERTY_NAME_SUFFIX, propertyValueMap);
        if (geoPoint.getDescription() != null) {
            jsonBuilder.field(property.getName(), geoPoint.getDescription());
        }
    }

    protected void convertGeoPoint(Map<String, Object> propertiesMap, Property property, GeoPoint geoPoint) throws IOException {
        Map<String, Object> propertyValueMap = new HashMap<>();
        propertyValueMap.put("lat", geoPoint.getLatitude());
        propertyValueMap.put("lon", geoPoint.getLongitude());
        addPropertyValueToPropertiesMap(propertiesMap, property.getName() + GEO_PROPERTY_NAME_SUFFIX, propertyValueMap);
        if (geoPoint.getDescription() != null) {
            addPropertyValueToPropertiesMap(propertiesMap, property.getName(), geoPoint.getDescription());
        }
    }

    protected void convertGeoCircle(XContentBuilder jsonBuilder, Property property, GeoCircle geoCircle) throws IOException {
        Map<String, Object> propertyValueMap = new HashMap<>();
        propertyValueMap.put("type", "circle");
        List<Double> coordinates = new ArrayList<>();
        coordinates.add(geoCircle.getLongitude());
        coordinates.add(geoCircle.getLatitude());
        propertyValueMap.put("coordinates", coordinates);
        propertyValueMap.put("radius", geoCircle.getRadius() + "km");
        jsonBuilder.field(property.getName() + GEO_PROPERTY_NAME_SUFFIX, propertyValueMap);
        if (geoCircle.getDescription() != null) {
            jsonBuilder.field(property.getName(), geoCircle.getDescription());
        }
    }

    protected void convertGeoCircle(Map<String, Object> propertiesMap, Property property, GeoCircle geoCircle) throws IOException {
        Map<String, Object> propertyValueMap = new HashMap<>();
        propertyValueMap.put("type", "circle");
        List<Double> coordinates = new ArrayList<>();
        coordinates.add(geoCircle.getLongitude());
        coordinates.add(geoCircle.getLatitude());
        propertyValueMap.put("coordinates", coordinates);
        propertyValueMap.put("radius", geoCircle.getRadius() + "km");
        addPropertyValueToPropertiesMap(propertiesMap, property.getName() + GEO_PROPERTY_NAME_SUFFIX, propertyValueMap);
        if (geoCircle.getDescription() != null) {
            addPropertyValueToPropertiesMap(propertiesMap, property.getName(), geoCircle.getDescription());
        }
    }

    public IndexSelectionStrategy getIndexSelectionStrategy() {
        return indexSelectionStrategy;
    }

    public boolean isAuthorizationFilterEnabled() {
        return getConfig().isAuthorizationFilterEnabled();
    }
}

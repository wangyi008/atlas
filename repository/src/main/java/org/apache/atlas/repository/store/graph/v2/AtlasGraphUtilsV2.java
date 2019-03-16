/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.atlas.repository.store.graph.v2;


import org.apache.atlas.ApplicationProperties;
import org.apache.atlas.AtlasErrorCode;
import org.apache.atlas.GraphTransactionInterceptor;
import org.apache.atlas.RequestContext;
import org.apache.atlas.SortOrder;
import org.apache.atlas.annotation.GraphTransaction;
import org.apache.atlas.discovery.SearchProcessor;
import org.apache.atlas.exception.AtlasBaseException;
import org.apache.atlas.model.TypeCategory;
import org.apache.atlas.model.instance.AtlasEntity;
import org.apache.atlas.model.patches.AtlasPatch;
import org.apache.atlas.model.patches.AtlasPatch.AtlasPatches;
import org.apache.atlas.model.patches.AtlasPatch.PatchStatus;
import org.apache.atlas.model.typedef.AtlasBaseTypeDef;
import org.apache.atlas.repository.Constants;
import org.apache.atlas.repository.graph.AtlasGraphProvider;
import org.apache.atlas.repository.graph.GraphHelper;
import org.apache.atlas.repository.graphdb.AtlasEdge;
import org.apache.atlas.repository.graphdb.AtlasElement;
import org.apache.atlas.repository.graphdb.AtlasGraphQuery;
import org.apache.atlas.repository.graphdb.AtlasIndexQuery;
import org.apache.atlas.repository.graphdb.AtlasIndexQuery.Result;
import org.apache.atlas.repository.graphdb.AtlasVertex;
import org.apache.atlas.type.AtlasEntityType;
import org.apache.atlas.type.AtlasStructType;
import org.apache.atlas.type.AtlasStructType.AtlasAttribute;
import org.apache.atlas.type.AtlasType;
import org.apache.atlas.utils.AtlasPerfMetrics.MetricRecorder;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.atlas.model.patches.AtlasPatch.PatchStatus.UNKNOWN;
import static org.apache.atlas.repository.Constants.CREATED_BY_KEY;
import static org.apache.atlas.repository.Constants.INDEX_SEARCH_VERTEX_PREFIX_DEFAULT;
import static org.apache.atlas.repository.Constants.INDEX_SEARCH_VERTEX_PREFIX_PROPERTY;
import static org.apache.atlas.repository.Constants.MODIFICATION_TIMESTAMP_PROPERTY_KEY;
import static org.apache.atlas.repository.Constants.MODIFIED_BY_KEY;
import static org.apache.atlas.repository.Constants.PATCH_ACTION_PROPERTY_KEY;
import static org.apache.atlas.repository.Constants.PATCH_ID_PROPERTY_KEY;
import static org.apache.atlas.repository.Constants.PATCH_DESCRIPTION_PROPERTY_KEY;
import static org.apache.atlas.repository.Constants.PATCH_STATE_PROPERTY_KEY;
import static org.apache.atlas.repository.Constants.PATCH_TYPE_PROPERTY_KEY;
import static org.apache.atlas.repository.Constants.TIMESTAMP_PROPERTY_KEY;
import static org.apache.atlas.repository.Constants.VERTEX_INDEX;
import static org.apache.atlas.repository.graphdb.AtlasGraphQuery.SortOrder.*;

/**
 * Utility methods for Graph.
 */
public class AtlasGraphUtilsV2 {
    private static final Logger LOG = LoggerFactory.getLogger(AtlasGraphUtilsV2.class);

    public static final String PROPERTY_PREFIX             = Constants.INTERNAL_PROPERTY_KEY_PREFIX + "type.";
    public static final String SUPERTYPE_EDGE_LABEL        = PROPERTY_PREFIX + ".supertype";
    public static final String ENTITYTYPE_EDGE_LABEL       = PROPERTY_PREFIX + ".entitytype";
    public static final String RELATIONSHIPTYPE_EDGE_LABEL = PROPERTY_PREFIX + ".relationshipType";
    public static final String VERTEX_TYPE                 = "typeSystem";

    private static boolean USE_INDEX_QUERY_TO_FIND_ENTITY_BY_UNIQUE_ATTRIBUTES = false;
    private static boolean USE_UNIQUE_INDEX_PROPERTY_TO_FIND_ENTITY            = false;
    private static String  INDEX_SEARCH_PREFIX;

    static {
        try {
            Configuration conf = ApplicationProperties.get();

            USE_INDEX_QUERY_TO_FIND_ENTITY_BY_UNIQUE_ATTRIBUTES = conf.getBoolean("atlas.use.index.query.to.find.entity.by.unique.attributes", USE_INDEX_QUERY_TO_FIND_ENTITY_BY_UNIQUE_ATTRIBUTES);
            USE_UNIQUE_INDEX_PROPERTY_TO_FIND_ENTITY            = conf.getBoolean("atlas.unique.index.property.to.find.entity", USE_UNIQUE_INDEX_PROPERTY_TO_FIND_ENTITY);
            INDEX_SEARCH_PREFIX                                 = conf.getString(INDEX_SEARCH_VERTEX_PREFIX_PROPERTY, INDEX_SEARCH_VERTEX_PREFIX_DEFAULT);
        } catch (Exception excp) {
            LOG.error("Error reading configuration", excp);
        } finally {
            LOG.info("atlas.use.index.query.to.find.entity.by.unique.attributes=" + USE_INDEX_QUERY_TO_FIND_ENTITY_BY_UNIQUE_ATTRIBUTES);
        }
    }

    public static String getTypeDefPropertyKey(AtlasBaseTypeDef typeDef) {
        return getTypeDefPropertyKey(typeDef.getName());
    }

    public static String getTypeDefPropertyKey(AtlasBaseTypeDef typeDef, String child) {
        return getTypeDefPropertyKey(typeDef.getName(), child);
    }

    public static String getTypeDefPropertyKey(String typeName) {
        return PROPERTY_PREFIX + typeName;
    }

    public static String getTypeDefPropertyKey(String typeName, String child) {
        return PROPERTY_PREFIX + typeName + "." + child;
    }

    public static String getIdFromVertex(AtlasVertex vertex) {
        return vertex.getProperty(Constants.GUID_PROPERTY_KEY, String.class);
    }

    public static String getIdFromEdge(AtlasEdge edge) {
        return edge.getProperty(Constants.GUID_PROPERTY_KEY, String.class);
    }

    public static String getTypeName(AtlasElement element) {
        return element.getProperty(Constants.ENTITY_TYPE_PROPERTY_KEY, String.class);
    }

    public static String getEdgeLabel(String fromNode, String toNode) {
        return PROPERTY_PREFIX + "edge." + fromNode + "." + toNode;
    }

    public static String getEdgeLabel(String property) {
        return GraphHelper.EDGE_LABEL_PREFIX + property;
    }

    public static String getQualifiedAttributePropertyKey(AtlasStructType fromType, String attributeName) throws AtlasBaseException {
        switch (fromType.getTypeCategory()) {
         case ENTITY:
         case STRUCT:
         case CLASSIFICATION:
             return fromType.getQualifiedAttributePropertyKey(attributeName);
        default:
            throw new AtlasBaseException(AtlasErrorCode.UNKNOWN_TYPE, fromType.getTypeCategory().name());
        }
    }

    public static boolean isEntityVertex(AtlasVertex vertex) {
        return StringUtils.isNotEmpty(getIdFromVertex(vertex)) && StringUtils.isNotEmpty(getTypeName(vertex));
    }

    public static boolean isReference(AtlasType type) {
        return isReference(type.getTypeCategory());
    }

    public static boolean isReference(TypeCategory typeCategory) {
        return typeCategory == TypeCategory.STRUCT ||
               typeCategory == TypeCategory.ENTITY ||
               typeCategory == TypeCategory.OBJECT_ID_TYPE;
    }

    public static String encodePropertyKey(String key) {
        return AtlasAttribute.encodePropertyKey(key);
    }

    public static String decodePropertyKey(String key) {
        return AtlasAttribute.decodePropertyKey(key);
    }

    /**
     * Adds an additional value to a multi-property.
     *
     * @param propertyName
     * @param value
     */
    public static AtlasVertex addProperty(AtlasVertex vertex, String propertyName, Object value) {
        return addProperty(vertex, propertyName, value, false);
    }

    public static AtlasVertex addEncodedProperty(AtlasVertex vertex, String propertyName, Object value) {
        return addProperty(vertex, propertyName, value, true);
    }

    public static AtlasVertex addProperty(AtlasVertex vertex, String propertyName, Object value, boolean isEncoded) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> addProperty({}, {}, {})", toString(vertex), propertyName, value);
        }

        if (!isEncoded) {
            propertyName = encodePropertyKey(propertyName);
        }

        vertex.addProperty(propertyName, value);

        return vertex;
    }

    public static <T extends AtlasElement> void setProperty(T element, String propertyName, Object value) {
        setProperty(element, propertyName, value, false);
    }

    public static <T extends AtlasElement> void setEncodedProperty(T element, String propertyName, Object value) {
        setProperty(element, propertyName, value, true);
    }

    public static <T extends AtlasElement> void setProperty(T element, String propertyName, Object value, boolean isEncoded) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> setProperty({}, {}, {})", toString(element), propertyName, value);
        }

        if (!isEncoded) {
            propertyName = encodePropertyKey(propertyName);
        }

        Object existingValue = element.getProperty(propertyName, Object.class);

        if (value == null || (value instanceof Collection && ((Collection)value).isEmpty())) {
            if (existingValue != null) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Removing property {} from {}", propertyName, toString(element));
                }

                element.removeProperty(propertyName);
            }
        } else {
            if (!value.equals(existingValue)) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Setting property {} in {}", propertyName, toString(element));
                }

                if ( value instanceof Date) {
                    Long encodedValue = ((Date) value).getTime();
                    element.setProperty(propertyName, encodedValue);
                } else {
                    element.setProperty(propertyName, value);
                }
            }
        }
    }

    public static <T extends AtlasElement, O> O getProperty(T element, String propertyName, Class<O> returnType) {
        return getProperty(element, propertyName, returnType, false);
    }

    public static <T extends AtlasElement, O> O getEncodedProperty(T element, String propertyName, Class<O> returnType) {
        return getProperty(element, propertyName, returnType, true);
    }

    public static <T extends AtlasElement, O> O getProperty(T element, String propertyName, Class<O> returnType, boolean isEncoded) {
        if (!isEncoded) {
            propertyName = encodePropertyKey(propertyName);
        }

        Object property = element.getProperty(propertyName, returnType);

        if (LOG.isDebugEnabled()) {
            LOG.debug("getProperty({}, {}) ==> {}", toString(element), propertyName, returnType.cast(property));
        }

        return returnType.cast(property);
    }

    public static AtlasVertex getVertexByUniqueAttributes(AtlasEntityType entityType, Map<String, Object> attrValues) throws AtlasBaseException {
        AtlasVertex vertex = findByUniqueAttributes(entityType, attrValues);

        if (vertex == null) {
            throw new AtlasBaseException(AtlasErrorCode.INSTANCE_BY_UNIQUE_ATTRIBUTE_NOT_FOUND, entityType.getTypeName(),
                                         attrValues.toString());
        }

        return vertex;
    }

    public static String getGuidByUniqueAttributes(AtlasEntityType entityType, Map<String, Object> attrValues) throws AtlasBaseException {
        AtlasVertex vertexByUniqueAttributes = getVertexByUniqueAttributes(entityType, attrValues);
        return getIdFromVertex(vertexByUniqueAttributes);
    }

    public static AtlasVertex findByUniqueAttributes(AtlasEntityType entityType, Map<String, Object> attrValues) {
        MetricRecorder metric = RequestContext.get().startMetricRecord("findByUniqueAttributes");

        AtlasVertex vertex = null;

        final Map<String, AtlasAttribute> uniqueAttributes = entityType.getUniqAttributes();

        if (MapUtils.isNotEmpty(uniqueAttributes) && MapUtils.isNotEmpty(attrValues)) {
            for (AtlasAttribute attribute : uniqueAttributes.values()) {
                Object attrValue = attrValues.get(attribute.getName());

                if (attrValue == null) {
                    continue;
                }

                if (canUseIndexQuery(entityType, attribute.getName())) {
                    vertex = AtlasGraphUtilsV2.getAtlasVertexFromIndexQuery(entityType, attribute, attrValue);
                } else {
                    if (USE_UNIQUE_INDEX_PROPERTY_TO_FIND_ENTITY && attribute.getVertexUniquePropertyName() != null) {
                        vertex = AtlasGraphUtilsV2.findByTypeAndUniquePropertyName(entityType.getTypeName(), attribute.getVertexUniquePropertyName(), attrValue);

                        // if no instance of given typeName is found, try to find an instance of type's sub-type
                        if (vertex == null && !entityType.getAllSubTypes().isEmpty()) {
                            vertex = AtlasGraphUtilsV2.findBySuperTypeAndUniquePropertyName(entityType.getTypeName(), attribute.getVertexUniquePropertyName(), attrValue);
                        }
                    } else {
                        vertex = AtlasGraphUtilsV2.findByTypeAndPropertyName(entityType.getTypeName(), attribute.getVertexPropertyName(), attrValue);

                        // if no instance of given typeName is found, try to find an instance of type's sub-type
                        if (vertex == null && !entityType.getAllSubTypes().isEmpty()) {
                            vertex = AtlasGraphUtilsV2.findBySuperTypeAndPropertyName(entityType.getTypeName(), attribute.getVertexPropertyName(), attrValue);
                        }
                    }

                }

                if (vertex != null) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("findByUniqueAttributes(type={}, attrName={}, attrValue={}: found vertex {}",
                                  entityType.getTypeName(), attribute.getName(), attrValue, vertex);
                    }

                    break;
                }
            }
        }

        RequestContext.get().endMetricRecord(metric);

        return vertex;
    }

    public static AtlasVertex findByPatchId(String patchId) {
        AtlasVertex ret        = null;
        String      indexQuery = getIndexSearchPrefix() + "\"" + PATCH_ID_PROPERTY_KEY + "\" : ("+ patchId +")";
        Iterator<Result<Object, Object>> results = AtlasGraphProvider.getGraphInstance().indexQuery(VERTEX_INDEX, indexQuery).vertices();

        while (results != null && results.hasNext()) {
            ret = results.next().getVertex();

            if (ret != null) {
                break;
            }
        }

        return ret;
    }

    public static AtlasVertex findByGuid(String guid) {
        AtlasVertex ret = GraphTransactionInterceptor.getVertexFromCache(guid);

        if (ret == null) {
            AtlasGraphQuery query = AtlasGraphProvider.getGraphInstance().query()
                    .has(Constants.GUID_PROPERTY_KEY, guid);

            Iterator<AtlasVertex> results = query.vertices().iterator();

            ret = results.hasNext() ? results.next() : null;

            if (ret != null) {
                GraphTransactionInterceptor.addToVertexCache(guid, ret);
            }
        }

        return ret;
    }

    public static String getTypeNameFromGuid(String guid) {
        String ret = null;

        if (StringUtils.isNotEmpty(guid)) {
            AtlasVertex vertex = AtlasGraphUtilsV2.findByGuid(guid);

            ret = (vertex != null) ? AtlasGraphUtilsV2.getTypeName(vertex) : null;
        }

        return ret;
    }

    public static boolean typeHasInstanceVertex(String typeName) throws AtlasBaseException {
        AtlasGraphQuery query = AtlasGraphProvider.getGraphInstance()
                .query()
                .has(Constants.TYPE_NAME_PROPERTY_KEY, AtlasGraphQuery.ComparisionOperator.EQUAL, typeName);

        Iterator<AtlasVertex> results = query.vertices().iterator();

        boolean hasInstanceVertex = results != null && results.hasNext();

        if (LOG.isDebugEnabled()) {
            LOG.debug("typeName {} has instance vertex {}", typeName, hasInstanceVertex);
        }

        return hasInstanceVertex;
    }

    public static AtlasVertex findByTypeAndUniquePropertyName(String typeName, String propertyName, Object attrVal) {
        MetricRecorder metric = RequestContext.get().startMetricRecord("findByTypeAndUniquePropertyName");

        AtlasGraphQuery query = AtlasGraphProvider.getGraphInstance().query()
                                                    .has(Constants.ENTITY_TYPE_PROPERTY_KEY, typeName)
                                                    .has(propertyName, attrVal);

        Iterator<AtlasVertex> results = query.vertices().iterator();

        AtlasVertex vertex = results.hasNext() ? results.next() : null;

        RequestContext.get().endMetricRecord(metric);

        return vertex;
    }

    public static AtlasVertex findBySuperTypeAndUniquePropertyName(String typeName, String propertyName, Object attrVal) {
        MetricRecorder metric = RequestContext.get().startMetricRecord("findBySuperTypeAndUniquePropertyName");

        AtlasGraphQuery query = AtlasGraphProvider.getGraphInstance().query()
                                                    .has(Constants.SUPER_TYPES_PROPERTY_KEY, typeName)
                                                    .has(propertyName, attrVal);

        Iterator<AtlasVertex> results = query.vertices().iterator();

        AtlasVertex vertex = results.hasNext() ? results.next() : null;

        RequestContext.get().endMetricRecord(metric);

        return vertex;
    }

    public static AtlasVertex findByTypeAndPropertyName(String typeName, String propertyName, Object attrVal) {
        MetricRecorder metric = RequestContext.get().startMetricRecord("findByTypeAndPropertyName");

        AtlasGraphQuery query = AtlasGraphProvider.getGraphInstance().query()
                                                    .has(Constants.ENTITY_TYPE_PROPERTY_KEY, typeName)
                                                    .has(propertyName, attrVal)
                                                    .has(Constants.STATE_PROPERTY_KEY, AtlasEntity.Status.ACTIVE.name());

        Iterator<AtlasVertex> results = query.vertices().iterator();

        AtlasVertex vertex = results.hasNext() ? results.next() : null;

        RequestContext.get().endMetricRecord(metric);

        return vertex;
    }

    public static AtlasVertex findBySuperTypeAndPropertyName(String typeName, String propertyName, Object attrVal) {
        MetricRecorder metric = RequestContext.get().startMetricRecord("findBySuperTypeAndPropertyName");

        AtlasGraphQuery query = AtlasGraphProvider.getGraphInstance().query()
                                                    .has(Constants.SUPER_TYPES_PROPERTY_KEY, typeName)
                                                    .has(propertyName, attrVal)
                                                    .has(Constants.STATE_PROPERTY_KEY, AtlasEntity.Status.ACTIVE.name());

        Iterator<AtlasVertex> results = query.vertices().iterator();

        AtlasVertex vertex = results.hasNext() ? results.next() : null;

        RequestContext.get().endMetricRecord(metric);

        return vertex;
    }

    public static Map<String, PatchStatus> initPatchesRegistry() {
        Map<String, PatchStatus>  ret     = new HashMap<>();
        AtlasPatches              patches = getPatches();

        for (AtlasPatch patch : patches.getPatches()) {
            String      patchId     = patch.getId();
            PatchStatus patchStatus = patch.getStatus();

            if (patchId != null && patchStatus != null) {
                ret.put(patchId, patchStatus);
            }
        }

        return ret;
    }

    public static AtlasPatches getPatches() {
        List<AtlasPatch>                 patches    = new ArrayList<>();
        String                           indexQuery = getIndexSearchPrefix() + "\"" + PATCH_ID_PROPERTY_KEY + "\" : (*)";
        Iterator<Result<Object, Object>> results    = AtlasGraphProvider.getGraphInstance().indexQuery(VERTEX_INDEX, indexQuery).vertices();

        while (results != null && results.hasNext()) {
            AtlasVertex patchVertex = results.next().getVertex();
            AtlasPatch  patch       = toAtlasPatch(patchVertex);

            patches.add(patch);
        }

        // Sort the patches based on patch id
        if (CollectionUtils.isNotEmpty(patches)) {
            Collections.sort(patches, (p1, p2) -> p1.getId().compareTo(p2.getId()));
        }

        return new AtlasPatches(patches);
    }

    private static AtlasPatch toAtlasPatch(AtlasVertex vertex) {
        AtlasPatch ret = new AtlasPatch();

        ret.setId(getEncodedProperty(vertex, PATCH_ID_PROPERTY_KEY, String.class));
        ret.setDescription(getEncodedProperty(vertex, PATCH_DESCRIPTION_PROPERTY_KEY, String.class));
        ret.setType(getEncodedProperty(vertex, PATCH_TYPE_PROPERTY_KEY, String.class));
        ret.setAction(getEncodedProperty(vertex, PATCH_ACTION_PROPERTY_KEY, String.class));
        ret.setCreatedBy(getEncodedProperty(vertex, CREATED_BY_KEY, String.class));
        ret.setUpdatedBy(getEncodedProperty(vertex, MODIFIED_BY_KEY, String.class));
        ret.setCreatedTime(getEncodedProperty(vertex, TIMESTAMP_PROPERTY_KEY, Long.class));
        ret.setUpdatedTime(getEncodedProperty(vertex, MODIFICATION_TIMESTAMP_PROPERTY_KEY, Long.class));
        ret.setStatus(getPatchStatus(vertex));

        return ret;
    }

    private static PatchStatus getPatchStatus(AtlasVertex vertex) {
        String patchStatus = AtlasGraphUtilsV2.getEncodedProperty(vertex, PATCH_STATE_PROPERTY_KEY, String.class);

        return patchStatus != null ? PatchStatus.valueOf(patchStatus) : UNKNOWN;
    }

    public static List<String> findEntityGUIDsByType(String typename, SortOrder sortOrder) {
        AtlasGraphQuery query = AtlasGraphProvider.getGraphInstance().query()
                                                  .has(Constants.ENTITY_TYPE_PROPERTY_KEY, typename);
        if (sortOrder != null) {
            AtlasGraphQuery.SortOrder qrySortOrder = sortOrder == SortOrder.ASCENDING ? ASC : DESC;
            query.orderBy(Constants.QUALIFIED_NAME, qrySortOrder);
        }

        Iterator<AtlasVertex> results = query.vertices().iterator();
        ArrayList<String> ret = new ArrayList<>();

        if (!results.hasNext()) {
            return Collections.emptyList();
        }

        while (results.hasNext()) {
            ret.add(getIdFromVertex(results.next()));
        }

        return ret;
    }

    public static List<String> findEntityGUIDsByType(String typename) {
        return findEntityGUIDsByType(typename, null);
    }

    public static boolean relationshipTypeHasInstanceEdges(String typeName) throws AtlasBaseException {
        AtlasGraphQuery query = AtlasGraphProvider.getGraphInstance()
                .query()
                .has(Constants.TYPE_NAME_PROPERTY_KEY, AtlasGraphQuery.ComparisionOperator.EQUAL, typeName);

        Iterator<AtlasEdge> results = query.edges().iterator();

        boolean hasInstanceEdges = results != null && results.hasNext();

        if (LOG.isDebugEnabled()) {
            LOG.debug("relationshipType {} has instance edges {}", typeName, hasInstanceEdges);
        }

        return hasInstanceEdges;
    }

    private static String toString(AtlasElement element) {
        if (element instanceof AtlasVertex) {
            return toString((AtlasVertex) element);
        } else if (element instanceof AtlasEdge) {
            return toString((AtlasEdge)element);
        }

        return element.toString();
    }

    public static String toString(AtlasVertex vertex) {
        if(vertex == null) {
            return "vertex[null]";
        } else {
            if (LOG.isDebugEnabled()) {
                return getVertexDetails(vertex);
            } else {
                return String.format("vertex[id=%s]", vertex.getId().toString());
            }
        }
    }


    public static String toString(AtlasEdge edge) {
        if(edge == null) {
            return "edge[null]";
        } else {
            if (LOG.isDebugEnabled()) {
                return getEdgeDetails(edge);
            } else {
                return String.format("edge[id=%s]", edge.getId().toString());
            }
        }
    }

    public static String getVertexDetails(AtlasVertex vertex) {
        return String.format("vertex[id=%s type=%s guid=%s]",
                vertex.getId().toString(), getTypeName(vertex), getIdFromVertex(vertex));
    }

    public static String getEdgeDetails(AtlasEdge edge) {
        return String.format("edge[id=%s label=%s from %s -> to %s]", edge.getId(), edge.getLabel(),
                toString(edge.getOutVertex()), toString(edge.getInVertex()));
    }

    public static AtlasEntity.Status getState(AtlasElement element) {
        String state = getStateAsString(element);
        return state == null ? null : AtlasEntity.Status.valueOf(state);
    }

    public static String getStateAsString(AtlasElement element) {
        return element.getProperty(Constants.STATE_PROPERTY_KEY, String.class);
    }

    private static boolean canUseIndexQuery(AtlasEntityType entityType, String attributeName) {
        boolean ret = false;

        if (USE_INDEX_QUERY_TO_FIND_ENTITY_BY_UNIQUE_ATTRIBUTES) {
            final String typeAndSubTypesQryStr = entityType.getTypeAndAllSubTypesQryStr();

            ret = typeAndSubTypesQryStr.length() <= SearchProcessor.MAX_QUERY_STR_LENGTH_TYPES;

            if (ret) {
                Set<String> indexSet = AtlasGraphProvider.getGraphInstance().getVertexIndexKeys();
                try {
                    ret = indexSet.contains(entityType.getQualifiedAttributeName(attributeName));
                }
                catch (AtlasBaseException ex) {
                    ret = false;
                }
            }
        }

        return ret;
    }

    private static AtlasVertex getAtlasVertexFromIndexQuery(AtlasEntityType entityType, AtlasAttribute attribute, Object attrVal) {
        String          propertyName = attribute.getVertexPropertyName();
        AtlasIndexQuery query        = getIndexQuery(entityType, propertyName, attrVal.toString());

        for (Iterator<Result> iter = query.vertices(); iter.hasNext(); ) {
            Result      result = iter.next();
            AtlasVertex vertex = result.getVertex();

            // skip non-entity vertices, if any got returned
            if (vertex == null || !vertex.getPropertyKeys().contains(Constants.GUID_PROPERTY_KEY)) {
                continue;
            }

            // verify the typeName
            String typeNameInVertex = getTypeName(vertex);

            if (!entityType.getTypeAndAllSubTypes().contains(typeNameInVertex)) {
                LOG.warn("incorrect vertex type from index-query: expected='{}'; found='{}'", entityType.getTypeName(), typeNameInVertex);

                continue;
            }

            if (attrVal.getClass() == String.class) {
                String s         = (String) attrVal;
                String vertexVal = vertex.getProperty(propertyName, String.class);

                if (!s.equalsIgnoreCase(vertexVal)) {
                    LOG.warn("incorrect match from index-query for property {}: expected='{}'; found='{}'", propertyName, s, vertexVal);

                    continue;
                }
            }

            return vertex;
        }

        return null;
    }

    private static AtlasIndexQuery getIndexQuery(AtlasEntityType entityType, String propertyName, String value) {
        StringBuilder sb = new StringBuilder();

        sb.append(INDEX_SEARCH_PREFIX + "\"").append(Constants.TYPE_NAME_PROPERTY_KEY).append("\":").append(entityType.getTypeAndAllSubTypesQryStr())
                .append(" AND ")
                .append(INDEX_SEARCH_PREFIX + "\"").append(propertyName).append("\":").append(AtlasAttribute.escapeIndexQueryValue(value))
                .append(" AND ")
                .append(INDEX_SEARCH_PREFIX + "\"").append(Constants.STATE_PROPERTY_KEY).append("\":ACTIVE");

        return AtlasGraphProvider.getGraphInstance().indexQuery(Constants.VERTEX_INDEX, sb.toString());
    }

    public static String getIndexSearchPrefix() {
        return INDEX_SEARCH_PREFIX;
    }
}

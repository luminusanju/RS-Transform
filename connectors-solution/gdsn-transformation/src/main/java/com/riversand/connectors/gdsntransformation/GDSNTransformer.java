/*
   FILE: GDSNTransformer.java

   PURPOSE: Transform record fields based on the mapping.

   COPYRIGHT: Copyright (c) 2017 Riversand Technologies, Inc. All rights reserved.

   HISTORY: 19 June 2020  Sravan Oddi  Created
*/
package com.riversand.connectors.gdsntransformation;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Pattern;

import org.apache.commons.collections.CollectionUtils;

import com.google.common.base.Strings;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.riversand.connectors.extension.helpers.TransformerHelper;
import com.riversand.dataplatform.ps.diagnosticmanager.ProfilerManager;
import com.riversand.dataplatform.ps.diagnosticmanager.ProfilerManagerLogger;
import com.riversand.rsconnect.common.config.AppConfig;
import com.riversand.rsconnect.common.config.FieldMapping;
import com.riversand.rsconnect.common.config.RSConnectContext;
import com.riversand.rsconnect.common.config.TransformConfig;
import com.riversand.rsconnect.common.helpers.ConnectIllegalArgumentException;
import com.riversand.rsconnect.common.helpers.ConnectRuntimeException;
import com.riversand.rsconnect.common.rsconnect.driver.Constants;
import com.riversand.rsconnect.common.transform.FieldMapMacro;
import com.riversand.rsconnect.interfaces.clients.IServiceClient;
import com.riversand.rsconnect.interfaces.models.ContextMapping;
import com.riversand.rsconnect.interfaces.models.IRecord;
import com.riversand.rsconnect.interfaces.models.JsonRecord;
import com.riversand.rsconnect.interfaces.models.RdpStatusDetail;
import com.riversand.rsconnect.interfaces.transformer.IRecordTransformer;

import static com.riversand.connectors.extension.Constants.LogCodes.RSC_7273;
import static com.riversand.connectors.extension.Constants.LogCodes.RSC_7820;
import static java.util.stream.Collectors.toList;

public class GDSNTransformer implements IRecordTransformer {

   private ProfilerManagerLogger pmLogger = ProfilerManager.getLogger(GDSNTransformer.class);
   private RSConnectContext connectContext;
   private TransformConfig config;
   private boolean manageSelfDataInContext;
   private String contextDelimiter;

   public GDSNTransformer(RSConnectContext connectContext, IServiceClient client) {
      this(connectContext);
   }

   /**
    * Class to transform the data from source format to destination format using with fieldMappings
    *
    * @param connectContext - Contains execution context and profile configuration
    */
   public GDSNTransformer(RSConnectContext connectContext) {
      this.connectContext = connectContext;
      this.config = connectContext.getConnectProfile().getTransform();
      if (CollectionUtils.isEmpty(this.config.getFieldMap())) {
         throw new ConnectIllegalArgumentException(RSC_7820, "fieldMaps are empty");
      }
      this.manageSelfDataInContext = this.config.getSettings().isManageSelfDataInContext();
      this.contextDelimiter = AppConfig.getInstance().getContextDelimiter(connectContext.getExecutionContext().getTenantId());
   }

   /**
    * Transform record data into destination format.
    *
    * @param record   input IRecord: Sample supports only JsonRecord
    * @param messages To Log messages when transform not happened with the field.
    * @return IRecord of output format
    */
   @Override
   public IRecord transform(IRecord record, RdpStatusDetail messages) {
      if (!(record instanceof JsonRecord)) {
         throw new ConnectRuntimeException(RSC_7820, "Record doesn't support for transformation");
      }

      String entityType = record.getValue(Constants.TYPE);
      if (Strings.isNullOrEmpty(entityType)) {
         throw new ConnectRuntimeException(RSC_7820, "Failed to get entityType from Object" + ((JsonRecord) record).getJsonObject());
      }

      IRecord outboundRecord = transformRecord(record, entityType);
      transformRelationshipRecords(entityType, record, outboundRecord);
      return outboundRecord;
   }

   /**
    * Method to transform record to JsonRecord
    *
    * @param inboundRecord - Input Record
    * @param entityType    - entity Type
    */
   private IRecord transformRecord(IRecord inboundRecord, String entityType) {
      //Copy to new List
      List<FieldMapping> fieldMap = config.getFieldMap().stream().map(FieldMapping::new).collect(toList());
      IRecord outboundRecord = new JsonRecord();
      JsonObject inboundObject = ((JsonRecord) inboundRecord).getJsonObject();
      List<ContextMapping> contexts = TransformerHelper.getContextMappings(inboundObject, connectContext.getConnectProfile().getCollect().getFormat().getType(), connectContext.getConnectProfile().getPublish().getFormat().getType(), contextDelimiter);
      Boolean isContextRecord = false;
      if (contexts != null) {
         for (ContextMapping contextMapping : contexts) {
            contextMapping.Initialize(contextDelimiter, connectContext.getConnectProfile().getCollect().getFormat().getType(), connectContext.getConnectProfile().getPublish().getFormat().getType());
            contextMapping.setISJson(true);
            String contextKey = contextMapping.getKeyFromSourceRecord(inboundRecord);
            // The context mapping may not be defined for this record.
            // Perhaps this record defines data for some other context.
            if (Strings.isNullOrEmpty(contextKey)) {
               continue;
            }

            // The context is defined, set all fields defined in this context.
            isContextRecord = true;
            getAndSetRecordValues(inboundRecord, outboundRecord, entityType, contextKey, fieldMap);
         }
      }
      // No context defined or some fields were not defined in any context, set field values in self context.
      if ((!isContextRecord || this.manageSelfDataInContext) && !fieldMap.isEmpty()) {
         getAndSetRecordValues(inboundRecord, outboundRecord, entityType, null, fieldMap);
      }
      return outboundRecord;
   }

   private void transformRelationshipRecords(String entityType, IRecord inboundRecord, IRecord outboundRecord) {
      try {
         JsonElement relationships = JsonRecord.findObject(((JsonRecord) inboundRecord).getJsonObject(), String.format("%s.%s", Constants.DATA, Constants.OPERATION_SEARCH_RELATIONSHIPS));
         if (relationships != null && relationships.isJsonObject()) {
            List<FieldMapping> mappings = config.getRelationships().getFieldMap();
            if (CollectionUtils.isNotEmpty(mappings)) {
               transformRelationships(entityType, outboundRecord, relationships, mappings);
            }
         }
      } catch (Exception ex) {
         pmLogger.error(Constants.RSCONNECT_SERVICE, RSC_7273, ex.getMessage());
      }
   }

   private void transformRelationships(String entityType, IRecord outboundRecord, JsonElement relationships, List<FieldMapping> mappings) {
      for (Map.Entry<String, JsonElement> entry : relationships.getAsJsonObject().entrySet()) {
         if (entry.getValue() != null && entry.getValue().isJsonArray()) {
            JsonArray jsonArray = new JsonArray();
            for (JsonElement element : entry.getValue().getAsJsonArray()) {
               if (element.isJsonObject()) {
                  JsonRecord outboundRelRecord = getAndSetRelationshipAttributeValues(entityType, new JsonRecord(element.getAsJsonObject(), null), mappings);
                  setRelToAttributeValues(element, outboundRelRecord);
                  jsonArray.add(outboundRelRecord.getJsonObject());
               }
            }
            //read root path of relationship from profile config.
            String path = String.format("nextLowerLevelTradeItemInformation.%s", entry.getKey());
            JsonRecord.setValue(((JsonRecord) outboundRecord).getJsonObject(), path, jsonArray);
         }
      }
   }

   private void setRelToAttributeValues(JsonElement element, JsonRecord outboundRelRecord) {
      JsonObject relToAttributesElement = JsonRecord.findObject(element, "relTo.data." + Constants.ATTRIBUTES);
      if (relToAttributesElement != null) {
         for (Map.Entry<String, JsonElement> attribute : relToAttributesElement.entrySet()) {
            String value = JsonRecord.getValue(attribute.getValue(), Constants.VALUES_ARRAY);
            if (!Strings.isNullOrEmpty(value)) {
               JsonRecord.setValue(outboundRelRecord.getJsonObject(), attribute.getKey(), value);
            }
         }
      }
   }

   private JsonRecord getAndSetRelationshipAttributeValues(String entityType, JsonRecord record, List<FieldMapping> mappings) {
      IRecord outboundRecord = new JsonRecord();
      Iterator<FieldMapping> iterator = mappings.iterator();
      while (iterator.hasNext()) {
         FieldMapping fieldMapping = iterator.next();
         if (fieldMapping.getEntityType().equals(entityType)) {
            String value = getRelationshipValue(record, fieldMapping, Constants.Mappings.VALUE);
            if (!Strings.isNullOrEmpty(value)) {
               setValue(outboundRecord, value, null, fieldMapping, null, record);
            }
         }
      }
      return ((JsonRecord) outboundRecord);
   }

   private String getRelationshipValue(IRecord inbound, FieldMapping fieldMapping, String key) {
      String attributePath = "";
      String field = fieldMapping.getSource();
      if (Strings.isNullOrEmpty(field)) {
         throw new ConnectIllegalArgumentException(RSC_7820, "source cannot be empty. Method: getrelationshipValue");
      }
      if (FieldMapMacro.isAttribute(field)) {
         String attributeName = FieldMapMacro.getAttribute(field);
         attributePath = String.format("attributes.%s.values[0].%s", attributeName, key);
      } else if (FieldMapMacro.isRelationshipAttribute(field) || FieldMapMacro.isRelToAttribute(field)) {
         FieldMapMacro.RelAttrMacroValues macroValues = FieldMapMacro.getValuesFromRelationshipAttribute(null, field);
         attributePath = String.format("attributes.%s.values[0].%s", macroValues.attributeName, key);
      }
      if (Strings.isNullOrEmpty(attributePath)) {
         attributePath = field;
      }
      return inbound.getValue(attributePath);
   }

   /**
    * Set all fields defined in this entity and context.
    */
   private void getAndSetRecordValues(IRecord inboundRecord, IRecord outboundRecord, String entityType, String sourceContextKey, List<FieldMapping> fieldMap) {
      try {
         Iterator<FieldMapping> iterator = fieldMap.iterator();
         while (iterator.hasNext()) {
            FieldMapping fieldMapping = iterator.next();
            if (!fieldMapping.isEnabled() || fieldMapping.getEntityType() == null || !fieldMapping.getEntityType().equals(entityType)) {
               continue;
            }
            //Get destination context key for this field mapping.
            String destinationContextKey = fieldMapping.getDestinationContextKey(sourceContextKey);

            if (Strings.isNullOrEmpty(sourceContextKey) || !Strings.isNullOrEmpty(destinationContextKey)) {
               if (!Strings.isNullOrEmpty(destinationContextKey) && destinationContextKey.equalsIgnoreCase(Constants.Mapping.ATTRIBUTES_SELF)) {
                  destinationContextKey = null;
               }

               if (isFieldPathAttribute(fieldMapping, "nested")) {
                  //TODO: Nested attriute implementation
                  //setRecordValueForNestedAttributes(inboundRecord, outboundRecord, fieldMapping, sourceContextKey, destinationContextKey);
               } else {
                  int index = 0;
                  String value = getValue(inboundRecord, sourceContextKey, fieldMapping, index);
                  if (!Strings.isNullOrEmpty(value)) {
                     String uomValue = null;
                     if (fieldMapping.hasUOM()) {
                        uomValue = TransformerHelper.getValueFromUOMField(inboundRecord, sourceContextKey, config.getSettings().getCollectionSeparator(), fieldMapping, index, value);
                     }
                     setValue(outboundRecord, value, destinationContextKey, fieldMapping, uomValue, inboundRecord);
                  } else {
                     pmLogger.debug("", Constants.RSCONNECT_SERVICE, RSC_7273, fieldMapping.getSource());
                  }
               }
               if (fieldMapping.getContextKeys() != null) {
                  if (Strings.isNullOrEmpty(sourceContextKey) || Strings.isNullOrEmpty(destinationContextKey)) {
                     fieldMapping.getContextKeys().remove(Constants.Mapping.ATTRIBUTES_SELF);
                  } else {
                     fieldMapping.getContextKeys().remove(sourceContextKey);
                  }
               }
               if (fieldMapping.getContextKeys() == null || fieldMapping.getContextKeys().size() == 0) {
                  iterator.remove();
               }
            }
         }
      } catch (Exception ex) {
         pmLogger.error("", Constants.RSCONNECT_SERVICE, RSC_7273, ex);
      }
   }

   /**
    * Get the value of this field.
    */
   private String getValue(IRecord record, String sourceContextKey, FieldMapping fieldMapping, int index, Integer... parentIndices) {
      if (fieldMapping.isCollectionType() || fieldMapping.isLocalizable()) {
         StringJoiner joiner = new StringJoiner(config.getSettings().getCollectionSeparator());
         index = 0;
         while (true) {
            String value = getValueInContext(record, fieldMapping, sourceContextKey, index, parentIndices);
            // When null, we have reached the end of the array.
            if (Strings.isNullOrEmpty(value)) {
               break;
            }
            joiner.add(value);
            index++;
         }
         return joiner.toString();
      } else {
         return getValueInContext(record, fieldMapping, sourceContextKey, index, parentIndices);
      }
   }

   private boolean isFieldPathAttribute(FieldMapping fieldMapping, String fieldPath) {
      return fieldMapping.getType() != null && fieldMapping.getType().equalsIgnoreCase(fieldPath);
   }

   /**
    * Get value from record. If value not found, check in self context. To support flat hierarchy RSJSON format.
    */
   private String getValueInContext(IRecord record, FieldMapping fieldMapping, String sourceContextKey, int index, Integer... parentIndices) {
      String attributePath = TransformerHelper.getSourceFieldInContext(fieldMapping.getSource(), sourceContextKey, Constants.Mappings.VALUE, 0, parentIndices);
      index = getIndex(record, fieldMapping, index, attributePath);

      return TransformerHelper.getSourceFieldValue(record, fieldMapping, sourceContextKey, Constants.Mappings.VALUE, index, parentIndices);
   }

   private int getIndex(IRecord record, FieldMapping fieldMapping, int index, String attributePath) {
      attributePath = attributePath.replaceAll(Pattern.quote("[0].value"), "").replaceAll(Pattern.quote("[0].src"), "");
      JsonArray values = JsonRecord.findArray(((JsonRecord) record).getJsonObject(), attributePath);
      int matchCount = 0;
      if (values != null && values.size() > 0) {
         int loopIndex = 0;
         for (JsonElement valueElement : values) {
            ++matchCount;
            if (!fieldMapping.isCollectionType() && !fieldMapping.isLocalizable()) {
               index = loopIndex;
               break;
            } else {
               if (index == matchCount - 1) {
                  index = loopIndex;
                  break;
               }
            }
            ++loopIndex;
         }
         if (loopIndex == values.size()) {
            index = loopIndex;
         }
      }
      if (matchCount == 0) {
         index = 10000;
      }
      return index;
   }

   /**
    * Set the value of this field in specified context.
    */
   private void setValue(IRecord record, String value, String contextKey, FieldMapping fieldMapping, String uom, IRecord inboundRecord) {
      int index = 0;
      if (fieldMapping.isCollectionType() || fieldMapping.isLocalizable()) {
         String[] uoms = null;
         if (fieldMapping.hasUOM() && !Strings.isNullOrEmpty(uom)) {
            uoms = uom.split(Pattern.quote(config.getSettings().getCollectionSeparator()));
         }
         for (String subValue : value.split(Pattern.quote(config.getSettings().getCollectionSeparator()))) {
            String field = getDestinationField(fieldMapping.getDestination(), index, fieldMapping);
            if (!Strings.isNullOrEmpty(field)) {
               String uomValue = null;
               if (fieldMapping.hasUOM() && uoms != null && index < uoms.length) {
                  uomValue = uoms[index];
               }
               setFieldValue(inboundRecord, record, contextKey, fieldMapping, index, subValue, field, uomValue);
               index++;
            }
         }
      } else {
         String field = getDestinationField(fieldMapping.getDestination(), index, fieldMapping);
         if (!Strings.isNullOrEmpty(field)) {
            setFieldValue(inboundRecord, record, contextKey, fieldMapping, index, value, field, uom);
         }
      }
   }

   private void setFieldValue(IRecord inboundRecord, IRecord record, String contextKey, FieldMapping fieldMapping, int index, String subValue, String field, String uomValue) {
      if (fieldMapping.getType().equalsIgnoreCase("referenceTypeData")) {
         String[] fields = field.split("#@#");
         if (fields.length == 2) {
            field = getDestinationField(fields[0], index, fieldMapping);
            setValue(record, fieldMapping, subValue, field);
            subValue = TransformerHelper.getSourceFieldValue(inboundRecord, fieldMapping, contextKey, "properties.referenceDataIdentifier", index);
            if (subValue != null) {
               field = getDestinationField(fields[1], index, fieldMapping);
               setValue(record, fieldMapping, subValue, field);
            }
         }
      } else if (fieldMapping.isLocalizable()) {
         String locale = TransformerHelper.getSourceFieldValue(inboundRecord, fieldMapping, contextKey, "locale", index);
         setValue(record, fieldMapping, locale, "%s.languageCode", field);
         setValue(record, fieldMapping, subValue, "%s.__value__", field);
      } else if (fieldMapping.hasUOM()) {
         setValue(record, fieldMapping, uomValue, "%s.measurementUnitCode", field);
         setValue(record, fieldMapping, subValue, "%s.__value__", field);
      } else {
         record.setValue(field, subValue, fieldMapping.getType(), true);
      }
   }

   private void setValue(IRecord record, FieldMapping fieldMapping, String value, String path, Object... params) {
      String destinationField = String.format(path, params);
      record.setValue(destinationField, value, fieldMapping.getType(), true);
   }

   /**
    * Get the final destination field which can be understood by the record set/get value.
    */
   private String getDestinationField(String field, int index, FieldMapping fieldMapping) {
      if (Strings.isNullOrEmpty(field)) {
         throw new ConnectIllegalArgumentException(RSC_7820, "field cannot be null");
      }
      if (GDSNFieldMapMacro.isPath(field)) {
         String path = GDSNFieldMapMacro.getPath(field);
         if (fieldMapping.getType().equalsIgnoreCase("referenceTypeData")) {
            return path;
         }
         if (fieldMapping.isCollectionType() || fieldMapping.isLocalizable()) {
            return String.format(path, index);
         } else {
            return path;
         }
      } else if (fieldMapping.getType().equalsIgnoreCase("referenceTypeData")) {
         return String.format(field, index);
      }
      return field;
   }

   @Override
   public String toString() {
      return "GDSNTransformer: inboundFormat = " + connectContext.getConnectProfile().getCollect().getFormat().getType() +
            ", outboundFormat = " + connectContext.getConnectProfile().getPublish().getFormat().getType();
   }

   @Override
   public void close() throws Exception {
      /*
         Implementation not required in most cases
       */
   }
}
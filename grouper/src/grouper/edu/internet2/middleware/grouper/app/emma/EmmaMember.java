package edu.internet2.middleware.grouper.app.emma;

import java.sql.Types;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.internet2.middleware.grouper.app.provisioning.ProvisioningEntity;
import edu.internet2.middleware.grouper.ddl.DdlVersionBean;
import edu.internet2.middleware.grouper.ddl.GrouperDdlUtils;
import edu.internet2.middleware.grouper.ext.org.apache.ddlutils.model.Database;
import edu.internet2.middleware.grouper.ext.org.apache.ddlutils.model.Table;
import edu.internet2.middleware.grouper.util.GrouperUtil;
import edu.internet2.middleware.grouperClient.jdbc.GcDbAccess;
import edu.internet2.middleware.grouperClient.util.GrouperClientUtils;

/**
 * Represents an Emma audience member.
 *
 * Emma members have a numeric member_id and an email. First name / last name and
 * any other user-defined values live inside a nested "fields" object in the Emma
 * JSON. The two well-known fields first_name and last_name are modeled directly;
 * all other user-defined fields are carried through the generic customFields map
 * and surfaced as provisioning attributes prefixed with "field_".
 */
public class EmmaMember {

  public static void createTableEmmaMember(DdlVersionBean ddlVersionBean, Database database) {

    final String tableName = "mock_emma_member";

    try {
      new GcDbAccess().sql("select count(*) from " + tableName).select(int.class);
    } catch (Exception e) {
      Table loaderTable = GrouperDdlUtils.ddlutilsFindOrCreateTable(database, tableName);
      GrouperDdlUtils.ddlutilsFindOrCreateColumn(loaderTable, "email", Types.VARCHAR, "256", false, true);
      GrouperDdlUtils.ddlutilsFindOrCreateColumn(loaderTable, "first_name", Types.VARCHAR, "256", false, false);
      GrouperDdlUtils.ddlutilsFindOrCreateColumn(loaderTable, "last_name", Types.VARCHAR, "256", false, false);
      GrouperDdlUtils.ddlutilsFindOrCreateColumn(loaderTable, "id", Types.BIGINT, "20", true, true);

      GrouperDdlUtils.ddlutilsFindOrCreateColumn(loaderTable, "member_status_id", Types.VARCHAR, "1", false, false);
      GrouperDdlUtils.ddlutilsFindOrCreateColumn(loaderTable, "fields", Types.VARCHAR, "4000", false, false);

      GrouperDdlUtils.ddlutilsFindOrCreateIndex(database, tableName, "mock_emma_member_email_idx", true, "email");
    }

  }

  private Long id;
  private String email;
  private String firstName;
  private String lastName;

  /** Emma member_status_id: 'a' (active), 'o' (optout), or 'e' (error). */
  private String memberStatusId;

  /**
   * Additional user-defined values from the Emma "fields" object (other than
   * first_name / last_name). Keys are arbitrary, values must be String, Long, or Boolean.
   */
  private Map<String, Object> customFields = new HashMap<>();

  /** Prefix for provisioning entity attributes which represent an Emma user-defined field. */
  public static final String CUSTOM_FIELD_ATTRIBUTE_PREFIX = "field_";

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getFirstName() {
    return firstName;
  }

  public void setFirstName(String firstName) {
    this.firstName = firstName;
  }

  public String getLastName() {
    return lastName;
  }

  public void setLastName(String lastName) {
    this.lastName = lastName;
  }

  public String getMemberStatusId() {
    return memberStatusId;
  }

  public void setMemberStatusId(String memberStatusId) {
    this.memberStatusId = memberStatusId;
  }

  public Map<String, Object> getCustomFields() {
    return customFields;
  }

  public void setCustomFields(Map<String, Object> customFields) {
    this.customFields = customFields == null ? new HashMap<String, Object>() : normalizeCustomFields(customFields);
  }

  /**
   * Hibernate mapping helper: persist customFields map as JSON in the fields column.
   */
  public String getFieldsJson() {
    if (this.customFields == null || this.customFields.isEmpty()) {
      return null;
    }
    try {
      return GrouperUtil.objectMapper.writeValueAsString(this.customFields);
    } catch (Exception e) {
      throw new RuntimeException("Unable to serialize EmmaMember.customFields to JSON", e);
    }
  }

  /**
   * Hibernate mapping helper: load customFields map from JSON stored in the fields column.
   */
  public void setFieldsJson(String fieldsJson) {
    if (GrouperUtil.isBlank(fieldsJson)) {
      this.customFields = new HashMap<>();
      return;
    }
    try {
      Map<String, Object> customFieldsMap = GrouperUtil.objectMapper.readValue(fieldsJson,
          new TypeReference<Map<String, Object>>() {
          });
      this.customFields = customFieldsMap == null ? new HashMap<>() : normalizeCustomFields(customFieldsMap, fieldsJson);
    } catch (RuntimeException re) {
      throw re;
    } catch (Exception e) {
      throw new RuntimeException("Unable to parse EmmaMember.fieldsJson. json='" + fieldsJson + "'", e);
    }
  }

  /**
   * Convert to a Grouper provisioning entity
   * @return the converted entity
   */
  public ProvisioningEntity toProvisioningEntity() {
    ProvisioningEntity targetEntity = new ProvisioningEntity(false);

    if (this.id != null) {
      targetEntity.assignAttributeValue("id", this.id);
    }
    targetEntity.assignAttributeValue("email", this.email);
    targetEntity.assignAttributeValue("firstName", this.firstName);
    targetEntity.assignAttributeValue("lastName", this.lastName);
    targetEntity.assignAttributeValue("memberStatusId", this.memberStatusId);

    // Additional user-defined fields are represented as attributes named field_<fieldName>
    if (this.customFields != null && !this.customFields.isEmpty()) {
      for (Map.Entry<String, Object> entry : this.customFields.entrySet()) {
        String fieldName = entry.getKey();
        Object value = entry.getValue();
        if (GrouperUtil.isBlank(fieldName) || value == null) {
          continue;
        }
        targetEntity.assignAttributeValue(CUSTOM_FIELD_ATTRIBUTE_PREFIX + fieldName, value);
      }
    }

    return targetEntity;
  }

  /**
   * Convert from a provisioning entity to an EmmaMember
   * @param targetEntity the Grouper provisioning entity to convert
   * @param fieldNamesToSet the field names to be set
   * @return the EmmaMember created from the provisioning entity
   */
  public static EmmaMember fromProvisioningEntity(ProvisioningEntity targetEntity, Set<String> fieldNamesToSet) {
    EmmaMember emmaMember = new EmmaMember();

    if (fieldNamesToSet == null || fieldNamesToSet.contains("id")) {
      if (targetEntity.getId() == null) {
        emmaMember.setId(null);
      } else {
        emmaMember.setId(Long.parseLong(targetEntity.getId()));
      }
    }

    if (fieldNamesToSet == null || fieldNamesToSet.contains("email")) {
      emmaMember.setEmail(targetEntity.retrieveAttributeValueString("email"));
    }

    if (fieldNamesToSet == null || fieldNamesToSet.contains("firstName")) {
      emmaMember.setFirstName(targetEntity.retrieveAttributeValueString("firstName"));
    }

    if (fieldNamesToSet == null || fieldNamesToSet.contains("lastName")) {
      emmaMember.setLastName(targetEntity.retrieveAttributeValueString("lastName"));
    }

    if (fieldNamesToSet == null || fieldNamesToSet.contains("memberStatusId")) {
      emmaMember.setMemberStatusId(targetEntity.retrieveAttributeValueString("memberStatusId"));
    }

    // User-defined fields: provisioned as attributes named field_<fieldName>
    Set<String> attributeNames = fieldNamesToSet == null
        ? targetEntity.retrieveAttributes().keySet() : fieldNamesToSet;

    Map<String, Object> customFieldsToSet = null;
    for (String attributeName : attributeNames) {
      if (GrouperUtil.isBlank(attributeName) || !attributeName.startsWith(CUSTOM_FIELD_ATTRIBUTE_PREFIX)) {
        continue;
      }
      String fieldName = attributeName.substring(CUSTOM_FIELD_ATTRIBUTE_PREFIX.length());
      if (GrouperUtil.isBlank(fieldName)) {
        continue;
      }
      Object value = targetEntity.retrieveAttributeValue(attributeName);
      if (value == null) {
        continue;
      }
      if (customFieldsToSet == null) {
        customFieldsToSet = new HashMap<>();
      }
      customFieldsToSet.put(fieldName, value);
    }
    if (customFieldsToSet != null) {
      emmaMember.setCustomFields(customFieldsToSet);
    }

    return emmaMember;
  }

  /**
   * Get an EmmaMember object from an Emma JSON member node
   * @param entityNode the node containing the member
   * @return the EmmaMember object
   */
  public static EmmaMember fromJson(JsonNode entityNode) {
    if (entityNode == null) {
      return null;
    }

    EmmaMember emmaMember = new EmmaMember();

    emmaMember.id = GrouperUtil.jsonJacksonGetLong(entityNode, "member_id");
    emmaMember.email = GrouperUtil.jsonJacksonGetString(entityNode, "email");
    emmaMember.memberStatusId = GrouperUtil.jsonJacksonGetString(entityNode, "member_status_id");

    // first_name / last_name and any other user-defined values live in the "fields" object
    JsonNode fieldsNode = GrouperUtil.jsonJacksonGetNode(entityNode, "fields");
    if (fieldsNode != null && fieldsNode.isObject()) {
      emmaMember.firstName = GrouperUtil.jsonJacksonGetString(fieldsNode, "first_name");
      emmaMember.lastName = GrouperUtil.jsonJacksonGetString(fieldsNode, "last_name");

      String fieldsJson = null;
      try {
        fieldsJson = GrouperUtil.objectMapper.writeValueAsString(fieldsNode);
      } catch (Exception e) {
        fieldsJson = String.valueOf(fieldsNode);
      }

      try {
        Map<String, Object> fieldsMap = GrouperUtil.objectMapper.convertValue(fieldsNode,
            new TypeReference<Map<String, Object>>() {});
        if (fieldsMap != null) {
          // first_name / last_name are modeled directly, don't duplicate into customFields
          fieldsMap.remove("first_name");
          fieldsMap.remove("last_name");
          emmaMember.customFields = normalizeCustomFields(fieldsMap, fieldsJson);
        }
      } catch (RuntimeException re) {
        throw re;
      } catch (Exception e) {
        throw new RuntimeException("Unable to parse EmmaMember.fields from Emma JSON. json='" + fieldsJson + "'", e);
      }
    }

    return emmaMember;
  }

  /**
   * Convert an EmmaMember to the JSON body used by the /members/add endpoint.
   * The Emma add/update payload is { "email": ..., "fields": { ... } }.
   * @param fieldNamesToSet the field names we'll be setting
   * @return the json representation of the EmmaMember
   */
  public ObjectNode toJson(Set<String> fieldNamesToSet) {
    ObjectNode result = GrouperUtil.jsonJacksonNode();

    if (fieldNamesToSet == null || fieldNamesToSet.contains("email")) {
      if (!GrouperUtil.isBlank(this.email)) {
        result.put("email", this.email);
      }
    }

    ObjectNode fieldsNode = GrouperUtil.jsonJacksonNode();

    if (fieldNamesToSet == null || fieldNamesToSet.contains("firstName")) {
      if (!GrouperUtil.isBlank(this.firstName)) {
        fieldsNode.put("first_name", this.firstName);
      }
    }
    if (fieldNamesToSet == null || fieldNamesToSet.contains("lastName")) {
      if (!GrouperUtil.isBlank(this.lastName)) {
        fieldsNode.put("last_name", this.lastName);
      }
    }

    // user-defined fields
    if (this.customFields != null && !this.customFields.isEmpty()) {
      for (Map.Entry<String, Object> entry : this.customFields.entrySet()) {
        String fieldName = entry.getKey();
        Object value = entry.getValue();
        if (GrouperUtil.isBlank(fieldName) || value == null) {
          continue;
        }
        // when a specific field set is requested, only include matching field_<name> attributes
        if (fieldNamesToSet != null && !fieldNamesToSet.contains(CUSTOM_FIELD_ATTRIBUTE_PREFIX + fieldName)) {
          continue;
        }
        if (value instanceof String) {
          fieldsNode.put(fieldName, (String) value);
        } else if (value instanceof Boolean) {
          fieldsNode.put(fieldName, ((Boolean) value).booleanValue());
        } else if (value instanceof Number) {
          fieldsNode.put(fieldName, ((Number) value).longValue());
        } else {
          throw new RuntimeException("EmmaMember.customFields['" + fieldName + "'] had unsupported type "
              + value.getClass().getName());
        }
      }
    }

    if (fieldsNode.size() > 0) {
      result.set("fields", fieldsNode);
    }

    return result;
  }

  private static Map<String, Object> normalizeCustomFields(Map<String, Object> customFieldsMap) {
    return normalizeCustomFields(customFieldsMap, null);
  }

  private static Map<String, Object> normalizeCustomFields(Map<String, Object> customFieldsMap, String customFieldsJsonForError) {
    Map<String, Object> result = new HashMap<>();
    for (Map.Entry<String, Object> entry : customFieldsMap.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();

      if (value == null) {
        result.put(key, null);
        continue;
      }
      if (value instanceof String) {
        result.put(key, value);
        continue;
      }
      if (value instanceof Boolean) {
        result.put(key, value);
        continue;
      }
      if (value instanceof Number) {
        if (value instanceof Float || value instanceof Double) {
          throw new RuntimeException("EmmaMember.customFields['" + key
              + "'] must be String, Long, or Boolean but was decimal number: " + value
              + (customFieldsJsonForError == null ? "" : (". json='" + customFieldsJsonForError + "'")));
        }
        result.put(key, ((Number) value).longValue());
        continue;
      }

      throw new RuntimeException("EmmaMember.customFields['" + key
          + "'] must be String, Long, or Boolean but was: " + value.getClass().getName()
          + (customFieldsJsonForError == null ? "" : (". json='" + customFieldsJsonForError + "'")));
    }
    return result;
  }

  @Override
  public String toString() {
    return GrouperClientUtils.toStringReflection(this);
  }

}

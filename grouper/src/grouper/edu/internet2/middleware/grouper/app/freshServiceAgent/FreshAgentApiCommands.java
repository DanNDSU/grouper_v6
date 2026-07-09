package edu.internet2.middleware.grouper.app.freshServiceAgent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.internet2.middleware.grouper.app.externalSystem.WsBearerTokenExternalSystem;
import edu.internet2.middleware.grouper.app.loader.GrouperLoaderConfig;
import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioner;
import edu.internet2.middleware.grouper.app.provisioning.ProvisioningObjectChangeAction;
import edu.internet2.middleware.grouper.misc.GrouperStartup;
import edu.internet2.middleware.grouper.util.GrouperHttpClient;
import edu.internet2.middleware.grouper.util.GrouperUtil;
import edu.internet2.middleware.grouperClient.util.GrouperClientUtils;

public class FreshAgentApiCommands {

  private static final int MAX_PAGE_SIZE = 100;
  public static final Set<String> doNotLogParameters = GrouperUtil.toSet("client_secret");
  public static final Set<String> doNotLogHeaders = GrouperUtil.toSet("authorization");

  public static GrouperLoaderConfig grouperLoaderConfig = GrouperLoaderConfig.retrieveConfig();

  public static void main(String[] args) {

    GrouperStartup.startup();

    try {
      String configId = "freshserviceRequester";
      
      //Test API calls here
      
    } catch (Exception e) {
      System.out.println("Error: " + GrouperClientUtils.getFullStackTrace(e));
    }
    System.exit(0);
  }

  private static JsonNode executeMethod(Map<String, Object> debugMap, String debugLabel,
      String httpMethodName, String configId, String urlSuffix, Set<Integer> allowedReturnCodes,
      int[] returnCode, String bodyParam, Integer page, boolean addPageSize, String queryParam) {

    GrouperHttpClient grouperHttpClient = new GrouperHttpClient();

    grouperHttpClient.assignDoNotLogHeaders(doNotLogHeaders).assignDoNotLogParameters(doNotLogParameters);

    WsBearerTokenExternalSystem.attachAuthenticationToHttpClient(grouperHttpClient, configId, grouperLoaderConfig, debugMap);

    String url = grouperLoaderConfig.propertyValueStringRequired("grouper.wsBearerToken." + configId + ".endpoint");

    if (url.endsWith("/")) {
      url = url.substring(0, url.length() - 1);
    }
    // in a nextLink, url is specified, so it might not have a prefix of the resourceEndpoint
    if(!urlSuffix.startsWith("http")) {
      url += (urlSuffix.startsWith("/") ? "" : "/") + urlSuffix;
    } else {
      url = urlSuffix;
    }
    debugMap.put("url", url);

    grouperHttpClient.assignUrl(url);
    grouperHttpClient.assignGrouperHttpMethod(httpMethodName);

    if (StringUtils.isNotBlank(bodyParam)) {
      grouperHttpClient.assignBody(bodyParam);
    }

    if (page != null && page > 0) {
      grouperHttpClient.addUrlParameter("page", Integer.toString(page));
    }

    if (addPageSize) {
      // default page size to max which is 100
      int pageSize = grouperLoaderConfig.propertyValueInt("grouper.wsBearerToken." + configId + ".pageSize", MAX_PAGE_SIZE);
      grouperHttpClient.addUrlParameter("per_page", Integer.toString(pageSize));
    }

    if (StringUtils.isNotBlank(queryParam)) {
      grouperHttpClient.addUrlParameter("query", queryParam);
    }

    if (httpMethodName.equals("POST") || httpMethodName.equals("PUT")) {
      grouperHttpClient.addHeader("Content-Type", "application/json; charset=utf-8");
    }

    long httpCallStartMillis = System.currentTimeMillis();
    try {
      grouperHttpClient.executeRequest();
    } finally {
      GrouperProvisioner.incrementCommandsCallsStats(debugLabel, 1,
          System.currentTimeMillis() - httpCallStartMillis);
    }

    int code = -1;
    String json = null;

    try {
      code = grouperHttpClient.getResponseCode();
      returnCode[0] = code;
      json = grouperHttpClient.getResponseBody();
    } catch (Exception e) {
      throw new RuntimeException("Error connecting to '" + debugMap.get("url") + "'", e);
    }

    if (!allowedReturnCodes.contains(code)) {
      throw new RuntimeException(
          "Invalid return code '" + code + "', expecting: " + GrouperUtil.setToString(allowedReturnCodes)
              + ". '" + debugMap.get("url") + "' " + json);
    }

    if (StringUtils.isBlank(json)) {
      return null;
    }

    try {
      JsonNode rootNode = GrouperUtil.jsonJacksonNode(json);
      return rootNode;
    } catch (Exception e) {
      throw new RuntimeException("Error parsing response: '" + json + "'", e);
    }
  }

  // Group methods

  /**
   * The only group fields this provisioner writes to Freshservice.
   * The Freshservice group GET endpoint returns many additional fields
   * (created_at, workspace_id, members_pending_approval, ocs_schedule_id, etc.)
   * that its PUT/POST endpoints reject as invalid. Rather than read-everything
   * and blacklist the rejected fields (which breaks whenever Freshservice adds
   * a new read-only field), we build every write body from this explicit
   * whitelist. Only name and description are managed by this provisioner.
   */
  private static final Set<String> WRITABLE_GROUP_FIELDS = GrouperUtil.toSet("name", "description");

  /**
   * Build a Freshservice group write body (POST/PUT) containing only the
   * fields this provisioner is allowed to write.
   * @param sourceNode a group ObjectNode (e.g. from a GET), may be null
   * @return a new ObjectNode containing only the whitelisted writable fields
   */
  private static ObjectNode buildWritableGroupNode(ObjectNode sourceNode) {
    ObjectNode result = GrouperUtil.jsonJacksonNode();
    if (sourceNode == null) {
      return result;
    }
    for (String field : WRITABLE_GROUP_FIELDS) {
      JsonNode value = sourceNode.get(field);
      if (value != null) {
        result.set(field, value.deepCopy());
      }
    }
    return result;
  }

  /**
   * Create an agent group in Freshservice
   * @param configId the id of the external system
   * @param grouperAgentGroup the agent group to be created in Freshservice
   */
   public static FreshAgentGroup createAgentGroup(String configId, FreshAgentGroup grouperAgentGroup) {
    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();

    debugMap.put("method", "createAgentGroup");

    long startTime = System.nanoTime();

    try {
      // only send writable fields (name, description); never send id on create
      ObjectNode jsonToSend = buildWritableGroupNode(grouperAgentGroup.toJson(null));

      String jsonStringToSend = GrouperUtil.jsonJacksonToString(jsonToSend);

      int[] returnCode = new int[] { -1 };
      JsonNode jsonNode = executeMethod(debugMap, "createAgentGroup", "POST", configId, "api/v2/groups",
          GrouperUtil.toSet(200, 201, 409), returnCode, jsonStringToSend, null, false, null);

      if (returnCode[0] == 409) {
        throw new RuntimeException("Agent group already exists: " + grouperAgentGroup.getName());
      }

      JsonNode groupNode = GrouperUtil.jsonJacksonGetNode(jsonNode, "group");
      FreshAgentGroup createdGroup = FreshAgentGroup.fromJson(groupNode);

      return createdGroup;

    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      FreshAgentLog.freshserviceLog(debugMap, startTime);
    }
  }

  /**
   * Update a Freshservice agent group.
   *
   * This provisioner only manages the group name and description. The update
   * body is built from a whitelist of writable fields ({@link #WRITABLE_GROUP_FIELDS}),
   * so read-only fields returned by the Freshservice GET endpoint are never
   * echoed back on the PUT.
   *
   * @param configId the id of the external system
   * @param grouperAgentGroup the group to be updated in Freshservice (must have id set)
   * @param fieldsToUpdate the fields to update; supported keys: "name", "description".
   *   A {@link ProvisioningObjectChangeAction#delete} action nulls the field out.
   */
  public static FreshAgentGroup updateAgentGroup(String configId, FreshAgentGroup grouperAgentGroup, Map<String, ProvisioningObjectChangeAction> fieldsToUpdate) {
    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();

    debugMap.put("method", "updateAgentGroup");

    long startTime = System.nanoTime();

    try {

      if (grouperAgentGroup == null) {
        throw new RuntimeException("grouperAgentGroup is null");
      }

      Long groupId = grouperAgentGroup.getId();
      if (groupId == null || groupId == 0L) {
        throw new RuntimeException("groupId is null or 0 (unset)");
      }

      // Confirm the group exists in the target before attempting an update.
      // We do not carry any fields forward from the GET; the body is built
      // entirely from the whitelist below.
      ObjectNode existingNode = retrieveAgentGroupRawNode(configId, groupId);
      if (existingNode == null) {
        throw new RuntimeException("Cannot update agent group that does not exist in target. id=" + groupId);
      }

      // Build the PUT body from only the writable fields.
      ObjectNode jsonToSend = GrouperUtil.jsonJacksonNode();

      if (fieldsToUpdate != null) {
        for (Map.Entry<String, ProvisioningObjectChangeAction> entry : fieldsToUpdate.entrySet()) {
          String fieldName = entry.getKey();
          ProvisioningObjectChangeAction action = entry.getValue();
          if (action == null || StringUtils.isBlank(fieldName)) {
            continue;
          }

          // ignore any field we are not allowed to write
          if (!WRITABLE_GROUP_FIELDS.contains(fieldName)) {
            throw new RuntimeException("Field '" + fieldName + "' is not writable for agent groups. "
                + "Writable fields: " + GrouperUtil.setToString(WRITABLE_GROUP_FIELDS)
                + ". Set CRUD update to false for this attribute in the provisioner configuration.");
          }

          boolean isDelete = action == ProvisioningObjectChangeAction.delete;

          if ("name".equals(fieldName)) {
            if (isDelete || grouperAgentGroup.getName() == null) {
              jsonToSend.putNull("name");
            } else {
              jsonToSend.put("name", grouperAgentGroup.getName());
            }
          } else if ("description".equals(fieldName)) {
            if (isDelete || grouperAgentGroup.getDescription() == null) {
              jsonToSend.putNull("description");
            } else {
              jsonToSend.put("description", grouperAgentGroup.getDescription());
            }
          }
        }
      }

      // nothing to update
      if (jsonToSend.size() == 0) {
        return grouperAgentGroup;
      }

      String jsonStringToSend = GrouperUtil.jsonJacksonToString(jsonToSend);

      JsonNode jsonNode = executeMethod(debugMap, "updateAgentGroup", "PUT", configId, "api/v2/groups/" + String.valueOf(groupId),
          GrouperUtil.toSet(200, 201), new int[] { -1 }, jsonStringToSend, null, false, null);

      JsonNode groupNode = GrouperUtil.jsonJacksonGetNode(jsonNode, "group");
      FreshAgentGroup updatedGroup = FreshAgentGroup.fromJson(groupNode);
      return updatedGroup;

    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      FreshAgentLog.freshserviceLog(debugMap, startTime);
    }
  }

  /**
   * The only Agent fields this provisioner writes to Freshservice.
   *
   * The Freshservice agent GET endpoint returns many additional fields
   * (created_at, updated_at, last_login_at, has_logged_in, department_names,
   * location_name, etc.) that its POST/PUT endpoints reject as read-only or
   * invalid (HTTP 400). Rather than read-everything-and-blacklist (which breaks
   * whenever Freshservice adds a new read-only field), every write body is built
   * from this explicit whitelist.
   *
   * These are Java-style field names. The mapping to Freshservice JSON attribute
   * names is:
   *   firstName  -> first_name
   *   lastName   -> last_name
   *   email      -> email
   *   roles      -> roles      (array, required by Freshservice on create)
   * Custom fields are handled separately via the "customField_" attribute prefix
   * and are nested under the JSON "custom_fields" object.
   */
  private static final Set<String> WRITABLE_AGENT_FIELDS = GrouperUtil.toSet(
      "firstName", "lastName", "email", "roles",
      "jobTitle", "workPhoneNumber", "departmentId", "reportingManagerId",
      "address", "externalId");

  /**
   * Build a Freshservice agent write body (POST/PUT) containing only the fields
   * this provisioner is allowed to write: first_name, last_name, email,
   * job_title, work_phone_number, department_ids, reporting_manager_id, address,
   * external_id, roles, and custom_fields. (Everything except id and active.)
   * Reads its values from the supplied FreshAgentUser.
   *
   * @param grouperAgentUser the agent whose writable values to serialize
   * @param fieldsToWrite the Java field names to include. If null, all writable
   *   fields (plus all custom fields present on the user) are written. Field
   *   names not in {@link #WRITABLE_AGENT_FIELDS} and not prefixed with
   *   {@link FreshAgentUser#CUSTOM_FIELD_ATTRIBUTE_PREFIX} are ignored.
   * @return an ObjectNode containing only whitelisted writable fields
   */
  private static ObjectNode buildWritableAgentNode(FreshAgentUser grouperAgentUser, Set<String> fieldsToWrite) {

    ObjectNode result = GrouperUtil.jsonJacksonNode();
    if (grouperAgentUser == null) {
      return result;
    }

    // firstName -> first_name
    if (fieldsToWrite == null || fieldsToWrite.contains("firstName")) {
      if (grouperAgentUser.getFirstName() != null) {
        result.put("first_name", grouperAgentUser.getFirstName());
      } else {
        result.putNull("first_name");
      }
    }

    // lastName -> last_name
    if (fieldsToWrite == null || fieldsToWrite.contains("lastName")) {
      if (grouperAgentUser.getLastName() != null) {
        result.put("last_name", grouperAgentUser.getLastName());
      } else {
        result.putNull("last_name");
      }
    }

    // email -> email (agents use "email", not "primary_email" like requesters)
    if (fieldsToWrite == null || fieldsToWrite.contains("email")) {
      if (grouperAgentUser.getEmail() != null) {
        result.put("email", grouperAgentUser.getEmail());
      } else {
        result.putNull("email");
      }
    }

    // jobTitle -> job_title
    if (fieldsToWrite == null || fieldsToWrite.contains("jobTitle")) {
      if (grouperAgentUser.getJobTitle() != null) {
        result.put("job_title", grouperAgentUser.getJobTitle());
      } else {
        result.putNull("job_title");
      }
    }

    // workPhoneNumber -> work_phone_number
    if (fieldsToWrite == null || fieldsToWrite.contains("workPhoneNumber")) {
      if (grouperAgentUser.getWorkPhoneNumber() != null) {
        result.put("work_phone_number", grouperAgentUser.getWorkPhoneNumber());
      } else {
        result.putNull("work_phone_number");
      }
    }

    // departmentId -> department_ids (Freshservice expects an array on writes).
    // We model a single departmentId, so we emit a one-element array. When the
    // value is null we send an empty array to clear the association.
    if (fieldsToWrite == null || fieldsToWrite.contains("departmentId")) {
      ArrayNode departmentIdsNode = GrouperUtil.jsonJacksonArrayNode();
      if (grouperAgentUser.getDepartmentId() != null) {
        departmentIdsNode.add(grouperAgentUser.getDepartmentId().longValue());
      }
      result.set("department_ids", departmentIdsNode);
    }

    // reportingManagerId -> reporting_manager_id
    if (fieldsToWrite == null || fieldsToWrite.contains("reportingManagerId")) {
      if (grouperAgentUser.getReportingManagerId() != null) {
        result.put("reporting_manager_id", grouperAgentUser.getReportingManagerId().longValue());
      } else {
        result.putNull("reporting_manager_id");
      }
    }

    // address -> address
    if (fieldsToWrite == null || fieldsToWrite.contains("address")) {
      if (grouperAgentUser.getAddress() != null) {
        result.put("address", grouperAgentUser.getAddress());
      } else {
        result.putNull("address");
      }
    }

    // externalId -> external_id
    if (fieldsToWrite == null || fieldsToWrite.contains("externalId")) {
      if (grouperAgentUser.getExternalId() != null) {
        result.put("external_id", grouperAgentUser.getExternalId());
      } else {
        result.putNull("external_id");
      }
    }

    // roles -> roles (array). Required by Freshservice on create. We carry the
    // raw roles JSON through unchanged so updates never strip an agent's roles.
    if (fieldsToWrite == null || fieldsToWrite.contains("roles")) {
      if (!StringUtils.isBlank(grouperAgentUser.getRolesJson())) {
        try {
          JsonNode rolesNode = GrouperUtil.objectMapper.readTree(grouperAgentUser.getRolesJson());
          if (rolesNode != null && rolesNode.isArray()) {
            result.set("roles", rolesNode);
          }
        } catch (Exception e) {
          throw new RuntimeException("Unable to parse FreshAgentUser.rolesJson. json='"
              + grouperAgentUser.getRolesJson() + "'", e);
        }
      }
    }

    // custom fields -> nested under custom_fields object.
    // When fieldsToWrite is null, write every custom field present on the user.
    // Otherwise, write only the customField_<name> entries named in fieldsToWrite.
    Map<String, Object> customFields = grouperAgentUser.getCustomFields();
    if (customFields != null && !customFields.isEmpty()) {
      ObjectNode customFieldsNode = null;

      if (fieldsToWrite == null) {
        for (Map.Entry<String, Object> entry : customFields.entrySet()) {
          String customFieldName = entry.getKey();
          Object value = entry.getValue();
          if (StringUtils.isBlank(customFieldName) || value == null) {
            continue;
          }
          if (customFieldsNode == null) {
            customFieldsNode = GrouperUtil.jsonJacksonNode();
          }
          putCustomFieldValue(customFieldsNode, customFieldName, value);
        }
      } else {
        for (String attributeName : fieldsToWrite) {
          if (StringUtils.isBlank(attributeName)
              || !attributeName.startsWith(FreshAgentUser.CUSTOM_FIELD_ATTRIBUTE_PREFIX)) {
            continue;
          }
          String customFieldName = attributeName.substring(FreshAgentUser.CUSTOM_FIELD_ATTRIBUTE_PREFIX.length());
          if (StringUtils.isBlank(customFieldName)) {
            continue;
          }
          Object value = customFields.get(customFieldName);
          if (value == null) {
            continue;
          }
          if (customFieldsNode == null) {
            customFieldsNode = GrouperUtil.jsonJacksonNode();
          }
          putCustomFieldValue(customFieldsNode, customFieldName, value);
        }
      }

      if (customFieldsNode != null && customFieldsNode.size() > 0) {
        result.set("custom_fields", customFieldsNode);
      }
    }

    return result;
  }

  /**
   * Put a single custom field value into the custom_fields node, coercing it to
   * one of the supported Freshservice types (String, Long, Boolean).
   */
  private static void putCustomFieldValue(ObjectNode customFieldsNode, String customFieldName, Object value) {
    if (value instanceof String) {
      customFieldsNode.put(customFieldName, (String) value);
    } else if (value instanceof Boolean) {
      customFieldsNode.put(customFieldName, ((Boolean) value).booleanValue());
    } else if (value instanceof Number) {
      customFieldsNode.put(customFieldName, ((Number) value).longValue());
    } else {
      throw new RuntimeException("Unsupported custom field type for " + customFieldName + ": "
          + value.getClass().getName());
    }
  }

  /**
   * Create a Freshservice agent.
   *
   * Looks up an existing agent by email first:
   * - If an agent already exists and is inactive, it is reactivated and then
   *   updated with the writable fields from grouperAgentUser.
   * - If an agent already exists and is active, it is updated with the writable
   *   fields from grouperAgentUser.
   * - If no agent exists with that email, a new agent is created via POST.
   *
   * Only the whitelisted Agent fields are ever sent to Freshservice:
   *   first_name, last_name, email, roles, custom_fields.
   * Freshservice requires a non-empty roles array on create, so the
   * grouperAgentUser must carry roles (rolesJson) or the create will fail with
   * an HTTP 400 validation error.
   *
   * @param configId the id of the external system
   * @param grouperAgentUser the agent to be created (or updated if it exists)
   * @return the created or updated agent
   */
  public static FreshAgentUser createAgentUser(String configId, FreshAgentUser grouperAgentUser) {
    return createAgentUser(configId, grouperAgentUser, true);
  }

  /**
   * Create a Freshservice agent (see {@link #createAgentUser(String, FreshAgentUser)}),
   * with control over how an existing inactive agent is reactivated.
   *
   * @param configId the id of the external system
   * @param grouperAgentUser the agent to be created (or updated if it exists)
   * @param reactivateAsFullTime when an existing agent is inactive and must be
   *   reactivated first, whether to restore them as full-time (true) or leave
   *   them occasional (false)
   * @return the created or updated agent
   */
  public static FreshAgentUser createAgentUser(String configId, FreshAgentUser grouperAgentUser, boolean reactivateAsFullTime) {
    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();

    debugMap.put("method", "createAgentUser");

    long startTime = System.nanoTime();

    try {

      // look up existing agent by email address
      FreshAgentUser existingUser = null;
      if (!StringUtils.isBlank(grouperAgentUser.getEmail())) {
        existingUser = retrieveAgentUserByEmail(configId, grouperAgentUser.getEmail(), true);
      }

      if (existingUser != null) {

        // if the existing agent is not active, reactivate it first
        if (existingUser.getActive() == null || !existingUser.getActive()) {
          reactivateAgentUser(configId, existingUser.getId(), reactivateAsFullTime);
        }

        // agent already exists - update the writable fields
        Set<String> fieldsToUpdate = new java.util.LinkedHashSet<String>();
        fieldsToUpdate.add("firstName");
        fieldsToUpdate.add("lastName");
        fieldsToUpdate.add("email");

        // Only push roles to an existing agent when this entity actually carries
        // roles. A configured default role is meant for brand new agents only;
        // including "roles" unconditionally here would overwrite an existing
        // agent's real roles with the default. (buildWritableAgentNode also skips
        // a blank rolesJson, but leaving "roles" out of the field set makes the
        // intent explicit and avoids a needless no-op.)
        if (grouperAgentUser.hasRoles()) {
          fieldsToUpdate.add("roles");
        }

        // include any custom fields carried on the grouperAgentUser
        if (grouperAgentUser.getCustomFields() != null) {
          for (String customFieldName : grouperAgentUser.getCustomFields().keySet()) {
            if (!StringUtils.isBlank(customFieldName)) {
              fieldsToUpdate.add(FreshAgentUser.CUSTOM_FIELD_ATTRIBUTE_PREFIX + customFieldName);
            }
          }
        }

        // set the id from the existing agent so updateAgentUser can find it
        grouperAgentUser.setId(existingUser.getId());

        return updateAgentUser(configId, grouperAgentUser, fieldsToUpdate);
      }

      // no existing agent found - create a new one via POST
      return createAgentUserHelper(configId, grouperAgentUser);

    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      FreshAgentLog.freshserviceLog(debugMap, startTime);
    }
  }

  /**
   * Helper that creates a new agent in Freshservice via POST /api/v2/agents.
   * The request body contains only the whitelisted writable Agent fields
   * (first_name, last_name, email, roles, custom_fields).
   *
   * Callers should typically use {@link #createAgentUser(String, FreshAgentUser)}
   * which handles the lookup-by-email and update-if-exists logic.
   *
   * @param configId the id of the external system
   * @param grouperAgentUser the agent to be created
   * @return the created agent with its assigned id
   */
  public static FreshAgentUser createAgentUserHelper(String configId, FreshAgentUser grouperAgentUser) {
    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();

    debugMap.put("method", "createAgentUserHelper");

    long startTime = System.nanoTime();

    try {
      // build the POST body from the writable whitelist only
      ObjectNode jsonToSend = buildWritableAgentNode(grouperAgentUser, null);

      String jsonStringToSend = GrouperUtil.jsonJacksonToString(jsonToSend);

      int[] returnCode = new int[] { -1 };
      JsonNode jsonNode = executeMethod(debugMap, "createAgentUserHelper", "POST", configId, "api/v2/agents",
          GrouperUtil.toSet(200, 201, 409), returnCode, jsonStringToSend, null, false, null);

      if (returnCode[0] == 409) {
        throw new RuntimeException("Agent already exists: " + grouperAgentUser.getEmail());
      }

      JsonNode userNode = GrouperUtil.jsonJacksonGetNode(jsonNode, "agent");
      FreshAgentUser createdUser = FreshAgentUser.fromJson(userNode);
      return createdUser;

    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      FreshAgentLog.freshserviceLog(debugMap, startTime);
    }
  }

  /**
   * Update a Freshservice agent.
   *
   * The PUT body is built entirely from the writable-field whitelist
   * ({@link #WRITABLE_AGENT_FIELDS} plus custom fields), so read-only fields
   * returned by the Freshservice GET endpoint are never echoed back on the PUT.
   * This avoids HTTP 400 (readonly_field / invalid_field) errors.
   *
   * Only the following Agent fields are written:
   *   first_name, last_name, email, roles, custom_fields.
   *
   * The fieldsToUpdate set uses Java-style field names. Custom fields use the
   * prefix "customField_" followed by the Freshservice custom field name
   * (e.g. "customField_pennkey" sets custom_fields.pennkey).
   *
   * Note: unlike requesters, agents use the "email" attribute (not
   * "primary_email"), and group membership is handled via the agent group's
   * members array (see addGroupMembership / removeGroupMembership), not on the
   * agent record.
   *
   * @param configId the id of the external system
   * @param grouperAgentUser the agent containing the new values.
   *   Must have id set to identify which agent to update.
   * @param fieldsToUpdate set of Java field names to update. Supported values:
   *   "firstName", "lastName", "email", "roles", and custom fields with prefix
   *   "customField_" (e.g. "customField_pennkey"). Any other field name (e.g.
   *   a read-only attribute, or an unmanaged field) results in an exception so
   *   the misconfiguration is caught rather than silently producing an HTTP 400.
   * @return the updated agent parsed from the PUT response
   */
  public static FreshAgentUser updateAgentUser(String configId, FreshAgentUser grouperAgentUser, Set<String> fieldsToUpdate) {
    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();

    debugMap.put("method", "updateAgentUser");

    long startTime = System.nanoTime();

    try {

      // validate input
      if (grouperAgentUser == null) {
        throw new RuntimeException("grouperAgentUser is null");
      }

      Long userId = grouperAgentUser.getId();
      if (userId == null || userId == 0L) {
        throw new RuntimeException("userId is null or 0 (unset)");
      }

      // Confirm the agent exists in the target before attempting an update.
      // We do NOT carry any fields forward from the GET; the PUT body is built
      // entirely from the writable whitelist below.
      // GET /api/v2/agents/{id} returns { "agent": { ... } }
      int[] getReturnCode = new int[] { -1 };
      String getUrlSuffix = "api/v2/agents/" + String.valueOf(userId);
      JsonNode getJsonNode = executeMethod(debugMap, "updateAgentUser", "GET", configId, getUrlSuffix,
          GrouperUtil.toSet(200, 404), getReturnCode, null, null, false, null);

      if (getReturnCode[0] == 404 || getJsonNode == null || getJsonNode.get("agent") == null) {
        throw new RuntimeException("Cannot update agent that does not exist in target. id=" + userId);
      }

      // Validate the requested fields against the writable whitelist. Anything
      // that is neither a whitelisted field nor a custom field attribute is a
      // configuration error (e.g. CRUD update left enabled on a read-only or
      // unmanaged attribute).
      if (fieldsToUpdate != null) {
        for (String fieldName : fieldsToUpdate) {
          if (StringUtils.isBlank(fieldName)) {
            continue;
          }
          boolean isCustomField = fieldName.startsWith(FreshAgentUser.CUSTOM_FIELD_ATTRIBUTE_PREFIX);
          if (!WRITABLE_AGENT_FIELDS.contains(fieldName) && !isCustomField) {
            throw new RuntimeException("Field '" + fieldName + "' is not writable for agents. "
                + "Writable fields: " + GrouperUtil.setToString(WRITABLE_AGENT_FIELDS)
                + " (plus custom fields prefixed '" + FreshAgentUser.CUSTOM_FIELD_ATTRIBUTE_PREFIX + "'). "
                + "Set CRUD update to false for this attribute in the provisioner configuration.");
          }
        }
      }

      // Build the PUT body from only the requested writable fields.
      ObjectNode jsonToSend = buildWritableAgentNode(grouperAgentUser, fieldsToUpdate);

      // nothing to update
      if (jsonToSend.size() == 0) {
        return grouperAgentUser;
      }

      String jsonStringToSend = GrouperUtil.jsonJacksonToString(jsonToSend);

      // PUT /api/v2/agents/{id} returns { "agent": { ... } }
      JsonNode responseNode = executeMethod(debugMap, "updateAgentUser", "PUT", configId, "api/v2/agents/" + String.valueOf(userId),
          GrouperUtil.toSet(200, 201), new int[] { -1 }, jsonStringToSend, null, false, null);

      // parse the updated agent from the response
      JsonNode updatedUserNode = GrouperUtil.jsonJacksonGetNode(responseNode, "agent");
      FreshAgentUser updatedUser = FreshAgentUser.fromJson(updatedUserNode);
      return updatedUser;

    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      FreshAgentLog.freshserviceLog(debugMap, startTime);
    }
  }

  /**
   * Delete an agent group
   * @param configId the id of the external system
   * @param groupId the id of the group to be deleted
   */
  public static void deleteAgentGroup(String configId, Long groupId) {
    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();

    debugMap.put("method", "deleteAgentGroup");

    long startTime = System.nanoTime();

    try {

      if (groupId == null) {
        throw new RuntimeException("groupId is null");
      }
      String id = String.valueOf(groupId);

      executeMethod(debugMap, "deleteAgentGroup", "DELETE", configId, "api/v2/groups/" + id,
          GrouperUtil.toSet(200, 204, 404), new int[] { -1 }, null, null, false, null);

    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      FreshAgentLog.freshserviceLog(debugMap, startTime);
    }

  }

  /**
   * Get a Freshservice agent group
   * @param configId the id of the external system
   * @param id the agent group id
   * @return the GrouperAgentGroup matching the Freshservice group retrieved
   */
  public static FreshAgentGroup retrieveAgentGroup(String configId, Long id) {

    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();

    debugMap.put("method", "retrieveAgentGroup");

    long startTime = System.nanoTime();

    try {
      String urlSuffix = "api/v2/groups/" + String.valueOf(id);
      int[] returnCode = new int[] { -1 };
      JsonNode jsonNode = executeMethod(debugMap, "retrieveAgentGroup", "GET", configId, urlSuffix,
          GrouperUtil.toSet(200, 404), returnCode, null, null, false, null);
      if (returnCode[0] == 404) {
        return null;
      }

      JsonNode groupNode = GrouperUtil.jsonJacksonGetNode(jsonNode, "group");
      if (groupNode == null) {
        return null;
      }
      FreshAgentGroup grouperAgentGroup = FreshAgentGroup.fromJson(groupNode);

      return grouperAgentGroup;

    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      FreshAgentLog.freshserviceLog(debugMap, startTime);
    }
  }

  /**
   * Get the raw "group" ObjectNode for an agent group, including its members array.
   * Used by membership and update operations that must read the group before writing.
   * @param configId the id of the external system
   * @param id the agent group id
   * @return the mutable group ObjectNode, or null if not found
   */
  private static ObjectNode retrieveAgentGroupRawNode(String configId, Long id) {
    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();
    debugMap.put("method", "retrieveAgentGroupRawNode");

    long startTime = System.nanoTime();

    try {
      String urlSuffix = "api/v2/groups/" + String.valueOf(id);
      int[] returnCode = new int[] { -1 };
      JsonNode jsonNode = executeMethod(debugMap, "retrieveAgentGroupRawNode", "GET", configId, urlSuffix,
          GrouperUtil.toSet(200, 404), returnCode, null, null, false, null);
      if (returnCode[0] == 404 || jsonNode == null) {
        return null;
      }
      JsonNode groupNode = GrouperUtil.jsonJacksonGetNode(jsonNode, "group");
      if (groupNode == null) {
        return null;
      }
      return groupNode.deepCopy();
    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      FreshAgentLog.freshserviceLog(debugMap, startTime);
    }
  }

  /**
   * Get a list of all Freshservice agent groups
   * @param configId the id of the external system
   * @return
   */
  public static List<FreshAgentGroup> retrieveAgentGroups(String configId) {
    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();
    debugMap.put("method", "retrieveAgentGroups");

    List<FreshAgentGroup> results = new ArrayList<FreshAgentGroup>();

    long startTime = System.nanoTime();

    try {

      boolean lastPage = false;
      int page = 1;

      while (!lastPage) {

        JsonNode jsonNode = executeMethod(debugMap, "retrieveAgentGroups", "GET", configId, "api/v2/groups",
            GrouperUtil.toSet(200), new int[] { -1 }, null, page, true, null);

        ArrayNode groupsArray = (ArrayNode) jsonNode.get("groups");

        for (int i = 0; i < (groupsArray == null ? 0 : groupsArray.size()); i++) {
          JsonNode groupNode = groupsArray.get(i);
          FreshAgentGroup grouperAgentGroup = FreshAgentGroup.fromJson(groupNode);
          results.add(grouperAgentGroup);
        }

        page++;

        if (groupsArray == null || groupsArray.size() < grouperLoaderConfig.propertyValueInt("grouper.wsBearerToken." + configId + ".pageSize", MAX_PAGE_SIZE)) {
          lastPage = true;
        }

      }

    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      FreshAgentLog.freshserviceLog(debugMap, startTime);
    }

    return results;
  }

  /**
   * Retrieve all agents from Freshservice
   * @param configId the id of the external system
   * @param includeInactiveAgents if true, include inactive (deactivated) agents
   *   in the results. If false, only active agents are returned.
   * @return a list of Freshservice agents
   */
  public static List<FreshAgentUser> retrieveAgentUsers(String configId, boolean includeInactiveAgents) {

    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();

    List<FreshAgentUser> results = new ArrayList<FreshAgentUser>();

    debugMap.put("method", "retrieveAgentUsers");

    long startTime = System.nanoTime();

    try {

      boolean lastPage = false;
      int page = 1;

      while (!lastPage) {

        JsonNode jsonNode = executeMethod(debugMap, "retrieveAgentUsers", "GET", configId, "api/v2/agents",
            GrouperUtil.toSet(200), new int[] { -1 }, null, page, true, null);

        ArrayNode agentUsersArray = (ArrayNode) jsonNode.get("agents");

        for (int i = 0; i < (agentUsersArray == null ? 0 : agentUsersArray.size()); i++) {
          JsonNode userNode = agentUsersArray.get(i);
          FreshAgentUser grouperAgentUser = FreshAgentUser.fromJson(userNode);
          // skip inactive agents unless caller explicitly wants them
          if (!includeInactiveAgents
              && (grouperAgentUser.getActive() == null || !grouperAgentUser.getActive())) {
            continue;
          }
          results.add(grouperAgentUser);
        }

        page++;

        if (agentUsersArray == null || agentUsersArray.size() < grouperLoaderConfig.propertyValueInt("grouper.wsBearerToken." + configId + ".pageSize", MAX_PAGE_SIZE)) {
          lastPage = true;
        }
      }

    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      FreshAgentLog.freshserviceLog(debugMap, startTime);
    }

    return results;
  }

  /**
   * Get a Freshservice agent by id
   * @param configId the id of the external system
   * @param id the id of the agent to be retrieved
   * @param includeInactiveAgents if true, return the agent even if inactive.
   *   If false, return null for inactive agents.
   * @return the agent, or null if not found (or inactive when not included)
   */
  public static FreshAgentUser retrieveAgentUserById(String configId, Long id, boolean includeInactiveAgents) {

    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();

    debugMap.put("method", "retrieveAgentUserById");

    long startTime = System.nanoTime();

    try {
      int[] returnCode = new int[] { -1 };

      String urlSuffix = "api/v2/agents/" + String.valueOf(id);
      JsonNode jsonNode = executeMethod(debugMap, "retrieveAgentUserById", "GET", configId, urlSuffix,
          GrouperUtil.toSet(200, 404), returnCode, null, null, false, null);

      if (returnCode[0] == 404) {
        return null;
      }

      JsonNode userNode = jsonNode.get("agent");
      if (userNode == null) {
        return null;
      }

      FreshAgentUser grouperAgentUser = FreshAgentUser.fromJson(userNode);

      // skip inactive agents unless caller explicitly wants them
      if (!includeInactiveAgents
          && (grouperAgentUser.getActive() == null || !grouperAgentUser.getActive())) {
        return null;
      }

      return grouperAgentUser;

    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      FreshAgentLog.freshserviceLog(debugMap, startTime);
    }

  }

  /**
   * Get a Freshservice agent by email address.
   * Uses the Freshservice email parameter:
   *   GET /api/v2/agents?email=jsmith@upenn.edu
   *
   * @param configId the id of the external system
   * @param email the email address of the agent to be retrieved
   * @param includeInactiveAgents if true, return the agent even if inactive.
   *   If false, return null for inactive agents.
   * @return the agent, or null if not found (or inactive when not included)
   */
  public static FreshAgentUser retrieveAgentUserByEmail(String configId, String email, boolean includeInactiveAgents) {

    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();

    debugMap.put("method", "retrieveAgentUserByEmail");

    long startTime = System.nanoTime();

    try {
      int[] returnCode = new int[] { -1 };

      // use the email= URL parameter instead of query=
      String urlSuffix = "api/v2/agents?email=" + GrouperUtil.escapeUrlEncode(email);
      JsonNode jsonNode = executeMethod(debugMap, "retrieveAgentUserByEmail", "GET", configId, urlSuffix,
          GrouperUtil.toSet(200), returnCode, null, null, false, null);

      if (jsonNode == null) {
        return null;
      }

      ArrayNode agentUserArray = (ArrayNode) jsonNode.get("agents");

      if (agentUserArray != null && agentUserArray.size() == 1) {
        JsonNode userNode = agentUserArray.get(0);
        FreshAgentUser grouperAgentUser = FreshAgentUser.fromJson(userNode);
        // skip inactive agents unless caller explicitly wants them
        if (!includeInactiveAgents
            && (grouperAgentUser.getActive() == null || !grouperAgentUser.getActive())) {
          return null;
        }
        return grouperAgentUser;
      }

      return null;

    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      FreshAgentLog.freshserviceLog(debugMap, startTime);
    }

  }

  /**
   * Retrieve an agent by a provisioning attribute name and value.
   *
   * If attributeName is "id" or "email", delegates to the existing lookup methods.
   * If attributeName is "externalId", searches by external_id.
   * If attributeName starts with "customField_", searches by that custom field name.
   * Otherwise throws an exception.
   *
   * The Freshservice API query format is:
   *   GET /api/v2/agents?query=attributeName:'value'   (for strings)
   *   GET /api/v2/agents?query=attributeName:value      (for numbers)
   *
   * @param configId the id of the external system
   * @param attributeName the provisioning attribute name (e.g. "id", "email", "externalId", "customField_pennkey")
   * @param attributeValue the value to search for. Must be String, Long, or Integer.
   *   String values are always quoted in the query (even if they contain digits).
   *   Long/Integer values are sent as bare numbers.
   * @return the agent if found, null if not found
   * @throws RuntimeException if multiple agents are found or attributeValue is an unsupported type
   */
  public static FreshAgentUser retrieveAgentUserByAttribute(String configId, String attributeName, Object attributeValue) {

    if (StringUtils.isBlank(attributeName)) {
      throw new RuntimeException("attributeName is required");
    }
    if (attributeValue == null) {
      return null;
    }

    // validate attributeValue type
    if (!(attributeValue instanceof String) && !(attributeValue instanceof Long) && !(attributeValue instanceof Integer)) {
      throw new RuntimeException("attributeValue must be String, Long, or Integer, but was: " + attributeValue.getClass().getName());
    }

    // delegate to existing methods for id and email
    if ("id".equals(attributeName)) {
      return retrieveAgentUserById(configId, GrouperUtil.longValue(attributeValue), false);
    }
    if ("email".equals(attributeName)) {
      return retrieveAgentUserByEmail(configId, GrouperUtil.stringValue(attributeValue), false);
    }

    // determine the Freshservice query attribute name
    String freshserviceAttributeName = null;
    if ("externalId".equals(attributeName)) {
      freshserviceAttributeName = "external_id";
    } else if (attributeName.startsWith(FreshAgentUser.CUSTOM_FIELD_ATTRIBUTE_PREFIX)) {
      freshserviceAttributeName = attributeName.substring(FreshAgentUser.CUSTOM_FIELD_ATTRIBUTE_PREFIX.length());
    } else {
      throw new RuntimeException("Unsupported attributeName for agent lookup: '" + attributeName
          + "'. Expected 'id', 'email', 'externalId', or 'customField_<name>'");
    }

    if (StringUtils.isBlank(freshserviceAttributeName)) {
      throw new RuntimeException("Could not determine Freshservice attribute name from: '" + attributeName + "'");
    }

    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();

    debugMap.put("method", "retrieveAgentUserByAttribute");
    debugMap.put("attributeName", attributeName);

    long startTime = System.nanoTime();

    try {
      int[] returnCode = new int[] { -1 };

      // build the query value: Long/Integer are numeric (no quotes), String always gets single quotes
      String queryValue;
      if (attributeValue instanceof Long || attributeValue instanceof Integer) {
        queryValue = freshserviceAttributeName + ":" + attributeValue;
      } else {
        queryValue = freshserviceAttributeName + ":'" + attributeValue + "'";
      }

      JsonNode jsonNode = executeMethod(debugMap, "retrieveAgentUserByAttribute", "GET", configId, "api/v2/agents",
          GrouperUtil.toSet(200), returnCode, null, null, false, queryValue);

      if (jsonNode == null) {
        return null;
      }

      ArrayNode agentUserArray = (ArrayNode) jsonNode.get("agents");

      if (agentUserArray == null || agentUserArray.size() == 0) {
        return null;
      }

      if (agentUserArray.size() == 1) {
        JsonNode userNode = agentUserArray.get(0);
        FreshAgentUser grouperAgentUser = FreshAgentUser.fromJson(userNode);
        return grouperAgentUser;
      }

      // multiple agents found - throw a descriptive exception with first 10k of json
      String jsonString = GrouperUtil.jsonJacksonToString(jsonNode);
      if (jsonString.length() > 10000) {
        jsonString = jsonString.substring(0, 10000);
      }
      throw new RuntimeException("Expected 0 or 1 agents for attribute '" + attributeName
          + "' = '" + attributeValue + "', but found " + agentUserArray.size()
          + ". First 10k of response: " + jsonString);

    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      FreshAgentLog.freshserviceLog(debugMap, startTime);
    }

  }

  /**
   * Add an agent to an agent group.
   *
   * Unlike requester groups (which expose a dedicated members sub-resource),
   * agent group membership is managed through the group's "members" array.
   * This method reads the group, adds the agent id to its members array if not
   * already present, and PUTs the group back.
   *
   * @param configId the id of the external system
   * @param groupId the id of the group gaining a member agent
   * @param userId the id of the new group member agent
   */
  public static void addGroupMembership(String configId, Long groupId, Long userId) {
    updateGroupMembershipInternal(configId, groupId, userId, true);
  }

  /**
   * Remove an agent from an agent group.
   *
   * Reads the group, removes the agent id from its members array if present,
   * and PUTs the group back.
   *
   * @param configId the id of the external system
   * @param groupId the id of the group losing a member agent
   * @param userId the id of the group member agent to remove
   */
  public static void removeGroupMembership(String configId, Long groupId, Long userId) {
    updateGroupMembershipInternal(configId, groupId, userId, false);
  }

  /**
   * Shared implementation to add/remove an agent to/from an agent group's members array.
   *
   * The PUT body is built from the writable-field whitelist plus the recomputed
   * members array, so read-only fields returned by the GET are never echoed back.
   */
  private static void updateGroupMembershipInternal(String configId, Long groupId, Long userId, boolean add) {
    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();

    debugMap.put("method", "updateGroupMembership");
    debugMap.put("add", add);

    long startTime = System.nanoTime();

    try {
      if (groupId == null) {
        throw new RuntimeException("groupId is null");
      }
      if (userId == null) {
        throw new RuntimeException("userId is null");
      }

      // read the current group (including its members array)
      ObjectNode groupNode = retrieveAgentGroupRawNode(configId, groupId);
      if (groupNode == null) {
        // group does not exist; nothing to do for removal, error for add
        if (add) {
          throw new RuntimeException("Cannot add membership to agent group that does not exist. id=" + groupId);
        }
        return;
      }

      // get the existing members array (list of agent ids)
      ArrayNode membersArray = (ArrayNode) GrouperUtil.jsonJacksonGetNode(groupNode, "members");
      if (membersArray == null) {
        membersArray = GrouperUtil.jsonJacksonArrayNode();
      }

      // build a fresh members array applying the add/remove
      ArrayNode newMembers = GrouperUtil.jsonJacksonArrayNode();
      boolean alreadyPresent = false;
      for (int i = 0; i < membersArray.size(); i++) {
        JsonNode memberNode = membersArray.get(i);
        if (memberNode == null || !memberNode.isNumber()) {
          continue;
        }
        long existingId = memberNode.longValue();
        if (existingId == userId.longValue()) {
          alreadyPresent = true;
          // when removing, skip adding it back
          if (!add) {
            continue;
          }
        }
        newMembers.add(existingId);
      }

      if (add && !alreadyPresent) {
        newMembers.add(userId.longValue());
      }

      // if nothing changed, avoid the PUT
      if (add && alreadyPresent) {
        return;
      }
      if (!add && !alreadyPresent) {
        return;
      }

      // build the PUT body from the writable whitelist, then add the members array.
      // members is writable on update but is not in WRITABLE_GROUP_FIELDS because
      // it is not a managed group attribute - it is set explicitly here.
      ObjectNode jsonToSend = buildWritableGroupNode(groupNode);
      jsonToSend.set("members", newMembers);

      String jsonStringToSend = GrouperUtil.jsonJacksonToString(jsonToSend);

      executeMethod(debugMap, "updateGroupMembership", "PUT", configId, "api/v2/groups/" + String.valueOf(groupId),
          GrouperUtil.toSet(200, 201), new int[] { -1 }, jsonStringToSend, null, false, null);

    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      FreshAgentLog.freshserviceLog(debugMap, startTime);
    }
  }

  /**
   * Retrieve the members of an agent group.
   *
   * Agent group membership lives in the group's "members" array (a list of agent
   * ids). This method reads the group, then resolves each member id to a
   * FreshAgentUser.
   *
   * @param configId the id of the external system
   * @param groupId the id of the group to get members from
   * @return list of agents who are members of the group
   */
  public static List<FreshAgentUser> retrieveMembershipsByGroup(String configId, Long groupId) {

    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();

    List<FreshAgentUser> results = new ArrayList<FreshAgentUser>();

    debugMap.put("method", "retrieveMembershipsByGroup");

    long startTime = System.nanoTime();

    try {

      ObjectNode groupNode = retrieveAgentGroupRawNode(configId, groupId);
      if (groupNode == null) {
        return results;
      }

      ArrayNode membersArray = (ArrayNode) GrouperUtil.jsonJacksonGetNode(groupNode, "members");
      if (membersArray == null) {
        return results;
      }

      for (int i = 0; i < membersArray.size(); i++) {
        JsonNode memberNode = membersArray.get(i);
        if (memberNode == null || !memberNode.isNumber()) {
          continue;
        }
        // represent each member as a lightweight FreshAgentUser carrying just the id.
        // The TargetDao only needs the id to build the ProvisioningMembership.
        FreshAgentUser grouperAgentUser = new FreshAgentUser();
        grouperAgentUser.setId(memberNode.longValue());
        results.add(grouperAgentUser);
      }

    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      FreshAgentLog.freshserviceLog(debugMap, startTime);
    }

    return results;
  }

  /**
   * Deactivate (delete) an agent in Freshservice.
   * Endpoint: DELETE /api/v2/agents/{id}
   * Expected response: 204 No Content (sometimes 200/404 depending on Freshservice behavior)
   * @param configId the id of the external system
   * @param userId the agent id
   */
  public static void deactivateAgentUser(String configId, Long userId) {
    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();

    debugMap.put("method", "deactivateAgentUser");

    long startTime = System.nanoTime();

    try {
      if (userId == null) {
        throw new RuntimeException("userId is null");
      }
      String id = String.valueOf(userId);

      executeMethod(debugMap, "deactivateAgentUser", "DELETE", configId, "api/v2/agents/" + id,
          GrouperUtil.toSet(200, 204, 404), new int[] { -1 }, null, null, false, null);

    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      FreshAgentLog.freshserviceLog(debugMap, startTime);
    }
  }

  /**
   * Reactivate a deactivated agent in Freshservice, restoring them as full-time.
   *
   * Convenience overload equivalent to
   * {@link #reactivateAgentUser(String, Long, boolean)} with
   * reactivateAsFullTime = true. The Freshservice /reactivate endpoint always
   * restores an agent as occasional regardless of their prior license type, so
   * this follows the reactivate with a PUT setting occasional=false.
   *
   * @param configId the id of the external system
   * @param userId the agent id
   */
  public static void reactivateAgentUser(String configId, Long userId) {
    reactivateAgentUser(configId, userId, true);
  }

  /**
   * Reactivate a deactivated agent in Freshservice.
   * Endpoint: PUT /api/v2/agents/{id}/reactivate
   * Returns 200 if successful.  400 with body if already active.
   *
   * Note: the reactivate endpoint restores the agent as OCCASIONAL regardless of
   * their prior license type. When reactivateAsFullTime is true, this follows the
   * reactivate with a PUT /api/v2/agents/{id} setting occasional=false to restore
   * full-time. Setting a full-time agent consumes a licensed seat, so callers
   * that want the agent to remain occasional (day-pass) should pass false.
   *
   * The follow-up PUT only runs when the reactivate call actually reactivated the
   * agent (HTTP 200). A 400 means the agent was already active, in which case the
   * license type is left untouched.
   *
   * @param configId the id of the external system
   * @param userId the agent id
   * @param reactivateAsFullTime true to restore the agent as full-time after
   *   reactivation, false to leave them occasional
   */
  public static void reactivateAgentUser(String configId, Long userId, boolean reactivateAsFullTime) {
    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();

    debugMap.put("method", "reactivateAgentUser");
    debugMap.put("reactivateAsFullTime", reactivateAsFullTime);

    long startTime = System.nanoTime();

    try {
      if (userId == null) {
        throw new RuntimeException("userId is null");
      }
      String id = String.valueOf(userId);

      int[] returnCode = new int[] { -1 };
      executeMethod(debugMap, "reactivateAgentUser", "PUT", configId, "api/v2/agents/" + id + "/reactivate",
          GrouperUtil.toSet(200, 400), returnCode, null, null, false, null);

      // The reactivate endpoint always restores the agent as occasional. When
      // configured to restore full-time, follow up with a PUT setting
      // occasional=false. Only do this on a genuine reactivation (200); a 400
      // means the agent was already active, so leave the license type alone.
      if (reactivateAsFullTime && returnCode[0] == 200) {
        ObjectNode fullTimeBody = GrouperUtil.jsonJacksonNode();
        fullTimeBody.put("occasional", false);
        String fullTimeJson = GrouperUtil.jsonJacksonToString(fullTimeBody);

        executeMethod(debugMap, "reactivateAgentUser", "PUT", configId, "api/v2/agents/" + id,
            GrouperUtil.toSet(200, 201), new int[] { -1 }, fullTimeJson, null, false, null);
      }

    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      FreshAgentLog.freshserviceLog(debugMap, startTime);
    }
  }

  /**
   * Permanently delete (forget) an agent from Freshservice.
   * This removes the agent entirely, unlike deactivate which just sets active=false.
   * Endpoint: DELETE /api/v2/agents/{id}/forget
   * Expected response: 204 No Content, 404 if already deleted
   * @param configId the id of the external system
   * @param userId the agent id
   */
  public static void forgetAgentUser(String configId, Long userId) {
    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();

    debugMap.put("method", "forgetAgentUser");

    long startTime = System.nanoTime();

    try {
      if (userId == null) {
        throw new RuntimeException("userId is null");
      }
      String id = String.valueOf(userId);

      executeMethod(debugMap, "forgetAgentUser", "DELETE", configId, "api/v2/agents/" + id + "/forget",
          GrouperUtil.toSet(204, 404), new int[] { -1 }, null, null, false, null);

    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      FreshAgentLog.freshserviceLog(debugMap, startTime);
    }
  }

}
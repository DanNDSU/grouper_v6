package edu.internet2.middleware.grouper.app.emma;

import java.util.ArrayList;
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
import edu.internet2.middleware.grouper.misc.GrouperStartup;
import edu.internet2.middleware.grouper.util.GrouperHttpClient;
import edu.internet2.middleware.grouper.util.GrouperUtil;
import edu.internet2.middleware.grouperClient.util.GrouperClientUtils;

/**
 * All HTTP interactions with the Emma external API (https://api.e2ma.net/).
 *
 * Emma authenticates with HTTP Basic auth using the public API key as the
 * username and the private API key as the password. This is configured through
 * a WsBearerToken external system with basicAuthUser / basicAuthPassword, the
 * same mechanism the Freshservice provisioner uses.
 *
 * The Emma endpoint (grouper.wsBearerToken.<configId>.endpoint) must already
 * include the account id in its path, e.g. https://api.e2ma.net/123456
 *
 * Emma paginates with the start / end query parameters (max page size 500),
 * rather than page / per_page.
 */
public class EmmaApiCommands {

  /** Emma's maximum page size is 500. */
  private static final int MAX_PAGE_SIZE = 500;

  public static final Set<String> doNotLogParameters = GrouperUtil.toSet("private_api_key");
  public static final Set<String> doNotLogHeaders = GrouperUtil.toSet("authorization");

  public static GrouperLoaderConfig grouperLoaderConfig = GrouperLoaderConfig.retrieveConfig();

  public static void main(String[] args) {

    GrouperStartup.startup();

    try {
      String configId = "emma";
      
      EmmaGroup group = new EmmaGroup();
      group.setName("Test API Group");      
      
      EmmaGroup createdGroup = createGroup(configId, group);
      
      System.out.println(createdGroup.getName() + " " + createdGroup.getId());

      System.out.println("done " + configId);

    } catch (Exception e) {
      System.out.println("Error: " + GrouperClientUtils.getFullStackTrace(e));
    }
    System.exit(0);
  }

  /**
   * configured page size for this Emma config, capped at MAX_PAGE_SIZE
   */
  private static int pageSize(String configId) {
    int pageSize = grouperLoaderConfig.propertyValueInt("grouper.wsBearerToken." + configId + ".pageSize", MAX_PAGE_SIZE);
    return Math.min(pageSize, MAX_PAGE_SIZE);
  }

  /**
   * Execute an HTTP call against the Emma API.
   *
   * @param debugMap map for debug logging
   * @param debugLabel label for stats
   * @param httpMethodName GET/POST/PUT/DELETE
   * @param configId external system config id
   * @param urlSuffix path appended to the endpoint (which already includes the account id)
   * @param allowedReturnCodes set of acceptable HTTP status codes
   * @param returnCode single-element array to receive the actual status code
   * @param bodyParam JSON request body, or null
   * @param start pagination start index (inclusive), or null
   * @param end pagination end index (exclusive), or null
   * @return the parsed JSON response, or null if the body was blank
   */
  private static JsonNode executeMethod(Map<String, Object> debugMap, String debugLabel,
      String httpMethodName, String configId, String urlSuffix, Set<Integer> allowedReturnCodes,
      int[] returnCode, String bodyParam, Integer start, Integer end) {

    GrouperHttpClient grouperHttpClient = new GrouperHttpClient();

    grouperHttpClient.assignDoNotLogHeaders(doNotLogHeaders).assignDoNotLogParameters(doNotLogParameters);

    WsBearerTokenExternalSystem.attachAuthenticationToHttpClient(grouperHttpClient, configId, grouperLoaderConfig, debugMap);

    String url = grouperLoaderConfig.propertyValueStringRequired("grouper.wsBearerToken." + configId + ".endpoint");

    if (url.endsWith("/")) {
      url = url.substring(0, url.length() - 1);
    }
    if (!urlSuffix.startsWith("http")) {
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

    if (start != null && end != null) {
      grouperHttpClient.addUrlParameter("start", Integer.toString(start));
      grouperHttpClient.addUrlParameter("end", Integer.toString(end));
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
      return GrouperUtil.jsonJacksonNode(json);
    } catch (Exception e) {
      throw new RuntimeException("Error parsing response: '" + json + "'", e);
    }
  }

  // ==================== Group methods ====================

  /**
   * Create a member group in Emma.
   * POST /#account_id/groups with body { "groups": [ { "group_name": ... } ] }
   * returns [ { "member_group_id": ..., "group_name": ... } ]
   * @param configId the id of the external system
   * @param emmaGroup the group to create
   * @return the created group with its assigned id
   */
  public static EmmaGroup createGroup(String configId, EmmaGroup emmaGroup) {
    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();
    debugMap.put("method", "createGroup");
    long startTime = System.nanoTime();

    try {
      // wrap the single group in the { "groups": [ ... ] } envelope
      ObjectNode groupNode = emmaGroup.toJson(null);
      ObjectNode body = GrouperUtil.jsonJacksonNode();
      ArrayNode groupsArray = GrouperUtil.jsonJacksonArrayNode();
      groupsArray.add(groupNode);
      body.set("groups", groupsArray);

      String jsonStringToSend = GrouperUtil.jsonJacksonToString(body);

      int[] returnCode = new int[] { -1 };
      JsonNode jsonNode = executeMethod(debugMap, "createGroup", "POST", configId, "groups",
          GrouperUtil.toSet(200, 201), returnCode, jsonStringToSend, null, null);

      // response is an array of created groups
      if (jsonNode == null || !jsonNode.isArray() || jsonNode.size() == 0) {
        throw new RuntimeException("Unexpected response creating Emma group: " + jsonNode);
      }

      return EmmaGroup.fromJson(jsonNode.get(0));

    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      EmmaLog.emmaLog(debugMap, startTime);
    }
  }

  /**
   * Update a member group name in Emma.
   * PUT /#account_id/groups/#member_group_id with body { "group_name": ... }
   * @param configId the id of the external system
   * @param emmaGroup the group to update (must have id set)
   */
  public static void updateGroup(String configId, EmmaGroup emmaGroup) {
    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();
    debugMap.put("method", "updateGroup");
    long startTime = System.nanoTime();

    try {
      if (emmaGroup == null) {
        throw new RuntimeException("emmaGroup is null");
      }
      Long groupId = emmaGroup.getId();
      if (groupId == null || groupId == 0L) {
        throw new RuntimeException("groupId is null or 0 (unset)");
      }

      ObjectNode body = GrouperUtil.jsonJacksonNode();
      body.put("group_name", emmaGroup.getName());
      String jsonStringToSend = GrouperUtil.jsonJacksonToString(body);

      executeMethod(debugMap, "updateGroup", "PUT", configId, "groups/" + String.valueOf(groupId),
          GrouperUtil.toSet(200), new int[] { -1 }, jsonStringToSend, null, null);

    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      EmmaLog.emmaLog(debugMap, startTime);
    }
  }

  /**
   * Delete a member group in Emma.
   * DELETE /#account_id/groups/#member_group_id
   * @param configId the id of the external system
   * @param groupId the id of the group to delete
   */
  public static void deleteGroup(String configId, Long groupId) {
    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();
    debugMap.put("method", "deleteGroup");
    long startTime = System.nanoTime();

    try {
      if (groupId == null) {
        throw new RuntimeException("groupId is null");
      }
      executeMethod(debugMap, "deleteGroup", "DELETE", configId, "groups/" + String.valueOf(groupId),
          GrouperUtil.toSet(200, 204, 404), new int[] { -1 }, null, null, null);

    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      EmmaLog.emmaLog(debugMap, startTime);
    }
  }

  /**
   * Get a single Emma member group by id.
   * GET /#account_id/groups/#member_group_id
   * @param configId the id of the external system
   * @param id the group id
   * @return the group, or null if it does not exist
   */
  public static EmmaGroup retrieveGroup(String configId, Long id) {
    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();
    debugMap.put("method", "retrieveGroup");
    long startTime = System.nanoTime();

    try {
      int[] returnCode = new int[] { -1 };
      JsonNode jsonNode = executeMethod(debugMap, "retrieveGroup", "GET", configId, "groups/" + String.valueOf(id),
          GrouperUtil.toSet(200, 404), returnCode, null, null, null);

      if (returnCode[0] == 404 || jsonNode == null) {
        return null;
      }
      return EmmaGroup.fromJson(jsonNode);

    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      EmmaLog.emmaLog(debugMap, startTime);
    }
  }

  /**
   * Get all Emma member groups.
   * GET /#account_id/groups
   * @param configId the id of the external system
   * @return list of all groups
   */
  public static List<EmmaGroup> retrieveGroups(String configId) {
    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();
    debugMap.put("method", "retrieveGroups");
    List<EmmaGroup> results = new ArrayList<EmmaGroup>();
    long startTime = System.nanoTime();

    try {
      int size = pageSize(configId);
      int start = 0;
      boolean lastPage = false;

      while (!lastPage) {
        int end = start + size;
        JsonNode jsonNode = executeMethod(debugMap, "retrieveGroups", "GET", configId, "groups",
            GrouperUtil.toSet(200), new int[] { -1 }, null, start, end);

        int returned = 0;
        if (jsonNode != null && jsonNode.isArray()) {
          returned = jsonNode.size();
          for (int i = 0; i < returned; i++) {
            results.add(EmmaGroup.fromJson(jsonNode.get(i)));
          }
        }

        if (returned < size) {
          lastPage = true;
        }
        start += size;
      }

    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      EmmaLog.emmaLog(debugMap, startTime);
    }

    return results;
  }

  // ==================== Member methods ====================

  /**
   * Add or update a single member in Emma.
   * POST /#account_id/members/add with body { "email": ..., "fields": { ... } }
   * returns { "status": ..., "added": ..., "member_id": ... }
   *
   * Emma keys members on email, so this endpoint both creates new members and
   * updates existing ones; it always returns the member_id.
   *
   * @param configId the id of the external system
   * @param emmaMember the member to add or update
   * @return the member with its id populated from the response
   */
  public static EmmaMember addMember(String configId, EmmaMember emmaMember) {
    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();
    debugMap.put("method", "addMember");
    long startTime = System.nanoTime();

    try {
      if (emmaMember == null || StringUtils.isBlank(emmaMember.getEmail())) {
        throw new RuntimeException("email is required to add an Emma member");
      }

      ObjectNode jsonToSend = emmaMember.toJson(null);
      String jsonStringToSend = GrouperUtil.jsonJacksonToString(jsonToSend);

      int[] returnCode = new int[] { -1 };
      JsonNode jsonNode = executeMethod(debugMap, "addMember", "POST", configId, "members/add",
          GrouperUtil.toSet(200, 201), returnCode, jsonStringToSend, null, null);

      Long memberId = GrouperUtil.jsonJacksonGetLong(jsonNode, "member_id");
      emmaMember.setId(memberId);
      emmaMember.setMemberStatusId(GrouperUtil.jsonJacksonGetString(jsonNode, "status"));
      return emmaMember;

    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      EmmaLog.emmaLog(debugMap, startTime);
    }
  }

  /**
   * Update a single member in Emma by id.
   * PUT /#account_id/members/#member_id with body { "email": ..., "fields": { ... } }
   * Only the fields named in fieldsToUpdate are sent.
   *
   * @param configId the id of the external system
   * @param emmaMember the member containing the new values (must have id set)
   * @param fieldsToUpdate the set of provisioning field names to update
   */
  public static void updateMember(String configId, EmmaMember emmaMember, Set<String> fieldsToUpdate) {
    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();
    debugMap.put("method", "updateMember");
    long startTime = System.nanoTime();

    try {
      if (emmaMember == null) {
        throw new RuntimeException("emmaMember is null");
      }
      Long memberId = emmaMember.getId();
      if (memberId == null || memberId == 0L) {
        throw new RuntimeException("memberId is null or 0 (unset)");
      }

      ObjectNode jsonToSend = emmaMember.toJson(fieldsToUpdate);
      String jsonStringToSend = GrouperUtil.jsonJacksonToString(jsonToSend);

      executeMethod(debugMap, "updateMember", "PUT", configId, "members/" + String.valueOf(memberId),
          GrouperUtil.toSet(200), new int[] { -1 }, jsonStringToSend, null, null);

    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      EmmaLog.emmaLog(debugMap, startTime);
    }
  }

  /**
   * Archive (delete) a single member in Emma.
   * DELETE /#account_id/members/#member_id
   * @param configId the id of the external system
   * @param memberId the id of the member to delete
   */
  public static void deleteMember(String configId, Long memberId) {
    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();
    debugMap.put("method", "deleteMember");
    long startTime = System.nanoTime();

    try {
      if (memberId == null) {
        throw new RuntimeException("memberId is null");
      }
      executeMethod(debugMap, "deleteMember", "DELETE", configId, "members/" + String.valueOf(memberId),
          GrouperUtil.toSet(200, 204, 404), new int[] { -1 }, null, null, null);

    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      EmmaLog.emmaLog(debugMap, startTime);
    }
  }

  /**
   * Retrieve all members in the account.
   * GET /#account_id/members
   * @param configId the id of the external system
   * @return list of all members
   */
  public static List<EmmaMember> retrieveMembers(String configId) {
    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();
    debugMap.put("method", "retrieveMembers");
    List<EmmaMember> results = new ArrayList<EmmaMember>();
    long startTime = System.nanoTime();

    try {
      int size = pageSize(configId);
      int start = 0;
      boolean lastPage = false;

      while (!lastPage) {
        int end = start + size;
        JsonNode jsonNode = executeMethod(debugMap, "retrieveMembers", "GET", configId, "members",
            GrouperUtil.toSet(200), new int[] { -1 }, null, start, end);

        int returned = 0;
        if (jsonNode != null && jsonNode.isArray()) {
          returned = jsonNode.size();
          for (int i = 0; i < returned; i++) {
            results.add(EmmaMember.fromJson(jsonNode.get(i)));
          }
        }

        if (returned < size) {
          lastPage = true;
        }
        start += size;
      }

    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      EmmaLog.emmaLog(debugMap, startTime);
    }

    return results;
  }

  /**
   * Retrieve a single Emma member by id.
   * GET /#account_id/members/#member_id
   * @param configId the id of the external system
   * @param id the member id
   * @return the member, or null if not found
   */
  public static EmmaMember retrieveMemberById(String configId, Long id) {
    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();
    debugMap.put("method", "retrieveMemberById");
    long startTime = System.nanoTime();

    try {
      int[] returnCode = new int[] { -1 };
      JsonNode jsonNode = executeMethod(debugMap, "retrieveMemberById", "GET", configId, "members/" + String.valueOf(id),
          GrouperUtil.toSet(200, 404), returnCode, null, null, null);

      if (returnCode[0] == 404 || jsonNode == null) {
        return null;
      }
      return EmmaMember.fromJson(jsonNode);

    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      EmmaLog.emmaLog(debugMap, startTime);
    }
  }

  /**
   * Retrieve a single Emma member by email address.
   * GET /#account_id/members/email/:email
   * @param configId the id of the external system
   * @param email the email address
   * @return the member, or null if not found
   */
  public static EmmaMember retrieveMemberByEmail(String configId, String email) {
    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();
    debugMap.put("method", "retrieveMemberByEmail");
    long startTime = System.nanoTime();

    try {
      if (StringUtils.isBlank(email)) {
        return null;
      }
      int[] returnCode = new int[] { -1 };
      String urlSuffix = "members/email/" + GrouperUtil.escapeUrlEncode(email);
      JsonNode jsonNode = executeMethod(debugMap, "retrieveMemberByEmail", "GET", configId, urlSuffix,
          GrouperUtil.toSet(200, 404), returnCode, null, null, null);

      if (returnCode[0] == 404 || jsonNode == null) {
        return null;
      }
      return EmmaMember.fromJson(jsonNode);

    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      EmmaLog.emmaLog(debugMap, startTime);
    }
  }

  /**
   * Retrieve a member by a provisioning attribute name and value.
   * Supports "id" and "email".
   * @param configId the id of the external system
   * @param attributeName "id" or "email"
   * @param attributeValue the value to search for
   * @return the member if found, else null
   */
  public static EmmaMember retrieveMemberByAttribute(String configId, String attributeName, Object attributeValue) {
    if (StringUtils.isBlank(attributeName)) {
      throw new RuntimeException("attributeName is required");
    }
    if (attributeValue == null) {
      return null;
    }

    if ("id".equals(attributeName)) {
      return retrieveMemberById(configId, GrouperUtil.longValue(attributeValue));
    }
    if ("email".equals(attributeName)) {
      return retrieveMemberByEmail(configId, GrouperUtil.stringValue(attributeValue));
    }
    throw new RuntimeException("Unsupported attributeName for member lookup: '" + attributeName
        + "'. Expected 'id' or 'email'");
  }

  // ==================== Membership methods ====================

  /**
   * Add a member to a group.
   * PUT /#account_id/groups/#member_group_id/members with body { "member_ids": [ ... ] }
   * @param configId the id of the external system
   * @param groupId the group gaining a member
   * @param memberId the member to add
   */
  public static void addGroupMembership(String configId, Long groupId, Long memberId) {
    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();
    debugMap.put("method", "addGroupMembership");
    long startTime = System.nanoTime();

    try {
      if (groupId == null) {
        throw new RuntimeException("groupId is null");
      }
      if (memberId == null) {
        throw new RuntimeException("memberId is null");
      }

      ObjectNode body = GrouperUtil.jsonJacksonNode();
      ArrayNode memberIds = GrouperUtil.jsonJacksonArrayNode();
      memberIds.add(memberId.longValue());
      body.set("member_ids", memberIds);
      String jsonStringToSend = GrouperUtil.jsonJacksonToString(body);

      executeMethod(debugMap, "addGroupMembership", "PUT", configId,
          "groups/" + String.valueOf(groupId) + "/members",
          GrouperUtil.toSet(200), new int[] { -1 }, jsonStringToSend, null, null);

    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      EmmaLog.emmaLog(debugMap, startTime);
    }
  }

  /**
   * Remove a member from a group.
   * PUT /#account_id/groups/#member_group_id/members/remove with body { "member_ids": [ ... ] }
   * @param configId the id of the external system
   * @param groupId the group losing a member
   * @param memberId the member to remove
   */
  public static void removeGroupMembership(String configId, Long groupId, Long memberId) {
    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();
    debugMap.put("method", "removeGroupMembership");
    long startTime = System.nanoTime();

    try {
      if (groupId == null) {
        throw new RuntimeException("groupId is null");
      }
      if (memberId == null) {
        throw new RuntimeException("memberId is null");
      }

      ObjectNode body = GrouperUtil.jsonJacksonNode();
      ArrayNode memberIds = GrouperUtil.jsonJacksonArrayNode();
      memberIds.add(memberId.longValue());
      body.set("member_ids", memberIds);
      String jsonStringToSend = GrouperUtil.jsonJacksonToString(body);

      executeMethod(debugMap, "removeGroupMembership", "PUT", configId,
          "groups/" + String.valueOf(groupId) + "/members/remove",
          GrouperUtil.toSet(200), new int[] { -1 }, jsonStringToSend, null, null);

    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      EmmaLog.emmaLog(debugMap, startTime);
    }
  }

  /**
   * Retrieve the members of a group.
   * GET /#account_id/groups/#member_group_id/members
   * @param configId the id of the external system
   * @param groupId the group to get members of
   * @return list of members in the group
   */
  public static List<EmmaMember> retrieveMembershipsByGroup(String configId, Long groupId) {
    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();
    debugMap.put("method", "retrieveMembershipsByGroup");
    List<EmmaMember> results = new ArrayList<EmmaMember>();
    long startTime = System.nanoTime();

    try {
      int size = pageSize(configId);
      int start = 0;
      boolean lastPage = false;

      while (!lastPage) {
        int end = start + size;
        JsonNode jsonNode = executeMethod(debugMap, "retrieveMembershipsByGroup", "GET", configId,
            "groups/" + String.valueOf(groupId) + "/members",
            GrouperUtil.toSet(200), new int[] { -1 }, null, start, end);

        int returned = 0;
        if (jsonNode != null && jsonNode.isArray()) {
          returned = jsonNode.size();
          for (int i = 0; i < returned; i++) {
            results.add(EmmaMember.fromJson(jsonNode.get(i)));
          }
        }

        if (returned < size) {
          lastPage = true;
        }
        start += size;
      }

    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      EmmaLog.emmaLog(debugMap, startTime);
    }

    return results;
  }

}

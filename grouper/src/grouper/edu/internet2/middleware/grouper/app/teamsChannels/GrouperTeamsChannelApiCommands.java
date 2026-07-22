package edu.internet2.middleware.grouper.app.teamsChannels;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.internet2.middleware.grouper.app.azure.AzureGrouperExternalSystem;
import edu.internet2.middleware.grouper.app.loader.GrouperLoaderConfig;
import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioner;
import edu.internet2.middleware.grouper.util.GrouperHttpClient;
import edu.internet2.middleware.grouper.util.GrouperUtil;
import edu.internet2.middleware.grouperClient.collections.MultiKey;
import edu.internet2.middleware.grouperClient.util.GrouperClientUtils;

/**
 * This class interacts with the Microsoft Graph API for Teams channels and
 * channel memberships.
 *
 * Auth and the resource endpoint are taken from the same
 * grouper.azureConnector.&lt;configId&gt;.* external system used by the Azure
 * provisioner - the bearer token, resource endpoint and proxy settings are all
 * reused via AzureGrouperExternalSystem.
 *
 * Channel endpoints are always nested under a team:
 *   GET    /teams/{teamId}/channels
 *   POST   /teams/{teamId}/channels
 *   GET    /teams/{teamId}/channels/{channelId}
 *   PATCH  /teams/{teamId}/channels/{channelId}
 *   DELETE /teams/{teamId}/channels/{channelId}
 *   GET    /teams/{teamId}/channels/{channelId}/members
 *   POST   /teams/{teamId}/channels/{channelId}/members
 *   DELETE /teams/{teamId}/channels/{channelId}/members/{membershipId}
 *
 * Modeled on GrouperAzureApiCommands, but the $batch machinery is dropped in
 * favor of straightforward per-object calls: channel and membership mutations
 * are far lower volume than directory group/user syncs and the endpoints are
 * team-scoped, which does not batch as cleanly.
 */
public class GrouperTeamsChannelApiCommands {

  /** logger */
  private static final Log LOG = GrouperUtil.getLog(GrouperTeamsChannelApiCommands.class);

  private static final int DEFAULT_THROTTLE_SECONDS = 155;

  // ==================================================================
  // low-level http helpers (mirrors GrouperAzureApiCommands)
  // ==================================================================

  private static JsonNode executeGetMethod(Map<String, Object> debugMap, String debugLabel, String configId,
      String urlSuffix, int[] returnCode) {
    return executeMethod(debugMap, debugLabel, "GET", configId, urlSuffix,
        GrouperUtil.toSet(200, 404, 429), returnCode, null);
  }

  private static JsonNode executeMethod(Map<String, Object> debugMap, String debugLabel,
      String httpMethodName, String configId,
      String urlSuffix, Set<Integer> allowedReturnCodes, int[] returnCode, String body) {

    GrouperHttpClient grouperHttpCall = new GrouperHttpClient();

    String bearerToken = AzureGrouperExternalSystem.retrieveBearerTokenForAzureConfigId(debugMap, configId);
    String graphEndpoint = GrouperLoaderConfig.retrieveConfig()
        .propertyValueStringRequired("grouper.azureConnector." + configId + ".resourceEndpoint");
    String url = graphEndpoint;
    if (url.endsWith("/")) {
      url = url.substring(0, url.length() - 1);
    }
    // in a nextLink, url is specified, so it might not have a prefix of the resourceEndpoint
    if (!urlSuffix.startsWith("http")) {
      url += (urlSuffix.startsWith("/") ? "" : "/") + urlSuffix;
    } else {
      url = urlSuffix;
    }
    debugMap.put("url", url);

    grouperHttpCall.assignUrl(url);
    grouperHttpCall.assignGrouperHttpMethod(httpMethodName);

    String proxyUrl = GrouperLoaderConfig.retrieveConfig().propertyValueString("grouper.azureConnector." + configId + ".proxyUrl");
    String proxyType = GrouperLoaderConfig.retrieveConfig().propertyValueString("grouper.azureConnector." + configId + ".proxyType");

    grouperHttpCall.assignProxyUrl(proxyUrl);
    grouperHttpCall.assignProxyType(proxyType);

    grouperHttpCall.addHeader("Content-Type", "application/json");
    grouperHttpCall.addHeader("Authorization", "Bearer " + bearerToken);
    grouperHttpCall.assignBody(body);
    long httpCallStartMillis = System.currentTimeMillis();
    try {
      grouperHttpCall.executeRequest();
    } finally {
      GrouperProvisioner.incrementCommandsCallsStats(debugLabel, 1,
          System.currentTimeMillis() - httpCallStartMillis);
    }

    int code = -1;
    String json = null;

    try {
      code = grouperHttpCall.getResponseCode();
      returnCode[0] = code;
      json = grouperHttpCall.getResponseBody();
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

  private static String debugLabel(Map<String, Object> debugMap, String fallback) {
    String debugLabel = GrouperUtil.stringValue(debugMap == null ? null : debugMap.get("method"));
    if (StringUtils.isBlank(debugLabel)) {
      return fallback;
    }
    return debugLabel;
  }

  private static void throttleSleep(Map<String, Object> debugMap, int secondsToSleep) {
    if (secondsToSleep < 0) {
      secondsToSleep = DEFAULT_THROTTLE_SECONDS;
    }
    GrouperUtil.sleep(secondsToSleep * 1000L);
    GrouperUtil.mapAddValue(debugMap, "teamsThrottleSleepSeconds", secondsToSleep);
    GrouperUtil.mapAddValue(debugMap, "teamsThrottleCount", 1);
    if (GrouperProvisioner.retrieveCurrentGrouperProvisioner() != null) {
      GrouperUtil.mapAddValue(GrouperProvisioner.retrieveCurrentGrouperProvisioner().getDebugMap(), "teamsThrottleSleepSeconds", secondsToSleep);
      GrouperUtil.mapAddValue(GrouperProvisioner.retrieveCurrentGrouperProvisioner().getDebugMap(), "teamsThrottleCount", 1);
    }
  }

  // ==================================================================
  // channel CRUD
  // ==================================================================

  /**
   * retrieve all channels across all teams that Grouper knows about.  Teams
   * cannot be enumerated cheaply from the channel endpoint (channels are
   * team-scoped), so the caller supplies the set of team ids to walk.
   *
   * @param configId
   * @param teamIds the parent team ids to enumerate channels for
   * @param extensionAttributes extra $select attributes
   * @return the channels
   */
  public static List<GrouperTeamsChannel> retrieveTeamsChannels(String configId, Set<String> teamIds,
      Set<String> extensionAttributes) {

    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();
    debugMap.put("method", "retrieveTeamsChannels");
    long startTime = System.nanoTime();

    List<GrouperTeamsChannel> results = new ArrayList<GrouperTeamsChannel>();

    try {
      for (String teamId : GrouperUtil.nonNull(teamIds)) {
        retrieveChannelsForTeam(configId, debugMap, teamId, extensionAttributes, results);
      }
      debugMap.put("size", GrouperClientUtils.length(results));
      return results;
    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      GrouperTeamsChannelLog.teamsLog(debugMap, startTime);
    }
  }

  private static void retrieveChannelsForTeam(String configId, Map<String, Object> debugMap, String teamId,
      Set<String> extensionAttributes, List<GrouperTeamsChannel> results) {

    String select = GrouperTeamsChannel.fieldsToSelect;
    if (extensionAttributes != null && extensionAttributes.size() > 0) {
      select = select + "," + StringUtils.join(extensionAttributes, ",");
    }

    String nextLink = "/teams/" + GrouperUtil.escapeUrlEncode(teamId) + "/channels?$select=" + select;

    int maxPages = 10000;
    for (int j = 0; j < maxPages; j++) {
      int[] returnCode = new int[] { -1 };
      JsonNode jsonNode = executeGetMethod(debugMap, debugLabel(debugMap, "retrieveTeamsChannels"), configId, nextLink, returnCode);

      if (returnCode[0] == 429) {
        throttleSleep(debugMap, DEFAULT_THROTTLE_SECONDS);
        continue;
      }

      if (returnCode[0] == 404 || jsonNode == null) {
        break;
      }

      ArrayNode channelsArray = (ArrayNode) GrouperUtil.jsonJacksonGetNode(jsonNode, "value");
      for (int i = 0; i < (channelsArray == null ? 0 : channelsArray.size()); i++) {
        JsonNode channelNode = channelsArray.get(i);
        GrouperTeamsChannel grouperTeamsChannel = GrouperTeamsChannel.fromJson(channelNode);
        if (grouperTeamsChannel != null) {
          grouperTeamsChannel.setTeamId(teamId);
          results.add(grouperTeamsChannel);
        }
      }

      nextLink = GrouperUtil.jsonJacksonGetString(jsonNode, "@odata.nextLink");
      if (StringUtils.isBlank(nextLink)) {
        break;
      }
    }
  }

  /**
   * retrieve specific channels by id (thread id) within their teams.
   * @param configId
   * @param teamIdChannelIds pairs of (teamId, channelId)
   * @param extensionAttributes
   * @return the channels found
   */
  public static List<GrouperTeamsChannel> retrieveTeamsChannelsByIds(String configId,
      List<MultiKey> teamIdChannelIds, Set<String> extensionAttributes) {

    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();
    debugMap.put("method", "retrieveTeamsChannelsByIds");
    long startTime = System.nanoTime();

    List<GrouperTeamsChannel> results = new ArrayList<GrouperTeamsChannel>();

    try {
      String select = GrouperTeamsChannel.fieldsToSelect;
      if (extensionAttributes != null && extensionAttributes.size() > 0) {
        select = select + "," + StringUtils.join(extensionAttributes, ",");
      }

      for (MultiKey teamIdChannelId : GrouperUtil.nonNull(teamIdChannelIds)) {
        String teamId = (String) teamIdChannelId.getKey(0);
        String channelId = (String) teamIdChannelId.getKey(1);
        if (StringUtils.isBlank(teamId) || StringUtils.isBlank(channelId)) {
          continue;
        }

        String urlSuffix = "/teams/" + GrouperUtil.escapeUrlEncode(teamId) + "/channels/"
            + GrouperUtil.escapeUrlEncode(channelId) + "?$select=" + select;

        int[] returnCode = new int[] { -1 };
        JsonNode jsonNode = executeGetMethod(debugMap, debugLabel(debugMap, "retrieveTeamsChannelsByIds"), configId, urlSuffix, returnCode);

        if (returnCode[0] == 429) {
          throttleSleep(debugMap, DEFAULT_THROTTLE_SECONDS);
          // retry this one
          jsonNode = executeGetMethod(debugMap, debugLabel(debugMap, "retrieveTeamsChannelsByIds"), configId, urlSuffix, returnCode);
        }

        if (returnCode[0] == 404 || jsonNode == null) {
          continue;
        }

        GrouperTeamsChannel grouperTeamsChannel = GrouperTeamsChannel.fromJson(jsonNode);
        if (grouperTeamsChannel != null) {
          grouperTeamsChannel.setTeamId(teamId);
          results.add(grouperTeamsChannel);
        }
      }

      return results;
    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      GrouperTeamsChannelLog.teamsLog(debugMap, startTime);
    }
  }

  /**
   * find a channel in a team by displayName (used when a channel has no id yet).
   * @param configId
   * @param teamId
   * @param displayName
   * @return the channel or null
   */
  public static GrouperTeamsChannel retrieveTeamsChannelByDisplayName(String configId, String teamId, String displayName) {

    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();
    debugMap.put("method", "retrieveTeamsChannelByDisplayName");
    long startTime = System.nanoTime();

    try {
      if (StringUtils.isBlank(teamId) || StringUtils.isBlank(displayName)) {
        return null;
      }

      String urlSuffix = "/teams/" + GrouperUtil.escapeUrlEncode(teamId) + "/channels?$filter=displayName%20eq%20'"
          + GrouperUtil.escapeUrlEncode(StringUtils.replace(displayName, "'", "''")) + "'&$select=" + GrouperTeamsChannel.fieldsToSelect;

      int[] returnCode = new int[] { -1 };
      JsonNode jsonNode = executeGetMethod(debugMap, debugLabel(debugMap, "retrieveTeamsChannelByDisplayName"), configId, urlSuffix, returnCode);

      if (returnCode[0] == 429) {
        throttleSleep(debugMap, DEFAULT_THROTTLE_SECONDS);
        return retrieveTeamsChannelByDisplayName(configId, teamId, displayName);
      }

      if (returnCode[0] == 404 || jsonNode == null) {
        return null;
      }

      ArrayNode value = (ArrayNode) GrouperUtil.jsonJacksonGetNode(jsonNode, "value");
      if (value != null && value.size() > 0) {
        if (value.size() > 1) {
          LOG.error("Query returned multiple channels for team " + teamId + " and displayName '" + displayName + "'");
        }
        GrouperTeamsChannel grouperTeamsChannel = GrouperTeamsChannel.fromJson(value.get(0));
        if (grouperTeamsChannel != null) {
          grouperTeamsChannel.setTeamId(teamId);
        }
        return grouperTeamsChannel;
      }
      return null;
    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      GrouperTeamsChannelLog.teamsLog(debugMap, startTime);
    }
  }

  /**
   * create channels.  Each channel must carry a teamId.
   * @param configId
   * @param channelToFieldNamesToInsert
   * @return map of channel to exception (null exception = success)
   */
  public static Map<GrouperTeamsChannel, Exception> createTeamsChannels(String configId,
      Map<GrouperTeamsChannel, Set<String>> channelToFieldNamesToInsert) {

    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();
    debugMap.put("method", "createTeamsChannels");
    long startTime = System.nanoTime();

    Map<GrouperTeamsChannel, Exception> channelToMayBeException = new HashMap<>();

    try {
      for (GrouperTeamsChannel channel : GrouperUtil.nonNull(channelToFieldNamesToInsert).keySet()) {
        try {
          createTeamsChannel(configId, debugMap, channel, channelToFieldNamesToInsert.get(channel));
          channelToMayBeException.put(channel, null);
        } catch (Exception e) {
          channelToMayBeException.put(channel, e);
        }
      }
      return channelToMayBeException;
    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      GrouperTeamsChannelLog.teamsLog(debugMap, startTime);
    }
  }

  private static void createTeamsChannel(String configId, Map<String, Object> debugMap,
      GrouperTeamsChannel channel, Set<String> fieldNamesToInsert) {

    if (StringUtils.isBlank(channel.getTeamId())) {
      throw new RuntimeException("Cannot create channel '" + channel.getDisplayName() + "' - no teamId supplied");
    }

    ObjectNode jsonToSend = channel.toJson(fieldNamesToInsert, true);
    jsonToSend.remove("id");
    String jsonStringToSend = GrouperUtil.jsonJacksonToString(jsonToSend);

    String urlSuffix = "/teams/" + GrouperUtil.escapeUrlEncode(channel.getTeamId()) + "/channels";

    int[] returnCode = new int[] { -1 };
    // 201 = created (standard/private), 202 = accepted async (shared)
    JsonNode responseNode = executeMethod(debugMap, debugLabel(debugMap, "createTeamsChannels"), "POST", configId, urlSuffix,
        GrouperUtil.toSet(201, 202, 429), returnCode, jsonStringToSend);

    if (returnCode[0] == 429) {
      throttleSleep(debugMap, DEFAULT_THROTTLE_SECONDS);
      createTeamsChannel(configId, debugMap, channel, fieldNamesToInsert);
      return;
    }

    if (returnCode[0] == 202) {
      // shared channel created asynchronously; the id is not returned inline.
      // resolve it by displayName so Grouper can link it.
      GrouperTeamsChannel resolved = retrieveTeamsChannelByDisplayName(configId, channel.getTeamId(), channel.getDisplayName());
      if (resolved != null) {
        channel.setId(resolved.getId());
      }
      return;
    }

    if (responseNode != null) {
      GrouperTeamsChannel created = GrouperTeamsChannel.fromJson(responseNode);
      if (created != null) {
        channel.setId(created.getId());
      }
    }
  }

  /**
   * update channels (PATCH displayName/description; membershipType is immutable).
   * @param configId
   * @param channelToFieldNamesToUpdate
   * @return map of channel to exception
   */
  public static Map<GrouperTeamsChannel, Exception> updateTeamsChannels(String configId,
      Map<GrouperTeamsChannel, Set<String>> channelToFieldNamesToUpdate) {

    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();
    debugMap.put("method", "updateTeamsChannels");
    long startTime = System.nanoTime();

    Map<GrouperTeamsChannel, Exception> channelToMayBeException = new HashMap<>();

    try {
      for (GrouperTeamsChannel channel : GrouperUtil.nonNull(channelToFieldNamesToUpdate).keySet()) {
        try {
          updateTeamsChannel(configId, debugMap, channel, channelToFieldNamesToUpdate.get(channel));
          channelToMayBeException.put(channel, null);
        } catch (Exception e) {
          channelToMayBeException.put(channel, e);
        }
      }
      return channelToMayBeException;
    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      GrouperTeamsChannelLog.teamsLog(debugMap, startTime);
    }
  }

  private static void updateTeamsChannel(String configId, Map<String, Object> debugMap,
      GrouperTeamsChannel channel, Set<String> fieldNamesToUpdate) {

    if (StringUtils.isBlank(channel.getTeamId()) || StringUtils.isBlank(channel.getId())) {
      throw new RuntimeException("Cannot update channel - missing teamId or channel id");
    }

    JsonNode jsonToSend = channel.toJson(fieldNamesToUpdate, false);
    String jsonStringToSend = GrouperUtil.jsonJacksonToString(jsonToSend);

    String urlSuffix = "/teams/" + GrouperUtil.escapeUrlEncode(channel.getTeamId()) + "/channels/"
        + GrouperUtil.escapeUrlEncode(channel.getId());

    int[] returnCode = new int[] { -1 };
    executeMethod(debugMap, debugLabel(debugMap, "updateTeamsChannels"), "PATCH", configId, urlSuffix,
        GrouperUtil.toSet(200, 204, 429), returnCode, jsonStringToSend);

    if (returnCode[0] == 429) {
      throttleSleep(debugMap, DEFAULT_THROTTLE_SECONDS);
      updateTeamsChannel(configId, debugMap, channel, fieldNamesToUpdate);
    }
    // 200/204 = success; nothing further to read
  }

  /**
   * delete channels.
   * @param configId
   * @param channels
   * @return map of channel to exception
   */
  public static Map<GrouperTeamsChannel, Exception> deleteTeamsChannels(String configId, List<GrouperTeamsChannel> channels) {

    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();
    debugMap.put("method", "deleteTeamsChannels");
    long startTime = System.nanoTime();

    Map<GrouperTeamsChannel, Exception> channelToMayBeException = new HashMap<>();

    try {
      for (GrouperTeamsChannel channel : GrouperUtil.nonNull(channels)) {
        try {
          deleteTeamsChannel(configId, debugMap, channel);
          channelToMayBeException.put(channel, null);
        } catch (Exception e) {
          channelToMayBeException.put(channel, e);
        }
      }
      return channelToMayBeException;
    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      GrouperTeamsChannelLog.teamsLog(debugMap, startTime);
    }
  }

  private static void deleteTeamsChannel(String configId, Map<String, Object> debugMap, GrouperTeamsChannel channel) {

    if (StringUtils.isBlank(channel.getTeamId()) || StringUtils.isBlank(channel.getId())) {
      throw new RuntimeException("Cannot delete channel - missing teamId or channel id");
    }

    String urlSuffix = "/teams/" + GrouperUtil.escapeUrlEncode(channel.getTeamId()) + "/channels/"
        + GrouperUtil.escapeUrlEncode(channel.getId());

    int[] returnCode = new int[] { -1 };
    // 204 = deleted, 404 = already gone (treat as success)
    executeMethod(debugMap, debugLabel(debugMap, "deleteTeamsChannels"), "DELETE", configId, urlSuffix,
        GrouperUtil.toSet(204, 404, 429), returnCode, null);

    if (returnCode[0] == 429) {
      throttleSleep(debugMap, DEFAULT_THROTTLE_SECONDS);
      deleteTeamsChannel(configId, debugMap, channel);
    }
  }

  // ==================================================================
  // channel membership
  // ==================================================================

  /**
   * retrieve the members of a channel.  Returns memberships that carry both the
   * user id (userId) and the opaque conversationMember id (id, needed for delete).
   *
   * @param configId
   * @param teamId
   * @param channelId
   * @return the memberships
   */
  public static List<GrouperTeamsChannelMembership> retrieveTeamsChannelMembers(String configId, String teamId, String channelId) {

    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();
    debugMap.put("method", "retrieveTeamsChannelMembers");
    long startTime = System.nanoTime();

    List<GrouperTeamsChannelMembership> result = new ArrayList<GrouperTeamsChannelMembership>();

    try {
      int pagingSize = GrouperLoaderConfig.retrieveConfig().propertyValueInt("teamsGetMembershipPagingSize", 999);
      String urlSuffix = "/teams/" + GrouperUtil.escapeUrlEncode(teamId) + "/channels/"
          + GrouperUtil.escapeUrlEncode(channelId) + "/members?$select=id,userId&$top=" + pagingSize;

      String resourceEndpoint = GrouperLoaderConfig.retrieveConfig()
          .propertyValueStringRequired("grouper.azureConnector." + configId + ".resourceEndpoint");

      for (int i = 0; i < 1000000; i++) {
        int[] returnCode = new int[] { -1 };
        JsonNode jsonNode = executeGetMethod(debugMap, debugLabel(debugMap, "retrieveTeamsChannelMembers"), configId, urlSuffix, returnCode);

        if (returnCode[0] == 429) {
          throttleSleep(debugMap, DEFAULT_THROTTLE_SECONDS);
          continue;
        }

        if (returnCode[0] == 404 || jsonNode == null) {
          break;
        }

        ArrayNode value = (ArrayNode) GrouperUtil.jsonJacksonGetNode(jsonNode, "value");
        for (int k = 0; k < (value == null ? 0 : value.size()); k++) {
          JsonNode memberNode = value.get(k);
          String userId = GrouperUtil.jsonJacksonGetString(memberNode, "userId");
          String membershipId = GrouperUtil.jsonJacksonGetString(memberNode, "id");
          if (StringUtils.isNotBlank(userId)) {
            GrouperTeamsChannelMembership membership = new GrouperTeamsChannelMembership();
            membership.setChannelId(channelId);
            membership.setUserId(userId);
            membership.setId(membershipId);
            result.add(membership);
          }
        }

        String nextLink = GrouperUtil.jsonJacksonGetString(jsonNode, "@odata.nextLink");
        if (StringUtils.isBlank(nextLink)) {
          break;
        }
        if (nextLink.startsWith(resourceEndpoint)) {
          urlSuffix = nextLink.substring(resourceEndpoint.length());
        } else {
          urlSuffix = nextLink;
        }
      }

      return result;
    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      GrouperTeamsChannelLog.teamsLog(debugMap, startTime);
    }
  }

  /**
   * add members (userIds) to a channel.  Returns the created conversationMember
   * id per user so the caller can cache it for later removal.
   *
   * @param configId
   * @param teamId
   * @param channelId
   * @param userIds
   * @return map of userId to either the created membership id (success) or an Exception
   */
  public static Map<String, Object> createTeamsChannelMemberships(String configId, String teamId, String channelId,
      List<String> userIds) {

    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();
    debugMap.put("method", "createTeamsChannelMemberships");
    long startTime = System.nanoTime();

    Map<String, Object> userIdToResult = new LinkedHashMap<String, Object>();

    try {
      String resourceEndpoint = GrouperLoaderConfig.retrieveConfig()
          .propertyValueStringRequired("grouper.azureConnector." + configId + ".resourceEndpoint");
      String userBindPrefix = GrouperUtil.stripLastSlashIfExists(resourceEndpoint) + "/users/";

      for (String userId : GrouperUtil.nonNull(userIds)) {
        try {
          Object result = createTeamsChannelMembership(configId, debugMap, teamId, channelId, userId, userBindPrefix);
          userIdToResult.put(userId, result);
        } catch (Exception e) {
          userIdToResult.put(userId, e);
        }
      }
      return userIdToResult;
    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      GrouperTeamsChannelLog.teamsLog(debugMap, startTime);
    }
  }

  private static Object createTeamsChannelMembership(String configId, Map<String, Object> debugMap,
      String teamId, String channelId, String userId, String userBindPrefix) {

    ObjectNode objectNode = GrouperUtil.jsonJacksonNode();
    objectNode.put("@odata.type", "#microsoft.graph.aadUserConversationMember");
    ArrayNode roles = GrouperUtil.jsonJacksonArrayNode();
    objectNode.set("roles", roles);
    objectNode.put("user@odata.bind", userBindPrefix + GrouperUtil.escapeUrlEncode(userId));

    String jsonStringToSend = GrouperUtil.jsonJacksonToString(objectNode);

    String urlSuffix = "/teams/" + GrouperUtil.escapeUrlEncode(teamId) + "/channels/"
        + GrouperUtil.escapeUrlEncode(channelId) + "/members";

    int[] returnCode = new int[] { -1 };
    JsonNode responseNode = executeMethod(debugMap, debugLabel(debugMap, "createTeamsChannelMemberships"), "POST", configId, urlSuffix,
        GrouperUtil.toSet(200, 201, 429), returnCode, jsonStringToSend);

    if (returnCode[0] == 429) {
      throttleSleep(debugMap, DEFAULT_THROTTLE_SECONDS);
      return createTeamsChannelMembership(configId, debugMap, teamId, channelId, userId, userBindPrefix);
    }

    // return the created conversationMember id so it can be cached for delete
    return responseNode == null ? null : GrouperUtil.jsonJacksonGetString(responseNode, "id");
  }

  /**
   * remove members from a channel.  Deletion requires the conversationMember id;
   * for any membership whose id is not known, it is looked up first.
   *
   * @param configId
   * @param teamId
   * @param channelId
   * @param userIdToMembershipId map of userId to known conversationMember id (id may be null)
   * @return map of userId to exception (null = success)
   */
  public static Map<String, Exception> deleteTeamsChannelMemberships(String configId, String teamId, String channelId,
      Map<String, String> userIdToMembershipId) {

    Map<String, Object> debugMap = new LinkedHashMap<String, Object>();
    debugMap.put("method", "deleteTeamsChannelMemberships");
    long startTime = System.nanoTime();

    Map<String, Exception> userIdToException = new LinkedHashMap<String, Exception>();

    try {
      // resolve any missing conversationMember ids by listing current members once
      boolean needLookup = false;
      for (String membershipId : GrouperUtil.nonNull(userIdToMembershipId).values()) {
        if (StringUtils.isBlank(membershipId)) {
          needLookup = true;
          break;
        }
      }

      Map<String, String> resolvedUserIdToMembershipId = new LinkedHashMap<String, String>(userIdToMembershipId);
      if (needLookup) {
        List<GrouperTeamsChannelMembership> currentMembers = retrieveTeamsChannelMembers(configId, teamId, channelId);
        Map<String, String> lookup = new HashMap<String, String>();
        for (GrouperTeamsChannelMembership m : currentMembers) {
          lookup.put(m.getUserId(), m.getId());
        }
        for (String userId : userIdToMembershipId.keySet()) {
          if (StringUtils.isBlank(resolvedUserIdToMembershipId.get(userId))) {
            resolvedUserIdToMembershipId.put(userId, lookup.get(userId));
          }
        }
      }

      for (String userId : resolvedUserIdToMembershipId.keySet()) {
        String membershipId = resolvedUserIdToMembershipId.get(userId);
        try {
          if (StringUtils.isBlank(membershipId)) {
            // already not a member; nothing to do
            userIdToException.put(userId, null);
            continue;
          }
          deleteTeamsChannelMembership(configId, debugMap, teamId, channelId, membershipId);
          userIdToException.put(userId, null);
        } catch (Exception e) {
          userIdToException.put(userId, e);
        }
      }

      return userIdToException;
    } catch (RuntimeException re) {
      debugMap.put("exception", GrouperClientUtils.getFullStackTrace(re));
      throw re;
    } finally {
      GrouperTeamsChannelLog.teamsLog(debugMap, startTime);
    }
  }

  private static void deleteTeamsChannelMembership(String configId, Map<String, Object> debugMap,
      String teamId, String channelId, String membershipId) {

    String urlSuffix = "/teams/" + GrouperUtil.escapeUrlEncode(teamId) + "/channels/"
        + GrouperUtil.escapeUrlEncode(channelId) + "/members/" + GrouperUtil.escapeUrlEncode(membershipId);

    int[] returnCode = new int[] { -1 };
    executeMethod(debugMap, debugLabel(debugMap, "deleteTeamsChannelMemberships"), "DELETE", configId, urlSuffix,
        GrouperUtil.toSet(204, 404, 429), returnCode, null);

    if (returnCode[0] == 429) {
      throttleSleep(debugMap, DEFAULT_THROTTLE_SECONDS);
      deleteTeamsChannelMembership(configId, debugMap, teamId, channelId, membershipId);
    }
  }

}

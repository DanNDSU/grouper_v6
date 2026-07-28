package edu.internet2.middleware.grouper.app.teamsChannels;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.internet2.middleware.grouper.app.azure.AzureMockServiceHandler;
import edu.internet2.middleware.grouper.app.azure.GrouperAzureAuth;
import edu.internet2.middleware.grouper.app.azure.GrouperAzureUser;
import edu.internet2.middleware.grouper.app.loader.GrouperLoaderConfig;
import edu.internet2.middleware.grouper.cfg.GrouperConfig;
import edu.internet2.middleware.grouper.ddl.DdlUtilsChangeDatabase;
import edu.internet2.middleware.grouper.ddl.DdlVersionBean;
import edu.internet2.middleware.grouper.ddl.GrouperDdlUtils;
import edu.internet2.middleware.grouper.ddl.GrouperMockDdl;
import edu.internet2.middleware.grouper.ext.org.apache.ddlutils.model.Database;
import edu.internet2.middleware.grouper.hibernate.HibernateSession;
import edu.internet2.middleware.grouper.internal.util.GrouperUuid;
import edu.internet2.middleware.grouper.j2ee.MockServiceHandler;
import edu.internet2.middleware.grouper.j2ee.MockServiceRequest;
import edu.internet2.middleware.grouper.j2ee.MockServiceResponse;
import edu.internet2.middleware.grouper.j2ee.MockServiceServlet;
import edu.internet2.middleware.grouper.util.GrouperUtil;
import edu.internet2.middleware.grouperClient.collections.MultiKey;
import edu.internet2.middleware.grouperClient.jdbc.GcDbAccess;

/**
 * Mock Microsoft Graph service for the Teams channel provisioner, so the
 * provisioner can be unit tested without a real tenant.
 *
 * Modeled on AzureMockServiceHandler.  Endpoints implemented:
 *
 *   POST   /auth/{tenantId}/oauth2/token          (delegated to the Azure mock)
 *   GET    /teams/{teamId}/channels
 *   POST   /teams/{teamId}/channels
 *   GET    /teams/{teamId}/channels/{channelId}
 *   PATCH  /teams/{teamId}/channels/{channelId}
 *   DELETE /teams/{teamId}/channels/{channelId}
 *   GET    /teams/{teamId}/channels/{channelId}/members
 *   POST   /teams/{teamId}/channels/{channelId}/members
 *   DELETE /teams/{teamId}/channels/{channelId}/members/{membershipId}
 *   GET    /users
 *   GET    /users/{idOrUpn}
 *   POST   /$batch
 *
 * Channels and channel memberships are stored in mock_teams_channel and
 * mock_teams_channel_mship.  Users and bearer tokens deliberately reuse the
 * Azure mock's mock_azure_user / mock_azure_auth tables: a Teams channel
 * provisioner authenticates against the same Entra app registration and reads
 * the same /users directory as the Azure provisioner, so there is nothing to
 * duplicate.
 *
 * This handler is registered by path (rather than being hard coded in
 * MockServiceServlet) via grouper.properties:
 *
 *   grouperExtraMockServer.teamsChannel.class =
 *     edu.internet2.middleware.grouper.app.teamsChannels.TeamsChannelMockServiceHandler
 *   grouperExtraMockServer.teamsChannel.path = teamsChannel
 */
public class TeamsChannelMockServiceHandler extends MockServiceHandler {

  public TeamsChannelMockServiceHandler() {
  }

  public static final Set<String> doNotLogParameters = GrouperUtil.toSet("client_secret");

  public static final Set<String> doNotLogHeaders = GrouperUtil.toSet("authorization");

  /** valid Teams channel membership types */
  public static final Set<String> validMembershipTypes = GrouperUtil.toSet("standard", "private", "shared");

  /** max length of a Teams channel display name */
  public static final int maxDisplayNameLength = 50;

  private String configId;

  @Override
  public Set<String> doNotLogParameters() {
    return doNotLogParameters;
  }

  @Override
  public Set<String> doNotLogHeaders() {
    return doNotLogHeaders;
  }

  /**
   * create mock_teams_channel and mock_teams_channel_mship if they are not there.
   * Also ensures the Azure mock tables exist, since users and auth tokens are
   * shared with the Azure mock.
   */
  public static void ensureTeamsChannelMockTables() {

    AzureMockServiceHandler.ensureAzureMockTables();

    try {
      new GcDbAccess().sql("select count(*) from mock_teams_channel").select(int.class);
      new GcDbAccess().sql("select count(*) from mock_teams_channel_mship").select(int.class);
    } catch (Exception e) {

      GrouperDdlUtils.changeDatabase(GrouperMockDdl.V1.getObjectName(), new DdlUtilsChangeDatabase() {
        public void changeDatabase(DdlVersionBean ddlVersionBean) {
          Database database = ddlVersionBean.getDatabase();
          GrouperTeamsChannel.createTableTeamsChannel(ddlVersionBean, database);
          GrouperTeamsChannelMembership.createTableTeamsChannelMembership(ddlVersionBean, database);
        }
      });
    }
  }

  /**
   * drop the teams mock tables (memberships first, they have an fkey to channels)
   */
  public static void dropTeamsChannelMockTables() {
    MockServiceServlet.dropMockTable("mock_teams_channel_mship");
    MockServiceServlet.dropMockTable("mock_teams_channel");
  }

  private static boolean mockTablesThere = false;

  @Override
  public void handleRequest(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {

    if (!mockTablesThere) {
      ensureTeamsChannelMockTables();
    }
    mockTablesThere = true;

    String[] paths = mockServiceRequest.getPostMockNamePaths();

    if (GrouperUtil.length(paths) == 0) {
      throw new RuntimeException("Pass in a path!");
    }

    this.configId = GrouperConfig.retrieveConfig().propertyValueString("grouperTest.teamsChannel.mock.configId");
    if (StringUtils.isBlank(this.configId)) {
      this.configId = "myAzure";
    }

    String httpMethod = mockServiceRequest.getHttpServletRequest().getMethod();

    // the bearer token endpoint is identical to the Azure one, so reuse it
    if (StringUtils.equals("POST", httpMethod) && "auth".equals(paths[0])) {
      new AzureMockServiceHandler().handleRequest(mockServiceRequest, mockServiceResponse);
      return;
    }

    boolean isTeams = "teams".equals(paths[0]) && paths.length >= 3 && "channels".equals(paths[2]);

    if (StringUtils.equals("GET", httpMethod)) {
      if (isTeams && paths.length == 3) {
        getChannels(mockServiceRequest, mockServiceResponse, paths[1]);
        return;
      }
      if (isTeams && paths.length == 4) {
        getChannel(mockServiceRequest, mockServiceResponse, paths[1], paths[3]);
        return;
      }
      if (isTeams && paths.length == 5 && "members".equals(paths[4])) {
        getChannelMembers(mockServiceRequest, mockServiceResponse, paths[1], paths[3]);
        return;
      }
      if ("users".equals(paths[0]) && paths.length == 1) {
        getUsers(mockServiceRequest, mockServiceResponse);
        return;
      }
      if ("users".equals(paths[0]) && paths.length == 2) {
        getUser(mockServiceRequest, mockServiceResponse, paths[1]);
        return;
      }
    }

    if (StringUtils.equals("POST", httpMethod)) {
      if (isTeams && paths.length == 3) {
        postChannel(mockServiceRequest, mockServiceResponse, paths[1]);
        return;
      }
      if (isTeams && paths.length == 5 && "members".equals(paths[4])) {
        postChannelMember(mockServiceRequest, mockServiceResponse, paths[1], paths[3]);
        return;
      }
      if ("$batch".equals(paths[0]) && paths.length == 1) {
        postBatch(mockServiceRequest, mockServiceResponse);
        return;
      }
    }

    if (StringUtils.equals("PATCH", httpMethod)) {
      if (isTeams && paths.length == 4) {
        patchChannel(mockServiceRequest, mockServiceResponse, paths[1], paths[3]);
        return;
      }
    }

    if (StringUtils.equals("DELETE", httpMethod)) {
      if (isTeams && paths.length == 4) {
        deleteChannel(mockServiceRequest, mockServiceResponse, paths[1], paths[3]);
        return;
      }
      if (isTeams && paths.length == 6 && "members".equals(paths[4])) {
        deleteChannelMember(mockServiceRequest, mockServiceResponse, paths[1], paths[3], paths[5]);
        return;
      }
    }

    throw new RuntimeException("Not expecting request: '" + httpMethod
        + "', '" + mockServiceRequest.getPostMockNamePath() + "'");
  }

  // ==================================================================
  // request validation
  // ==================================================================

  /**
   * the token was minted by the Azure auth mock, so validate it the same way
   */
  public void checkAuthorization(MockServiceRequest mockServiceRequest) {

    String bearerToken = mockServiceRequest.getHttpServletRequest().getHeader("Authorization");

    if (bearerToken == null || !bearerToken.startsWith("Bearer ")) {
      throw new RuntimeException("Authorization token must start with 'Bearer '");
    }

    String authorizationToken = GrouperUtil.prefixOrSuffix(bearerToken, "Bearer ", false);

    List<GrouperAzureAuth> grouperAzureAuths = HibernateSession.byHqlStatic()
        .createQuery("from GrouperAzureAuth where accessToken = :theAccessToken")
        .setString("theAccessToken", authorizationToken).list(GrouperAzureAuth.class);

    if (GrouperUtil.length(grouperAzureAuths) != 1) {
      throw new RuntimeException("Invalid access token, not found! " + StringUtils.abbreviate(authorizationToken, 5));
    }

    if (grouperAzureAuths.get(0).getExpiresOnSeconds() < System.currentTimeMillis() / 1000) {
      throw new RuntimeException("Invalid access token, expired!");
    }
  }

  /**
   * only enforced on requests that carry a body
   */
  private void checkRequestContentType(MockServiceRequest mockServiceRequest) {
    String contentType = mockServiceRequest.getHttpServletRequest().getContentType();
    if (!StringUtils.equals(contentType, "application/json")
        && !StringUtils.startsWith(contentType, "application/json;")) {
      throw new RuntimeException("Content type must be application/json but was '" + contentType + "'");
    }
  }

  private String odataContext(String suffix) {
    String resourceEndpoint = GrouperLoaderConfig.retrieveConfig().propertyValueString(
        "grouper.azureConnector." + this.configId + ".resourceEndpoint");
    return GrouperUtil.stripLastSlashIfExists(resourceEndpoint) + "/$metadata#" + suffix;
  }

  private static Set<String> fieldsToRetrieve(MockServiceRequest mockServiceRequest) {
    String fieldsToRetrieveString = mockServiceRequest.getHttpServletRequest().getParameter("$select");
    if (StringUtils.isBlank(fieldsToRetrieveString)) {
      return null;
    }
    return GrouperUtil.toSet(GrouperUtil.split(fieldsToRetrieveString, ","));
  }

  private static void respond(MockServiceResponse mockServiceResponse, int code, JsonNode body) {
    mockServiceResponse.setResponseCode(code);
    if (body != null) {
      mockServiceResponse.setContentType("application/json");
      mockServiceResponse.setResponseBody(GrouperUtil.jsonJacksonToString(body));
    }
  }

  // ==================================================================
  // channels
  // ==================================================================

  /**
   * render a channel the way Graph does.  Note this cannot reuse
   * GrouperTeamsChannel.toJson(): that method never emits the id (it is the
   * request-side serializer) and suppresses membershipType outside inserts.
   */
  private ObjectNode channelToJson(GrouperTeamsChannel channel, Set<String> fieldsToRetrieve) {

    ObjectNode node = GrouperUtil.jsonJacksonNode();

    if (fieldsToRetrieve == null || fieldsToRetrieve.contains("id")) {
      GrouperUtil.jsonJacksonAssignString(node, "id", channel.getId());
    }
    if (fieldsToRetrieve == null || fieldsToRetrieve.contains("displayName")) {
      GrouperUtil.jsonJacksonAssignString(node, "displayName", channel.getDisplayName());
    }
    if (fieldsToRetrieve == null || fieldsToRetrieve.contains("description")) {
      GrouperUtil.jsonJacksonAssignString(node, "description", channel.getDescription());
    }
    if (fieldsToRetrieve == null || fieldsToRetrieve.contains("membershipType")) {
      GrouperUtil.jsonJacksonAssignString(node, "membershipType", channel.getMembershipType());
    }

    return node;
  }

  /**
   * GET /teams/{teamId}/channels[?$filter=displayName eq 'x'][&amp;$select=...]
   */
  public MultiKey getChannels(String teamId, String filter, Set<String> fieldsToRetrieve) {

    List<GrouperTeamsChannel> channels = null;

    if (StringUtils.isBlank(filter)) {
      channels = HibernateSession.byHqlStatic()
          .createQuery("from GrouperTeamsChannel where teamId = :theTeamId")
          .setString("theTeamId", teamId).list(GrouperTeamsChannel.class);
    } else {
      // displayName eq 'something'
      Matcher matcher = Pattern.compile("^([^\\s]+)\\s+eq\\s+'(.+)'$").matcher(filter);
      GrouperUtil.assertion(matcher.matches(), "doesnt match regex '" + filter + "'");
      String field = matcher.group(1);
      String value = matcher.group(2);
      GrouperUtil.assertion(field.matches("^[a-zA-Z0-9]+$"), "field must be alphanumeric '" + field + "'");
      // Graph escapes a literal quote by doubling it
      value = StringUtils.replace(value, "''", "'");
      channels = HibernateSession.byHqlStatic()
          .createQuery("from GrouperTeamsChannel where teamId = :theTeamId and " + field + " = :theValue")
          .setString("theTeamId", teamId).setString("theValue", value).list(GrouperTeamsChannel.class);
    }

    ObjectNode resultNode = GrouperUtil.jsonJacksonNode();
    resultNode.put("@odata.context", odataContext("channels"));

    ArrayNode valueNode = GrouperUtil.jsonJacksonArrayNode();
    for (GrouperTeamsChannel channel : GrouperUtil.nonNull(channels)) {
      valueNode.add(channelToJson(channel, fieldsToRetrieve));
    }
    resultNode.set("value", valueNode);

    return new MultiKey(200, resultNode);
  }

  public void getChannels(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse, String teamId) {

    checkAuthorization(mockServiceRequest);

    MultiKey result = getChannels(teamId,
        mockServiceRequest.getHttpServletRequest().getParameter("$filter"),
        fieldsToRetrieve(mockServiceRequest));

    respond(mockServiceResponse, (Integer) result.getKey(0), (JsonNode) result.getKey(1));
  }

  /**
   * GET /teams/{teamId}/channels/{channelId}
   */
  public MultiKey getChannel(String teamId, String channelId, Set<String> fieldsToRetrieve) {

    GrouperUtil.assertion(GrouperUtil.length(channelId) > 0, "channel id is required");

    List<GrouperTeamsChannel> channels = HibernateSession.byHqlStatic()
        .createQuery("from GrouperTeamsChannel where teamId = :theTeamId and id = :theId")
        .setString("theTeamId", teamId).setString("theId", channelId).list(GrouperTeamsChannel.class);

    if (GrouperUtil.length(channels) == 0) {
      return new MultiKey(404, null);
    }
    if (GrouperUtil.length(channels) > 1) {
      throw new RuntimeException("Found multiple channels for id: " + channelId);
    }

    return new MultiKey(200, channelToJson(channels.get(0), fieldsToRetrieve));
  }

  public void getChannel(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse,
      String teamId, String channelId) {

    checkAuthorization(mockServiceRequest);

    MultiKey result = getChannel(teamId, channelId, fieldsToRetrieve(mockServiceRequest));

    respond(mockServiceResponse, (Integer) result.getKey(0), (JsonNode) result.getKey(1));
  }

  /**
   * POST /teams/{teamId}/channels
   */
  public void postChannel(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse, String teamId) {

    checkAuthorization(mockServiceRequest);
    checkRequestContentType(mockServiceRequest);

    JsonNode body = GrouperUtil.jsonJacksonNode(mockServiceRequest.getRequestBody());

    String displayName = GrouperUtil.jsonJacksonGetString(body, "displayName");
    String description = GrouperUtil.jsonJacksonGetString(body, "description");
    String membershipType = GrouperUtil.jsonJacksonGetString(body, "membershipType");

    GrouperUtil.assertion(GrouperUtil.length(displayName) > 0, "displayName is required");
    GrouperUtil.assertion(GrouperUtil.length(displayName) <= maxDisplayNameLength,
        "displayName must be " + maxDisplayNameLength + " characters or fewer, but was "
            + GrouperUtil.length(displayName) + ": '" + displayName + "'");
    GrouperUtil.assertion(GrouperUtil.length(description) <= 1024, "description must be less than 1024");
    GrouperUtil.assertion(GrouperUtil.length(GrouperUtil.jsonJacksonGetString(body, "id")) == 0, "id is forbidden");

    if (StringUtils.isNotBlank(membershipType)) {
      GrouperUtil.assertion(validMembershipTypes.contains(membershipType),
          "membershipType must be one of " + GrouperUtil.setToString(validMembershipTypes)
              + " but was: '" + membershipType + "'");
    }

    // Graph rejects a duplicate channel name within a team
    List<GrouperTeamsChannel> existing = HibernateSession.byHqlStatic()
        .createQuery("from GrouperTeamsChannel where teamId = :theTeamId and displayName = :theDisplayName")
        .setString("theTeamId", teamId).setString("theDisplayName", displayName).list(GrouperTeamsChannel.class);
    GrouperUtil.assertion(GrouperUtil.length(existing) == 0,
        "channel '" + displayName + "' already exists in team " + teamId);

    GrouperTeamsChannel channel = new GrouperTeamsChannel();
    channel.setId("19:" + GrouperUuid.getUuid() + "@thread.tacv2");
    channel.setTeamId(teamId);
    channel.setDisplayName(displayName);
    channel.setDescription(description);
    // setMembershipType() defaults a blank value to "standard"
    channel.setMembershipType(membershipType);

    HibernateSession.byObjectStatic().save(channel);

    respond(mockServiceResponse, 201, channelToJson(channel, null));
  }

  /**
   * PATCH /teams/{teamId}/channels/{channelId}
   */
  public void patchChannel(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse,
      String teamId, String channelId) {

    checkAuthorization(mockServiceRequest);
    checkRequestContentType(mockServiceRequest);

    JsonNode body = GrouperUtil.jsonJacksonNode(mockServiceRequest.getRequestBody());

    List<GrouperTeamsChannel> channels = HibernateSession.byHqlStatic()
        .createQuery("from GrouperTeamsChannel where teamId = :theTeamId and id = :theId")
        .setString("theTeamId", teamId).setString("theId", channelId).list(GrouperTeamsChannel.class);

    if (GrouperUtil.length(channels) == 0) {
      respond(mockServiceResponse, 404, null);
      return;
    }

    // these are immutable on a real channel; the DAO must never PATCH them
    if (body.has("id")) {
      throw new RuntimeException("Cant update the id field!");
    }
    if (body.has("membershipType")) {
      throw new RuntimeException("Cant update the membershipType field, it is set at create time only!");
    }

    GrouperTeamsChannel channel = channels.get(0);

    if (body.has("displayName")) {
      String displayName = GrouperUtil.jsonJacksonGetString(body, "displayName");
      GrouperUtil.assertion(GrouperUtil.length(displayName) <= maxDisplayNameLength,
          "displayName must be " + maxDisplayNameLength + " characters or fewer");
      channel.setDisplayName(displayName);
    }
    if (body.has("description")) {
      channel.setDescription(GrouperUtil.jsonJacksonGetString(body, "description"));
    }

    HibernateSession.byObjectStatic().saveOrUpdate(channel);

    respond(mockServiceResponse, 204, null);
  }

  /**
   * DELETE /teams/{teamId}/channels/{channelId}
   */
  public void deleteChannel(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse,
      String teamId, String channelId) {

    checkAuthorization(mockServiceRequest);

    GrouperUtil.assertion(GrouperUtil.length(channelId) > 0, "channel id is required");

    HibernateSession.byHqlStatic()
        .createQuery("delete from GrouperTeamsChannelMembership where channelId = :theChannelId")
        .setString("theChannelId", channelId).executeUpdateInt();

    int channelsDeleted = HibernateSession.byHqlStatic()
        .createQuery("delete from GrouperTeamsChannel where teamId = :theTeamId and id = :theId")
        .setString("theTeamId", teamId).setString("theId", channelId).executeUpdateInt();

    respond(mockServiceResponse, channelsDeleted == 1 ? 204 : 404, null);
  }

  // ==================================================================
  // channel members (conversationMembers)
  // ==================================================================

  private ObjectNode membershipToJson(GrouperTeamsChannelMembership membership) {
    ObjectNode node = GrouperUtil.jsonJacksonNode();
    node.put("@odata.type", "#microsoft.graph.aadUserConversationMember");
    GrouperUtil.jsonJacksonAssignString(node, "id", membership.getId());
    GrouperUtil.jsonJacksonAssignString(node, "userId", membership.getUserId());
    node.set("roles", GrouperUtil.jsonJacksonArrayNode());
    return node;
  }

  /**
   * GET /teams/{teamId}/channels/{channelId}/members
   */
  public void getChannelMembers(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse,
      String teamId, String channelId) {

    checkAuthorization(mockServiceRequest);

    List<GrouperTeamsChannelMembership> memberships = HibernateSession.byHqlStatic()
        .createQuery("from GrouperTeamsChannelMembership where channelId = :theChannelId")
        .setString("theChannelId", channelId).list(GrouperTeamsChannelMembership.class);

    ObjectNode resultNode = GrouperUtil.jsonJacksonNode();
    resultNode.put("@odata.context", odataContext("conversationMembers"));

    ArrayNode valueNode = GrouperUtil.jsonJacksonArrayNode();
    for (GrouperTeamsChannelMembership membership : GrouperUtil.nonNull(memberships)) {
      valueNode.add(membershipToJson(membership));
    }
    resultNode.set("value", valueNode);

    respond(mockServiceResponse, 200, resultNode);
  }

  /**
   * POST /teams/{teamId}/channels/{channelId}/members
   *
   * body: {"@odata.type":"#microsoft.graph.aadUserConversationMember","roles":[],
   *        "user@odata.bind":"&lt;resourceEndpoint&gt;/users/&lt;userId&gt;"}
   */
  public void postChannelMember(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse,
      String teamId, String channelId) {

    checkAuthorization(mockServiceRequest);
    checkRequestContentType(mockServiceRequest);

    JsonNode body = GrouperUtil.jsonJacksonNode(mockServiceRequest.getRequestBody());

    String userBind = GrouperUtil.jsonJacksonGetString(body, "user@odata.bind");
    GrouperUtil.assertion(GrouperUtil.length(userBind) > 0, "user@odata.bind is required");

    String userId = StringUtils.substringAfterLast(userBind, "/users/");
    GrouperUtil.assertion(GrouperUtil.length(userId) > 0,
        "cannot parse a user id out of user@odata.bind: '" + userBind + "'");
    userId = GrouperUtil.escapeUrlDecode(userId);

    // the channel must exist
    List<GrouperTeamsChannel> channels = HibernateSession.byHqlStatic()
        .createQuery("from GrouperTeamsChannel where teamId = :theTeamId and id = :theId")
        .setString("theTeamId", teamId).setString("theId", channelId).list(GrouperTeamsChannel.class);
    if (GrouperUtil.length(channels) == 0) {
      respond(mockServiceResponse, 404, null);
      return;
    }

    // Graph 404s when the user is not in the directory
    List<GrouperAzureUser> users = HibernateSession.byHqlStatic()
        .createQuery("from GrouperAzureUser where id = :theId")
        .setString("theId", userId).list(GrouperAzureUser.class);
    if (GrouperUtil.length(users) == 0) {
      respond(mockServiceResponse, 404, null);
      return;
    }

    // idempotent: adding an existing member returns the existing conversationMember
    List<GrouperTeamsChannelMembership> existing = HibernateSession.byHqlStatic()
        .createQuery("from GrouperTeamsChannelMembership where channelId = :theChannelId and userId = :theUserId")
        .setString("theChannelId", channelId).setString("theUserId", userId)
        .list(GrouperTeamsChannelMembership.class);

    if (GrouperUtil.length(existing) > 0) {
      respond(mockServiceResponse, 200, membershipToJson(existing.get(0)));
      return;
    }

    GrouperTeamsChannelMembership membership = new GrouperTeamsChannelMembership();
    membership.setId(GrouperUuid.getUuid());
    membership.setChannelId(channelId);
    membership.setUserId(userId);

    HibernateSession.byObjectStatic().save(membership);

    respond(mockServiceResponse, 201, membershipToJson(membership));
  }

  /**
   * DELETE /teams/{teamId}/channels/{channelId}/members/{membershipId}
   */
  public void deleteChannelMember(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse,
      String teamId, String channelId, String membershipId) {

    checkAuthorization(mockServiceRequest);

    GrouperUtil.assertion(GrouperUtil.length(membershipId) > 0, "membership id is required");

    int deleted = HibernateSession.byHqlStatic()
        .createQuery("delete from GrouperTeamsChannelMembership where channelId = :theChannelId and id = :theId")
        .setString("theChannelId", channelId).setString("theId", membershipId).executeUpdateInt();

    respond(mockServiceResponse, deleted == 1 ? 204 : 404, null);
  }

  // ==================================================================
  // users (read only - shared with the Azure mock's directory)
  // ==================================================================

  /**
   * GET /users[?$filter=field eq 'value'][&amp;$select=...]
   */
  public MultiKey getUsers(String filter, String fieldsToRetrieveString) {

    List<GrouperAzureUser> users = null;

    if (StringUtils.isBlank(filter)) {
      users = HibernateSession.byHqlStatic().createQuery("from GrouperAzureUser").list(GrouperAzureUser.class);
    } else {
      Matcher matcher = Pattern.compile("^([^\\s]+)\\s+eq\\s+'(.+)'$").matcher(filter);
      GrouperUtil.assertion(matcher.matches(), "doesnt match regex '" + filter + "'");
      String field = matcher.group(1);
      String value = StringUtils.replace(matcher.group(2), "''", "'");
      GrouperUtil.assertion(field.matches("^[a-zA-Z0-9]+$"), "field must be alphanumeric '" + field + "'");
      users = HibernateSession.byHqlStatic()
          .createQuery("from GrouperAzureUser where " + field + " = :theValue")
          .setString("theValue", value).list(GrouperAzureUser.class);
    }

    Set<String> fieldsToRetrieve = StringUtils.isBlank(fieldsToRetrieveString)
        ? null : GrouperUtil.toSet(GrouperUtil.split(fieldsToRetrieveString, ","));

    ObjectNode resultNode = GrouperUtil.jsonJacksonNode();
    resultNode.put("@odata.context", odataContext("users"));

    ArrayNode valueNode = GrouperUtil.jsonJacksonArrayNode();
    for (GrouperAzureUser user : GrouperUtil.nonNull(users)) {
      valueNode.add(user.toJson(fieldsToRetrieve));
    }
    resultNode.set("value", valueNode);

    return new MultiKey(200, resultNode);
  }

  public void getUsers(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {

    checkAuthorization(mockServiceRequest);

    MultiKey result = getUsers(mockServiceRequest.getHttpServletRequest().getParameter("$filter"),
        mockServiceRequest.getHttpServletRequest().getParameter("$select"));

    respond(mockServiceResponse, (Integer) result.getKey(0), (JsonNode) result.getKey(1));
  }

  /**
   * GET /users/{idOrUpn} - the Teams provisioner addresses users by either the
   * object id or the userPrincipalName, and the request does not say which, so
   * try the id first and fall back to the UPN.
   */
  public MultiKey getUser(String idOrUpn, String fieldsToRetrieveString) {

    GrouperUtil.assertion(GrouperUtil.length(idOrUpn) > 0, "id is required");

    List<GrouperAzureUser> users = HibernateSession.byHqlStatic()
        .createQuery("from GrouperAzureUser where id = :theValue")
        .setString("theValue", idOrUpn).list(GrouperAzureUser.class);

    if (GrouperUtil.length(users) == 0) {
      users = HibernateSession.byHqlStatic()
          .createQuery("from GrouperAzureUser where userPrincipalName = :theValue")
          .setString("theValue", idOrUpn).list(GrouperAzureUser.class);
    }

    if (GrouperUtil.length(users) == 0) {
      return new MultiKey(404, null);
    }

    Set<String> fieldsToRetrieve = StringUtils.isBlank(fieldsToRetrieveString)
        ? null : GrouperUtil.toSet(GrouperUtil.split(fieldsToRetrieveString, ","));

    return new MultiKey(200, users.get(0).toJson(fieldsToRetrieve));
  }

  public void getUser(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse, String idOrUpn) {

    checkAuthorization(mockServiceRequest);

    MultiKey result = getUser(idOrUpn, mockServiceRequest.getHttpServletRequest().getParameter("$select"));

    respond(mockServiceResponse, (Integer) result.getKey(0), (JsonNode) result.getKey(1));
  }

  // ==================================================================
  // $batch
  // ==================================================================

  /**
   * POST /$batch - the Teams provisioner only batches user lookups, either
   * /users/{idOrUpn}?$select=... or /users?$filter=...&amp;$select=...
   */
  public void postBatch(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {

    checkAuthorization(mockServiceRequest);

    JsonNode batchJsonNode = GrouperUtil.jsonJacksonNode(mockServiceRequest.getRequestBody());
    ArrayNode requestsArrayNode = (ArrayNode) GrouperUtil.jsonJacksonGetNode(batchJsonNode, "requests");

    ObjectNode resultNode = GrouperUtil.jsonJacksonNode();
    ArrayNode responsesNode = GrouperUtil.jsonJacksonArrayNode();
    resultNode.set("responses", responsesNode);

    for (int i = 0; i < (requestsArrayNode == null ? 0 : requestsArrayNode.size()); i++) {

      JsonNode singleRequestNode = requestsArrayNode.get(i);

      String httpMethod = GrouperUtil.jsonJacksonGetString(singleRequestNode, "method");
      String url = GrouperUtil.jsonJacksonGetString(singleRequestNode, "url");
      String id = GrouperUtil.jsonJacksonGetString(singleRequestNode, "id");

      if (!StringUtils.equalsIgnoreCase(httpMethod, "get")) {
        throw new RuntimeException("Not expecting batch method '" + httpMethod + "' for url: " + url);
      }

      List<String> urlParts = new ArrayList<String>(Arrays.asList(url.split("/")));
      urlParts.removeAll(Arrays.asList("", null));

      MultiKey oneResult = null;

      if (urlParts.size() == 1 && StringUtils.startsWith(urlParts.get(0), "users?")) {

        String queryString = urlParts.get(0).split("users\\?")[1];
        Map<String, String> keyValue = new HashMap<String, String>();
        for (NameValuePair nameValuePair : URLEncodedUtils.parse(queryString, Charset.defaultCharset())) {
          keyValue.put(nameValuePair.getName(), nameValuePair.getValue());
        }
        oneResult = getUsers(keyValue.get("$filter"), keyValue.get("$select"));

      } else if (urlParts.size() == 2 && "users".equals(urlParts.get(0))) {

        String[] beforeAfterSelect = urlParts.get(1).split("\\?\\$select=");
        String idOrUpn;
        try {
          idOrUpn = URLDecoder.decode(beforeAfterSelect[0], "UTF-8");
        } catch (UnsupportedEncodingException e) {
          throw new RuntimeException(e);
        }
        oneResult = getUser(idOrUpn,
            beforeAfterSelect.length > 1 ? GrouperUtil.escapeUrlDecode(beforeAfterSelect[1]) : null);

      } else {
        throw new RuntimeException("Not expecting batch get url: " + url);
      }

      ObjectNode oneResponse = GrouperUtil.jsonJacksonNode();
      oneResponse.put("id", id);
      oneResponse.put("status", (Integer) oneResult.getKey(0));
      if (oneResult.getKey(1) != null) {
        oneResponse.set("body", (JsonNode) oneResult.getKey(1));
      }
      responsesNode.add(oneResponse);
    }

    respond(mockServiceResponse, 200, resultNode);
  }

}

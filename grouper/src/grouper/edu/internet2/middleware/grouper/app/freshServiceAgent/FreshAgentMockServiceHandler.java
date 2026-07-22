package edu.internet2.middleware.grouper.app.freshServiceAgent;

import java.sql.Types;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.internet2.middleware.grouper.app.loader.GrouperLoaderConfig;
import edu.internet2.middleware.grouper.cfg.GrouperConfig;
import edu.internet2.middleware.grouper.ddl.DdlUtilsChangeDatabase;
import edu.internet2.middleware.grouper.ddl.DdlVersionBean;
import edu.internet2.middleware.grouper.ddl.GrouperDdlUtils;
import edu.internet2.middleware.grouper.ddl.GrouperMockDdl;
import edu.internet2.middleware.grouper.ext.org.apache.ddlutils.model.Database;
import edu.internet2.middleware.grouper.ext.org.apache.ddlutils.model.Table;
import edu.internet2.middleware.grouper.hibernate.ByHqlStatic;
import edu.internet2.middleware.grouper.hibernate.HibernateSession;
import edu.internet2.middleware.grouper.internal.dao.GrouperDAOException;
import edu.internet2.middleware.grouper.internal.dao.QueryOptions;
import edu.internet2.middleware.grouper.internal.dao.QuerySort;
import edu.internet2.middleware.grouper.j2ee.Authentication;
import edu.internet2.middleware.grouper.j2ee.MockServiceHandler;
import edu.internet2.middleware.grouper.j2ee.MockServiceRequest;
import edu.internet2.middleware.grouper.j2ee.MockServiceResponse;
import edu.internet2.middleware.grouper.util.GrouperUtil;
import edu.internet2.middleware.grouperClient.config.ConfigPropertiesCascadeBase;
import edu.internet2.middleware.grouperClient.jdbc.GcDbAccess;
import io.netty.util.internal.ThreadLocalRandom;

public class FreshAgentMockServiceHandler extends MockServiceHandler {

  public FreshAgentMockServiceHandler() {
  }

  /**
   *
   */
  public static final Set<String> doNotLogHeaders = GrouperUtil.toSet("authorization");

  /**
   *
   */
  public static final Set<String> doNotLogParameters = GrouperUtil.toSet("client_secret");

  /**
   * headers to not log all of
   */
  @Override
  public Set<String> doNotLogHeaders() {
    return doNotLogHeaders;
  }

  /**
   * params to not log all of
   */
  @Override
  public Set<String> doNotLogParameters() {
    return doNotLogParameters;
  }

  private static boolean mockTablesThere = false;

  public static void ensureFreshserviceMockTables() {
    try {
      new GcDbAccess().sql("select count(*) from mock_freshagent_group").select(int.class);
      new GcDbAccess().sql("select count(*) from mock_freshagent_user").select(int.class);
      new GcDbAccess().sql("select count(*) from mock_freshagent_membership").select(int.class);
      new GcDbAccess().sql("select count(*) from mock_freshagent_requester").select(int.class);
    } catch (Exception e) {

      //we need to delete the test table if it is there, and create a new one
      //drop field id col, first drop foreign keys
      GrouperDdlUtils.changeDatabase(GrouperMockDdl.V1.getObjectName(), new DdlUtilsChangeDatabase() {

        @Override
        public void changeDatabase(DdlVersionBean ddlVersionBean) {

          Database database = ddlVersionBean.getDatabase();
          FreshAgentGroup.createTableFreshGroup(ddlVersionBean, database);
          FreshAgentUser.createTableFreshUser(ddlVersionBean, database);
          FreshAgentMembership.createTableFreshMembership(ddlVersionBean, database);
          createTableFreshRequester(ddlVersionBean, database);

        }
      });

    }

  }

  /**
   * Create the mock_freshagent_requester table. This models the Freshservice
   * REQUESTER namespace, which is entirely separate from mock_freshagent_user
   * (the AGENT namespace) - matching real Freshservice, where /api/v2/agents and
   * /api/v2/requesters are disjoint resources.
   *
   * This table is intentionally NOT a Hibernate-mapped entity (unlike
   * FreshAgentUser/FreshAgentGroup/FreshAgentMembership). It only needs to
   * support two operations - lookup by email, and convert-to-agent (delete here,
   * insert into mock_freshagent_user) - so it is read and written with plain SQL
   * via GcDbAccess rather than adding a new Hibernate entity/mapping.
   */
  private static void createTableFreshRequester(DdlVersionBean ddlVersionBean, Database database) {

    final String tableName = "mock_freshagent_requester";

    try {
      new GcDbAccess().sql("select count(*) from " + tableName).select(int.class);
    } catch (Exception e) {
      Table requesterTable = GrouperDdlUtils.ddlutilsFindOrCreateTable(database, tableName);
      GrouperDdlUtils.ddlutilsFindOrCreateColumn(requesterTable, "id", Types.BIGINT, "20", true, true);
      GrouperDdlUtils.ddlutilsFindOrCreateColumn(requesterTable, "email", Types.VARCHAR, "256", false, false);
      GrouperDdlUtils.ddlutilsFindOrCreateColumn(requesterTable, "first_name", Types.VARCHAR, "256", false, false);
      GrouperDdlUtils.ddlutilsFindOrCreateColumn(requesterTable, "last_name", Types.VARCHAR, "256", false, false);
      GrouperDdlUtils.ddlutilsFindOrCreateColumn(requesterTable, "active", Types.VARCHAR, "1", false, false);

      GrouperDdlUtils.ddlutilsFindOrCreateIndex(database, tableName, "mock_freshagent_requester_email_idx", true, "email");
    }
  }

  /**
   * check authorization for the request
   * @param mockServiceRequest
   */
  public void checkAuthorization(MockServiceRequest mockServiceRequest) {
    String basicAuth = mockServiceRequest.getHttpServletRequest().getHeader("Authorization");

    // These are swapped because Freshservice swaps in the API call.
    String password = Authentication.retrieveUsername(basicAuth);
    String userName = Authentication.retrievePassword(basicAuth);

    String configId = GrouperConfig.retrieveConfig().propertyValueStringRequired("grouperTest.exampleFreshAgent.mockExternalSystem.configId");

    String expectedUserName = GrouperLoaderConfig.retrieveConfig().propertyValueStringRequired("grouper.wsBearerToken."+configId+".basicAuthUser");
    String expectedPassword = GrouperLoaderConfig.retrieveConfig().propertyValueStringRequired("grouper.wsBearerToken."+configId+".basicAuthPassword");

    if (!StringUtils.equals(expectedUserName, userName)) {
      throw new RuntimeException("Username does not match with what is in grouper config");
    }
    if (!StringUtils.equals(expectedPassword, password)) {
      throw new RuntimeException("password does not match with what is in grouper config");
    }

  }

  // ==================== Group operations ====================

  /**
   * Build the JSON for a single group, including its members array (list of agent ids).
   */
  private ObjectNode groupToJsonWithMembers(FreshAgentGroup freshAgentGroup) {
    ObjectNode objectNode = freshAgentGroup.toJson(null);
    objectNode.put("id", freshAgentGroup.getId());

    // members array: list of agent ids for this group, from the membership table
    List<FreshAgentMembership> memberships = HibernateSession.byHqlStatic()
        .createQuery("from FreshAgentMembership where groupId = :theGroupId")
        .setLong("theGroupId", freshAgentGroup.getId()).list(FreshAgentMembership.class);

    ArrayNode membersArray = GrouperUtil.jsonJacksonArrayNode();
    for (FreshAgentMembership membership : memberships) {
      membersArray.add(membership.getUserId());
    }
    objectNode.set("members", membersArray);

    return objectNode;
  }

  /**
   * Reconcile the membership table for a group with the supplied members array (list of agent ids).
   */
  private void syncGroupMembers(long groupId, JsonNode membersNode) {
    if (membersNode == null || !membersNode.isArray()) {
      return;
    }

    // delete all existing memberships for the group, then re-add from the supplied array
    HibernateSession.byHqlStatic()
        .createQuery("delete from FreshAgentMembership where groupId = :groupId")
        .setLong("groupId", groupId).executeUpdateInt();

    for (int i = 0; i < membersNode.size(); i++) {
      JsonNode memberNode = membersNode.get(i);
      if (memberNode == null || !memberNode.isNumber()) {
        continue;
      }
      long userId = memberNode.longValue();

      // only add if the agent exists
      List<FreshAgentUser> users = HibernateSession.byHqlStatic()
          .createQuery("from FreshAgentUser where id = :theId")
          .setLong("theId", userId).list(FreshAgentUser.class);
      if (GrouperUtil.length(users) == 0) {
        continue;
      }

      FreshAgentMembership membership = new FreshAgentMembership();
      membership.setGroupId(groupId);
      membership.setUserId(userId);
      membership.setId(ThreadLocalRandom.current().nextLong(1, 99999999));
      HibernateSession.byObjectStatic().save(membership);
    }
  }

  /**
   * GET /groups - retrieve all groups
   */
  public void getGroups(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {
    try {
      checkAuthorization(mockServiceRequest);
    } catch (RuntimeException e) {
      mockServiceResponse.setResponseCode(401);
      throw e;
    }

    List<FreshAgentGroup> freshAgentGroups = null;
    ByHqlStatic query = null;
    QueryOptions queryOptions = new QueryOptions();

    query = HibernateSession.byHqlStatic().createQuery("from FreshAgentGroup");

    queryOptions.sort(new QuerySort("id", true));
    query.options(queryOptions);

    freshAgentGroups = query.list(FreshAgentGroup.class);

    ObjectNode resultNode = GrouperUtil.jsonJacksonNode();

    ArrayNode groupsArray = GrouperUtil.jsonJacksonArrayNode();

    for (FreshAgentGroup freshAgentGroup : freshAgentGroups) {
      groupsArray.add(groupToJsonWithMembers(freshAgentGroup));
    }

    resultNode.set("groups", groupsArray);

    mockServiceResponse.setResponseCode(200);
    mockServiceResponse.setContentType("application/json");
    mockServiceResponse.setResponseBody(GrouperUtil.jsonJacksonToString(resultNode));

  }

  /**
   * GET /groups/{id} - retrieve a single group by id
   */
  public void getGroup(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {
    try {
      checkAuthorization(mockServiceRequest);
    } catch (RuntimeException e) {
      mockServiceResponse.setResponseCode(401);
      throw e;
    }

    String groupIdString = mockServiceRequest.getPostMockNamePaths()[1];

    GrouperUtil.assertion(GrouperUtil.length(groupIdString) > 0, "groupId is required");

    long groupId = GrouperUtil.longValue(groupIdString);

    List<FreshAgentGroup> freshAgentGroups = HibernateSession.byHqlStatic()
        .createQuery("from FreshAgentGroup where id = :theId")
        .setLong("theId", groupId).list(FreshAgentGroup.class);

    if (GrouperUtil.length(freshAgentGroups) == 1) {
      FreshAgentGroup freshAgentGroup = freshAgentGroups.get(0);

      ObjectNode resultNode = GrouperUtil.jsonJacksonNode();
      resultNode.set("group", groupToJsonWithMembers(freshAgentGroup));

      mockServiceResponse.setResponseCode(200);
      mockServiceResponse.setContentType("application/json");
      mockServiceResponse.setResponseBody(GrouperUtil.jsonJacksonToString(resultNode));

    } else if (GrouperUtil.length(freshAgentGroups) == 0) {
      mockServiceResponse.setResponseCode(404);
    } else {
      throw new RuntimeException("groupsById: " + GrouperUtil.length(freshAgentGroups) + ", id: " + groupId);
    }
  }

  /**
   * POST /groups - create a group
   */
  public void postGroup(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {

    try {
      checkAuthorization(mockServiceRequest);
      checkRequestContentType(mockServiceRequest);
    } catch (RuntimeException e) {
      mockServiceResponse.setResponseCode(401);
      throw e;
    }

    String groupJsonString = mockServiceRequest.getRequestBody();
    JsonNode groupJsonNode = GrouperUtil.jsonJacksonNode(groupJsonString);

    FreshAgentGroup freshAgentGroup = FreshAgentGroup.fromJson(groupJsonNode);

    // check if group with same name already exists
    List<FreshAgentGroup> existingGroups = HibernateSession.byHqlStatic()
        .createQuery("from FreshAgentGroup where name = :theName")
        .setString("theName", freshAgentGroup.getName()).list(FreshAgentGroup.class);

    if (GrouperUtil.length(existingGroups) > 0) {
      mockServiceResponse.setResponseCode(409);
      mockServiceResponse.setContentType("application/json");
      mockServiceResponse.setResponseBody("{\"description\":\"Validation failed\",\"errors\":[{\"field\":\"name\",\"message\":\"already exists\"}]}");
      return;
    }

    boolean idSaved = false;

    while(!idSaved) {
      try {
        freshAgentGroup.setId(ThreadLocalRandom.current().nextLong(1, 99999999));
        HibernateSession.byObjectStatic().save(freshAgentGroup);
        idSaved = true;
      } catch (GrouperDAOException e) {

      }
    }

    // honor any members array supplied on create
    syncGroupMembers(freshAgentGroup.getId(), GrouperUtil.jsonJacksonGetNode(groupJsonNode, "members"));

    ObjectNode resultNode = GrouperUtil.jsonJacksonNode();
    resultNode.set("group", groupToJsonWithMembers(freshAgentGroup));

    mockServiceResponse.setResponseCode(200);
    mockServiceResponse.setContentType("application/json");
    mockServiceResponse.setResponseBody(GrouperUtil.jsonJacksonToString(resultNode));

  }

  /**
   * PUT /groups/{id} - update a group (including its members array)
   */
  public void updateGroup(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {
    try {
      checkAuthorization(mockServiceRequest);
      checkRequestContentType(mockServiceRequest);
    } catch (RuntimeException e) {
      mockServiceResponse.setResponseCode(401);
      throw e;
    }

    String groupIdString = mockServiceRequest.getPostMockNamePaths()[1];

    mockServiceRequest.getDebugMap().put("groupId", groupIdString);

    long groupId = GrouperUtil.longValue(groupIdString);

    List<FreshAgentGroup> freshAgentGroups = HibernateSession.byHqlStatic()
        .createQuery("from FreshAgentGroup where id = :theId")
        .setLong("theId", groupId).list(FreshAgentGroup.class);

    if (GrouperUtil.length(freshAgentGroups) == 0) {
      mockServiceRequest.getDebugMap().put("cantFindGroup", true);
      mockServiceResponse.setResponseCode(404);
      return;
    }
    if (GrouperUtil.length(freshAgentGroups) > 1) {
      throw new RuntimeException("Found multiple matched groups! " + GrouperUtil.length(freshAgentGroups));
    }

    FreshAgentGroup freshAgentGroup = freshAgentGroups.get(0);

    String groupJsonString = mockServiceRequest.getRequestBody();
    JsonNode groupJsonNode = GrouperUtil.jsonJacksonNode(groupJsonString);

    String name = GrouperUtil.jsonJacksonGetString(groupJsonNode, "name");
    if (StringUtils.isNotBlank(name)) {
      freshAgentGroup.setName(name);
    }

    if (groupJsonNode.has("description")) {
      String description = GrouperUtil.jsonJacksonGetString(groupJsonNode, "description");
      freshAgentGroup.setDescription(description);
    }

    HibernateSession.byObjectStatic().saveOrUpdate(freshAgentGroup);

    // if a members array is present, reconcile group membership to match it
    if (groupJsonNode.has("members")) {
      syncGroupMembers(groupId, GrouperUtil.jsonJacksonGetNode(groupJsonNode, "members"));
    }

    ObjectNode resultNode = GrouperUtil.jsonJacksonNode();
    resultNode.set("group", groupToJsonWithMembers(freshAgentGroup));

    mockServiceResponse.setResponseCode(200);
    mockServiceResponse.setContentType("application/json");
    mockServiceResponse.setResponseBody(GrouperUtil.jsonJacksonToString(resultNode));
  }

  /**
   * DELETE /groups/{id} - delete a group
   */
  public void deleteGroup(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {
    try {
      checkAuthorization(mockServiceRequest);
    } catch (RuntimeException e) {
      mockServiceResponse.setResponseCode(401);
      throw e;
    }

    String groupIdString = mockServiceRequest.getPostMockNamePaths()[1];

    GrouperUtil.assertion(GrouperUtil.length(groupIdString) > 0, "groupId is required");

    long groupId = GrouperUtil.longValue(groupIdString);

    // check if group exists
    List<FreshAgentGroup> existingGroups = HibernateSession.byHqlStatic()
        .createQuery("from FreshAgentGroup where id = :theId")
        .setLong("theId", groupId).list(FreshAgentGroup.class);

    if (GrouperUtil.length(existingGroups) == 0) {
      mockServiceResponse.setResponseCode(404);
      mockServiceResponse.setContentType("application/json");
      return;
    }

    // delete memberships first
    HibernateSession.byHqlStatic()
        .createQuery("delete from FreshAgentMembership where groupId = :groupId")
        .setLong("groupId", groupId).executeUpdateInt();

    HibernateSession.byHqlStatic()
        .createQuery("delete from FreshAgentGroup where id = :theId")
        .setLong("theId", groupId).executeUpdateInt();

    mockServiceResponse.setResponseCode(204);
    mockServiceResponse.setContentType("application/json");
  }

  // ==================== Agent operations ====================

  /**
   * GET /agents - retrieve all agents
   */
  public void getUsers(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {
    try {
      checkAuthorization(mockServiceRequest);
    } catch (RuntimeException e) {
      mockServiceResponse.setResponseCode(401);
      throw e;
    }

    String emailParam = mockServiceRequest.getHttpServletRequest().getParameter("email");
    String queryParam = mockServiceRequest.getHttpServletRequest().getParameter("query");

    List<FreshAgentUser> freshAgentUsers = null;
    ByHqlStatic query = null;
    QueryOptions queryOptions = new QueryOptions();

    // parse the query parameter format: "attributeName:'value'" or "attributeName:value"
    String queryAttributeName = null;
    String queryAttributeValue = null;

    // email= parameter takes priority (e.g. /api/v2/agents?email=jsmith@upenn.edu)
    if (StringUtils.isNotBlank(emailParam)) {
      queryAttributeName = "email";
      queryAttributeValue = emailParam;
    } else if (StringUtils.isNotBlank(queryParam)) {
      int colonIndex = queryParam.indexOf(':');
      if (colonIndex > 0) {
        queryAttributeName = queryParam.substring(0, colonIndex);
        queryAttributeValue = queryParam.substring(colonIndex + 1);
        // strip surrounding quotes
        if (queryAttributeValue.startsWith("'") && queryAttributeValue.endsWith("'")) {
          queryAttributeValue = queryAttributeValue.substring(1, queryAttributeValue.length() - 1);
        }
      }
    }

    if ("email".equals(queryAttributeName)) {
      query = HibernateSession.byHqlStatic()
          .createQuery("from FreshAgentUser where email = :theEmail")
          .setString("theEmail", queryAttributeValue);
    } else if ("external_id".equals(queryAttributeName)) {
      query = HibernateSession.byHqlStatic()
          .createQuery("from FreshAgentUser where externalId = :theExternalId")
          .setString("theExternalId", queryAttributeValue);
    } else if (queryAttributeName != null) {
      // for custom fields, load all agents and filter in Java
      // since custom fields are stored as JSON
      query = HibernateSession.byHqlStatic().createQuery("from FreshAgentUser");
    } else {
      query = HibernateSession.byHqlStatic().createQuery("from FreshAgentUser");
    }

    queryOptions.sort(new QuerySort("id", true));
    query.options(queryOptions);

    freshAgentUsers = query.list(FreshAgentUser.class);

    ObjectNode resultNode = GrouperUtil.jsonJacksonNode();

    ArrayNode usersArray = GrouperUtil.jsonJacksonArrayNode();

    for (FreshAgentUser freshAgentUser : freshAgentUsers) {

      // for custom field queries, filter in Java since custom fields are stored as JSON
      if (queryAttributeName != null && !"email".equals(queryAttributeName) && !"external_id".equals(queryAttributeName)) {
        boolean matches = false;
        java.util.Map<String, Object> customFields = freshAgentUser.getCustomFields();
        if (customFields != null) {
          Object fieldValue = customFields.get(queryAttributeName);
          if (fieldValue != null && String.valueOf(fieldValue).equals(queryAttributeValue)) {
            matches = true;
          }
        }
        if (!matches) {
          continue;
        }
      }

      ObjectNode objectNode = freshAgentUser.toJson(null);
      objectNode.put("id", freshAgentUser.getId());
      if (freshAgentUser.getActive() != null) {
        objectNode.put("active", freshAgentUser.getActive().booleanValue());
      }
      
      usersArray.add(objectNode);
    }

    resultNode.set("agents", usersArray);

    mockServiceResponse.setResponseCode(200);
    mockServiceResponse.setContentType("application/json");
    mockServiceResponse.setResponseBody(GrouperUtil.jsonJacksonToString(resultNode));
  }

  /**
   * GET /agents/{id} - retrieve a single agent by id
   */
  public void getUser(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {
    try {
      checkAuthorization(mockServiceRequest);
    } catch (RuntimeException e) {
      mockServiceResponse.setResponseCode(401);
      throw e;
    }

    String userIdString = mockServiceRequest.getPostMockNamePaths()[1];

    GrouperUtil.assertion(GrouperUtil.length(userIdString) > 0, "userId is required");

    long userId = GrouperUtil.longValue(userIdString);

    List<FreshAgentUser> freshAgentUsers = HibernateSession.byHqlStatic()
        .createQuery("from FreshAgentUser where id = :theId")
        .setLong("theId", userId).list(FreshAgentUser.class);
    
    if (GrouperUtil.length(freshAgentUsers) == 1) {
      FreshAgentUser freshAgentUser = freshAgentUsers.get(0);

      ObjectNode resultNode = GrouperUtil.jsonJacksonNode();
      ObjectNode objectNode = freshAgentUser.toJson(null);
      objectNode.put("id", freshAgentUser.getId());
      if (freshAgentUser.getActive() != null) {
        objectNode.put("active", freshAgentUser.getActive().booleanValue());
      }
      resultNode.set("agent", objectNode);

      mockServiceResponse.setResponseCode(200);
      mockServiceResponse.setContentType("application/json");
      mockServiceResponse.setResponseBody(GrouperUtil.jsonJacksonToString(resultNode));

    } else if (GrouperUtil.length(freshAgentUsers) == 0) {
      mockServiceResponse.setResponseCode(404);
    } else {
      throw new RuntimeException("usersById: " + GrouperUtil.length(freshAgentUsers) + ", id: " + userId);
    }
  }

  /**
   * POST /agents - create an agent
   */
  public void postUser(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {
    try {
      checkAuthorization(mockServiceRequest);
      checkRequestContentType(mockServiceRequest);
    } catch (RuntimeException e) {
      mockServiceResponse.setResponseCode(401);
      throw e;
    }

    String userJsonString = mockServiceRequest.getRequestBody();
    JsonNode userJsonNode = GrouperUtil.jsonJacksonNode(userJsonString);

    FreshAgentUser freshAgentUser = FreshAgentUser.fromJson(userJsonNode);

    // Default active=true to match real Freshservice behavior
    if (freshAgentUser.getActive() == null) {
      freshAgentUser.setActive(true);
    }

    // Freshservice requires a roles array on create
    JsonNode rolesNode = GrouperUtil.jsonJacksonGetNode(userJsonNode, "roles");
    if (rolesNode == null || !rolesNode.isArray() || rolesNode.size() == 0) {
      mockServiceResponse.setResponseCode(400);
      mockServiceResponse.setContentType("application/json");
      mockServiceResponse.setResponseBody("{\"description\":\"Validation failed\",\"errors\":[{\"field\":\"roles\",\"message\":\"is required\"}]}");
      return;
    }

    // check if email already exists
    if (StringUtils.isNotBlank(freshAgentUser.getEmail())) {
      List<FreshAgentUser> existingUsers = HibernateSession.byHqlStatic()
          .createQuery("from FreshAgentUser where email = :theEmail")
          .setString("theEmail", freshAgentUser.getEmail()).list(FreshAgentUser.class);
      if (existingUsers != null && existingUsers.size() > 0) {
        mockServiceResponse.setResponseCode(409);
        mockServiceResponse.setContentType("application/json");
        mockServiceResponse.setResponseBody("{\"description\":\"Validation failed\",\"errors\":[{\"field\":\"email\",\"message\":\"already exists\"}]}");
        return;
      }
    }

    boolean idSaved = false;

    while(!idSaved) {
      try {
        freshAgentUser.setId(ThreadLocalRandom.current().nextLong(1, 99999999));
        HibernateSession.byObjectStatic().save(freshAgentUser);
        idSaved = true;
      } catch (GrouperDAOException e) {

      }
    }

    ObjectNode resultNode = GrouperUtil.jsonJacksonNode();
    ObjectNode objectNode = freshAgentUser.toJson(null);
    objectNode.put("id", freshAgentUser.getId());
    if (freshAgentUser.getActive() != null) {
      objectNode.put("active", freshAgentUser.getActive().booleanValue());
    }
    resultNode.set("agent", objectNode);

    mockServiceResponse.setResponseCode(201);
    mockServiceResponse.setContentType("application/json");
    mockServiceResponse.setResponseBody(GrouperUtil.jsonJacksonToString(resultNode));
  }

  /**
   * PUT /agents/{id} - update an agent
   */
  public void updateUser(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {
    try {
      checkAuthorization(mockServiceRequest);
      checkRequestContentType(mockServiceRequest);
    } catch (RuntimeException e) {
      mockServiceResponse.setResponseCode(401);
      throw e;
    }

    String userIdString = mockServiceRequest.getPostMockNamePaths()[1];

    mockServiceRequest.getDebugMap().put("userId", userIdString);

    long userId = GrouperUtil.longValue(userIdString);

    List<FreshAgentUser> freshAgentUsers = HibernateSession.byHqlStatic()
        .createQuery("from FreshAgentUser where id = :theId")
        .setLong("theId", userId).list(FreshAgentUser.class);

    if (GrouperUtil.length(freshAgentUsers) == 0) {
      mockServiceRequest.getDebugMap().put("cantFindUser", true);
      mockServiceResponse.setResponseCode(404);
      return;
    }
    if (GrouperUtil.length(freshAgentUsers) > 1) {
      throw new RuntimeException("Found multiple matched agents! " + GrouperUtil.length(freshAgentUsers));
    }

    FreshAgentUser freshAgentUser = freshAgentUsers.get(0);

    String userJsonString = mockServiceRequest.getRequestBody();
    JsonNode userJsonNode = GrouperUtil.jsonJacksonNode(userJsonString);

    String firstName = GrouperUtil.jsonJacksonGetString(userJsonNode, "first_name");
    if (firstName != null) {
      freshAgentUser.setFirstName(firstName);
    }

    String lastName = GrouperUtil.jsonJacksonGetString(userJsonNode, "last_name");
    if (lastName != null) {
      freshAgentUser.setLastName(lastName);
    }

    // agents use "email" (not "primary_email" like requesters)
    String email = GrouperUtil.jsonJacksonGetString(userJsonNode, "email");
    if (email != null) {
      freshAgentUser.setEmail(email);
    }

    String jobTitle = GrouperUtil.jsonJacksonGetString(userJsonNode, "job_title");
    if (jobTitle != null) {
      freshAgentUser.setJobTitle(jobTitle);
    }

    String workPhoneNumber = GrouperUtil.jsonJacksonGetString(userJsonNode, "work_phone_number");
    if (workPhoneNumber != null) {
      freshAgentUser.setWorkPhoneNumber(workPhoneNumber);
    }

    // department_ids is an array, take first value
    JsonNode departmentIdsNode = GrouperUtil.jsonJacksonGetNode(userJsonNode, "department_ids");
    if (departmentIdsNode != null && departmentIdsNode.isArray() && departmentIdsNode.size() > 0) {
      JsonNode firstDeptId = departmentIdsNode.get(0);
      if (firstDeptId != null && firstDeptId.isNumber()) {
        freshAgentUser.setDepartmentId(firstDeptId.longValue());
      }
    }

    Long reportingManagerId = GrouperUtil.jsonJacksonGetLong(userJsonNode, "reporting_manager_id");
    if (reportingManagerId != null) {
      freshAgentUser.setReportingManagerId(reportingManagerId);
    }

    String address = GrouperUtil.jsonJacksonGetString(userJsonNode, "address");
    if (address != null) {
      freshAgentUser.setAddress(address);
    }

    String externalId = GrouperUtil.jsonJacksonGetString(userJsonNode, "external_id");
    if (externalId != null) {
      freshAgentUser.setExternalId(externalId);
    }

    // carry roles through if present
    JsonNode rolesNode = GrouperUtil.jsonJacksonGetNode(userJsonNode, "roles");
    if (rolesNode != null && rolesNode.isArray()) {
      try {
        freshAgentUser.setRolesJson(GrouperUtil.objectMapper.writeValueAsString(rolesNode));
      } catch (Exception e) {
        // best effort
      }
    }

    Boolean active = GrouperUtil.jsonJacksonGetBoolean(userJsonNode, "active");
    if (active != null) {
      freshAgentUser.setActive(active);
    }

    // custom_fields
    JsonNode customFieldsNode = GrouperUtil.jsonJacksonGetNode(userJsonNode, "custom_fields");
    if (customFieldsNode != null && customFieldsNode.isObject()) {
      java.util.Map<String, Object> existingCustomFields = freshAgentUser.getCustomFields();
      if (existingCustomFields == null) {
        existingCustomFields = new java.util.HashMap<>();
      }
      java.util.Iterator<String> fieldNames = customFieldsNode.fieldNames();
      while (fieldNames.hasNext()) {
        String fieldName = fieldNames.next();
        JsonNode fieldValue = customFieldsNode.get(fieldName);
        if (fieldValue == null || fieldValue.isNull()) {
          existingCustomFields.put(fieldName, null);
        } else if (fieldValue.isTextual()) {
          existingCustomFields.put(fieldName, fieldValue.asText());
        } else if (fieldValue.isBoolean()) {
          existingCustomFields.put(fieldName, fieldValue.booleanValue());
        } else if (fieldValue.isNumber()) {
          existingCustomFields.put(fieldName, fieldValue.longValue());
        }
      }
      freshAgentUser.setCustomFields(existingCustomFields);
    }

    HibernateSession.byObjectStatic().saveOrUpdate(freshAgentUser);

    ObjectNode resultNode = GrouperUtil.jsonJacksonNode();
    ObjectNode objectNode = freshAgentUser.toJson(null);
    objectNode.put("id", freshAgentUser.getId());
    if (freshAgentUser.getActive() != null) {
      objectNode.put("active", freshAgentUser.getActive().booleanValue());
    }
    resultNode.set("agent", objectNode);

    mockServiceResponse.setResponseCode(200);
    mockServiceResponse.setContentType("application/json");
    mockServiceResponse.setResponseBody(GrouperUtil.jsonJacksonToString(resultNode));
  }

  /**
   * DELETE /agents/{id} - deactivate/delete an agent
   */
  public void deactivateUser(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {
    try {
      checkAuthorization(mockServiceRequest);
    } catch (RuntimeException e) {
      mockServiceResponse.setResponseCode(401);
      throw e;
    }

    String userIdString = mockServiceRequest.getPostMockNamePaths()[1];

    GrouperUtil.assertion(GrouperUtil.length(userIdString) > 0, "userId is required");

    long userId = GrouperUtil.longValue(userIdString);

    // check if agent exists
    List<FreshAgentUser> existingUsers = HibernateSession.byHqlStatic()
        .createQuery("from FreshAgentUser where id = :theId")
        .setLong("theId", userId).list(FreshAgentUser.class);

    if (GrouperUtil.length(existingUsers) == 0) {
      mockServiceResponse.setResponseCode(404);
      mockServiceResponse.setContentType("application/json");
      return;
    }

    // Freshservice DELETE agent deactivates instead of removing
    FreshAgentUser freshAgentUser = existingUsers.get(0);
    freshAgentUser.setActive(false);
    HibernateSession.byObjectStatic().saveOrUpdate(freshAgentUser);

    // delete all memberships for the deactivated agent
    HibernateSession.byHqlStatic()
        .createQuery("delete from FreshAgentMembership where userId = :userId")
        .setLong("userId", userId).executeUpdateInt();

    mockServiceResponse.setResponseCode(204);
    mockServiceResponse.setContentType("application/json");
  }

  /**
   * PUT /agents/{id}/reactivate - reactivate a deactivated agent
   * Returns 200 if successful.  400 with body if already active.
   */
  public void reactivateUser(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {
    try {
      checkAuthorization(mockServiceRequest);
    } catch (RuntimeException e) {
      mockServiceResponse.setResponseCode(401);
      throw e;
    }

    String userIdString = mockServiceRequest.getPostMockNamePaths()[1];

    GrouperUtil.assertion(GrouperUtil.length(userIdString) > 0, "userId is required");

    long userId = GrouperUtil.longValue(userIdString);

    // check if agent exists
    List<FreshAgentUser> existingUsers = HibernateSession.byHqlStatic()
        .createQuery("from FreshAgentUser where id = :theId")
        .setLong("theId", userId).list(FreshAgentUser.class);

    if (GrouperUtil.length(existingUsers) == 0) {
      mockServiceResponse.setResponseCode(404);
      mockServiceResponse.setContentType("application/json");
      return;
    }

    FreshAgentUser freshAgentUser = existingUsers.get(0);

    // if already active, return 400
    if (freshAgentUser.getActive() != null && freshAgentUser.getActive()) {
      mockServiceResponse.setResponseCode(400);
      mockServiceResponse.setContentType("application/json");
      mockServiceResponse.setResponseBody("{\"code\":\"contact_already_active\",\"message\":\"Contact is already active and cannot be restored.\"}");
      return;
    }

    // reactivate
    freshAgentUser.setActive(true);
    HibernateSession.byObjectStatic().saveOrUpdate(freshAgentUser);

    mockServiceResponse.setResponseCode(200);
    mockServiceResponse.setContentType("application/json");
  }

  /**
   * DELETE /agents/{id}/forget - permanently delete (forget) an agent
   */
  public void forgetUser(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {
    try {
      checkAuthorization(mockServiceRequest);
    } catch (RuntimeException e) {
      mockServiceResponse.setResponseCode(401);
      throw e;
    }

    String userIdString = mockServiceRequest.getPostMockNamePaths()[1];

    GrouperUtil.assertion(GrouperUtil.length(userIdString) > 0, "userId is required");

    long userId = GrouperUtil.longValue(userIdString);

    // check if agent exists
    List<FreshAgentUser> existingUsers = HibernateSession.byHqlStatic()
        .createQuery("from FreshAgentUser where id = :theId")
        .setLong("theId", userId).list(FreshAgentUser.class);

    if (GrouperUtil.length(existingUsers) == 0) {
      mockServiceResponse.setResponseCode(404);
      mockServiceResponse.setContentType("application/json");
      return;
    }

    // permanently delete: remove memberships first, then remove the agent
    HibernateSession.byHqlStatic()
        .createQuery("delete from FreshAgentMembership where userId = :userId")
        .setLong("userId", userId).executeUpdateInt();

    HibernateSession.byHqlStatic()
        .createQuery("delete from FreshAgentUser where id = :theId")
        .setLong("theId", userId).executeUpdateInt();

    mockServiceResponse.setResponseCode(204);
    mockServiceResponse.setContentType("application/json");
  }

  // ==================== Requester operations ====================
  //
  // These model just enough of the Freshservice REQUESTER namespace to support
  // FreshAgentApiCommands.createAgentUser's convert-existing-requester path:
  // look a requester up by email, and convert it into an agent. Requesters live
  // in mock_freshagent_requester (see createTableFreshRequester), a separate
  // table from mock_freshagent_user, and are read/written with plain SQL since
  // there is no Hibernate-mapped FreshAgentRequester entity.

  /**
   * GET /requesters?email=&lt;email&gt; - return the requester matching the email,
   * wrapped in a "requesters" array (0 or 1 elements; email is unique).
   */
  public void getRequestersByEmail(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {
    try {
      checkAuthorization(mockServiceRequest);
    } catch (RuntimeException e) {
      mockServiceResponse.setResponseCode(401);
      throw e;
    }

    String email = mockServiceRequest.getHttpServletRequest().getParameter("email");

    ArrayNode requestersArray = GrouperUtil.jsonJacksonArrayNode();

    if (StringUtils.isNotBlank(email)) {
      Long requesterId = new GcDbAccess().connectionName("grouper")
          .sql("select id from mock_freshagent_requester where email = ?")
          .addBindVar(email).select(Long.class);

      if (requesterId != null) {
        requestersArray.add(requesterRowToJson(requesterId));
      }
    }

    ObjectNode resultNode = GrouperUtil.jsonJacksonNode();
    resultNode.set("requesters", requestersArray);

    mockServiceResponse.setResponseCode(200);
    mockServiceResponse.setContentType("application/json");
    mockServiceResponse.setResponseBody(GrouperUtil.jsonJacksonToString(resultNode));
  }

  /**
   * PUT /requesters/{id}/convert_to_agent - convert a requester into an agent.
   *
   * Removes the row from mock_freshagent_requester and inserts a corresponding
   * row into mock_freshagent_user, reusing the same id (so callers that captured
   * the requester id can find the resulting agent at that id, matching real
   * Freshservice's convert_to_agent semantics). The converted agent is active
   * with no roles; FreshAgentApiCommands.createAgentUser follows this call with
   * an updateAgentUser to apply roles and other writable fields.
   *
   * Returns 404 if no such requester exists.
   */
  public void convertRequesterToAgent(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {
    try {
      checkAuthorization(mockServiceRequest);
    } catch (RuntimeException e) {
      mockServiceResponse.setResponseCode(401);
      throw e;
    }

    String requesterIdString = mockServiceRequest.getPostMockNamePaths()[1];
    GrouperUtil.assertion(GrouperUtil.length(requesterIdString) > 0, "requesterId is required");

    long requesterId = GrouperUtil.longValue(requesterIdString);

    String email = new GcDbAccess().connectionName("grouper")
        .sql("select email from mock_freshagent_requester where id = ?")
        .addBindVar(requesterId).select(String.class);

    if (StringUtils.isBlank(email)) {
      // no id column matched (select returns null on no rows for a scalar select)
      mockServiceResponse.setResponseCode(404);
      mockServiceResponse.setContentType("application/json");
      return;
    }

    String firstName = new GcDbAccess().connectionName("grouper")
        .sql("select first_name from mock_freshagent_requester where id = ?")
        .addBindVar(requesterId).select(String.class);
    String lastName = new GcDbAccess().connectionName("grouper")
        .sql("select last_name from mock_freshagent_requester where id = ?")
        .addBindVar(requesterId).select(String.class);

    // remove from the requester namespace
    new GcDbAccess().connectionName("grouper")
        .sql("delete from mock_freshagent_requester where id = ?")
        .addBindVar(requesterId).executeSql();

    // Insert into the agent namespace, reusing the same id. Converted agents are
    // active with no roles/custom fields; the caller (createAgentUser) applies
    // writable fields via a follow-up update (PUT /agents/{id}).
    //
    // Persist via Hibernate (byObjectStatic().save), NOT raw SQL, so the agent
    // row is created exactly the way every other agent-writing method in this
    // mock creates it (postUser/updateUser/reactivateUser). The follow-up
    // updateUser loads the agent through Hibernate HQL and calls saveOrUpdate;
    // creating this row with a raw INSERT behind Hibernate's back risks a stale
    // first-level cache or a duplicate-id INSERT on that follow-up. Reusing the
    // requester's id as the agent id is safe because ids are unique across the
    // two mock tables (both draw from the same random-id space in seeding).
    FreshAgentUser convertedAgent = new FreshAgentUser();
    convertedAgent.setId(requesterId);
    convertedAgent.setEmail(email);
    convertedAgent.setFirstName(firstName);
    convertedAgent.setLastName(lastName);
    convertedAgent.setActive(true);

    HibernateSession.byObjectStatic().save(convertedAgent);

    ObjectNode resultNode = GrouperUtil.jsonJacksonNode();
    ObjectNode objectNode = convertedAgent.toJson(null);
    objectNode.put("id", convertedAgent.getId());
    objectNode.put("active", true);
    resultNode.set("agent", objectNode);

    mockServiceResponse.setResponseCode(200);
    mockServiceResponse.setContentType("application/json");
    mockServiceResponse.setResponseBody(GrouperUtil.jsonJacksonToString(resultNode));
  }

  /**
   * Build the JSON for a single requester row identified by id. Issued as
   * individual scalar column selects (matching the only GcDbAccess query shape
   * used elsewhere in this mock) rather than a single multi-column row read.
   */
  private ObjectNode requesterRowToJson(Long requesterId) {
    String email = new GcDbAccess().connectionName("grouper")
        .sql("select email from mock_freshagent_requester where id = ?")
        .addBindVar(requesterId).select(String.class);
    String firstName = new GcDbAccess().connectionName("grouper")
        .sql("select first_name from mock_freshagent_requester where id = ?")
        .addBindVar(requesterId).select(String.class);
    String lastName = new GcDbAccess().connectionName("grouper")
        .sql("select last_name from mock_freshagent_requester where id = ?")
        .addBindVar(requesterId).select(String.class);
    String active = new GcDbAccess().connectionName("grouper")
        .sql("select active from mock_freshagent_requester where id = ?")
        .addBindVar(requesterId).select(String.class);

    ObjectNode objectNode = GrouperUtil.jsonJacksonNode();
    objectNode.put("id", requesterId);
    objectNode.put("email", email);
    objectNode.put("first_name", firstName);
    objectNode.put("last_name", lastName);
    objectNode.put("active", "T".equals(active));
    return objectNode;
  }

  // ==================== Request routing ====================

  @Override
  public void handleRequest(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {

    if (!mockTablesThere) {
      ensureFreshserviceMockTables();
    }
    mockTablesThere = true;

    // this must be there and it might be a caching issue:
    //     String configId = GrouperConfig.retrieveConfig().propertyValueStringRequired("grouperTest.exampleFreshAgent.mockExternalSystem.configId");
    // loop 10 times and wait a second each time if not there, to avoid issues with tables not being found
    // if no there at end give a good exception
    // after 10 seconds the caches should have cleared if everything is setup correctly.
    for (int i=0; i<10; i++) {
      // clear cache
      ConfigPropertiesCascadeBase.clearCache();
      String configId = GrouperConfig.retrieveConfig().propertyValueString("grouperTest.exampleFreshAgent.mockExternalSystem.configId");
      if (!StringUtils.isBlank(configId)) {
        break;
      }
      if (i >= 9) {
        throw new RuntimeException("grouper.properties grouperTest.exampleFreshAgent.mockExternalSystem.configId must be set to the configId of the external system used by mock!");
      }
      GrouperUtil.sleep(1000);
    }

    if (GrouperUtil.length(mockServiceRequest.getPostMockNamePaths()) == 0) {
      throw new RuntimeException("Pass in a path!");
    }

    List<String> mockNamePaths = GrouperUtil.toList(mockServiceRequest.getPostMockNamePaths());

    // strip "api/v2" prefix
    GrouperUtil.assertion(mockNamePaths.size() >= 3, "Must start with api/v2/");
    GrouperUtil.assertion(StringUtils.equals(mockNamePaths.get(0), "api"), "first path must be 'api'");
    GrouperUtil.assertion(StringUtils.equals(mockNamePaths.get(1), "v2"), "second path must be 'v2'");

    mockNamePaths = mockNamePaths.subList(2, mockNamePaths.size());

    String[] paths = new String[mockNamePaths.size()];
    paths = mockNamePaths.toArray(paths);

    mockServiceRequest.setPostMockNamePaths(paths);

    String httpMethod = mockServiceRequest.getHttpServletRequest().getMethod();

    // GET requests
    if (StringUtils.equals("GET", httpMethod)) {
      // GET /groups
      if ("groups".equals(mockNamePaths.get(0)) && 1 == mockNamePaths.size()) {
        getGroups(mockServiceRequest, mockServiceResponse);
        return;
      }
      // GET /groups/{id}
      if ("groups".equals(mockNamePaths.get(0)) && 2 == mockNamePaths.size()) {
        getGroup(mockServiceRequest, mockServiceResponse);
        return;
      }
      // GET /agents
      if ("agents".equals(mockNamePaths.get(0)) && 1 == mockNamePaths.size()) {
        getUsers(mockServiceRequest, mockServiceResponse);
        return;
      }
      // GET /agents/{id}
      if ("agents".equals(mockNamePaths.get(0)) && 2 == mockNamePaths.size()) {
        getUser(mockServiceRequest, mockServiceResponse);
        return;
      }
      // GET /requesters?email=...
      if ("requesters".equals(mockNamePaths.get(0)) && 1 == mockNamePaths.size()) {
        getRequestersByEmail(mockServiceRequest, mockServiceResponse);
        return;
      }
    }

    // POST requests
    if (StringUtils.equals("POST", httpMethod)) {
      // POST /groups
      if ("groups".equals(mockNamePaths.get(0)) && 1 == mockNamePaths.size()) {
        postGroup(mockServiceRequest, mockServiceResponse);
        return;
      }
      // POST /agents
      if ("agents".equals(mockNamePaths.get(0)) && 1 == mockNamePaths.size()) {
        postUser(mockServiceRequest, mockServiceResponse);
        return;
      }
    }

    // PUT requests
    if (StringUtils.equals("PUT", httpMethod)) {
      // PUT /groups/{id}
      if ("groups".equals(mockNamePaths.get(0)) && 2 == mockNamePaths.size()) {
        updateGroup(mockServiceRequest, mockServiceResponse);
        return;
      }
      // PUT /agents/{id}/reactivate
      if ("agents".equals(mockNamePaths.get(0)) && 3 == mockNamePaths.size()
          && "reactivate".equals(mockNamePaths.get(2))) {
        reactivateUser(mockServiceRequest, mockServiceResponse);
        return;
      }
      // PUT /agents/{id}
      if ("agents".equals(mockNamePaths.get(0)) && 2 == mockNamePaths.size()) {
        updateUser(mockServiceRequest, mockServiceResponse);
        return;
      }
      // PUT /requesters/{id}/convert_to_agent
      if ("requesters".equals(mockNamePaths.get(0)) && 3 == mockNamePaths.size()
          && "convert_to_agent".equals(mockNamePaths.get(2))) {
        convertRequesterToAgent(mockServiceRequest, mockServiceResponse);
        return;
      }
    }

    // DELETE requests
    if (StringUtils.equals("DELETE", httpMethod)) {
      // DELETE /groups/{id}
      if ("groups".equals(mockNamePaths.get(0)) && 2 == mockNamePaths.size()) {
        deleteGroup(mockServiceRequest, mockServiceResponse);
        return;
      }
      // DELETE /agents/{id}/forget
      if ("agents".equals(mockNamePaths.get(0)) && 3 == mockNamePaths.size()
          && "forget".equals(mockNamePaths.get(2))) {
        forgetUser(mockServiceRequest, mockServiceResponse);
        return;
      }
      // DELETE /agents/{id}
      if ("agents".equals(mockNamePaths.get(0)) && 2 == mockNamePaths.size()) {
        deactivateUser(mockServiceRequest, mockServiceResponse);
        return;
      }
    }

    throw new RuntimeException("Not expecting request: '" + httpMethod
        + "', '" + mockServiceRequest.getPostMockNamePath() + "'");

  }

  private void checkRequestContentType(MockServiceRequest mockServiceRequest) {
    if (!StringUtils.equals(mockServiceRequest.getHttpServletRequest().getContentType(), "application/json")
            && !StringUtils.startsWith(mockServiceRequest.getHttpServletRequest().getContentType(), "application/json;")) {
      throw new RuntimeException("Content type must be application/json");
    }
  }

}
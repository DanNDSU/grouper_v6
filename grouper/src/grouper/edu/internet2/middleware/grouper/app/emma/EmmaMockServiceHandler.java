package edu.internet2.middleware.grouper.app.emma;

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

/**
 * Mock implementation of the Emma API for provisioner testing.
 *
 * Emma paths do not have an api/v2 prefix; the account id lives in the endpoint
 * configuration and is not seen here. The mock therefore routes paths that start
 * with "groups" or "members".
 */
public class EmmaMockServiceHandler extends MockServiceHandler {

  public EmmaMockServiceHandler() {
  }

  public static final Set<String> doNotLogHeaders = GrouperUtil.toSet("authorization");

  public static final Set<String> doNotLogParameters = GrouperUtil.toSet("private_api_key");

  @Override
  public Set<String> doNotLogHeaders() {
    return doNotLogHeaders;
  }

  @Override
  public Set<String> doNotLogParameters() {
    return doNotLogParameters;
  }

  private static boolean mockTablesThere = false;

  public static void ensureEmmaMockTables() {
    try {
      new GcDbAccess().sql("select count(*) from mock_emma_group").select(int.class);
      new GcDbAccess().sql("select count(*) from mock_emma_member").select(int.class);
      new GcDbAccess().sql("select count(*) from mock_emma_membership").select(int.class);
    } catch (Exception e) {

      GrouperDdlUtils.changeDatabase(GrouperMockDdl.V1.getObjectName(), new DdlUtilsChangeDatabase() {

        @Override
        public void changeDatabase(DdlVersionBean ddlVersionBean) {
          Database database = ddlVersionBean.getDatabase();
          EmmaGroup.createTableEmmaGroup(ddlVersionBean, database);
          EmmaMember.createTableEmmaMember(ddlVersionBean, database);
          EmmaMembership.createTableEmmaMembership(ddlVersionBean, database);
        }
      });

    }
  }

  /**
   * check authorization for the request. Emma uses HTTP Basic auth with the public
   * API key as username and the private API key as password.
   */
  public void checkAuthorization(MockServiceRequest mockServiceRequest) {
    String basicAuth = mockServiceRequest.getHttpServletRequest().getHeader("Authorization");

    String userName = Authentication.retrieveUsername(basicAuth);
    String password = Authentication.retrievePassword(basicAuth);

    String configId = GrouperConfig.retrieveConfig().propertyValueStringRequired("grouperTest.exampleEmma.mockExternalSystem.configId");

    String expectedUserName = GrouperLoaderConfig.retrieveConfig().propertyValueStringRequired("grouper.wsBearerToken." + configId + ".basicAuthUser");
    String expectedPassword = GrouperLoaderConfig.retrieveConfig().propertyValueStringRequired("grouper.wsBearerToken." + configId + ".basicAuthPassword");

    if (!StringUtils.equals(expectedUserName, userName)) {
      throw new RuntimeException("Username does not match with what is in grouper config");
    }
    if (!StringUtils.equals(expectedPassword, password)) {
      throw new RuntimeException("password does not match with what is in grouper config");
    }
  }

  // ==================== Group operations ====================

  /**
   * GET /groups - array of all groups
   */
  public void getGroups(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {
    authOr401(mockServiceRequest, mockServiceResponse);

    QueryOptions queryOptions = new QueryOptions();
    queryOptions.sort(new QuerySort("id", true));

    List<EmmaGroup> emmaGroups = HibernateSession.byHqlStatic()
        .createQuery("from EmmaGroup").options(queryOptions).list(EmmaGroup.class);

    ArrayNode groupsArray = GrouperUtil.jsonJacksonArrayNode();
    for (EmmaGroup emmaGroup : emmaGroups) {
      groupsArray.add(groupToEmmaJson(emmaGroup));
    }

    ok(mockServiceResponse, GrouperUtil.jsonJacksonToString(groupsArray));
  }

  /**
   * GET /groups/{id} - single group object
   */
  public void getGroup(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {
    authOr401(mockServiceRequest, mockServiceResponse);

    long groupId = GrouperUtil.longValue(mockServiceRequest.getPostMockNamePaths()[1]);

    List<EmmaGroup> emmaGroups = HibernateSession.byHqlStatic()
        .createQuery("from EmmaGroup where id = :theId")
        .setLong("theId", groupId).list(EmmaGroup.class);

    if (GrouperUtil.length(emmaGroups) == 1) {
      ok(mockServiceResponse, GrouperUtil.jsonJacksonToString(groupToEmmaJson(emmaGroups.get(0))));
    } else {
      mockServiceResponse.setResponseCode(404);
    }
  }

  /**
   * POST /groups - body { "groups": [ { "group_name": ... } ] }
   * returns an array of { "member_group_id": ..., "group_name": ... }
   */
  public void postGroups(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {
    authAndContentTypeOr401(mockServiceRequest, mockServiceResponse);

    JsonNode body = GrouperUtil.jsonJacksonNode(mockServiceRequest.getRequestBody());
    ArrayNode groupsToCreate = (ArrayNode) body.get("groups");

    ArrayNode resultArray = GrouperUtil.jsonJacksonArrayNode();

    for (int i = 0; i < (groupsToCreate == null ? 0 : groupsToCreate.size()); i++) {
      JsonNode groupNode = groupsToCreate.get(i);
      String groupName = GrouperUtil.jsonJacksonGetString(groupNode, "group_name");

      EmmaGroup emmaGroup = new EmmaGroup();
      emmaGroup.setName(groupName);

      boolean idSaved = false;
      while (!idSaved) {
        try {
          emmaGroup.setId(ThreadLocalRandom.current().nextLong(1, 99999999));
          HibernateSession.byObjectStatic().save(emmaGroup);
          idSaved = true;
        } catch (GrouperDAOException e) {
          // id collision, retry
        }
      }

      resultArray.add(groupToEmmaJson(emmaGroup));
    }

    ok(mockServiceResponse, GrouperUtil.jsonJacksonToString(resultArray));
  }

  /**
   * PUT /groups/{id} - body { "group_name": ... }, returns true
   */
  public void updateGroup(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {
    authAndContentTypeOr401(mockServiceRequest, mockServiceResponse);

    long groupId = GrouperUtil.longValue(mockServiceRequest.getPostMockNamePaths()[1]);

    List<EmmaGroup> emmaGroups = HibernateSession.byHqlStatic()
        .createQuery("from EmmaGroup where id = :theId")
        .setLong("theId", groupId).list(EmmaGroup.class);

    if (GrouperUtil.length(emmaGroups) == 0) {
      mockServiceResponse.setResponseCode(404);
      return;
    }

    EmmaGroup emmaGroup = emmaGroups.get(0);
    JsonNode body = GrouperUtil.jsonJacksonNode(mockServiceRequest.getRequestBody());
    String groupName = GrouperUtil.jsonJacksonGetString(body, "group_name");
    if (StringUtils.isNotBlank(groupName)) {
      emmaGroup.setName(groupName);
    }
    HibernateSession.byObjectStatic().saveOrUpdate(emmaGroup);

    ok(mockServiceResponse, "true");
  }

  /**
   * DELETE /groups/{id} - returns true
   */
  public void deleteGroup(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {
    authOr401(mockServiceRequest, mockServiceResponse);

    long groupId = GrouperUtil.longValue(mockServiceRequest.getPostMockNamePaths()[1]);

    List<EmmaGroup> existing = HibernateSession.byHqlStatic()
        .createQuery("from EmmaGroup where id = :theId")
        .setLong("theId", groupId).list(EmmaGroup.class);

    if (GrouperUtil.length(existing) == 0) {
      mockServiceResponse.setResponseCode(404);
      return;
    }

    HibernateSession.byHqlStatic()
        .createQuery("delete from EmmaMembership where groupId = :groupId")
        .setLong("groupId", groupId).executeUpdateInt();
    HibernateSession.byHqlStatic()
        .createQuery("delete from EmmaGroup where id = :theId")
        .setLong("theId", groupId).executeUpdateInt();

    ok(mockServiceResponse, "true");
  }

  // ==================== Member operations ====================

  /**
   * GET /members - array of all members
   */
  public void getMembers(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {
    authOr401(mockServiceRequest, mockServiceResponse);

    QueryOptions queryOptions = new QueryOptions();
    queryOptions.sort(new QuerySort("id", true));

    List<EmmaMember> members = HibernateSession.byHqlStatic()
        .createQuery("from EmmaMember").options(queryOptions).list(EmmaMember.class);

    ArrayNode membersArray = GrouperUtil.jsonJacksonArrayNode();
    for (EmmaMember member : members) {
      membersArray.add(memberToEmmaJson(member));
    }

    ok(mockServiceResponse, GrouperUtil.jsonJacksonToString(membersArray));
  }

  /**
   * GET /members/{id} - single member object (404 if not found)
   */
  public void getMember(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {
    authOr401(mockServiceRequest, mockServiceResponse);

    long memberId = GrouperUtil.longValue(mockServiceRequest.getPostMockNamePaths()[1]);

    List<EmmaMember> members = HibernateSession.byHqlStatic()
        .createQuery("from EmmaMember where id = :theId")
        .setLong("theId", memberId).list(EmmaMember.class);

    if (GrouperUtil.length(members) == 1) {
      ok(mockServiceResponse, GrouperUtil.jsonJacksonToString(memberToEmmaJson(members.get(0))));
    } else {
      mockServiceResponse.setResponseCode(404);
    }
  }

  /**
   * GET /members/email/{email} - single member object by email (404 if not found)
   */
  public void getMemberByEmail(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {
    authOr401(mockServiceRequest, mockServiceResponse);

    String email;
    try {
      email = java.net.URLDecoder.decode(mockServiceRequest.getPostMockNamePaths()[2], "UTF-8");
    } catch (java.io.UnsupportedEncodingException uee) {
      throw new RuntimeException(uee);
    }

    List<EmmaMember> members = HibernateSession.byHqlStatic()
        .createQuery("from EmmaMember where email = :theEmail")
        .setString("theEmail", email).list(EmmaMember.class);

    if (GrouperUtil.length(members) >= 1) {
      ok(mockServiceResponse, GrouperUtil.jsonJacksonToString(memberToEmmaJson(members.get(0))));
    } else {
      mockServiceResponse.setResponseCode(404);
    }
  }

  /**
   * POST /members/add - body { "email": ..., "fields": { ... } }
   * upserts on email; returns { "status": ..., "added": ..., "member_id": ... }
   */
  public void addMember(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {
    authAndContentTypeOr401(mockServiceRequest, mockServiceResponse);

    JsonNode body = GrouperUtil.jsonJacksonNode(mockServiceRequest.getRequestBody());
    EmmaMember incoming = EmmaMember.fromJson(body);

    if (StringUtils.isBlank(incoming.getEmail())) {
      mockServiceResponse.setResponseCode(400);
      mockServiceResponse.setResponseBody("{\"message\":\"email is required\"}");
      return;
    }

    List<EmmaMember> existing = HibernateSession.byHqlStatic()
        .createQuery("from EmmaMember where email = :theEmail")
        .setString("theEmail", incoming.getEmail()).list(EmmaMember.class);

    boolean added;
    EmmaMember member;
    if (GrouperUtil.length(existing) >= 1) {
      // update existing
      member = existing.get(0);
      member.setFirstName(incoming.getFirstName());
      member.setLastName(incoming.getLastName());
      member.setCustomFields(incoming.getCustomFields());
      HibernateSession.byObjectStatic().saveOrUpdate(member);
      added = false;
    } else {
      member = incoming;
      if (member.getMemberStatusId() == null) {
        member.setMemberStatusId("a");
      }
      boolean idSaved = false;
      while (!idSaved) {
        try {
          member.setId(ThreadLocalRandom.current().nextLong(1, 99999999));
          HibernateSession.byObjectStatic().save(member);
          idSaved = true;
        } catch (GrouperDAOException e) {
          // id collision, retry
        }
      }
      added = true;
    }

    ObjectNode resultNode = GrouperUtil.jsonJacksonNode();
    resultNode.put("status", member.getMemberStatusId() == null ? "a" : member.getMemberStatusId());
    resultNode.put("added", added);
    resultNode.put("member_id", member.getId());
    ok(mockServiceResponse, GrouperUtil.jsonJacksonToString(resultNode));
  }

  /**
   * PUT /members/{id} - body { "email": ..., "fields": { ... } }, returns true
   */
  public void updateMember(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {
    authAndContentTypeOr401(mockServiceRequest, mockServiceResponse);

    long memberId = GrouperUtil.longValue(mockServiceRequest.getPostMockNamePaths()[1]);

    List<EmmaMember> members = HibernateSession.byHqlStatic()
        .createQuery("from EmmaMember where id = :theId")
        .setLong("theId", memberId).list(EmmaMember.class);

    if (GrouperUtil.length(members) == 0) {
      mockServiceResponse.setResponseCode(404);
      return;
    }

    EmmaMember member = members.get(0);
    JsonNode body = GrouperUtil.jsonJacksonNode(mockServiceRequest.getRequestBody());

    String email = GrouperUtil.jsonJacksonGetString(body, "email");
    if (email != null) {
      member.setEmail(email);
    }
    String statusTo = GrouperUtil.jsonJacksonGetString(body, "status_to");
    if (statusTo != null) {
      member.setMemberStatusId(statusTo);
    }
    JsonNode fieldsNode = GrouperUtil.jsonJacksonGetNode(body, "fields");
    if (fieldsNode != null && fieldsNode.isObject()) {
      String firstName = GrouperUtil.jsonJacksonGetString(fieldsNode, "first_name");
      if (firstName != null) {
        member.setFirstName(firstName);
      }
      String lastName = GrouperUtil.jsonJacksonGetString(fieldsNode, "last_name");
      if (lastName != null) {
        member.setLastName(lastName);
      }
      // merge remaining user-defined fields
      java.util.Map<String, Object> customFields = member.getCustomFields();
      if (customFields == null) {
        customFields = new java.util.HashMap<>();
      }
      java.util.Iterator<String> fieldNames = fieldsNode.fieldNames();
      while (fieldNames.hasNext()) {
        String fieldName = fieldNames.next();
        if ("first_name".equals(fieldName) || "last_name".equals(fieldName)) {
          continue;
        }
        JsonNode value = fieldsNode.get(fieldName);
        if (value == null || value.isNull()) {
          customFields.put(fieldName, null);
        } else if (value.isTextual()) {
          customFields.put(fieldName, value.asText());
        } else if (value.isBoolean()) {
          customFields.put(fieldName, value.booleanValue());
        } else if (value.isNumber()) {
          customFields.put(fieldName, value.longValue());
        }
      }
      member.setCustomFields(customFields);
    }

    HibernateSession.byObjectStatic().saveOrUpdate(member);
    ok(mockServiceResponse, "true");
  }

  /**
   * DELETE /members/{id} - archive a member, returns true
   */
  public void deleteMember(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {
    authOr401(mockServiceRequest, mockServiceResponse);

    long memberId = GrouperUtil.longValue(mockServiceRequest.getPostMockNamePaths()[1]);

    List<EmmaMember> existing = HibernateSession.byHqlStatic()
        .createQuery("from EmmaMember where id = :theId")
        .setLong("theId", memberId).list(EmmaMember.class);

    if (GrouperUtil.length(existing) == 0) {
      mockServiceResponse.setResponseCode(404);
      return;
    }

    HibernateSession.byHqlStatic()
        .createQuery("delete from EmmaMembership where userId = :userId")
        .setLong("userId", memberId).executeUpdateInt();
    HibernateSession.byHqlStatic()
        .createQuery("delete from EmmaMember where id = :theId")
        .setLong("theId", memberId).executeUpdateInt();

    ok(mockServiceResponse, "true");
  }

  // ==================== Membership operations ====================

  /**
   * GET /groups/{id}/members - array of members in a group
   */
  public void getGroupMembers(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {
    authOr401(mockServiceRequest, mockServiceResponse);

    long groupId = GrouperUtil.longValue(mockServiceRequest.getPostMockNamePaths()[1]);

    List<EmmaMembership> memberships = HibernateSession.byHqlStatic()
        .createQuery("from EmmaMembership where groupId = :theGroupId")
        .setLong("theGroupId", groupId).list(EmmaMembership.class);

    ArrayNode membersArray = GrouperUtil.jsonJacksonArrayNode();
    for (EmmaMembership membership : memberships) {
      List<EmmaMember> members = HibernateSession.byHqlStatic()
          .createQuery("from EmmaMember where id = :theId")
          .setLong("theId", membership.getUserId()).list(EmmaMember.class);
      if (GrouperUtil.length(members) == 1) {
        membersArray.add(memberToEmmaJson(members.get(0)));
      }
    }

    ok(mockServiceResponse, GrouperUtil.jsonJacksonToString(membersArray));
  }

  /**
   * PUT /groups/{id}/members - body { "member_ids": [ ... ] } to add members.
   * Returns an array of the member ids added.
   */
  public void addGroupMembers(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {
    authAndContentTypeOr401(mockServiceRequest, mockServiceResponse);

    long groupId = GrouperUtil.longValue(mockServiceRequest.getPostMockNamePaths()[1]);

    List<EmmaGroup> groups = HibernateSession.byHqlStatic()
        .createQuery("from EmmaGroup where id = :theId")
        .setLong("theId", groupId).list(EmmaGroup.class);
    if (GrouperUtil.length(groups) == 0) {
      mockServiceResponse.setResponseCode(404);
      return;
    }

    JsonNode body = GrouperUtil.jsonJacksonNode(mockServiceRequest.getRequestBody());
    ArrayNode memberIdsNode = (ArrayNode) body.get("member_ids");

    ArrayNode addedArray = GrouperUtil.jsonJacksonArrayNode();

    for (int i = 0; i < (memberIdsNode == null ? 0 : memberIdsNode.size()); i++) {
      long memberId = memberIdsNode.get(i).longValue();

      List<EmmaMember> members = HibernateSession.byHqlStatic()
          .createQuery("from EmmaMember where id = :theId")
          .setLong("theId", memberId).list(EmmaMember.class);
      if (GrouperUtil.length(members) == 0) {
        continue;
      }

      List<EmmaMembership> existing = HibernateSession.byHqlStatic()
          .createQuery("from EmmaMembership where groupId = :groupId and userId = :userId")
          .setLong("groupId", groupId).setLong("userId", memberId).list(EmmaMembership.class);

      if (GrouperUtil.length(existing) == 0) {
        EmmaMembership membership = new EmmaMembership();
        membership.setGroupId(groupId);
        membership.setUserId(memberId);
        membership.setId(ThreadLocalRandom.current().nextLong(1, 99999999));
        HibernateSession.byObjectStatic().save(membership);
        addedArray.add(memberId);
      }
    }

    ok(mockServiceResponse, GrouperUtil.jsonJacksonToString(addedArray));
  }

  /**
   * PUT /groups/{id}/members/remove - body { "member_ids": [ ... ] } to remove members.
   * Returns an array of the member ids removed.
   */
  public void removeGroupMembers(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {
    authAndContentTypeOr401(mockServiceRequest, mockServiceResponse);

    long groupId = GrouperUtil.longValue(mockServiceRequest.getPostMockNamePaths()[1]);

    JsonNode body = GrouperUtil.jsonJacksonNode(mockServiceRequest.getRequestBody());
    ArrayNode memberIdsNode = (ArrayNode) body.get("member_ids");

    ArrayNode removedArray = GrouperUtil.jsonJacksonArrayNode();

    for (int i = 0; i < (memberIdsNode == null ? 0 : memberIdsNode.size()); i++) {
      long memberId = memberIdsNode.get(i).longValue();
      int deleted = HibernateSession.byHqlStatic()
          .createQuery("delete from EmmaMembership where groupId = :groupId and userId = :userId")
          .setLong("groupId", groupId).setLong("userId", memberId).executeUpdateInt();
      if (deleted > 0) {
        removedArray.add(memberId);
      }
    }

    ok(mockServiceResponse, GrouperUtil.jsonJacksonToString(removedArray));
  }

  // ==================== JSON helpers ====================

  /**
   * Render an EmmaGroup as the Emma API group JSON (member_group_id, group_name).
   */
  private static ObjectNode groupToEmmaJson(EmmaGroup emmaGroup) {
    ObjectNode node = GrouperUtil.jsonJacksonNode();
    node.put("member_group_id", emmaGroup.getId());
    node.put("group_name", emmaGroup.getName());
    node.put("group_type", "g");
    return node;
  }

  /**
   * Render an EmmaMember as the Emma API member JSON (member_id, email, fields, ...).
   */
  private static ObjectNode memberToEmmaJson(EmmaMember member) {
    ObjectNode node = GrouperUtil.jsonJacksonNode();
    node.put("member_id", member.getId());
    node.put("email", member.getEmail());
    node.put("member_status_id", member.getMemberStatusId() == null ? "a" : member.getMemberStatusId());

    ObjectNode fields = GrouperUtil.jsonJacksonNode();
    if (member.getFirstName() != null) {
      fields.put("first_name", member.getFirstName());
    }
    if (member.getLastName() != null) {
      fields.put("last_name", member.getLastName());
    }
    if (member.getCustomFields() != null) {
      for (java.util.Map.Entry<String, Object> entry : member.getCustomFields().entrySet()) {
        Object value = entry.getValue();
        if (value == null) {
          fields.putNull(entry.getKey());
        } else if (value instanceof String) {
          fields.put(entry.getKey(), (String) value);
        } else if (value instanceof Boolean) {
          fields.put(entry.getKey(), (Boolean) value);
        } else if (value instanceof Number) {
          fields.put(entry.getKey(), ((Number) value).longValue());
        }
      }
    }
    node.set("fields", fields);
    return node;
  }

  // ==================== small response helpers ====================

  private void authOr401(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {
    try {
      checkAuthorization(mockServiceRequest);
    } catch (RuntimeException e) {
      mockServiceResponse.setResponseCode(401);
      throw e;
    }
  }

  private void authAndContentTypeOr401(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {
    try {
      checkAuthorization(mockServiceRequest);
      checkRequestContentType(mockServiceRequest);
    } catch (RuntimeException e) {
      mockServiceResponse.setResponseCode(401);
      throw e;
    }
  }

  private void ok(MockServiceResponse mockServiceResponse, String body) {
    mockServiceResponse.setResponseCode(200);
    mockServiceResponse.setContentType("application/json");
    mockServiceResponse.setResponseBody(body);
  }

  // ==================== Request routing ====================

  @Override
  public void handleRequest(MockServiceRequest mockServiceRequest, MockServiceResponse mockServiceResponse) {

    if (!mockTablesThere) {
      ensureEmmaMockTables();
    }
    mockTablesThere = true;

    for (int i = 0; i < 10; i++) {
      ConfigPropertiesCascadeBase.clearCache();
      String configId = GrouperConfig.retrieveConfig().propertyValueString("grouperTest.exampleEmma.mockExternalSystem.configId");
      if (!StringUtils.isBlank(configId)) {
        break;
      }
      if (i >= 9) {
        throw new RuntimeException("grouper.properties grouperTest.exampleEmma.mockExternalSystem.configId must be set to the configId of the external system used by mock!");
      }
      GrouperUtil.sleep(1000);
    }

    if (GrouperUtil.length(mockServiceRequest.getPostMockNamePaths()) == 0) {
      throw new RuntimeException("Pass in a path!");
    }

    List<String> mockNamePaths = GrouperUtil.toList(mockServiceRequest.getPostMockNamePaths());

    String httpMethod = mockServiceRequest.getHttpServletRequest().getMethod();
    String first = mockNamePaths.get(0);
    int size = mockNamePaths.size();

    // ---------------- GET ----------------
    if (StringUtils.equals("GET", httpMethod)) {
      // GET /groups
      if ("groups".equals(first) && size == 1) {
        getGroups(mockServiceRequest, mockServiceResponse);
        return;
      }
      // GET /groups/{id}/members
      if ("groups".equals(first) && size == 3 && "members".equals(mockNamePaths.get(2))) {
        getGroupMembers(mockServiceRequest, mockServiceResponse);
        return;
      }
      // GET /groups/{id}
      if ("groups".equals(first) && size == 2) {
        getGroup(mockServiceRequest, mockServiceResponse);
        return;
      }
      // GET /members/email/{email}
      if ("members".equals(first) && size == 3 && "email".equals(mockNamePaths.get(1))) {
        getMemberByEmail(mockServiceRequest, mockServiceResponse);
        return;
      }
      // GET /members
      if ("members".equals(first) && size == 1) {
        getMembers(mockServiceRequest, mockServiceResponse);
        return;
      }
      // GET /members/{id}
      if ("members".equals(first) && size == 2) {
        getMember(mockServiceRequest, mockServiceResponse);
        return;
      }
    }

    // ---------------- POST ----------------
    if (StringUtils.equals("POST", httpMethod)) {
      // POST /groups
      if ("groups".equals(first) && size == 1) {
        postGroups(mockServiceRequest, mockServiceResponse);
        return;
      }
      // POST /members/add
      if ("members".equals(first) && size == 2 && "add".equals(mockNamePaths.get(1))) {
        addMember(mockServiceRequest, mockServiceResponse);
        return;
      }
    }

    // ---------------- PUT ----------------
    if (StringUtils.equals("PUT", httpMethod)) {
      // PUT /groups/{id}/members/remove
      if ("groups".equals(first) && size == 4 && "members".equals(mockNamePaths.get(2))
          && "remove".equals(mockNamePaths.get(3))) {
        removeGroupMembers(mockServiceRequest, mockServiceResponse);
        return;
      }
      // PUT /groups/{id}/members
      if ("groups".equals(first) && size == 3 && "members".equals(mockNamePaths.get(2))) {
        addGroupMembers(mockServiceRequest, mockServiceResponse);
        return;
      }
      // PUT /groups/{id}
      if ("groups".equals(first) && size == 2) {
        updateGroup(mockServiceRequest, mockServiceResponse);
        return;
      }
      // PUT /members/{id}
      if ("members".equals(first) && size == 2) {
        updateMember(mockServiceRequest, mockServiceResponse);
        return;
      }
    }

    // ---------------- DELETE ----------------
    if (StringUtils.equals("DELETE", httpMethod)) {
      // DELETE /groups/{id}
      if ("groups".equals(first) && size == 2) {
        deleteGroup(mockServiceRequest, mockServiceResponse);
        return;
      }
      // DELETE /members/{id}
      if ("members".equals(first) && size == 2) {
        deleteMember(mockServiceRequest, mockServiceResponse);
        return;
      }
    }

    throw new RuntimeException("Not expecting request: '" + httpMethod
        + "', '" + mockServiceRequest.getPostMockNamePath() + "'");
  }

  private void checkRequestContentType(MockServiceRequest mockServiceRequest) {
    String contentType = mockServiceRequest.getHttpServletRequest().getContentType();
    if (!StringUtils.equals(contentType, "application/json")
        && !StringUtils.startsWith(contentType, "application/json;")) {
      throw new RuntimeException("Content type must be application/json");
    }
  }

}

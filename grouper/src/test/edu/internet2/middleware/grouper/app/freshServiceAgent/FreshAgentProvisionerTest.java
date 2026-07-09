package edu.internet2.middleware.grouper.app.freshServiceAgent;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;

import edu.internet2.middleware.grouper.Group;
import edu.internet2.middleware.grouper.GroupSave;
import edu.internet2.middleware.grouper.GrouperSession;
import edu.internet2.middleware.grouper.Stem;
import edu.internet2.middleware.grouper.StemSave;
import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioningAttributeValue;
import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioningBaseTest;
import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioningOutput;
import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioningService;
import edu.internet2.middleware.grouper.app.provisioning.ProvisioningObjectChangeAction;
import edu.internet2.middleware.grouper.cfg.dbConfig.GrouperDbConfig;
import edu.internet2.middleware.grouper.helper.SubjectTestHelper;
import edu.internet2.middleware.grouper.misc.GrouperStartup;
import edu.internet2.middleware.grouper.misc.SaveMode;
import edu.internet2.middleware.grouper.util.GrouperUtil;
import edu.internet2.middleware.grouperClient.config.ConfigPropertiesCascadeBase;
import edu.internet2.middleware.grouperClient.jdbc.GcDbAccess;
import junit.textui.TestRunner;

public class FreshAgentProvisionerTest extends GrouperProvisioningBaseTest {

  public static void main(String[] args) {

    FreshAgentMockServiceHandler.ensureFreshserviceMockTables();
    TestRunner.run(new FreshAgentProvisionerTest("testRetrieveAgentUserByCustomAttribute"));

    System.exit(0);
  }

  @Override
  public String defaultConfigId() {
    return "freshAgentProvisioner";
  }

  public static boolean startTomcat = false;

  public FreshAgentProvisionerTest(String name) {
    super(name);
  }

  @Override
  protected void setUp() {
    super.setUp();

    FreshAgentMockServiceHandler.ensureFreshserviceMockTables();

    new GcDbAccess().connectionName("grouper").sql("delete from mock_freshagent_membership").executeSql();
    new GcDbAccess().connectionName("grouper").sql("delete from mock_freshagent_group").executeSql();
    new GcDbAccess().connectionName("grouper").sql("delete from mock_freshagent_user").executeSql();
  }

  public void testRetrieveAgentGroups() {

    FreshAgentProvisionerTestUtils.setupFreshAgentExternalSystem();

    // insert some groups directly into the mock table
    new GcDbAccess().connectionName("grouper").sql("insert into mock_freshagent_group (id, name, description) values (1001, 'IT Support', 'IT support team group')").executeSql();
    new GcDbAccess().connectionName("grouper").sql("insert into mock_freshagent_group (id, name, description) values (1002, 'HR Team', 'Human resources department')").executeSql();
    new GcDbAccess().connectionName("grouper").sql("insert into mock_freshagent_group (id, name, description) values (1003, 'Engineering', 'Engineering department')").executeSql();

    List<FreshAgentGroup> groups = FreshAgentApiCommands.retrieveAgentGroups("freshServiceDev");

    assertEquals(3, groups.size());

    Map<Long, FreshAgentGroup> groupById = new HashMap<Long, FreshAgentGroup>();
    for (FreshAgentGroup group : groups) {
      groupById.put(group.getId(), group);
    }

    FreshAgentGroup group1001 = groupById.get(1001L);
    assertNotNull(group1001);
    assertEquals("IT Support", group1001.getName());
    assertEquals("IT support team group", group1001.getDescription());

    FreshAgentGroup group1002 = groupById.get(1002L);
    assertNotNull(group1002);
    assertEquals("HR Team", group1002.getName());
    assertEquals("Human resources department", group1002.getDescription());

    FreshAgentGroup group1003 = groupById.get(1003L);
    assertNotNull(group1003);
    assertEquals("Engineering", group1003.getName());
    assertEquals("Engineering department", group1003.getDescription());
  }

  public void testRetrieveAgentGroup() {

    FreshAgentProvisionerTestUtils.setupFreshAgentExternalSystem();

    // insert a group directly into the mock table
    new GcDbAccess().connectionName("grouper").sql("insert into mock_freshagent_group (id, name, description) values (1001, 'IT Support', 'IT support team group')").executeSql();

    // retrieve existing group
    FreshAgentGroup group = FreshAgentApiCommands.retrieveAgentGroup("freshServiceDev", 1001L);

    assertNotNull(group);
    assertEquals(1001L, (long)group.getId());
    assertEquals("IT Support", group.getName());
    assertEquals("IT support team group", group.getDescription());

    // retrieve non-existing group should return null
    FreshAgentGroup notFound = FreshAgentApiCommands.retrieveAgentGroup("freshServiceDev", 9999L);

    assertNull(notFound);
  }

  public void testCreateAgentGroup() {

    FreshAgentProvisionerTestUtils.setupFreshAgentExternalSystem();

    FreshAgentGroup groupToCreate = new FreshAgentGroup();
    groupToCreate.setName("Branch Managers");
    groupToCreate.setDescription("Agent group for branch managers across all locations");

    // create the group
    FreshAgentGroup createdGroup = FreshAgentApiCommands.createAgentGroup("freshServiceDev", groupToCreate);

    assertNotNull(createdGroup);
    assertTrue(createdGroup.getId() > 0);
    assertEquals("Branch Managers", createdGroup.getName());
    assertEquals("Agent group for branch managers across all locations", createdGroup.getDescription());

    // verify it can be retrieved
    FreshAgentGroup retrievedGroup = FreshAgentApiCommands.retrieveAgentGroup("freshServiceDev", createdGroup.getId());

    assertNotNull(retrievedGroup);
    assertEquals(createdGroup.getId(), retrievedGroup.getId());
    assertEquals("Branch Managers", retrievedGroup.getName());
    assertEquals("Agent group for branch managers across all locations", retrievedGroup.getDescription());

    // creating a group with the same name should throw an exception (409)
    FreshAgentGroup duplicateGroup = new FreshAgentGroup();
    duplicateGroup.setName("Branch Managers");
    duplicateGroup.setDescription("duplicate");

    try {
      FreshAgentApiCommands.createAgentGroup("freshServiceDev", duplicateGroup);
      fail("Should have thrown exception for duplicate group name");
    } catch (RuntimeException e) {
      assertTrue(e.getMessage().contains("already exists"));
    }
  }

  public void testDeleteAgentGroup() {

    FreshAgentProvisionerTestUtils.setupFreshAgentExternalSystem();

    // create a group to delete
    FreshAgentGroup groupToCreate = new FreshAgentGroup();
    groupToCreate.setName("Temp Group");
    groupToCreate.setDescription("Temporary group for delete test");

    FreshAgentGroup createdGroup = FreshAgentApiCommands.createAgentGroup("freshServiceDev", groupToCreate);
    assertNotNull(createdGroup);
    assertTrue(createdGroup.getId() > 0);

    // verify it exists
    FreshAgentGroup retrievedGroup = FreshAgentApiCommands.retrieveAgentGroup("freshServiceDev", createdGroup.getId());
    assertNotNull(retrievedGroup);

    // delete the group
    FreshAgentApiCommands.deleteAgentGroup("freshServiceDev", createdGroup.getId());

    // verify it no longer exists
    FreshAgentGroup deletedGroup = FreshAgentApiCommands.retrieveAgentGroup("freshServiceDev", createdGroup.getId());
    assertNull(deletedGroup);

    // delete again should not throw an error (404 is acceptable)
    FreshAgentApiCommands.deleteAgentGroup("freshServiceDev", createdGroup.getId());
  }

  public void testUpdateAgentGroup() {

    FreshAgentProvisionerTestUtils.setupFreshAgentExternalSystem();

    // create a group to update
    FreshAgentGroup groupToCreate = new FreshAgentGroup();
    groupToCreate.setName("Original Name");
    groupToCreate.setDescription("Original description");

    FreshAgentGroup createdGroup = FreshAgentApiCommands.createAgentGroup("freshServiceDev", groupToCreate);
    assertNotNull(createdGroup);
    assertTrue(createdGroup.getId() > 0);

    // update name only
    FreshAgentGroup groupToUpdate = new FreshAgentGroup();
    groupToUpdate.setId(createdGroup.getId());
    groupToUpdate.setName("Human Resources");

    Map<String, ProvisioningObjectChangeAction> fieldsToUpdate = new LinkedHashMap<String, ProvisioningObjectChangeAction>();
    fieldsToUpdate.put("name", ProvisioningObjectChangeAction.update);

    FreshAgentGroup updatedGroup = FreshAgentApiCommands.updateAgentGroup("freshServiceDev", groupToUpdate, fieldsToUpdate);

    assertNotNull(updatedGroup);
    assertEquals(createdGroup.getId(), updatedGroup.getId());
    assertEquals("Human Resources", updatedGroup.getName());
    assertEquals("Original description", updatedGroup.getDescription());

    // update description only
    FreshAgentGroup groupToUpdate2 = new FreshAgentGroup();
    groupToUpdate2.setId(createdGroup.getId());
    groupToUpdate2.setDescription("Agent group for HR employees");

    Map<String, ProvisioningObjectChangeAction> fieldsToUpdate2 = new LinkedHashMap<String, ProvisioningObjectChangeAction>();
    fieldsToUpdate2.put("description", ProvisioningObjectChangeAction.update);

    FreshAgentGroup updatedGroup2 = FreshAgentApiCommands.updateAgentGroup("freshServiceDev", groupToUpdate2, fieldsToUpdate2);

    assertNotNull(updatedGroup2);
    assertEquals(createdGroup.getId(), updatedGroup2.getId());
    assertEquals("Human Resources", updatedGroup2.getName());
    assertEquals("Agent group for HR employees", updatedGroup2.getDescription());

    // update both name and description
    FreshAgentGroup groupToUpdate3 = new FreshAgentGroup();
    groupToUpdate3.setId(createdGroup.getId());
    groupToUpdate3.setName("Engineering");
    groupToUpdate3.setDescription("Engineering department group");

    Map<String, ProvisioningObjectChangeAction> fieldsToUpdate3 = new LinkedHashMap<String, ProvisioningObjectChangeAction>();
    fieldsToUpdate3.put("name", ProvisioningObjectChangeAction.update);
    fieldsToUpdate3.put("description", ProvisioningObjectChangeAction.update);

    FreshAgentGroup updatedGroup3 = FreshAgentApiCommands.updateAgentGroup("freshServiceDev", groupToUpdate3, fieldsToUpdate3);

    assertNotNull(updatedGroup3);
    assertEquals(createdGroup.getId(), updatedGroup3.getId());
    assertEquals("Engineering", updatedGroup3.getName());
    assertEquals("Engineering department group", updatedGroup3.getDescription());

    // verify via retrieve that the final state persisted
    FreshAgentGroup retrievedGroup = FreshAgentApiCommands.retrieveAgentGroup("freshServiceDev", createdGroup.getId());
    assertNotNull(retrievedGroup);
    assertEquals("Engineering", retrievedGroup.getName());
    assertEquals("Engineering department group", retrievedGroup.getDescription());

    // update non-existing group should throw an exception
    FreshAgentGroup nonExistingGroup = new FreshAgentGroup();
    nonExistingGroup.setId(9999L);
    nonExistingGroup.setName("Does Not Exist");

    Map<String, ProvisioningObjectChangeAction> fieldsToUpdate4 = new LinkedHashMap<String, ProvisioningObjectChangeAction>();
    fieldsToUpdate4.put("name", ProvisioningObjectChangeAction.update);

    try {
      FreshAgentApiCommands.updateAgentGroup("freshServiceDev", nonExistingGroup, fieldsToUpdate4);
      fail("Should have thrown exception for non-existing group");
    } catch (RuntimeException e) {
      assertTrue(e.getMessage().contains("does not exist"));
    }
  }

  public void testRetrieveAgentUsers() {

    FreshAgentProvisionerTestUtils.setupFreshAgentExternalSystem();

    // insert some users directly into the mock table
    new GcDbAccess().connectionName("grouper")
        .sql("insert into mock_freshagent_user (id, email, first_name, last_name, active) values (2001, 'jsmith@test.edu', 'John', 'Smith', 'T')")
        .executeSql();
    new GcDbAccess().connectionName("grouper")
        .sql("insert into mock_freshagent_user (id, email, first_name, last_name, active) values (2002, 'jdoe@test.edu', 'Jane', 'Doe', 'T')")
        .executeSql();
    new GcDbAccess().connectionName("grouper")
        .sql("insert into mock_freshagent_user (id, email, first_name, last_name, active) values (2003, 'bwilson@test.edu', 'Bob', 'Wilson', 'F')")
        .executeSql();

    List<FreshAgentUser> users = FreshAgentApiCommands.retrieveAgentUsers("freshServiceDev", true);

    assertEquals(3, users.size());

    Map<Long, FreshAgentUser> userById = new HashMap<Long, FreshAgentUser>();
    for (FreshAgentUser user : users) {
      userById.put(user.getId(), user);
    }

    FreshAgentUser user2001 = userById.get(2001L);
    assertNotNull(user2001);
    assertEquals("jsmith@test.edu", user2001.getEmail());
    assertEquals("John", user2001.getFirstName());
    assertEquals("Smith", user2001.getLastName());
    assertEquals(Boolean.TRUE, user2001.getActive());

    FreshAgentUser user2002 = userById.get(2002L);
    assertNotNull(user2002);
    assertEquals("jdoe@test.edu", user2002.getEmail());
    assertEquals("Jane", user2002.getFirstName());
    assertEquals("Doe", user2002.getLastName());
    assertEquals(Boolean.TRUE, user2002.getActive());

    FreshAgentUser user2003 = userById.get(2003L);
    assertNotNull(user2003);
    assertEquals("bwilson@test.edu", user2003.getEmail());
    assertEquals("Bob", user2003.getFirstName());
    assertEquals("Wilson", user2003.getLastName());
    assertEquals(Boolean.FALSE, user2003.getActive());
  }

  public void testRetrieveAgentUser() {

    FreshAgentProvisionerTestUtils.setupFreshAgentExternalSystem();

    // insert a user directly into the mock table
    new GcDbAccess().connectionName("grouper")
        .sql("insert into mock_freshagent_user (id, email, first_name, last_name, active) values (2001, 'jsmith@test.edu', 'John', 'Smith', 'T')")
        .executeSql();

    // retrieve existing user
    FreshAgentUser user = FreshAgentApiCommands.retrieveAgentUserById("freshServiceDev", 2001L, false);

    assertNotNull(user);
    assertEquals(2001L, (long)user.getId());
    assertEquals("jsmith@test.edu", user.getEmail());
    assertEquals("John", user.getFirstName());
    assertEquals("Smith", user.getLastName());
    assertEquals(Boolean.TRUE, user.getActive());

    // retrieve non-existing user should return null
    FreshAgentUser notFound = FreshAgentApiCommands.retrieveAgentUserById("freshServiceDev", 9999L, false);

    assertNull(notFound);
  }

  public void testRetrieveAgentUserByEmail() {

    FreshAgentProvisionerTestUtils.setupFreshAgentExternalSystem();

    // insert some users directly into the mock table
    new GcDbAccess().connectionName("grouper")
        .sql("insert into mock_freshagent_user (id, email, first_name, last_name, active) values (2001, 'jsmith@test.edu', 'John', 'Smith', 'T')")
        .executeSql();
    new GcDbAccess().connectionName("grouper")
        .sql("insert into mock_freshagent_user (id, email, first_name, last_name, active) values (2002, 'jdoe@test.edu', 'Jane', 'Doe', 'T')")
        .executeSql();

    // retrieve existing user by email
    FreshAgentUser user = FreshAgentApiCommands.retrieveAgentUserByEmail("freshServiceDev", "jsmith@test.edu", false);

    assertNotNull(user);
    assertEquals(2001L, (long)user.getId());
    assertEquals("jsmith@test.edu", user.getEmail());
    assertEquals("John", user.getFirstName());
    assertEquals("Smith", user.getLastName());

    // retrieve non-existing email should return null
    FreshAgentUser notFound = FreshAgentApiCommands.retrieveAgentUserByEmail("freshServiceDev", "nobody@test.edu", false);

    assertNull(notFound);
  }

  public void testRetrieveAgentUserByCustomAttribute() {

    FreshAgentProvisionerTestUtils.setupFreshAgentExternalSystem();

    // insert some users directly into the mock table with custom_fields JSON
    // pennId is numeric (no quotes around the value in JSON)
    new GcDbAccess().connectionName("grouper")
        .sql("insert into mock_freshagent_user (id, email, first_name, last_name, active, custom_fields) values (2001, 'jsmith@test.edu', 'John', 'Smith', 'T', '{\"pennId\":12345678,\"pennkey\":\"jsmith\"}')")
        .executeSql();
    new GcDbAccess().connectionName("grouper")
        .sql("insert into mock_freshagent_user (id, email, first_name, last_name, active, custom_fields) values (2002, 'jdoe@test.edu', 'Jane', 'Doe', 'T', '{\"pennId\":87654321,\"pennkey\":\"jdoe\"}')")
        .executeSql();
    new GcDbAccess().connectionName("grouper")
        .sql("insert into mock_freshagent_user (id, email, first_name, last_name, active, custom_fields) values (2003, 'bwilson@test.edu', 'Bob', 'Wilson', 'T', null)")
        .executeSql();

    // retrieve existing user by custom field pennId as Long
    FreshAgentUser user = FreshAgentApiCommands.retrieveAgentUserByAttribute("freshServiceDev", "customField_pennId", 12345678L);

    assertNotNull(user);
    assertEquals(2001L, (long)user.getId());
    assertEquals("jsmith@test.edu", user.getEmail());
    assertEquals("John", user.getFirstName());
    assertEquals("Smith", user.getLastName());
    assertNotNull(user.getCustomFields());
    assertEquals(12345678L, user.getCustomFields().get("pennId"));
    assertEquals("jsmith", user.getCustomFields().get("pennkey"));

    // retrieve by a different custom field pennkey (String)
    FreshAgentUser user2 = FreshAgentApiCommands.retrieveAgentUserByAttribute("freshServiceDev", "customField_pennkey", "jdoe");

    assertNotNull(user2);
    assertEquals(2002L, (long)user2.getId());
    assertEquals("jdoe@test.edu", user2.getEmail());

    // retrieve non-existing custom field value should return null
    FreshAgentUser notFound = FreshAgentApiCommands.retrieveAgentUserByAttribute("freshServiceDev", "customField_pennId", 99999999L);

    assertNull(notFound);

    // retrieve by custom field when user has no custom_fields should return null
    FreshAgentUser notFoundNull = FreshAgentApiCommands.retrieveAgentUserByAttribute("freshServiceDev", "customField_pennId", 11111111L);

    assertNull(notFoundNull);
  }

  public void testDeactivateAgentUser() {

    FreshAgentProvisionerTestUtils.setupFreshAgentExternalSystem();

    // create a user to deactivate
    FreshAgentUser userToCreate = new FreshAgentUser();
    userToCreate.setFirstName("John");
    userToCreate.setLastName("Smith");
    userToCreate.setEmail("jsmith@test.edu");
    userToCreate.setActive(true);

    FreshAgentUser createdUser = seedAgent(userToCreate);
    assertNotNull(createdUser);
    assertTrue(createdUser.getId() > 0);

    // verify active is true
    FreshAgentUser retrievedUser = FreshAgentApiCommands.retrieveAgentUserById("freshServiceDev", createdUser.getId(), false);
    assertNotNull(retrievedUser);
    assertEquals(Boolean.TRUE, retrievedUser.getActive());

    // deactivate the user
    FreshAgentApiCommands.deactivateAgentUser("freshServiceDev", createdUser.getId());

    // verify user still exists but active is now false (pass true to include inactive)
    FreshAgentUser deactivatedUser = FreshAgentApiCommands.retrieveAgentUserById("freshServiceDev", createdUser.getId(), true);
    assertNotNull(deactivatedUser);
    assertEquals(createdUser.getId(), deactivatedUser.getId());
    assertEquals("jsmith@test.edu", deactivatedUser.getEmail());
    assertEquals(Boolean.FALSE, deactivatedUser.getActive());

    // deactivate again should not throw an error (still 204 since user exists but inactive)
    FreshAgentApiCommands.deactivateAgentUser("freshServiceDev", createdUser.getId());

    // deactivate non-existing user should not throw an error (404 is acceptable)
    FreshAgentApiCommands.deactivateAgentUser("freshServiceDev", 9999L);
  }

  public void testForgetAgentUser() {

    FreshAgentProvisionerTestUtils.setupFreshAgentExternalSystem();

    // create a user to forget
    FreshAgentUser userToCreate = new FreshAgentUser();
    userToCreate.setFirstName("John");
    userToCreate.setLastName("Smith");
    userToCreate.setEmail("jsmith@test.edu");
    userToCreate.setActive(true);

    FreshAgentUser createdUser = seedAgent(userToCreate);
    assertNotNull(createdUser);
    assertTrue(createdUser.getId() > 0);

    // verify it exists
    FreshAgentUser retrievedUser = FreshAgentApiCommands.retrieveAgentUserById("freshServiceDev", createdUser.getId(), false);
    assertNotNull(retrievedUser);

    // forget (permanently delete) the user
    FreshAgentApiCommands.forgetAgentUser("freshServiceDev", createdUser.getId());

    // verify it no longer exists
    FreshAgentUser forgottenUser = FreshAgentApiCommands.retrieveAgentUserById("freshServiceDev", createdUser.getId(), false);
    assertNull(forgottenUser);

    // forget again should not throw an error (404 is acceptable)
    FreshAgentApiCommands.forgetAgentUser("freshServiceDev", createdUser.getId());
  }

  /**
   * Build a minimal roles JSON array string for a single role id, matching what
   * applyDefaultRole produces: [{"role_id":<id>,"assignment_scope":"entire_helpdesk"}]
   */
  private String rolesJsonForRole(long roleId) {
    return "[{\"role_id\":" + roleId + ",\"assignment_scope\":\"entire_helpdesk\"}]";
  }

  /**
   * createAgentUser should create a brand new agent via POST when no agent with
   * that email exists, and the roles the agent carries should round-trip.
   */
  public void testCreateAgentUser() {

    FreshAgentProvisionerTestUtils.setupFreshAgentExternalSystem();

    FreshAgentUser userToCreate = new FreshAgentUser();
    userToCreate.setFirstName("New");
    userToCreate.setLastName("Agent");
    userToCreate.setEmail("newagent@test.edu");
    userToCreate.setJobTitle("Software Engineer");
    userToCreate.setRolesJson(rolesJsonForRole(31000111111L));

    FreshAgentUser createdUser = FreshAgentApiCommands.createAgentUser("freshServiceDev", userToCreate);

    assertNotNull(createdUser);
    assertTrue(createdUser.getId() > 0);
    assertEquals("newagent@test.edu", createdUser.getEmail());
    assertEquals("New", createdUser.getFirstName());
    assertEquals("Agent", createdUser.getLastName());
    assertEquals("Software Engineer", createdUser.getJobTitle());

    // verify via retrieve, including roles round-trip
    FreshAgentUser retrievedUser = FreshAgentApiCommands.retrieveAgentUserById("freshServiceDev", createdUser.getId(), false);
    assertNotNull(retrievedUser);
    assertEquals("newagent@test.edu", retrievedUser.getEmail());
    assertTrue(retrievedUser.hasRoles());
    assertTrue(retrievedUser.getRolesJson().contains("31000111111"));
    assertEquals("Software Engineer", retrievedUser.getJobTitle());
  }

  /**
   * createAgentUser should fail with the mock's 400 "roles is required" when the
   * agent carries no roles (mirrors real Freshservice requiring a roles array on
   * create). The provisioner relies on a configured defaultAgentRoleId to avoid
   * this; here we call the API directly with no roles to confirm the requirement.
   */
  public void testCreateAgentUserRolesRequired() {

    FreshAgentProvisionerTestUtils.setupFreshAgentExternalSystem();

    FreshAgentUser userToCreate = new FreshAgentUser();
    userToCreate.setFirstName("No");
    userToCreate.setLastName("Roles");
    userToCreate.setEmail("noroles@test.edu");
    // intentionally no roles

    try {
      FreshAgentApiCommands.createAgentUser("freshServiceDev", userToCreate);
      fail("Should have thrown for missing roles on create");
    } catch (RuntimeException e) {
      // mock returns 400 with a roles validation body; executeMethod throws on
      // the unexpected 400
      assertTrue("expected roles-required failure but was: " + e.getMessage(),
          e.getMessage().contains("400") || e.getMessage().contains("roles"));
    }

    // nothing should have been created
    FreshAgentUser notCreated = FreshAgentApiCommands.retrieveAgentUserByEmail("freshServiceDev", "noroles@test.edu", true);
    assertNull(notCreated);
  }

  /**
   * When an agent with the same email already exists and is active, createAgentUser
   * should update it (not create a duplicate) with the writable fields.
   */
  public void testCreateAgentUserExistingActiveUpdates() {

    FreshAgentProvisionerTestUtils.setupFreshAgentExternalSystem();

    // seed an existing active agent
    FreshAgentUser existing = new FreshAgentUser();
    existing.setFirstName("Existing");
    existing.setLastName("Agent");
    existing.setEmail("existing@test.edu");
    existing.setActive(true);
    FreshAgentUser seeded = seedAgent(existing);

    // call createAgentUser with the same email but different first name
    FreshAgentUser incoming = new FreshAgentUser();
    incoming.setFirstName("Updated");
    incoming.setLastName("Agent");
    incoming.setEmail("existing@test.edu");
    incoming.setRolesJson(rolesJsonForRole(31000222222L));

    FreshAgentUser result = FreshAgentApiCommands.createAgentUser("freshServiceDev", incoming);

    assertNotNull(result);
    // should be the same agent id (updated, not created new)
    assertEquals(seeded.getId(), result.getId());

    // only one agent with that email should exist
    List<FreshAgentUser> all = FreshAgentApiCommands.retrieveAgentUsers("freshServiceDev", true);
    int count = 0;
    for (FreshAgentUser u : all) {
      if ("existing@test.edu".equals(u.getEmail())) {
        count++;
      }
    }
    assertEquals(1, count);

    // the first name should have been updated
    FreshAgentUser retrieved = FreshAgentApiCommands.retrieveAgentUserById("freshServiceDev", seeded.getId(), false);
    assertNotNull(retrieved);
    assertEquals("Updated", retrieved.getFirstName());
  }

  /**
   * When an agent with the same email already exists but is inactive, createAgentUser
   * should reactivate it first, then update it - ending with an active agent.
   */
  public void testCreateAgentUserExistingInactiveReactivates() {

    FreshAgentProvisionerTestUtils.setupFreshAgentExternalSystem();

    // seed an existing INACTIVE agent
    FreshAgentUser existing = new FreshAgentUser();
    existing.setFirstName("Inactive");
    existing.setLastName("Agent");
    existing.setEmail("inactive@test.edu");
    existing.setActive(false);
    FreshAgentUser seeded = seedAgent(existing);

    // confirm inactive
    FreshAgentUser before = FreshAgentApiCommands.retrieveAgentUserById("freshServiceDev", seeded.getId(), true);
    assertNotNull(before);
    assertEquals(Boolean.FALSE, before.getActive());

    // createAgentUser with same email should reactivate + update
    FreshAgentUser incoming = new FreshAgentUser();
    incoming.setFirstName("Reactivated");
    incoming.setLastName("Agent");
    incoming.setEmail("inactive@test.edu");
    incoming.setRolesJson(rolesJsonForRole(31000333333L));

    FreshAgentUser result = FreshAgentApiCommands.createAgentUser("freshServiceDev", incoming);

    assertNotNull(result);
    assertEquals(seeded.getId(), result.getId());

    // should now be active with the updated first name
    FreshAgentUser after = FreshAgentApiCommands.retrieveAgentUserById("freshServiceDev", seeded.getId(), false);
    assertNotNull(after);
    assertEquals(Boolean.TRUE, after.getActive());
    assertEquals("Reactivated", after.getFirstName());
  }

  /**
   * Unit test for the FreshAgentUser.hasRoles / applyDefaultRole helpers used by
   * the default-role feature, independent of any API calls.
   */
  public void testApplyDefaultRoleHelper() {

    FreshAgentUser user = new FreshAgentUser();

    // no roles initially
    assertFalse(user.hasRoles());

    // applying a null role id is a no-op
    user.applyDefaultRole(null, "entire_helpdesk");
    assertFalse(user.hasRoles());

    // applying a role id builds a single-role array with the given scope
    user.applyDefaultRole(31000444444L, "entire_helpdesk");
    assertTrue(user.hasRoles());
    assertNotNull(user.getRolesJson());
    assertTrue(user.getRolesJson().contains("31000444444"));
    assertTrue(user.getRolesJson().contains("entire_helpdesk"));

    // blank scope should default to entire_helpdesk
    FreshAgentUser user2 = new FreshAgentUser();
    user2.applyDefaultRole(31000555555L, null);
    assertTrue(user2.hasRoles());
    assertTrue(user2.getRolesJson().contains("entire_helpdesk"));

    // an empty roles array is not "having roles"
    FreshAgentUser user3 = new FreshAgentUser();
    user3.setRolesJson("[]");
    assertFalse(user3.hasRoles());
  }

  public void testUpdateAgentUser() {

    FreshAgentProvisionerTestUtils.setupFreshAgentExternalSystem();

    // create a user to update
    Map<String, Object> customFields = new HashMap<String, Object>();
    customFields.put("pennkey", "jsmith");
    customFields.put("penn_id", "12345678");

    FreshAgentUser userToCreate = new FreshAgentUser();
    userToCreate.setFirstName("John");
    userToCreate.setLastName("Smith");
    userToCreate.setEmail("jsmith@test.edu");
    userToCreate.setJobTitle("Software Engineer");
    userToCreate.setActive(true);
    userToCreate.setDepartmentId(39000211201L);
    userToCreate.setCustomFields(customFields);

    FreshAgentUser createdUser = seedAgent(userToCreate);
    assertNotNull(createdUser);
    assertTrue(createdUser.getId() > 0);

    // update email only
    FreshAgentUser userToUpdate = new FreshAgentUser();
    userToUpdate.setId(createdUser.getId());
    userToUpdate.setEmail("jsmith2@upenn.edu");
    userToUpdate.setJobTitle("Software Engineer 2");

    Set<String> fieldsToUpdate = new java.util.LinkedHashSet<String>();
    fieldsToUpdate.add("email");
    fieldsToUpdate.add("jobTitle");

    FreshAgentUser updatedUser = FreshAgentApiCommands.updateAgentUser("freshServiceDev", userToUpdate, fieldsToUpdate);

    assertNotNull(updatedUser);
    assertEquals(createdUser.getId(), updatedUser.getId());
    assertEquals("jsmith2@upenn.edu", updatedUser.getEmail());
    assertEquals("John", updatedUser.getFirstName());
    assertEquals("Smith", updatedUser.getLastName());
    assertEquals("Software Engineer 2", updatedUser.getJobTitle());

    // verify via retrieve
    FreshAgentUser retrievedUser = FreshAgentApiCommands.retrieveAgentUserById("freshServiceDev", createdUser.getId(), false);
    assertNotNull(retrievedUser);
    assertEquals("jsmith2@upenn.edu", retrievedUser.getEmail());
    assertEquals("John", retrievedUser.getFirstName());
    assertEquals("Smith", retrievedUser.getLastName());
    assertEquals("Software Engineer 2", retrievedUser.getJobTitle());

    // update multiple fields including custom_fields
    Map<String, Object> updatedCustomFields = new HashMap<String, Object>();
    updatedCustomFields.put("pennkey", "jsmith2");
    updatedCustomFields.put("penn_id", "12345679");

    FreshAgentUser userToUpdate2 = new FreshAgentUser();
    userToUpdate2.setId(createdUser.getId());
    userToUpdate2.setFirstName("Johnny");
    userToUpdate2.setCustomFields(updatedCustomFields);

    Set<String> fieldsToUpdate2 = new java.util.LinkedHashSet<String>();
    fieldsToUpdate2.add("firstName");
    fieldsToUpdate2.add("customField_pennkey");
    fieldsToUpdate2.add("customField_penn_id");

    FreshAgentUser updatedUser2 = FreshAgentApiCommands.updateAgentUser("freshServiceDev", userToUpdate2, fieldsToUpdate2);

    assertNotNull(updatedUser2);
    assertEquals(createdUser.getId(), updatedUser2.getId());
    assertEquals("Johnny", updatedUser2.getFirstName());
    assertEquals("Smith", updatedUser2.getLastName());
    assertEquals("jsmith2@upenn.edu", updatedUser2.getEmail());
    assertNotNull(updatedUser2.getCustomFields());
    assertEquals("jsmith2", updatedUser2.getCustomFields().get("pennkey"));
    assertEquals("12345679", updatedUser2.getCustomFields().get("penn_id"));

    // verify via retrieve that final state persisted
    FreshAgentUser retrievedUser2 = FreshAgentApiCommands.retrieveAgentUserById("freshServiceDev", createdUser.getId(), false);
    assertNotNull(retrievedUser2);
    assertEquals("Johnny", retrievedUser2.getFirstName());
    assertEquals("Smith", retrievedUser2.getLastName());
    assertEquals("jsmith2@upenn.edu", retrievedUser2.getEmail());
    assertNotNull(retrievedUser2.getCustomFields());
    assertEquals("jsmith2", retrievedUser2.getCustomFields().get("pennkey"));
    assertEquals("12345679", retrievedUser2.getCustomFields().get("penn_id"));

    // update non-existing user should throw an exception
    FreshAgentUser nonExistingUser = new FreshAgentUser();
    nonExistingUser.setId(9999L);
    nonExistingUser.setFirstName("Nobody");

    Set<String> fieldsToUpdate3 = new java.util.LinkedHashSet<String>();
    fieldsToUpdate3.add("firstName");

    try {
      FreshAgentApiCommands.updateAgentUser("freshServiceDev", nonExistingUser, fieldsToUpdate3);
      fail("Should have thrown exception for non-existing user");
    } catch (RuntimeException e) {
      assertTrue(e.getMessage().contains("does not exist"));
    }
  }

  public void testAddGroupMembership() {

    FreshAgentProvisionerTestUtils.setupFreshAgentExternalSystem();

    // create a group and a user
    FreshAgentGroup groupToCreate = new FreshAgentGroup();
    groupToCreate.setName("IT Support");
    groupToCreate.setDescription("IT support team");

    FreshAgentGroup createdGroup = FreshAgentApiCommands.createAgentGroup("freshServiceDev", groupToCreate);
    assertNotNull(createdGroup);

    FreshAgentUser userToCreate = new FreshAgentUser();
    userToCreate.setFirstName("John");
    userToCreate.setLastName("Smith");
    userToCreate.setEmail("jsmith@test.edu");

    FreshAgentUser createdUser = seedAgent(userToCreate);
    assertNotNull(createdUser);

    // verify no memberships exist yet
    int count = new GcDbAccess().connectionName("grouper")
        .sql("select count(*) from mock_freshagent_membership where group_id = ? and user_id = ?")
        .addBindVar(createdGroup.getId()).addBindVar(createdUser.getId())
        .select(int.class);
    assertEquals(0, count);

    // add membership
    FreshAgentApiCommands.addGroupMembership("freshServiceDev", createdGroup.getId(), createdUser.getId());

    // verify membership exists
    count = new GcDbAccess().connectionName("grouper")
        .sql("select count(*) from mock_freshagent_membership where group_id = ? and user_id = ?")
        .addBindVar(createdGroup.getId()).addBindVar(createdUser.getId())
        .select(int.class);
    assertEquals(1, count);

    // add same membership again should not throw an error (200 if already existed)
    FreshAgentApiCommands.addGroupMembership("freshServiceDev", createdGroup.getId(), createdUser.getId());

    // verify still only one membership row
    count = new GcDbAccess().connectionName("grouper")
        .sql("select count(*) from mock_freshagent_membership where group_id = ? and user_id = ?")
        .addBindVar(createdGroup.getId()).addBindVar(createdUser.getId())
        .select(int.class);
    assertEquals(1, count);
  }

  public void testRemoveGroupMembership() {

    FreshAgentProvisionerTestUtils.setupFreshAgentExternalSystem();

    // create a group and two users
    FreshAgentGroup groupToCreate = new FreshAgentGroup();
    groupToCreate.setName("IT Support");
    groupToCreate.setDescription("IT support team");

    FreshAgentGroup createdGroup = FreshAgentApiCommands.createAgentGroup("freshServiceDev", groupToCreate);
    assertNotNull(createdGroup);

    FreshAgentUser user1 = new FreshAgentUser();
    user1.setFirstName("John");
    user1.setLastName("Smith");
    user1.setEmail("jsmith@test.edu");

    FreshAgentUser createdUser1 = seedAgent(user1);
    assertNotNull(createdUser1);

    FreshAgentUser user2 = new FreshAgentUser();
    user2.setFirstName("Jane");
    user2.setLastName("Doe");
    user2.setEmail("jdoe@test.edu");

    FreshAgentUser createdUser2 = seedAgent(user2);
    assertNotNull(createdUser2);

    // add both memberships
    FreshAgentApiCommands.addGroupMembership("freshServiceDev", createdGroup.getId(), createdUser1.getId());
    FreshAgentApiCommands.addGroupMembership("freshServiceDev", createdGroup.getId(), createdUser2.getId());

    // verify both exist
    int count = new GcDbAccess().connectionName("grouper")
        .sql("select count(*) from mock_freshagent_membership where group_id = ?")
        .addBindVar(createdGroup.getId())
        .select(int.class);
    assertEquals(2, count);

    // remove first membership
    FreshAgentApiCommands.removeGroupMembership("freshServiceDev", createdGroup.getId(), createdUser1.getId());

    // verify only second remains
    count = new GcDbAccess().connectionName("grouper")
        .sql("select count(*) from mock_freshagent_membership where group_id = ?")
        .addBindVar(createdGroup.getId())
        .select(int.class);
    assertEquals(1, count);

    count = new GcDbAccess().connectionName("grouper")
        .sql("select count(*) from mock_freshagent_membership where group_id = ? and user_id = ?")
        .addBindVar(createdGroup.getId()).addBindVar(createdUser2.getId())
        .select(int.class);
    assertEquals(1, count);

    // remove again should not throw (404 is acceptable)
    FreshAgentApiCommands.removeGroupMembership("freshServiceDev", createdGroup.getId(), createdUser1.getId());

    // remove non-existing membership should not throw (404 is acceptable)
    FreshAgentApiCommands.removeGroupMembership("freshServiceDev", createdGroup.getId(), 9999L);
  }

  public void testRetrieveMembershipsByGroup() {

    FreshAgentProvisionerTestUtils.setupFreshAgentExternalSystem();

    // create a group and two users
    FreshAgentGroup groupToCreate = new FreshAgentGroup();
    groupToCreate.setName("Engineering");
    groupToCreate.setDescription("Engineering team");

    FreshAgentGroup createdGroup = FreshAgentApiCommands.createAgentGroup("freshServiceDev", groupToCreate);
    assertNotNull(createdGroup);

    FreshAgentUser user1 = new FreshAgentUser();
    user1.setFirstName("John");
    user1.setLastName("Smith");
    user1.setEmail("jsmith@test.edu");

    FreshAgentUser createdUser1 = seedAgent(user1);
    assertNotNull(createdUser1);

    FreshAgentUser user2 = new FreshAgentUser();
    user2.setFirstName("Jane");
    user2.setLastName("Doe");
    user2.setEmail("jdoe@test.edu");

    FreshAgentUser createdUser2 = seedAgent(user2);
    assertNotNull(createdUser2);

    // empty group should return empty list
    List<FreshAgentUser> members = FreshAgentApiCommands.retrieveMembershipsByGroup("freshServiceDev", createdGroup.getId());
    assertEquals(0, members.size());

    // add both memberships
    FreshAgentApiCommands.addGroupMembership("freshServiceDev", createdGroup.getId(), createdUser1.getId());
    FreshAgentApiCommands.addGroupMembership("freshServiceDev", createdGroup.getId(), createdUser2.getId());

    // retrieve members
    members = FreshAgentApiCommands.retrieveMembershipsByGroup("freshServiceDev", createdGroup.getId());
    assertEquals(2, members.size());

    Map<Long, FreshAgentUser> memberById = new HashMap<Long, FreshAgentUser>();
    for (FreshAgentUser member : members) {
      memberById.put(member.getId(), member);
    }

    FreshAgentUser member1 = memberById.get(createdUser1.getId());
    assertNotNull(member1);
    assertEquals(createdUser1.getId(), member1.getId());

    FreshAgentUser member2 = memberById.get(createdUser2.getId());
    assertNotNull(member2);
    assertEquals(createdUser2.getId(), member2.getId());

    // remove one membership and verify list shrinks
    FreshAgentApiCommands.removeGroupMembership("freshServiceDev", createdGroup.getId(), createdUser1.getId());

    members = FreshAgentApiCommands.retrieveMembershipsByGroup("freshServiceDev", createdGroup.getId());
    assertEquals(1, members.size());
    assertEquals(createdUser2.getId(), members.get(0).getId());
  }

  public void testUpdateGroupDescriptionFull() {
    updateGroupDescription(true);
  }

  public void testUpdateGroupDescriptionIncremental() {
    updateGroupDescription(false);
  }

  public void updateGroupDescription(boolean isFull) {

    if (!tomcatRunTests()) {
      return;
    }

    FreshAgentProvisionerTestUtils.setupFreshAgentExternalSystem();

    FreshAgentProvisionerTestUtils.configureFreshAgentProvisioner(
        new FreshAgentProvisionerTestConfigInput()
            .assignConfigId("freshAgentProvisioner")
            .addExtraConfig("defaultAgentRoleId", "31000123456")
    );

    GrouperUtil.sleep(5000);

    GrouperStartup.startup();

    try {
      // this will create tables
      FreshAgentApiCommands.retrieveAgentUsers("freshServiceDev", false);

      new GcDbAccess().connectionName("grouper").sql("delete from mock_freshagent_membership").executeSql();
      new GcDbAccess().connectionName("grouper").sql("delete from mock_freshagent_group").executeSql();
      new GcDbAccess().connectionName("grouper").sql("delete from mock_freshagent_user").executeSql();

      GrouperSession grouperSession = GrouperSession.startRootSession();

      Stem stem = new StemSave(grouperSession).assignName("test").save();

      Group testGroup = new GroupSave(grouperSession).assignName("test:testGroup").assignDescription("test description").save();

      testGroup.addMember(SubjectTestHelper.SUBJ0, false);

      // if incremental, initialize provisioner state before attaching provisioning attribute
      if (!isFull) {
        fullProvision();
        incrementalProvision();
      }

      final GrouperProvisioningAttributeValue attributeValue = new GrouperProvisioningAttributeValue();
      attributeValue.setDirectAssignment(true);
      attributeValue.setDoProvision("freshAgentProvisioner");
      attributeValue.setTargetName("freshAgentProvisioner");
      attributeValue.setStemScopeString("sub");

      GrouperProvisioningService.saveOrUpdateProvisioningAttributes(attributeValue, stem);

      //
      // first provision: should create group with description "test description"
      //
      if (isFull) {
        fullProvision();
      } else {
        incrementalProvision();
      }

      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_group").select(int.class));

      String dbDescription = new GcDbAccess().connectionName("grouper")
          .sql("select description from mock_freshagent_group where name = ?").addBindVar("testGroup").select(String.class);
      assertEquals("test description", dbDescription);

      Long groupId = new GcDbAccess().connectionName("grouper")
          .sql("select id from mock_freshagent_group where name = ?").addBindVar("testGroup").select(Long.class);

      FreshAgentGroup retrievedGroup = FreshAgentApiCommands.retrieveAgentGroup("freshServiceDev", groupId);
      assertNotNull(retrievedGroup);
      assertEquals("test description", retrievedGroup.getDescription());

      //
      // update description to "new description 1"
      //
      new GroupSave(grouperSession).assignUuid(testGroup.getUuid()).assignDescription("new description 1").assignSaveMode(SaveMode.INSERT_OR_UPDATE).save();

      if (isFull) {
        fullProvision();
      } else {
        incrementalProvision();
      }

      dbDescription = new GcDbAccess().connectionName("grouper")
          .sql("select description from mock_freshagent_group where name = ?").addBindVar("testGroup").select(String.class);
      assertEquals("new description 1", dbDescription);

      retrievedGroup = FreshAgentApiCommands.retrieveAgentGroup("freshServiceDev", groupId);
      assertNotNull(retrievedGroup);
      assertEquals("new description 1", retrievedGroup.getDescription());

      //
      // set description to null
      //
      new GroupSave(grouperSession).assignUuid(testGroup.getUuid()).assignDescription(null).assignSaveMode(SaveMode.INSERT_OR_UPDATE).save();

      if (isFull) {
        fullProvision();
      } else {
        incrementalProvision();
      }

      dbDescription = new GcDbAccess().connectionName("grouper")
          .sql("select description from mock_freshagent_group where name = ?").addBindVar("testGroup").select(String.class);
      assertNull(dbDescription);

      retrievedGroup = FreshAgentApiCommands.retrieveAgentGroup("freshServiceDev", groupId);
      assertNotNull(retrievedGroup);
      assertNull(retrievedGroup.getDescription());

    } finally {

    }
  }

  public void testFullSyncProvisionGroupAndThenDeleteGroup() {
    provisionGroupAndThenDeleteGroup(true);
  }

  public void testIncrementalProvisionGroupAndThenDeleteGroup() {
    provisionGroupAndThenDeleteGroup(false);
  }

  public void provisionGroupAndThenDeleteGroup(boolean isFull) {

    if (!tomcatRunTests()) {
      return;
    }

    FreshAgentProvisionerTestUtils.setupFreshAgentExternalSystem();

    FreshAgentProvisionerTestUtils.configureFreshAgentProvisioner(
        new FreshAgentProvisionerTestConfigInput()
            .assignConfigId("freshAgentProvisioner")
            .addExtraConfig("defaultAgentRoleId", "31000123456")
    );

    GrouperUtil.sleep(5000);

    GrouperStartup.startup();

    try {
      // this will create tables
      FreshAgentApiCommands.retrieveAgentUsers("freshServiceDev", false);

      new GcDbAccess().connectionName("grouper").sql("delete from mock_freshagent_membership").executeSql();
      new GcDbAccess().connectionName("grouper").sql("delete from mock_freshagent_group").executeSql();
      new GcDbAccess().connectionName("grouper").sql("delete from mock_freshagent_user").executeSql();

      GrouperSession grouperSession = GrouperSession.startRootSession();

      Stem stem = new StemSave(grouperSession).assignName("test").save();

      // mark the stem to provision
      Group testGroup = new GroupSave(grouperSession).assignName("test:testGroup").save();

      testGroup.addMember(SubjectTestHelper.SUBJ0, false);
      testGroup.addMember(SubjectTestHelper.SUBJ1, false);

      // if incremental, initialize provisioner state before attaching provisioning attribute
      if (!isFull) {
        fullProvision();
        incrementalProvision();
      }

      final GrouperProvisioningAttributeValue attributeValue = new GrouperProvisioningAttributeValue();
      attributeValue.setDirectAssignment(true);
      attributeValue.setDoProvision("freshAgentProvisioner");
      attributeValue.setTargetName("freshAgentProvisioner");
      attributeValue.setStemScopeString("sub");

      GrouperProvisioningService.saveOrUpdateProvisioningAttributes(attributeValue, stem);

      // assert mock tables are empty before sync
      assertEquals(new Integer(0), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_group").select(int.class));
      assertEquals(new Integer(0), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_user").select(int.class));
      assertEquals(new Integer(0), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_membership").select(int.class));

      //
      // first provision: should provision group, 2 users, 2 memberships
      //
      if (isFull) {
        fullProvision();
      } else {
        incrementalProvision();
      }

      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_group").select(int.class));
      assertEquals(new Integer(2), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_user where active = 'T'").select(int.class));
      assertEquals(new Integer(2), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_membership").select(int.class));

      String groupName = new GcDbAccess().connectionName("grouper").sql("select name from mock_freshagent_group").select(String.class);
      assertEquals("testGroup", groupName);

      //
      // remove one member and provision again
      //
      testGroup.deleteMember(SubjectTestHelper.SUBJ1);

      if (isFull) {
        fullProvision();
      } else {
        incrementalProvision();
      }

      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_group").select(int.class));
      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_user where active = 'T'").select(int.class));
      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_membership").select(int.class));

      //
      // add a different member and provision again
      //
      testGroup.addMember(SubjectTestHelper.SUBJ2, false);

      if (isFull) {
        fullProvision();
      } else {
        incrementalProvision();
      }

      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_group").select(int.class));
      assertEquals(new Integer(2), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_user where active = 'T'").select(int.class));
      assertEquals(new Integer(2), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_membership").select(int.class));

      //
      // delete the group entirely and provision again
      //
      testGroup.delete();

      if (isFull) {
        fullProvision();
      } else {
        incrementalProvision();
      }

      assertEquals(new Integer(0), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_group").select(int.class));
      assertEquals(new Integer(0), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_membership").select(int.class));
      assertEquals(new Integer(0), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_user where active = 'T'").select(int.class));

    } finally {

    }
  }

  public void testMemberAddRemoveReAddFull() {
    memberAddRemoveReAdd(true);
  }

  public void testMemberAddRemoveReAddIncremental() {
    memberAddRemoveReAdd(false);
  }

  public void memberAddRemoveReAdd(boolean isFull) {

    if (!tomcatRunTests()) {
      return;
    }

    FreshAgentProvisionerTestUtils.setupFreshAgentExternalSystem();

    FreshAgentProvisionerTestUtils.configureFreshAgentProvisioner(
        new FreshAgentProvisionerTestConfigInput()
            .assignConfigId("freshAgentProvisioner")
            .addExtraConfig("defaultAgentRoleId", "31000123456")
    );

    GrouperUtil.sleep(5000);

    GrouperStartup.startup();

    try {
      // this will create tables
      FreshAgentApiCommands.retrieveAgentUsers("freshServiceDev", false);

      new GcDbAccess().connectionName("grouper").sql("delete from mock_freshagent_membership").executeSql();
      new GcDbAccess().connectionName("grouper").sql("delete from mock_freshagent_group").executeSql();
      new GcDbAccess().connectionName("grouper").sql("delete from mock_freshagent_user").executeSql();

      GrouperSession grouperSession = GrouperSession.startRootSession();

      Stem stem = new StemSave(grouperSession).assignName("test").save();

      Group testGroup = new GroupSave(grouperSession).assignName("test:testGroup").save();

      testGroup.addMember(SubjectTestHelper.SUBJ0, false);

      // if incremental, initialize provisioner state before attaching provisioning attribute
      if (!isFull) {
        fullProvision();
        incrementalProvision();
      }

      final GrouperProvisioningAttributeValue attributeValue = new GrouperProvisioningAttributeValue();
      attributeValue.setDirectAssignment(true);
      attributeValue.setDoProvision("freshAgentProvisioner");
      attributeValue.setTargetName("freshAgentProvisioner");
      attributeValue.setStemScopeString("sub");

      GrouperProvisioningService.saveOrUpdateProvisioningAttributes(attributeValue, stem);

      //
      // first provision: should provision group, 1 user, 1 membership
      //
      if (isFull) {
        fullProvision();
      } else {
        incrementalProvision();
      }

      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_group").select(int.class));
      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_user where active = 'T'").select(int.class));
      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_membership").select(int.class));

      Long userId = new GcDbAccess().connectionName("grouper")
          .sql("select id from mock_freshagent_user where active = 'T'").select(Long.class);
      Long groupId = new GcDbAccess().connectionName("grouper")
          .sql("select id from mock_freshagent_group").select(Long.class);

      // verify via commands class
      FreshAgentUser retrievedUser = FreshAgentApiCommands.retrieveAgentUserById("freshServiceDev", userId, false);
      assertNotNull(retrievedUser);
      assertEquals(Boolean.TRUE, retrievedUser.getActive());

      List<FreshAgentUser> members = FreshAgentApiCommands.retrieveMembershipsByGroup("freshServiceDev", groupId);
      assertEquals(1, members.size());

      //
      // remove member and provision again - user should be deactivated, memberships deleted
      //
      testGroup.deleteMember(SubjectTestHelper.SUBJ0);

      if (isFull) {
        fullProvision();
      } else {
        incrementalProvision();
      }

      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_group").select(int.class));
      assertEquals(new Integer(0), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_user where active = 'T'").select(int.class));
      assertEquals(new Integer(0), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_membership").select(int.class));

      // user still exists in mock table but is inactive
      String activeFlag = new GcDbAccess().connectionName("grouper")
          .sql("select active from mock_freshagent_user where id = ?").addBindVar(userId).select(String.class);
      assertEquals("F", activeFlag);

      // commands class: should not return inactive user without includeInactive flag
      FreshAgentUser inactiveUserFiltered = FreshAgentApiCommands.retrieveAgentUserById("freshServiceDev", userId, false);
      assertNull(inactiveUserFiltered);

      // commands class: should return inactive user with includeInactive flag
      FreshAgentUser inactiveUser = FreshAgentApiCommands.retrieveAgentUserById("freshServiceDev", userId, true);
      assertNotNull(inactiveUser);
      assertEquals(Boolean.FALSE, inactiveUser.getActive());

      // commands class: no memberships
      members = FreshAgentApiCommands.retrieveMembershipsByGroup("freshServiceDev", groupId);
      assertEquals(0, members.size());

      //
      // re-add the same member and provision again - user should be reactivated, membership re-created
      //
      testGroup.addMember(SubjectTestHelper.SUBJ0, false);

      if (isFull) {
        fullProvision();
      } else {
        incrementalProvision();
      }

      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_group").select(int.class));
      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_user where active = 'T'").select(int.class));
      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_membership").select(int.class));

      // commands class: user is active again
      FreshAgentUser reactivatedUser = FreshAgentApiCommands.retrieveAgentUserById("freshServiceDev", userId, false);
      assertNotNull(reactivatedUser);
      assertEquals(Boolean.TRUE, reactivatedUser.getActive());

      // commands class: membership is back
      members = FreshAgentApiCommands.retrieveMembershipsByGroup("freshServiceDev", groupId);
      assertEquals(1, members.size());

    } finally {

    }
  }

  public void testFullSyncEditFirstName() {

    if (!tomcatRunTests()) {
      return;
    }

    FreshAgentProvisionerTestUtils.setupFreshAgentExternalSystem();

    FreshAgentProvisionerTestUtils.configureFreshAgentProvisioner(
        new FreshAgentProvisionerTestConfigInput()
            .assignConfigId("freshAgentProvisioner")
            // Freshservice requires a non-empty roles array on agent create, so
            // configure a default role id; without it, insertEntity's create POST
            // fails with the mock's 400 "roles is required".
            .addExtraConfig("defaultAgentRoleId", "31000123456")
            .addExtraConfig("numberOfEntityAttributes", "3")
            .addExtraConfig("targetEntityAttribute.2.name", "firstName")
            .addExtraConfig("targetEntityAttribute.2.translateExpressionType", "grouperProvisioningEntityField")
            .addExtraConfig("targetEntityAttribute.2.translateFromGrouperProvisioningEntityField", "subjectId")
    );

    GrouperUtil.sleep(5000);

    GrouperStartup.startup();

    try {
      // this will create tables
      FreshAgentApiCommands.retrieveAgentUsers("freshServiceDev", false);

      new GcDbAccess().connectionName("grouper").sql("delete from mock_freshagent_membership").executeSql();
      new GcDbAccess().connectionName("grouper").sql("delete from mock_freshagent_group").executeSql();
      new GcDbAccess().connectionName("grouper").sql("delete from mock_freshagent_user").executeSql();

      GrouperSession grouperSession = GrouperSession.startRootSession();

      Stem stem = new StemSave(grouperSession).assignName("test").save();

      Group testGroup = new GroupSave(grouperSession).assignName("test:testGroup").save();

      testGroup.addMember(SubjectTestHelper.SUBJ0, false);

      final GrouperProvisioningAttributeValue attributeValue = new GrouperProvisioningAttributeValue();
      attributeValue.setDirectAssignment(true);
      attributeValue.setDoProvision("freshAgentProvisioner");
      attributeValue.setTargetName("freshAgentProvisioner");
      attributeValue.setStemScopeString("sub");

      GrouperProvisioningService.saveOrUpdateProvisioningAttributes(attributeValue, stem);

      //
      // first full sync: firstName should be subject id (test.subject.0)
      //
      fullProvision();

      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_user where active = 'T'").select(int.class));
      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_membership").select(int.class));

      // check mock table for first_name = subject id
      String dbFirstName = new GcDbAccess().connectionName("grouper")
          .sql("select first_name from mock_freshagent_user where active = 'T'").select(String.class);
      assertEquals("test.subject.0", dbFirstName);

      // check via commands class WS
      Long userId = new GcDbAccess().connectionName("grouper")
          .sql("select id from mock_freshagent_user where active = 'T'").select(Long.class);

      FreshAgentUser retrievedUser = FreshAgentApiCommands.retrieveAgentUserById("freshServiceDev", userId, false);
      assertNotNull(retrievedUser);
      assertEquals("test.subject.0", retrievedUser.getFirstName());

      // the agent was created with no roles from Grouper, so the configured
      // default role id should have been applied on create
      assertTrue("expected default role in roles json but was: " + retrievedUser.getRolesJson(),
          retrievedUser.hasRoles());
      assertTrue("expected default role id 31000123456 in roles json but was: " + retrievedUser.getRolesJson(),
          retrievedUser.getRolesJson() != null && retrievedUser.getRolesJson().contains("31000123456"));
      new GrouperDbConfig().configFileName("grouper-loader.properties")
          .propertyName("provisioner.freshAgentProvisioner.targetEntityAttribute.2.translateFromGrouperProvisioningEntityField")
          .value("name").store();

      ConfigPropertiesCascadeBase.clearCache();

      GrouperUtil.sleep(7000);

      //
      // second full sync: firstName should now be subject name (my name is test.subject.0)
      //
      fullProvision();

      // check mock table for first_name = subject name
      dbFirstName = new GcDbAccess().connectionName("grouper")
          .sql("select first_name from mock_freshagent_user where active = 'T'").select(String.class);
      assertEquals("my name is test.subject.0", dbFirstName);

      // check via commands class WS
      retrievedUser = FreshAgentApiCommands.retrieveAgentUserById("freshServiceDev", userId, false);
      assertNotNull(retrievedUser);
      assertEquals("my name is test.subject.0", retrievedUser.getFirstName());

    } finally {

    }
  }

  public void testFullSyncEditCustomFieldPennId() {

    if (!tomcatRunTests()) {
      return;
    }

    FreshAgentProvisionerTestUtils.setupFreshAgentExternalSystem();

    FreshAgentProvisionerTestUtils.configureFreshAgentProvisioner(
        new FreshAgentProvisionerTestConfigInput()
            .assignConfigId("freshAgentProvisioner")
            // agent create requires roles; supply a default role id
            .addExtraConfig("defaultAgentRoleId", "31000123456")
            .addExtraConfig("numberOfEntityAttributes", "3")
            .addExtraConfig("targetEntityAttribute.2.name.elConfig", "${'customField_pennId'}")
            .addExtraConfig("targetEntityAttribute.2.translateExpressionType", "grouperProvisioningEntityField")
            .addExtraConfig("targetEntityAttribute.2.translateFromGrouperProvisioningEntityField", "subjectId")
    );

    GrouperUtil.sleep(5000);

    GrouperStartup.startup();

    try {
      // this will create tables
      FreshAgentApiCommands.retrieveAgentUsers("freshServiceDev", false);

      new GcDbAccess().connectionName("grouper").sql("delete from mock_freshagent_membership").executeSql();
      new GcDbAccess().connectionName("grouper").sql("delete from mock_freshagent_group").executeSql();
      new GcDbAccess().connectionName("grouper").sql("delete from mock_freshagent_user").executeSql();

      GrouperSession grouperSession = GrouperSession.startRootSession();

      Stem stem = new StemSave(grouperSession).assignName("test").save();

      Group testGroup = new GroupSave(grouperSession).assignName("test:testGroup").save();

      testGroup.addMember(SubjectTestHelper.SUBJ0, false);

      final GrouperProvisioningAttributeValue attributeValue = new GrouperProvisioningAttributeValue();
      attributeValue.setDirectAssignment(true);
      attributeValue.setDoProvision("freshAgentProvisioner");
      attributeValue.setTargetName("freshAgentProvisioner");
      attributeValue.setStemScopeString("sub");

      GrouperProvisioningService.saveOrUpdateProvisioningAttributes(attributeValue, stem);

      //
      // first full sync: customField pennId should be subject id (test.subject.0)
      //
      fullProvision();

      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_user where active = 'T'").select(int.class));
      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_membership").select(int.class));

      // check mock table for custom_fields containing pennId = subject id
      String dbCustomFields = new GcDbAccess().connectionName("grouper")
          .sql("select custom_fields from mock_freshagent_user where active = 'T'").select(String.class);
      assertNotNull(dbCustomFields);
      assertTrue(dbCustomFields.contains("pennId"));
      assertTrue(dbCustomFields.contains("test.subject.0"));

      // check via commands class WS
      Long userId = new GcDbAccess().connectionName("grouper")
          .sql("select id from mock_freshagent_user where active = 'T'").select(Long.class);

      FreshAgentUser retrievedUser = FreshAgentApiCommands.retrieveAgentUserById("freshServiceDev", userId, false);
      assertNotNull(retrievedUser);
      assertNotNull(retrievedUser.getCustomFields());
      assertEquals("test.subject.0", retrievedUser.getCustomFields().get("pennId"));

      //
      // change config to map customField pennId to subject name instead of subject id
      //
      new GrouperDbConfig().configFileName("grouper-loader.properties")
          .propertyName("provisioner.freshAgentProvisioner.targetEntityAttribute.2.translateFromGrouperProvisioningEntityField")
          .value("name").store();

      ConfigPropertiesCascadeBase.clearCache();

      GrouperUtil.sleep(7000);

      //
      // second full sync: customField pennId should now be subject name (my name is test.subject.0)
      //
      fullProvision();

      // check mock table for custom_fields containing pennId = subject name
      dbCustomFields = new GcDbAccess().connectionName("grouper")
          .sql("select custom_fields from mock_freshagent_user where active = 'T'").select(String.class);
      assertNotNull(dbCustomFields);
      assertTrue(dbCustomFields.contains("pennId"));
      assertTrue(dbCustomFields.contains("my name is test.subject.0"));

      // check via commands class WS
      retrievedUser = FreshAgentApiCommands.retrieveAgentUserById("freshServiceDev", userId, false);
      assertNotNull(retrievedUser);
      assertNotNull(retrievedUser.getCustomFields());
      assertEquals("my name is test.subject.0", retrievedUser.getCustomFields().get("pennId"));

    } finally {

    }
  }

  public void testFullSyncMatchByCustomField() {
    matchByCustomFieldAddRemoveMembers(true);
  }

  public void testIncrementalSyncMatchByCustomField() {
    matchByCustomFieldAddRemoveMembers(false);
  }

  public void matchByCustomFieldAddRemoveMembers(boolean isFull) {

    if (!tomcatRunTests()) {
      return;
    }

    FreshAgentProvisionerTestUtils.setupFreshAgentExternalSystem();

    FreshAgentProvisionerTestUtils.configureFreshAgentProvisioner(
        new FreshAgentProvisionerTestConfigInput()
            .assignConfigId("freshAgentProvisioner")
            // agent create requires roles; supply a default role id
            .addExtraConfig("defaultAgentRoleId", "31000123456")
            .addExtraConfig("numberOfEntityAttributes", "3")
            .addExtraConfig("targetEntityAttribute.2.name.elConfig", "${'customField_pennId'}")
            .addExtraConfig("targetEntityAttribute.2.translateExpressionType", "grouperProvisioningEntityField")
            .addExtraConfig("targetEntityAttribute.2.translateFromGrouperProvisioningEntityField", "subjectId")
            .addExtraConfig("entityMatchingAttributeCount", "3")
            .addExtraConfig("entityMatchingAttribute2name", "customField_pennId")
            .addExtraConfig("entityAttributeValueCache2has", "true")
            .addExtraConfig("entityAttributeValueCache2source", "target")
            .addExtraConfig("entityAttributeValueCache2type", "entityAttribute")
            .addExtraConfig("entityAttributeValueCache2entityAttribute", "customField_pennId")
    );

    GrouperUtil.sleep(5000);

    GrouperStartup.startup();

    try {
      // this will create tables
      FreshAgentApiCommands.retrieveAgentUsers("freshServiceDev", false);

      new GcDbAccess().connectionName("grouper").sql("delete from mock_freshagent_membership").executeSql();
      new GcDbAccess().connectionName("grouper").sql("delete from mock_freshagent_group").executeSql();
      new GcDbAccess().connectionName("grouper").sql("delete from mock_freshagent_user").executeSql();

      GrouperSession grouperSession = GrouperSession.startRootSession();

      Stem stem = new StemSave(grouperSession).assignName("test").save();

      Group testGroup = new GroupSave(grouperSession).assignName("test:testGroup").save();

      testGroup.addMember(SubjectTestHelper.SUBJ0, false);
      testGroup.addMember(SubjectTestHelper.SUBJ1, false);

      // if incremental, initialize provisioner state before attaching provisioning attribute
      if (!isFull) {
        fullProvision();
        incrementalProvision();
      }

      final GrouperProvisioningAttributeValue attributeValue = new GrouperProvisioningAttributeValue();
      attributeValue.setDirectAssignment(true);
      attributeValue.setDoProvision("freshAgentProvisioner");
      attributeValue.setTargetName("freshAgentProvisioner");
      attributeValue.setStemScopeString("sub");

      GrouperProvisioningService.saveOrUpdateProvisioningAttributes(attributeValue, stem);

      // assert mock tables are empty before sync
      assertEquals(new Integer(0), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_group").select(int.class));
      assertEquals(new Integer(0), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_user").select(int.class));
      assertEquals(new Integer(0), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_membership").select(int.class));

      //
      // first provision: should create group, 2 users with customField_pennId, 2 memberships
      //
      if (isFull) {
        fullProvision();
      } else {
        incrementalProvision();
      }

      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_group").select(int.class));
      assertEquals(new Integer(2), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_user where active = 'T'").select(int.class));
      assertEquals(new Integer(2), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_membership").select(int.class));

      // verify customField_pennId is set to subjectId for both users
      String customFields0 = new GcDbAccess().connectionName("grouper")
          .sql("select custom_fields from mock_freshagent_user where email = ?")
          .addBindVar("test.subject.0@somewhere.someSchool.edu")
          .select(String.class);
      assertNotNull(customFields0);
      assertTrue(customFields0.contains("pennId"));
      assertTrue(customFields0.contains("test.subject.0"));

      String customFields1 = new GcDbAccess().connectionName("grouper")
          .sql("select custom_fields from mock_freshagent_user where email = ?")
          .addBindVar("test.subject.1@somewhere.someSchool.edu")
          .select(String.class);
      assertNotNull(customFields1);
      assertTrue(customFields1.contains("pennId"));
      assertTrue(customFields1.contains("test.subject.1"));

      // verify via commands class
      Long groupId = new GcDbAccess().connectionName("grouper")
          .sql("select id from mock_freshagent_group").select(Long.class);

      List<FreshAgentUser> members = FreshAgentApiCommands.retrieveMembershipsByGroup("freshServiceDev", groupId);
      assertEquals(2, members.size());

      // verify custom field via commands class attribute search
      FreshAgentUser user0 = FreshAgentApiCommands.retrieveAgentUserByAttribute("freshServiceDev", "customField_pennId", "test.subject.0");
      assertNotNull(user0);
      assertNotNull(user0.getCustomFields());
      assertEquals("test.subject.0", user0.getCustomFields().get("pennId"));

      FreshAgentUser user1 = FreshAgentApiCommands.retrieveAgentUserByAttribute("freshServiceDev", "customField_pennId", "test.subject.1");
      assertNotNull(user1);
      assertNotNull(user1.getCustomFields());
      assertEquals("test.subject.1", user1.getCustomFields().get("pennId"));

      //
      // remove one member and provision again
      //
      testGroup.deleteMember(SubjectTestHelper.SUBJ1);

      if (isFull) {
        fullProvision();
      } else {
        incrementalProvision();
      }

      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_group").select(int.class));
      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_user where active = 'T'").select(int.class));
      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_membership").select(int.class));

      // verify via commands class: only 1 membership remains
      members = FreshAgentApiCommands.retrieveMembershipsByGroup("freshServiceDev", groupId);
      assertEquals(1, members.size());

      // the remaining member should be SUBJ0
      FreshAgentUser remainingUser = FreshAgentApiCommands.retrieveAgentUserByAttribute("freshServiceDev", "customField_pennId", "test.subject.0");
      assertNotNull(remainingUser);
      assertEquals(Boolean.TRUE, remainingUser.getActive());

      //
      // add a new member and provision again
      //
      testGroup.addMember(SubjectTestHelper.SUBJ2, false);

      if (isFull) {
        fullProvision();
      } else {
        incrementalProvision();
      }

      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_group").select(int.class));
      assertEquals(new Integer(2), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_user where active = 'T'").select(int.class));
      assertEquals(new Integer(2), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_membership").select(int.class));

      // verify via commands class: 2 memberships
      members = FreshAgentApiCommands.retrieveMembershipsByGroup("freshServiceDev", groupId);
      assertEquals(2, members.size());

      // verify the new user has customField_pennId set
      FreshAgentUser user2 = FreshAgentApiCommands.retrieveAgentUserByAttribute("freshServiceDev", "customField_pennId", "test.subject.2");
      assertNotNull(user2);
      assertNotNull(user2.getCustomFields());
      assertEquals("test.subject.2", user2.getCustomFields().get("pennId"));

      //
      // remove all members and provision again
      //
      testGroup.deleteMember(SubjectTestHelper.SUBJ0);
      testGroup.deleteMember(SubjectTestHelper.SUBJ2);

      if (isFull) {
        fullProvision();
      } else {
        incrementalProvision();
      }

      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_group").select(int.class));
      assertEquals(new Integer(0), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_user where active = 'T'").select(int.class));
      assertEquals(new Integer(0), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_freshagent_membership").select(int.class));

      // verify via commands class: 0 memberships
      members = FreshAgentApiCommands.retrieveMembershipsByGroup("freshServiceDev", groupId);
      assertEquals(0, members.size());

    } finally {

    }
  }

  public void testReactivateAgentUser() {

    FreshAgentProvisionerTestUtils.setupFreshAgentExternalSystem();

    // create a user
    FreshAgentUser userToCreate = new FreshAgentUser();
    userToCreate.setFirstName("John");
    userToCreate.setLastName("Smith");
    userToCreate.setEmail("jsmith@test.edu");
    userToCreate.setActive(true);

    FreshAgentUser createdUser = seedAgent(userToCreate);
    assertNotNull(createdUser);
    assertTrue(createdUser.getId() > 0);

    // deactivate the user
    FreshAgentApiCommands.deactivateAgentUser("freshServiceDev", createdUser.getId());

    // verify user is inactive
    FreshAgentUser deactivatedUser = FreshAgentApiCommands.retrieveAgentUserById("freshServiceDev", createdUser.getId(), true);
    assertNotNull(deactivatedUser);
    assertEquals(Boolean.FALSE, deactivatedUser.getActive());

    // reactivate the user (2-arg overload defaults reactivateAsFullTime=true, which
    // issues a follow-up PUT setting occasional=false after the reactivate)
    FreshAgentApiCommands.reactivateAgentUser("freshServiceDev", createdUser.getId());

    // verify user is active again
    FreshAgentUser reactivatedUser = FreshAgentApiCommands.retrieveAgentUserById("freshServiceDev", createdUser.getId(), false);
    assertNotNull(reactivatedUser);
    assertEquals(Boolean.TRUE, reactivatedUser.getActive());

    // reactivate again should not throw (400 with body is allowed). Because the
    // agent is already active, the reactivate returns 400 and the full-time
    // follow-up PUT is intentionally skipped.
    FreshAgentApiCommands.reactivateAgentUser("freshServiceDev", createdUser.getId());

    // verify still active
    reactivatedUser = FreshAgentApiCommands.retrieveAgentUserById("freshServiceDev", createdUser.getId(), false);
    assertNotNull(reactivatedUser);
    assertEquals(Boolean.TRUE, reactivatedUser.getActive());
  }

  /**
   * The 3-arg reactivate overload with reactivateAsFullTime=true reactivates the
   * agent and then issues the occasional=false follow-up PUT. The mock does not
   * model the "occasional" license-type field, so this test verifies the agent is
   * reactivated (active flips to true) and that the follow-up PUT completes
   * without error; it does not assert the occasional value itself.
   */
  public void testReactivateAgentUserFullTime() {

    FreshAgentProvisionerTestUtils.setupFreshAgentExternalSystem();

    FreshAgentUser userToCreate = new FreshAgentUser();
    userToCreate.setFirstName("Full");
    userToCreate.setLastName("Time");
    userToCreate.setEmail("fulltime@test.edu");
    userToCreate.setActive(true);

    FreshAgentUser createdUser = seedAgent(userToCreate);

    FreshAgentApiCommands.deactivateAgentUser("freshServiceDev", createdUser.getId());

    FreshAgentUser deactivatedUser = FreshAgentApiCommands.retrieveAgentUserById("freshServiceDev", createdUser.getId(), true);
    assertNotNull(deactivatedUser);
    assertEquals(Boolean.FALSE, deactivatedUser.getActive());

    // reactivate as full-time (explicit true)
    FreshAgentApiCommands.reactivateAgentUser("freshServiceDev", createdUser.getId(), true);

    FreshAgentUser reactivatedUser = FreshAgentApiCommands.retrieveAgentUserById("freshServiceDev", createdUser.getId(), false);
    assertNotNull(reactivatedUser);
    assertEquals(Boolean.TRUE, reactivatedUser.getActive());
  }

  /**
   * The 3-arg reactivate overload with reactivateAsFullTime=false reactivates the
   * agent without the occasional=false follow-up PUT, leaving the agent occasional
   * (day-pass). As above, the mock does not model the occasional field, so this
   * verifies reactivation succeeds without error.
   */
  public void testReactivateAgentUserOccasional() {

    FreshAgentProvisionerTestUtils.setupFreshAgentExternalSystem();

    FreshAgentUser userToCreate = new FreshAgentUser();
    userToCreate.setFirstName("Occasional");
    userToCreate.setLastName("Agent");
    userToCreate.setEmail("occasional@test.edu");
    userToCreate.setActive(true);

    FreshAgentUser createdUser = seedAgent(userToCreate);

    FreshAgentApiCommands.deactivateAgentUser("freshServiceDev", createdUser.getId());

    FreshAgentUser deactivatedUser = FreshAgentApiCommands.retrieveAgentUserById("freshServiceDev", createdUser.getId(), true);
    assertNotNull(deactivatedUser);
    assertEquals(Boolean.FALSE, deactivatedUser.getActive());

    // reactivate but leave occasional (explicit false)
    FreshAgentApiCommands.reactivateAgentUser("freshServiceDev", createdUser.getId(), false);

    FreshAgentUser reactivatedUser = FreshAgentApiCommands.retrieveAgentUserById("freshServiceDev", createdUser.getId(), false);
    assertNotNull(reactivatedUser);
    assertEquals(Boolean.TRUE, reactivatedUser.getActive());
  }

  /**
   * Seed an agent directly into the mock_freshagent_user table, bypassing the
   * Freshservice API. Tests that need a pre-existing agent to already be in the
   * target insert one here and read back the generated id.
   *
   * Note: FreshAgentApiCommands.createAgentUser DOES exist and is exercised by the
   * create/provisioning tests. Seeding directly is used only when a test needs an
   * agent to pre-exist in the target without going through the create path (e.g.
   * to set up an update/deactivate/reactivate scenario, or to seed fields the
   * provisioner does not write, such as job_title/department_id).
   *
   * @param agent the agent values to seed (id is ignored and generated)
   * @return the same agent with its generated id populated
   */
  private FreshAgentUser seedAgent(FreshAgentUser agent) {
    Long id = java.util.concurrent.ThreadLocalRandom.current().nextLong(1, 99999999L);

    String activeFlag = (agent.getActive() == null || agent.getActive()) ? "T" : "F";

    new GcDbAccess().connectionName("grouper")
        .sql("insert into mock_freshagent_user "
            + "(id, email, first_name, last_name, job_title, department_id, custom_fields, roles_json, active) "
            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?)")
        .addBindVar(id)
        .addBindVar(agent.getEmail())
        .addBindVar(agent.getFirstName())
        .addBindVar(agent.getLastName())
        .addBindVar(agent.getJobTitle())
        .addBindVar(agent.getDepartmentId())
        .addBindVar(agent.getCustomFieldsJson())
        .addBindVar(agent.getRolesJson())
        .addBindVar(activeFlag)
        .executeSql();

    agent.setId(id);
    return agent;
  }

}


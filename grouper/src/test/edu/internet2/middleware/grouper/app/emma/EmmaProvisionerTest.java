package edu.internet2.middleware.grouper.app.emma;


import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.internet2.middleware.grouper.Group;
import edu.internet2.middleware.grouper.GroupSave;
import edu.internet2.middleware.grouper.GrouperSession;
import edu.internet2.middleware.grouper.Stem;
import edu.internet2.middleware.grouper.StemSave;
import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioningAttributeValue;
import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioningBaseTest;
import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioningService;
import edu.internet2.middleware.grouper.cfg.dbConfig.GrouperDbConfig;
import edu.internet2.middleware.grouper.helper.SubjectTestHelper;
import edu.internet2.middleware.grouper.misc.GrouperStartup;
import edu.internet2.middleware.grouper.misc.SaveMode;
import edu.internet2.middleware.grouper.util.GrouperUtil;
import edu.internet2.middleware.grouperClient.config.ConfigPropertiesCascadeBase;
import edu.internet2.middleware.grouperClient.jdbc.GcDbAccess;
import junit.textui.TestRunner;

public class EmmaProvisionerTest extends GrouperProvisioningBaseTest {

  public static void main(String[] args) {

    EmmaMockServiceHandler.ensureEmmaMockTables();
    TestRunner.run(new EmmaProvisionerTest("testAddMemberByCustomField"));

    System.exit(0);
  }

  @Override
  public String defaultConfigId() {
    return "emmaProvisioner";
  }

  public static boolean startTomcat = false;

  public EmmaProvisionerTest(String name) {
    super(name);
  }

  @Override
  protected void setUp() {
    super.setUp();

    EmmaMockServiceHandler.ensureEmmaMockTables();

    new GcDbAccess().connectionName("grouper").sql("delete from mock_emma_membership").executeSql();
    new GcDbAccess().connectionName("grouper").sql("delete from mock_emma_group").executeSql();
    new GcDbAccess().connectionName("grouper").sql("delete from mock_emma_member").executeSql();
  }

  // ============================================================================
  // Group API tests
  // ============================================================================

  public void testRetrieveGroups() {

    EmmaProvisionerTestUtils.setupEmmaExternalSystem();

    // insert some groups directly into the mock table (Emma groups have only id + group_name)
    new GcDbAccess().connectionName("grouper").sql("insert into mock_emma_group (id, group_name) values (1001, 'IT Support')").executeSql();
    new GcDbAccess().connectionName("grouper").sql("insert into mock_emma_group (id, group_name) values (1002, 'HR Team')").executeSql();
    new GcDbAccess().connectionName("grouper").sql("insert into mock_emma_group (id, group_name) values (1003, 'Engineering')").executeSql();

    List<EmmaGroup> groups = EmmaApiCommands.retrieveGroups("emmaDev");

    assertEquals(3, groups.size());

    Map<Long, EmmaGroup> groupById = new HashMap<Long, EmmaGroup>();
    for (EmmaGroup group : groups) {
      groupById.put(group.getId(), group);
    }

    EmmaGroup group1001 = groupById.get(1001L);
    assertNotNull(group1001);
    assertEquals("IT Support", group1001.getName());

    EmmaGroup group1002 = groupById.get(1002L);
    assertNotNull(group1002);
    assertEquals("HR Team", group1002.getName());

    EmmaGroup group1003 = groupById.get(1003L);
    assertNotNull(group1003);
    assertEquals("Engineering", group1003.getName());
  }

  public void testRetrieveGroup() {

    EmmaProvisionerTestUtils.setupEmmaExternalSystem();

    // insert a group directly into the mock table
    new GcDbAccess().connectionName("grouper").sql("insert into mock_emma_group (id, group_name) values (1001, 'IT Support')").executeSql();

    // retrieve existing group
    EmmaGroup group = EmmaApiCommands.retrieveGroup("emmaDev", 1001L);

    assertNotNull(group);
    assertEquals(1001L, (long)group.getId());
    assertEquals("IT Support", group.getName());

    // retrieve non-existing group should return null (mock returns 404)
    EmmaGroup notFound = EmmaApiCommands.retrieveGroup("emmaDev", 9999L);

    assertNull(notFound);
  }

  public void testCreateGroup() {

    EmmaProvisionerTestUtils.setupEmmaExternalSystem();

    EmmaGroup groupToCreate = new EmmaGroup();
    groupToCreate.setName("Branch Managers");

    // create the group (Emma POST /groups wraps the group in a { "groups": [ ... ] } envelope
    // and returns an array of created groups)
    EmmaGroup createdGroup = EmmaApiCommands.createGroup("emmaDev", groupToCreate);

    assertNotNull(createdGroup);
    assertTrue(createdGroup.getId() > 0);
    assertEquals("Branch Managers", createdGroup.getName());

    // verify it can be retrieved
    EmmaGroup retrievedGroup = EmmaApiCommands.retrieveGroup("emmaDev", createdGroup.getId());

    assertNotNull(retrievedGroup);
    assertEquals(createdGroup.getId(), retrievedGroup.getId());
    assertEquals("Branch Managers", retrievedGroup.getName());
  }

  public void testUpdateGroup() {

    EmmaProvisionerTestUtils.setupEmmaExternalSystem();

    // create a group to update
    EmmaGroup groupToCreate = new EmmaGroup();
    groupToCreate.setName("Original Name");

    EmmaGroup createdGroup = EmmaApiCommands.createGroup("emmaDev", groupToCreate);
    assertNotNull(createdGroup);
    assertTrue(createdGroup.getId() > 0);

    // update the group name (Emma groups only have a name)
    EmmaGroup groupToUpdate = new EmmaGroup();
    groupToUpdate.setId(createdGroup.getId());
    groupToUpdate.setName("Human Resources");

    EmmaApiCommands.updateGroup("emmaDev", groupToUpdate);

    // verify via retrieve that the new name persisted
    EmmaGroup retrievedGroup = EmmaApiCommands.retrieveGroup("emmaDev", createdGroup.getId());
    assertNotNull(retrievedGroup);
    assertEquals("Human Resources", retrievedGroup.getName());

    // update with an id of 0 / null should throw
    EmmaGroup badGroup = new EmmaGroup();
    badGroup.setName("No Id");
    try {
      EmmaApiCommands.updateGroup("emmaDev", badGroup);
      fail("Should have thrown exception for group with unset id");
    } catch (RuntimeException e) {
      assertTrue(e.getMessage().contains("null or 0"));
    }
  }

  public void testDeleteGroup() {

    EmmaProvisionerTestUtils.setupEmmaExternalSystem();

    // create a group to delete
    EmmaGroup groupToCreate = new EmmaGroup();
    groupToCreate.setName("Temp Group");

    EmmaGroup createdGroup = EmmaApiCommands.createGroup("emmaDev", groupToCreate);
    assertNotNull(createdGroup);
    assertTrue(createdGroup.getId() > 0);

    // verify it exists
    EmmaGroup retrievedGroup = EmmaApiCommands.retrieveGroup("emmaDev", createdGroup.getId());
    assertNotNull(retrievedGroup);

    // delete the group
    EmmaApiCommands.deleteGroup("emmaDev", createdGroup.getId());

    // verify it no longer exists
    EmmaGroup deletedGroup = EmmaApiCommands.retrieveGroup("emmaDev", createdGroup.getId());
    assertNull(deletedGroup);

    // delete again should not throw an error (404 is an accepted return code)
    EmmaApiCommands.deleteGroup("emmaDev", createdGroup.getId());
  }

  // ============================================================================
  // Member API tests
  // ============================================================================

  public void testRetrieveMembers() {

    EmmaProvisionerTestUtils.setupEmmaExternalSystem();

    // insert some members directly into the mock table. first_name / last_name and any
    // user-defined values are surfaced by the mock inside the JSON "fields" object.
    new GcDbAccess().connectionName("grouper")
        .sql("insert into mock_emma_member (id, email, first_name, last_name, member_status_id) values (2001, 'jsmith@test.edu', 'John', 'Smith', 'a')")
        .executeSql();
    new GcDbAccess().connectionName("grouper")
        .sql("insert into mock_emma_member (id, email, first_name, last_name, member_status_id) values (2002, 'jdoe@test.edu', 'Jane', 'Doe', 'a')")
        .executeSql();
    new GcDbAccess().connectionName("grouper")
        .sql("insert into mock_emma_member (id, email, first_name, last_name, member_status_id) values (2003, 'bwilson@test.edu', 'Bob', 'Wilson', 'o')")
        .executeSql();

    List<EmmaMember> members = EmmaApiCommands.retrieveMembers("emmaDev");

    assertEquals(3, members.size());

    Map<Long, EmmaMember> memberById = new HashMap<Long, EmmaMember>();
    for (EmmaMember member : members) {
      memberById.put(member.getId(), member);
    }

    EmmaMember member2001 = memberById.get(2001L);
    assertNotNull(member2001);
    assertEquals("jsmith@test.edu", member2001.getEmail());
    assertEquals("John", member2001.getFirstName());
    assertEquals("Smith", member2001.getLastName());
    assertEquals("a", member2001.getMemberStatusId());

    EmmaMember member2002 = memberById.get(2002L);
    assertNotNull(member2002);
    assertEquals("jdoe@test.edu", member2002.getEmail());
    assertEquals("Jane", member2002.getFirstName());
    assertEquals("Doe", member2002.getLastName());

    EmmaMember member2003 = memberById.get(2003L);
    assertNotNull(member2003);
    assertEquals("bwilson@test.edu", member2003.getEmail());
    assertEquals("o", member2003.getMemberStatusId());
  }

  public void testRetrieveMemberById() {

    EmmaProvisionerTestUtils.setupEmmaExternalSystem();

    new GcDbAccess().connectionName("grouper")
        .sql("insert into mock_emma_member (id, email, first_name, last_name, member_status_id) values (2001, 'jsmith@test.edu', 'John', 'Smith', 'a')")
        .executeSql();

    // retrieve existing member
    EmmaMember member = EmmaApiCommands.retrieveMemberById("emmaDev", 2001L);

    assertNotNull(member);
    assertEquals(2001L, (long)member.getId());
    assertEquals("jsmith@test.edu", member.getEmail());
    assertEquals("John", member.getFirstName());
    assertEquals("Smith", member.getLastName());

    // retrieve non-existing member should return null
    EmmaMember notFound = EmmaApiCommands.retrieveMemberById("emmaDev", 9999L);

    assertNull(notFound);
  }

  public void testRetrieveMemberByEmail() {

    EmmaProvisionerTestUtils.setupEmmaExternalSystem();

    new GcDbAccess().connectionName("grouper")
        .sql("insert into mock_emma_member (id, email, first_name, last_name, member_status_id) values (2001, 'jsmith@test.edu', 'John', 'Smith', 'a')")
        .executeSql();
    new GcDbAccess().connectionName("grouper")
        .sql("insert into mock_emma_member (id, email, first_name, last_name, member_status_id) values (2002, 'jdoe@test.edu', 'Jane', 'Doe', 'a')")
        .executeSql();

    // retrieve existing member by email
    EmmaMember member = EmmaApiCommands.retrieveMemberByEmail("emmaDev", "jsmith@test.edu");

    assertNotNull(member);
    assertEquals(2001L, (long)member.getId());
    assertEquals("jsmith@test.edu", member.getEmail());
    assertEquals("John", member.getFirstName());
    assertEquals("Smith", member.getLastName());

    // retrieve non-existing email should return null
    EmmaMember notFound = EmmaApiCommands.retrieveMemberByEmail("emmaDev", "nobody@test.edu");

    assertNull(notFound);

    // blank email should short-circuit to null
    EmmaMember blank = EmmaApiCommands.retrieveMemberByEmail("emmaDev", "");
    assertNull(blank);
  }

  public void testRetrieveMemberByAttribute() {

    EmmaProvisionerTestUtils.setupEmmaExternalSystem();

    new GcDbAccess().connectionName("grouper")
        .sql("insert into mock_emma_member (id, email, first_name, last_name, member_status_id) values (2001, 'jsmith@test.edu', 'John', 'Smith', 'a')")
        .executeSql();

    // lookup by id
    EmmaMember byId = EmmaApiCommands.retrieveMemberByAttribute("emmaDev", "id", 2001L);
    assertNotNull(byId);
    assertEquals("jsmith@test.edu", byId.getEmail());

    // lookup by email
    EmmaMember byEmail = EmmaApiCommands.retrieveMemberByAttribute("emmaDev", "email", "jsmith@test.edu");
    assertNotNull(byEmail);
    assertEquals(2001L, (long)byEmail.getId());

    // null value returns null
    EmmaMember nullValue = EmmaApiCommands.retrieveMemberByAttribute("emmaDev", "id", null);
    assertNull(nullValue);

    // blank attribute name throws
    try {
      EmmaApiCommands.retrieveMemberByAttribute("emmaDev", "", "x");
      fail("Should have thrown for blank attribute name");
    } catch (RuntimeException e) {
      assertTrue(e.getMessage().contains("attributeName is required"));
    }

    // unsupported attribute name throws
    try {
      EmmaApiCommands.retrieveMemberByAttribute("emmaDev", "firstName", "John");
      fail("Should have thrown for unsupported attribute name");
    } catch (RuntimeException e) {
      assertTrue(e.getMessage().contains("Unsupported attributeName"));
    }
  }

  /**
   * addMember on a brand new email should create the member (added=true) and return the id.
   */
  public void testAddMemberCreatesNew() {

    EmmaProvisionerTestUtils.setupEmmaExternalSystem();

    EmmaMember memberToAdd = new EmmaMember();
    memberToAdd.setEmail("newmember@test.edu");
    memberToAdd.setFirstName("New");
    memberToAdd.setLastName("Member");

    EmmaMember created = EmmaApiCommands.addMember("emmaDev", memberToAdd);

    assertNotNull(created);
    assertNotNull(created.getId());
    assertTrue(created.getId() > 0);

    // verify via retrieve
    EmmaMember retrieved = EmmaApiCommands.retrieveMemberById("emmaDev", created.getId());
    assertNotNull(retrieved);
    assertEquals("newmember@test.edu", retrieved.getEmail());
    assertEquals("New", retrieved.getFirstName());
    assertEquals("Member", retrieved.getLastName());
  }

  /**
   * Emma keys members on email, so addMember on an existing email should UPDATE the
   * existing member (not create a duplicate) and return that member's id.
   */
  public void testAddMemberUpdatesExistingByEmail() {

    EmmaProvisionerTestUtils.setupEmmaExternalSystem();

    // seed an existing member
    EmmaMember first = new EmmaMember();
    first.setEmail("existing@test.edu");
    first.setFirstName("Existing");
    first.setLastName("Member");
    EmmaMember seeded = EmmaApiCommands.addMember("emmaDev", first);
    assertNotNull(seeded.getId());

    // add again with the same email but a different first name
    EmmaMember second = new EmmaMember();
    second.setEmail("existing@test.edu");
    second.setFirstName("Updated");
    second.setLastName("Member");

    EmmaMember result = EmmaApiCommands.addMember("emmaDev", second);

    assertNotNull(result);
    // same id (updated, not created new)
    assertEquals(seeded.getId(), result.getId());

    // only one member with that email should exist
    List<EmmaMember> all = EmmaApiCommands.retrieveMembers("emmaDev");
    int count = 0;
    for (EmmaMember m : all) {
      if ("existing@test.edu".equals(m.getEmail())) {
        count++;
      }
    }
    assertEquals(1, count);

    // the first name should have been updated
    EmmaMember retrieved = EmmaApiCommands.retrieveMemberById("emmaDev", seeded.getId());
    assertNotNull(retrieved);
    assertEquals("Updated", retrieved.getFirstName());
  }

  /**
   * addMember requires an email; a blank email should throw before any HTTP call.
   */
  public void testAddMemberEmailRequired() {

    EmmaProvisionerTestUtils.setupEmmaExternalSystem();

    EmmaMember memberToAdd = new EmmaMember();
    memberToAdd.setFirstName("No");
    memberToAdd.setLastName("Email");

    try {
      EmmaApiCommands.addMember("emmaDev", memberToAdd);
      fail("Should have thrown for missing email");
    } catch (RuntimeException e) {
      assertTrue(e.getMessage().contains("email is required"));
    }

    // nothing should have been created
    List<EmmaMember> all = EmmaApiCommands.retrieveMembers("emmaDev");
    assertEquals(0, all.size());
  }

  /**
   * addMember should round-trip user-defined fields (carried in the customFields map,
   * which the Emma JSON nests under "fields").
   */
  public void testAddMemberByCustomField() {

    EmmaProvisionerTestUtils.setupEmmaExternalSystem();

    Map<String, Object> customFields = new HashMap<String, Object>();
    customFields.put("pennkey", "jsmith");
    customFields.put("pennId", 12345678L);

    EmmaMember memberToAdd = new EmmaMember();
    memberToAdd.setEmail("jsmith@test.edu");
    memberToAdd.setFirstName("John");
    memberToAdd.setLastName("Smith");
    memberToAdd.setCustomFields(customFields);

    EmmaMember created = EmmaApiCommands.addMember("emmaDev", memberToAdd);
    assertNotNull(created.getId());

    EmmaMember retrieved = EmmaApiCommands.retrieveMemberById("emmaDev", created.getId());
    assertNotNull(retrieved);
    assertNotNull(retrieved.getCustomFields());
    assertEquals("jsmith", retrieved.getCustomFields().get("pennkey"));
    // numeric field values normalize to Long
    assertEquals(12345678L, retrieved.getCustomFields().get("pennId"));
  }

  public void testUpdateMember() {

    EmmaProvisionerTestUtils.setupEmmaExternalSystem();

    // create a member to update, with a couple of custom fields
    Map<String, Object> customFields = new HashMap<String, Object>();
    customFields.put("pennkey", "jsmith");
    customFields.put("penn_id", "12345678");

    EmmaMember memberToAdd = new EmmaMember();
    memberToAdd.setEmail("jsmith@test.edu");
    memberToAdd.setFirstName("John");
    memberToAdd.setLastName("Smith");
    memberToAdd.setCustomFields(customFields);

    EmmaMember createdMember = EmmaApiCommands.addMember("emmaDev", memberToAdd);
    assertNotNull(createdMember.getId());

    // update email + firstName only
    EmmaMember memberToUpdate = new EmmaMember();
    memberToUpdate.setId(createdMember.getId());
    memberToUpdate.setEmail("jsmith2@upenn.edu");
    memberToUpdate.setFirstName("Johnny");

    Set<String> fieldsToUpdate = new java.util.LinkedHashSet<String>();
    fieldsToUpdate.add("email");
    fieldsToUpdate.add("firstName");

    EmmaApiCommands.updateMember("emmaDev", memberToUpdate, fieldsToUpdate);

    EmmaMember retrievedMember = EmmaApiCommands.retrieveMemberById("emmaDev", createdMember.getId());
    assertNotNull(retrievedMember);
    assertEquals("jsmith2@upenn.edu", retrievedMember.getEmail());
    assertEquals("Johnny", retrievedMember.getFirstName());
    // last name untouched
    assertEquals("Smith", retrievedMember.getLastName());

    // update a custom field (field_ prefixed attribute names select which fields go on the wire)
    Map<String, Object> updatedCustomFields = new HashMap<String, Object>();
    updatedCustomFields.put("pennkey", "jsmith2");

    EmmaMember memberToUpdate2 = new EmmaMember();
    memberToUpdate2.setId(createdMember.getId());
    memberToUpdate2.setCustomFields(updatedCustomFields);

    Set<String> fieldsToUpdate2 = new java.util.LinkedHashSet<String>();
    fieldsToUpdate2.add(EmmaMember.CUSTOM_FIELD_ATTRIBUTE_PREFIX + "pennkey");

    EmmaApiCommands.updateMember("emmaDev", memberToUpdate2, fieldsToUpdate2);

    EmmaMember retrievedMember2 = EmmaApiCommands.retrieveMemberById("emmaDev", createdMember.getId());
    assertNotNull(retrievedMember2);
    assertNotNull(retrievedMember2.getCustomFields());
    assertEquals("jsmith2", retrievedMember2.getCustomFields().get("pennkey"));
    // the previously stored penn_id field is preserved (mock merges fields)
    assertEquals("12345678", retrievedMember2.getCustomFields().get("penn_id"));

    // update a non-existing member should throw (mock returns 404, not an allowed code for PUT)
    EmmaMember nonExisting = new EmmaMember();
    nonExisting.setId(9999L);
    nonExisting.setFirstName("Nobody");

    Set<String> fieldsToUpdate3 = new java.util.LinkedHashSet<String>();
    fieldsToUpdate3.add("firstName");

    try {
      EmmaApiCommands.updateMember("emmaDev", nonExisting, fieldsToUpdate3);
      fail("Should have thrown exception for non-existing member");
    } catch (RuntimeException e) {
      assertTrue(e.getMessage().contains("404"));
    }
  }

  public void testDeleteMember() {

    EmmaProvisionerTestUtils.setupEmmaExternalSystem();

    // create a member to delete
    EmmaMember memberToAdd = new EmmaMember();
    memberToAdd.setEmail("jsmith@test.edu");
    memberToAdd.setFirstName("John");
    memberToAdd.setLastName("Smith");

    EmmaMember createdMember = EmmaApiCommands.addMember("emmaDev", memberToAdd);
    assertNotNull(createdMember.getId());

    // verify it exists
    EmmaMember retrievedMember = EmmaApiCommands.retrieveMemberById("emmaDev", createdMember.getId());
    assertNotNull(retrievedMember);

    // delete (archive) the member
    EmmaApiCommands.deleteMember("emmaDev", createdMember.getId());

    // verify it no longer exists
    EmmaMember deletedMember = EmmaApiCommands.retrieveMemberById("emmaDev", createdMember.getId());
    assertNull(deletedMember);

    // delete again should not throw (404 is an accepted return code)
    EmmaApiCommands.deleteMember("emmaDev", createdMember.getId());
  }

  // ============================================================================
  // Membership API tests
  // ============================================================================

  public void testAddGroupMembership() {

    EmmaProvisionerTestUtils.setupEmmaExternalSystem();

    // create a group and a member
    EmmaGroup groupToCreate = new EmmaGroup();
    groupToCreate.setName("IT Support");
    EmmaGroup createdGroup = EmmaApiCommands.createGroup("emmaDev", groupToCreate);
    assertNotNull(createdGroup);

    EmmaMember memberToAdd = new EmmaMember();
    memberToAdd.setEmail("jsmith@test.edu");
    memberToAdd.setFirstName("John");
    memberToAdd.setLastName("Smith");
    EmmaMember createdMember = EmmaApiCommands.addMember("emmaDev", memberToAdd);
    assertNotNull(createdMember.getId());

    // verify no memberships exist yet
    int count = new GcDbAccess().connectionName("grouper")
        .sql("select count(*) from mock_emma_membership where group_id = ? and user_id = ?")
        .addBindVar(createdGroup.getId()).addBindVar(createdMember.getId())
        .select(int.class);
    assertEquals(0, count);

    // add membership
    EmmaApiCommands.addGroupMembership("emmaDev", createdGroup.getId(), createdMember.getId());

    count = new GcDbAccess().connectionName("grouper")
        .sql("select count(*) from mock_emma_membership where group_id = ? and user_id = ?")
        .addBindVar(createdGroup.getId()).addBindVar(createdMember.getId())
        .select(int.class);
    assertEquals(1, count);

    // adding the same membership again should not create a duplicate (mock is idempotent)
    EmmaApiCommands.addGroupMembership("emmaDev", createdGroup.getId(), createdMember.getId());

    count = new GcDbAccess().connectionName("grouper")
        .sql("select count(*) from mock_emma_membership where group_id = ? and user_id = ?")
        .addBindVar(createdGroup.getId()).addBindVar(createdMember.getId())
        .select(int.class);
    assertEquals(1, count);

    // null group id or member id should throw
    try {
      EmmaApiCommands.addGroupMembership("emmaDev", null, createdMember.getId());
      fail("Should have thrown for null group id");
    } catch (RuntimeException e) {
      assertTrue(e.getMessage().contains("groupId is null"));
    }
    try {
      EmmaApiCommands.addGroupMembership("emmaDev", createdGroup.getId(), null);
      fail("Should have thrown for null member id");
    } catch (RuntimeException e) {
      assertTrue(e.getMessage().contains("memberId is null"));
    }
  }

  public void testRemoveGroupMembership() {

    EmmaProvisionerTestUtils.setupEmmaExternalSystem();

    EmmaGroup groupToCreate = new EmmaGroup();
    groupToCreate.setName("IT Support");
    EmmaGroup createdGroup = EmmaApiCommands.createGroup("emmaDev", groupToCreate);
    assertNotNull(createdGroup);

    EmmaMember member1 = new EmmaMember();
    member1.setEmail("jsmith@test.edu");
    member1.setFirstName("John");
    member1.setLastName("Smith");
    EmmaMember createdMember1 = EmmaApiCommands.addMember("emmaDev", member1);

    EmmaMember member2 = new EmmaMember();
    member2.setEmail("jdoe@test.edu");
    member2.setFirstName("Jane");
    member2.setLastName("Doe");
    EmmaMember createdMember2 = EmmaApiCommands.addMember("emmaDev", member2);

    // add both memberships
    EmmaApiCommands.addGroupMembership("emmaDev", createdGroup.getId(), createdMember1.getId());
    EmmaApiCommands.addGroupMembership("emmaDev", createdGroup.getId(), createdMember2.getId());

    int count = new GcDbAccess().connectionName("grouper")
        .sql("select count(*) from mock_emma_membership where group_id = ?")
        .addBindVar(createdGroup.getId())
        .select(int.class);
    assertEquals(2, count);

    // remove first membership
    EmmaApiCommands.removeGroupMembership("emmaDev", createdGroup.getId(), createdMember1.getId());

    count = new GcDbAccess().connectionName("grouper")
        .sql("select count(*) from mock_emma_membership where group_id = ?")
        .addBindVar(createdGroup.getId())
        .select(int.class);
    assertEquals(1, count);

    count = new GcDbAccess().connectionName("grouper")
        .sql("select count(*) from mock_emma_membership where group_id = ? and user_id = ?")
        .addBindVar(createdGroup.getId()).addBindVar(createdMember2.getId())
        .select(int.class);
    assertEquals(1, count);

    // remove again should not throw
    EmmaApiCommands.removeGroupMembership("emmaDev", createdGroup.getId(), createdMember1.getId());

    // remove non-existing membership should not throw
    EmmaApiCommands.removeGroupMembership("emmaDev", createdGroup.getId(), 9999L);
  }

  public void testRetrieveMembershipsByGroup() {

    EmmaProvisionerTestUtils.setupEmmaExternalSystem();

    EmmaGroup groupToCreate = new EmmaGroup();
    groupToCreate.setName("Engineering");
    EmmaGroup createdGroup = EmmaApiCommands.createGroup("emmaDev", groupToCreate);
    assertNotNull(createdGroup);

    EmmaMember member1 = new EmmaMember();
    member1.setEmail("jsmith@test.edu");
    member1.setFirstName("John");
    member1.setLastName("Smith");
    EmmaMember createdMember1 = EmmaApiCommands.addMember("emmaDev", member1);

    EmmaMember member2 = new EmmaMember();
    member2.setEmail("jdoe@test.edu");
    member2.setFirstName("Jane");
    member2.setLastName("Doe");
    EmmaMember createdMember2 = EmmaApiCommands.addMember("emmaDev", member2);

    // empty group should return empty list
    List<EmmaMember> members = EmmaApiCommands.retrieveMembershipsByGroup("emmaDev", createdGroup.getId());
    assertEquals(0, members.size());

    // add both memberships
    EmmaApiCommands.addGroupMembership("emmaDev", createdGroup.getId(), createdMember1.getId());
    EmmaApiCommands.addGroupMembership("emmaDev", createdGroup.getId(), createdMember2.getId());

    members = EmmaApiCommands.retrieveMembershipsByGroup("emmaDev", createdGroup.getId());
    assertEquals(2, members.size());

    Map<Long, EmmaMember> memberById = new HashMap<Long, EmmaMember>();
    for (EmmaMember member : members) {
      memberById.put(member.getId(), member);
    }

    assertNotNull(memberById.get(createdMember1.getId()));
    assertNotNull(memberById.get(createdMember2.getId()));

    // remove one and verify the list shrinks
    EmmaApiCommands.removeGroupMembership("emmaDev", createdGroup.getId(), createdMember1.getId());

    members = EmmaApiCommands.retrieveMembershipsByGroup("emmaDev", createdGroup.getId());
    assertEquals(1, members.size());
    assertEquals(createdMember2.getId(), members.get(0).getId());
  }

  // ============================================================================
  // End-to-end provisioning tests (require a running tomcat with the mock service)
  // ============================================================================

  public void testUpdateGroupNameFull() {
    updateGroupName(true);
  }

  public void testUpdateGroupNameIncremental() {
    updateGroupName(false);
  }

  /**
   * Emma groups carry no description, so unlike the Freshservice suite (which edits
   * the group description) this exercises editing the group name through a rename of
   * the Grouper group extension.
   */
  public void updateGroupName(boolean isFull) {

    if (!tomcatRunTests()) {
      return;
    }

    EmmaProvisionerTestUtils.setupEmmaExternalSystem();

    EmmaProvisionerTestUtils.configureEmmaProvisioner(
        new EmmaProvisionerTestConfigInput()
            .assignConfigId("emmaProvisioner")
    );

    GrouperUtil.sleep(5000);

    GrouperStartup.startup();

    try {
      // this will create tables
      EmmaApiCommands.retrieveMembers("emmaDev");

      new GcDbAccess().connectionName("grouper").sql("delete from mock_emma_membership").executeSql();
      new GcDbAccess().connectionName("grouper").sql("delete from mock_emma_group").executeSql();
      new GcDbAccess().connectionName("grouper").sql("delete from mock_emma_member").executeSql();

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
      attributeValue.setDoProvision("emmaProvisioner");
      attributeValue.setTargetName("emmaProvisioner");
      attributeValue.setStemScopeString("sub");

      GrouperProvisioningService.saveOrUpdateProvisioningAttributes(attributeValue, stem);

      //
      // first provision: should create group named "testGroup"
      //
      if (isFull) {
        fullProvision();
      } else {
        incrementalProvision();
      }

      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_emma_group").select(int.class));

      String dbName = new GcDbAccess().connectionName("grouper")
          .sql("select group_name from mock_emma_group").select(String.class);
      assertEquals("testGroup", dbName);

      Long groupId = new GcDbAccess().connectionName("grouper")
          .sql("select id from mock_emma_group").select(Long.class);

      EmmaGroup retrievedGroup = EmmaApiCommands.retrieveGroup("emmaDev", groupId);
      assertNotNull(retrievedGroup);
      assertEquals("testGroup", retrievedGroup.getName());

      //
      // rename the group extension and provision again
      //
      new GroupSave(grouperSession).assignUuid(testGroup.getUuid())
          .assignName("test:testGroupRenamed")
          .assignSaveMode(SaveMode.INSERT_OR_UPDATE).save();

      if (isFull) {
        fullProvision();
      } else {
        incrementalProvision();
      }

      // still only one group; the name should now match the renamed extension
      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_emma_group").select(int.class));

      dbName = new GcDbAccess().connectionName("grouper")
          .sql("select group_name from mock_emma_group").select(String.class);
      assertEquals("testGroupRenamed", dbName);

      retrievedGroup = EmmaApiCommands.retrieveGroup("emmaDev", groupId);
      assertNotNull(retrievedGroup);
      assertEquals("testGroupRenamed", retrievedGroup.getName());

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

    EmmaProvisionerTestUtils.setupEmmaExternalSystem();

    EmmaProvisionerTestUtils.configureEmmaProvisioner(
        new EmmaProvisionerTestConfigInput()
            .assignConfigId("emmaProvisioner")
    );

    GrouperUtil.sleep(5000);

    GrouperStartup.startup();

    try {
      // this will create tables
      EmmaApiCommands.retrieveMembers("emmaDev");

      new GcDbAccess().connectionName("grouper").sql("delete from mock_emma_membership").executeSql();
      new GcDbAccess().connectionName("grouper").sql("delete from mock_emma_group").executeSql();
      new GcDbAccess().connectionName("grouper").sql("delete from mock_emma_member").executeSql();

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
      attributeValue.setDoProvision("emmaProvisioner");
      attributeValue.setTargetName("emmaProvisioner");
      attributeValue.setStemScopeString("sub");

      GrouperProvisioningService.saveOrUpdateProvisioningAttributes(attributeValue, stem);

      // assert mock tables are empty before sync
      assertEquals(new Integer(0), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_emma_group").select(int.class));
      assertEquals(new Integer(0), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_emma_member").select(int.class));
      assertEquals(new Integer(0), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_emma_membership").select(int.class));

      //
      // first provision: should provision group, 2 members, 2 memberships
      //
      if (isFull) {
        fullProvision();
      } else {
        incrementalProvision();
      }

      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_emma_group").select(int.class));
      assertEquals(new Integer(2), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_emma_member").select(int.class));
      assertEquals(new Integer(2), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_emma_membership").select(int.class));

      String groupName = new GcDbAccess().connectionName("grouper").sql("select group_name from mock_emma_group").select(String.class);
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

      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_emma_group").select(int.class));
      // deleteEntitiesIfNotExistInGrouper is on, so the dropped member is archived (deleted) from Emma
      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_emma_member").select(int.class));
      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_emma_membership").select(int.class));

      //
      // add a different member and provision again
      //
      testGroup.addMember(SubjectTestHelper.SUBJ2, false);

      if (isFull) {
        fullProvision();
      } else {
        incrementalProvision();
      }

      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_emma_group").select(int.class));
      assertEquals(new Integer(2), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_emma_member").select(int.class));
      assertEquals(new Integer(2), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_emma_membership").select(int.class));

      //
      // delete the group entirely and provision again
      //
      testGroup.delete();

      if (isFull) {
        fullProvision();
      } else {
        incrementalProvision();
      }

      assertEquals(new Integer(0), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_emma_group").select(int.class));
      assertEquals(new Integer(0), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_emma_membership").select(int.class));
      assertEquals(new Integer(0), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_emma_member").select(int.class));

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

    EmmaProvisionerTestUtils.setupEmmaExternalSystem();

    EmmaProvisionerTestUtils.configureEmmaProvisioner(
        new EmmaProvisionerTestConfigInput()
            .assignConfigId("emmaProvisioner")
    );

    GrouperUtil.sleep(5000);

    GrouperStartup.startup();

    try {
      // this will create tables
      EmmaApiCommands.retrieveMembers("emmaDev");

      new GcDbAccess().connectionName("grouper").sql("delete from mock_emma_membership").executeSql();
      new GcDbAccess().connectionName("grouper").sql("delete from mock_emma_group").executeSql();
      new GcDbAccess().connectionName("grouper").sql("delete from mock_emma_member").executeSql();

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
      attributeValue.setDoProvision("emmaProvisioner");
      attributeValue.setTargetName("emmaProvisioner");
      attributeValue.setStemScopeString("sub");

      GrouperProvisioningService.saveOrUpdateProvisioningAttributes(attributeValue, stem);

      //
      // first provision: group, 1 member, 1 membership
      //
      if (isFull) {
        fullProvision();
      } else {
        incrementalProvision();
      }

      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_emma_group").select(int.class));
      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_emma_member").select(int.class));
      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_emma_membership").select(int.class));

      Long memberId = new GcDbAccess().connectionName("grouper")
          .sql("select id from mock_emma_member").select(Long.class);
      Long groupId = new GcDbAccess().connectionName("grouper")
          .sql("select id from mock_emma_group").select(Long.class);

      EmmaMember retrievedMember = EmmaApiCommands.retrieveMemberById("emmaDev", memberId);
      assertNotNull(retrievedMember);

      List<EmmaMember> members = EmmaApiCommands.retrieveMembershipsByGroup("emmaDev", groupId);
      assertEquals(1, members.size());

      //
      // remove member and provision again - member archived, membership deleted
      //
      testGroup.deleteMember(SubjectTestHelper.SUBJ0);

      if (isFull) {
        fullProvision();
      } else {
        incrementalProvision();
      }

      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_emma_group").select(int.class));
      assertEquals(new Integer(0), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_emma_member").select(int.class));
      assertEquals(new Integer(0), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_emma_membership").select(int.class));

      // commands class: member gone, no memberships
      EmmaMember goneMember = EmmaApiCommands.retrieveMemberById("emmaDev", memberId);
      assertNull(goneMember);

      members = EmmaApiCommands.retrieveMembershipsByGroup("emmaDev", groupId);
      assertEquals(0, members.size());

      //
      // re-add the same member and provision again - member re-created, membership re-created
      //
      testGroup.addMember(SubjectTestHelper.SUBJ0, false);

      if (isFull) {
        fullProvision();
      } else {
        incrementalProvision();
      }

      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_emma_group").select(int.class));
      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_emma_member").select(int.class));
      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_emma_membership").select(int.class));

      Long newGroupId = new GcDbAccess().connectionName("grouper")
          .sql("select id from mock_emma_group").select(Long.class);
      members = EmmaApiCommands.retrieveMembershipsByGroup("emmaDev", newGroupId);
      assertEquals(1, members.size());

    } finally {

    }
  }

  public void testFullSyncEditFirstName() {

    if (!tomcatRunTests()) {
      return;
    }

    EmmaProvisionerTestUtils.setupEmmaExternalSystem();

    EmmaProvisionerTestUtils.configureEmmaProvisioner(
        new EmmaProvisionerTestConfigInput()
            .assignConfigId("emmaProvisioner")
            .addExtraConfig("numberOfEntityAttributes", "3")
            .addExtraConfig("targetEntityAttribute.2.name", "firstName")
            .addExtraConfig("targetEntityAttribute.2.translateExpressionType", "grouperProvisioningEntityField")
            .addExtraConfig("targetEntityAttribute.2.translateFromGrouperProvisioningEntityField", "subjectId")
    );

    GrouperUtil.sleep(5000);

    GrouperStartup.startup();

    try {
      // this will create tables
      EmmaApiCommands.retrieveMembers("emmaDev");

      new GcDbAccess().connectionName("grouper").sql("delete from mock_emma_membership").executeSql();
      new GcDbAccess().connectionName("grouper").sql("delete from mock_emma_group").executeSql();
      new GcDbAccess().connectionName("grouper").sql("delete from mock_emma_member").executeSql();

      GrouperSession grouperSession = GrouperSession.startRootSession();

      Stem stem = new StemSave(grouperSession).assignName("test").save();

      Group testGroup = new GroupSave(grouperSession).assignName("test:testGroup").save();

      testGroup.addMember(SubjectTestHelper.SUBJ0, false);

      final GrouperProvisioningAttributeValue attributeValue = new GrouperProvisioningAttributeValue();
      attributeValue.setDirectAssignment(true);
      attributeValue.setDoProvision("emmaProvisioner");
      attributeValue.setTargetName("emmaProvisioner");
      attributeValue.setStemScopeString("sub");

      GrouperProvisioningService.saveOrUpdateProvisioningAttributes(attributeValue, stem);

      //
      // first full sync: firstName should be subject id (test.subject.0)
      //
      fullProvision();

      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_emma_member").select(int.class));
      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_emma_membership").select(int.class));

      String dbFirstName = new GcDbAccess().connectionName("grouper")
          .sql("select first_name from mock_emma_member").select(String.class);
      assertEquals("test.subject.0", dbFirstName);

      Long memberId = new GcDbAccess().connectionName("grouper")
          .sql("select id from mock_emma_member").select(Long.class);

      EmmaMember retrievedMember = EmmaApiCommands.retrieveMemberById("emmaDev", memberId);
      assertNotNull(retrievedMember);
      assertEquals("test.subject.0", retrievedMember.getFirstName());

      //
      // change config to map firstName to subject name instead of subject id
      //
      new GrouperDbConfig().configFileName("grouper-loader.properties")
          .propertyName("provisioner.emmaProvisioner.targetEntityAttribute.2.translateFromGrouperProvisioningEntityField")
          .value("name").store();

      ConfigPropertiesCascadeBase.clearCache();

      GrouperUtil.sleep(7000);

      //
      // second full sync: firstName should now be subject name (my name is test.subject.0)
      //
      fullProvision();

      dbFirstName = new GcDbAccess().connectionName("grouper")
          .sql("select first_name from mock_emma_member").select(String.class);
      assertEquals("my name is test.subject.0", dbFirstName);

      retrievedMember = EmmaApiCommands.retrieveMemberById("emmaDev", memberId);
      assertNotNull(retrievedMember);
      assertEquals("my name is test.subject.0", retrievedMember.getFirstName());

    } finally {

    }
  }

  public void testFullSyncEditCustomFieldPennId() {

    if (!tomcatRunTests()) {
      return;
    }

    EmmaProvisionerTestUtils.setupEmmaExternalSystem();

    // user-defined Emma fields are provisioned as attributes named field_<fieldName>
    EmmaProvisionerTestUtils.configureEmmaProvisioner(
        new EmmaProvisionerTestConfigInput()
            .assignConfigId("emmaProvisioner")
            .addExtraConfig("numberOfEntityAttributes", "3")
            .addExtraConfig("targetEntityAttribute.2.name.elConfig", "${'field_pennId'}")
            .addExtraConfig("targetEntityAttribute.2.translateExpressionType", "grouperProvisioningEntityField")
            .addExtraConfig("targetEntityAttribute.2.translateFromGrouperProvisioningEntityField", "subjectId")
    );

    GrouperUtil.sleep(5000);

    GrouperStartup.startup();

    try {
      // this will create tables
      EmmaApiCommands.retrieveMembers("emmaDev");

      new GcDbAccess().connectionName("grouper").sql("delete from mock_emma_membership").executeSql();
      new GcDbAccess().connectionName("grouper").sql("delete from mock_emma_group").executeSql();
      new GcDbAccess().connectionName("grouper").sql("delete from mock_emma_member").executeSql();

      GrouperSession grouperSession = GrouperSession.startRootSession();

      Stem stem = new StemSave(grouperSession).assignName("test").save();

      Group testGroup = new GroupSave(grouperSession).assignName("test:testGroup").save();

      testGroup.addMember(SubjectTestHelper.SUBJ0, false);

      final GrouperProvisioningAttributeValue attributeValue = new GrouperProvisioningAttributeValue();
      attributeValue.setDirectAssignment(true);
      attributeValue.setDoProvision("emmaProvisioner");
      attributeValue.setTargetName("emmaProvisioner");
      attributeValue.setStemScopeString("sub");

      GrouperProvisioningService.saveOrUpdateProvisioningAttributes(attributeValue, stem);

      //
      // first full sync: field pennId should be subject id (test.subject.0)
      //
      fullProvision();

      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_emma_member").select(int.class));
      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_emma_membership").select(int.class));

      // the mock stores user-defined fields as JSON in the "fields" column
      String dbFields = new GcDbAccess().connectionName("grouper")
          .sql("select fields from mock_emma_member").select(String.class);
      assertNotNull(dbFields);
      assertTrue(dbFields.contains("pennId"));
      assertTrue(dbFields.contains("test.subject.0"));

      Long memberId = new GcDbAccess().connectionName("grouper")
          .sql("select id from mock_emma_member").select(Long.class);

      EmmaMember retrievedMember = EmmaApiCommands.retrieveMemberById("emmaDev", memberId);
      assertNotNull(retrievedMember);
      assertNotNull(retrievedMember.getCustomFields());
      assertEquals("test.subject.0", retrievedMember.getCustomFields().get("pennId"));

      //
      // change config to map field pennId to subject name instead of subject id
      //
      new GrouperDbConfig().configFileName("grouper-loader.properties")
          .propertyName("provisioner.emmaProvisioner.targetEntityAttribute.2.translateFromGrouperProvisioningEntityField")
          .value("name").store();

      ConfigPropertiesCascadeBase.clearCache();

      GrouperUtil.sleep(7000);

      //
      // second full sync: field pennId should now be subject name (my name is test.subject.0)
      //
      fullProvision();

      dbFields = new GcDbAccess().connectionName("grouper")
          .sql("select fields from mock_emma_member").select(String.class);
      assertNotNull(dbFields);
      assertTrue(dbFields.contains("pennId"));
      assertTrue(dbFields.contains("my name is test.subject.0"));

      retrievedMember = EmmaApiCommands.retrieveMemberById("emmaDev", memberId);
      assertNotNull(retrievedMember);
      assertNotNull(retrievedMember.getCustomFields());
      assertEquals("my name is test.subject.0", retrievedMember.getCustomFields().get("pennId"));

    } finally {

    }
  }

  public void testFullSyncMatchByCustomField() {
    matchByCustomFieldAddRemoveMembers(true);
  }

  public void testIncrementalSyncMatchByCustomField() {
    matchByCustomFieldAddRemoveMembers(false);
  }

  /**
   * Exercises matching an existing Emma member by email (the natural key Emma keys on)
   * during provisioning: a member that already exists in the target should be linked
   * and updated rather than duplicated.
   */
  public void matchByCustomFieldAddRemoveMembers(boolean isFull) {

    if (!tomcatRunTests()) {
      return;
    }

    EmmaProvisionerTestUtils.setupEmmaExternalSystem();

    EmmaProvisionerTestUtils.configureEmmaProvisioner(
        new EmmaProvisionerTestConfigInput()
            .assignConfigId("emmaProvisioner")
    );

    GrouperUtil.sleep(5000);

    GrouperStartup.startup();

    try {
      // this will create tables
      EmmaApiCommands.retrieveMembers("emmaDev");

      new GcDbAccess().connectionName("grouper").sql("delete from mock_emma_membership").executeSql();
      new GcDbAccess().connectionName("grouper").sql("delete from mock_emma_group").executeSql();
      new GcDbAccess().connectionName("grouper").sql("delete from mock_emma_member").executeSql();

      // pre-seed a member in the target that will match SUBJ0 by email
      EmmaMember preexisting = new EmmaMember();
      preexisting.setEmail("test.subject.0@example.com");
      preexisting.setFirstName("Preexisting");
      preexisting.setLastName("Member");
      EmmaMember seeded = EmmaApiCommands.addMember("emmaDev", preexisting);
      assertNotNull(seeded.getId());

      GrouperSession grouperSession = GrouperSession.startRootSession();

      Stem stem = new StemSave(grouperSession).assignName("test").save();

      Group testGroup = new GroupSave(grouperSession).assignName("test:testGroup").save();

      testGroup.addMember(SubjectTestHelper.SUBJ0, false);

      if (!isFull) {
        fullProvision();
        incrementalProvision();
      }

      final GrouperProvisioningAttributeValue attributeValue = new GrouperProvisioningAttributeValue();
      attributeValue.setDirectAssignment(true);
      attributeValue.setDoProvision("emmaProvisioner");
      attributeValue.setTargetName("emmaProvisioner");
      attributeValue.setStemScopeString("sub");

      GrouperProvisioningService.saveOrUpdateProvisioningAttributes(attributeValue, stem);

      //
      // provision: SUBJ0 (email test.subject.0@example.com) should match the seeded member,
      // so no duplicate is created - still exactly one member with that email
      //
      if (isFull) {
        fullProvision();
      } else {
        incrementalProvision();
      }

      int matchingCount = new GcDbAccess().connectionName("grouper")
          .sql("select count(1) from mock_emma_member where email = ?")
          .addBindVar("test.subject.0@example.com").select(int.class);
      assertEquals(1, matchingCount);

      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_emma_group").select(int.class));
      assertEquals(new Integer(1), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_emma_membership").select(int.class));

      // membership should reference the seeded member's id
      List<EmmaMember> members = EmmaApiCommands.retrieveMembershipsByGroup("emmaDev",
          new GcDbAccess().connectionName("grouper").sql("select id from mock_emma_group").select(Long.class));
      assertEquals(1, members.size());
      assertEquals(seeded.getId(), members.get(0).getId());

      //
      // remove the member and provision again - membership removed
      //
      testGroup.deleteMember(SubjectTestHelper.SUBJ0);

      if (isFull) {
        fullProvision();
      } else {
        incrementalProvision();
      }

      assertEquals(new Integer(0), new GcDbAccess().connectionName("grouper").sql("select count(1) from mock_emma_membership").select(int.class));

    } finally {

    }
  }

}

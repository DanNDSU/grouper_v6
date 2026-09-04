package edu.internet2.middleware.grouper.app.emma;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.internet2.middleware.grouper.Group;
import edu.internet2.middleware.grouper.GroupSave;
import edu.internet2.middleware.grouper.GrouperSession;
import edu.internet2.middleware.grouper.Stem;
import edu.internet2.middleware.grouper.StemSave;
import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioner;
import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioningAttributeValue;
import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioningBaseTest;
import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioningDiagnosticsContainer;
import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioningOutput;
import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioningService;
import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioningType;
import edu.internet2.middleware.grouper.app.provisioning.ProvisioningEntity;
import edu.internet2.middleware.grouper.app.provisioning.ProvisioningGroup;
import edu.internet2.middleware.grouper.app.provisioning.ProvisioningMembership;
import edu.internet2.middleware.grouper.cfg.dbConfig.GrouperDbConfig;
import edu.internet2.middleware.grouper.helper.SubjectTestHelper;
import edu.internet2.middleware.grouper.hibernate.HibernateSession;
import edu.internet2.middleware.grouper.misc.GrouperStartup;
import edu.internet2.middleware.grouper.misc.SaveMode;
import edu.internet2.middleware.grouper.util.CommandLineExec;
import edu.internet2.middleware.grouper.util.GrouperUtil;
import edu.internet2.middleware.grouperClient.config.ConfigPropertiesCascadeBase;
import edu.internet2.middleware.grouperClient.jdbc.GcDbAccess;
import edu.internet2.middleware.grouperClient.jdbc.tableSync.GcGrouperSync;
import edu.internet2.middleware.grouperClient.jdbc.tableSync.GcGrouperSyncDao;
import edu.internet2.middleware.grouperClient.jdbc.tableSync.GcGrouperSyncGroup;
import junit.textui.TestRunner;

/**
 * Tests for the Emma provisioner, running against EmmaMockServiceHandler.
 *
 * Modeled on GrouperTeamsChannelProvisionerTest / GrouperDuoProvisionerTest.
 *
 * Unlike the Teams/Channels provisioner, Emma manages its own audience members:
 * makeChangesToEntities is on, so a Grouper subject is created, updated, and
 * deleted in Emma as needed, rather than only being resolved against an
 * existing directory.
 */
public class GrouperEmmaProvisionerTest extends GrouperProvisioningBaseTest {

  public static void main(String[] args) {
    TestRunner.run(new GrouperEmmaProvisionerTest("testFullSyncEmma"));
  }

  public GrouperEmmaProvisionerTest(String name) {
    super(name);
  }

  public GrouperEmmaProvisionerTest() {
  }

  public static boolean startTomcat = false;

  @Override
  public void setUp() {
    super.setUp();

    EmmaMockServiceHandler.ensureEmmaMockTables();

    new GcDbAccess().connectionName("grouper").sql("delete from mock_emma_membership").executeSql();
    new GcDbAccess().connectionName("grouper").sql("delete from mock_emma_member").executeSql();
    new GcDbAccess().connectionName("grouper").sql("delete from mock_emma_group").executeSql();
  }

  @Override
  public String defaultConfigId() {
    return "myEmmaProvisioner";
  }

  // ==================================================================
  // helpers
  // ==================================================================

  private static List<EmmaGroup> groups() {
    return HibernateSession.byHqlStatic().createQuery("from EmmaGroup").list(EmmaGroup.class);
  }

  private static EmmaGroup onlyGroup() {
    List<EmmaGroup> groups = groups();
    assertEquals("expecting exactly one group", 1, groups.size());
    return groups.get(0);
  }

  private static List<EmmaMember> members() {
    return HibernateSession.byHqlStatic().createQuery("from EmmaMember").list(EmmaMember.class);
  }

  private static List<EmmaMembership> memberships() {
    return HibernateSession.byHqlStatic().createQuery("from EmmaMembership").list(EmmaMembership.class);
  }

  private static int membershipCount() {
    return memberships().size();
  }

  /**
   * mark a folder as provisionable by this provisioner
   * @param stem
   * @param metadataNameValues optional metadata, may be null
   */
  private void assignProvisioningAttribute(Stem stem, Map<String, Object> metadataNameValues) {

    GrouperProvisioningAttributeValue attributeValue = new GrouperProvisioningAttributeValue();
    attributeValue.setDirectAssignment(true);
    attributeValue.setDoProvision(defaultConfigId());
    attributeValue.setTargetName(defaultConfigId());
    attributeValue.setStemScopeString("sub");
    if (metadataNameValues != null) {
      attributeValue.setMetadataNameValues(metadataNameValues);
    }

    GrouperProvisioningService.saveOrUpdateProvisioningAttributes(attributeValue, stem);
  }

  private void validateNoErrors(GrouperProvisioningDiagnosticsContainer grouperProvisioningDiagnosticsContainer) {
    String[] lines = grouperProvisioningDiagnosticsContainer.getReportFinal().split("\n");
    List<String> errorLines = new ArrayList<String>();
    for (String line : lines) {
      if (line.contains("'red'") || line.contains("Error:")) {
        errorLines.add(line);
      }
    }
    if (errorLines.size() > 0) {
      fail("There are " + errorLines.size() + " errors in report: " + errorLines);
    }
  }

  // ==================================================================
  // pure unit tests - no mock service or tomcat needed
  // ==================================================================

  /**
   * an EmmaGroup round trips through the Emma json shape (member_group_id / group_name)
   */
  public void testEmmaGroupJsonRoundTrip() {

    EmmaGroup emmaGroup = new EmmaGroup();
    emmaGroup.setId(12345L);
    emmaGroup.setName("testGroup");

    String json = GrouperUtil.jsonJacksonToString(emmaGroup.toJson(null));
    assertTrue(json, json.contains("\"group_name\""));
    // toJson never emits the id: it is used for the create/update request body, and
    // Emma assigns the id itself
    assertFalse(json, json.contains("member_group_id"));

    EmmaGroup fromJson = EmmaGroup.fromJson(GrouperUtil.jsonJacksonNode(
        "{\"member_group_id\":12345,\"group_name\":\"testGroup\",\"group_type\":\"g\"}"));

    assertEquals(new Long(12345L), fromJson.getId());
    assertEquals("testGroup", fromJson.getName());

    assertNull(EmmaGroup.fromJson(null));
  }

  /**
   * a group translates to and from a target ProvisioningGroup
   */
  public void testEmmaGroupToAndFromProvisioningGroup() {

    EmmaGroup emmaGroup = new EmmaGroup();
    emmaGroup.setId(999L);
    emmaGroup.setName("testGroup");

    ProvisioningGroup provisioningGroup = emmaGroup.toProvisioningGroup();

    assertEquals("999", provisioningGroup.getId());
    assertEquals("testGroup", provisioningGroup.retrieveAttributeValueString("name"));

    EmmaGroup roundTripped = EmmaGroup.fromProvisioningGroup(provisioningGroup, null);

    assertEquals(emmaGroup.getId(), roundTripped.getId());
    assertEquals(emmaGroup.getName(), roundTripped.getName());

    // fieldNamesToSet limits which fields get pulled off the provisioning group
    EmmaGroup nameOnly = EmmaGroup.fromProvisioningGroup(provisioningGroup, GrouperUtil.toSet("name"));
    assertNull(nameOnly.getId());
    assertEquals("testGroup", nameOnly.getName());
  }

  /**
   * an EmmaMember round trips through the Emma json shape.  first_name / last_name
   * live inside the nested "fields" object, along with any user-defined fields.
   */
  public void testEmmaMemberJsonRoundTrip() {

    EmmaMember emmaMember = new EmmaMember();
    emmaMember.setEmail("test@example.com");
    emmaMember.setFirstName("first");
    emmaMember.setLastName("last");
    Map<String, Object> customFields = new HashMap<String, Object>();
    customFields.put("department", "libraries");
    customFields.put("isVip", Boolean.TRUE);
    customFields.put("employeeNumber", 42L);
    emmaMember.setCustomFields(customFields);

    ObjectNode json = emmaMember.toJson(null);
    String jsonString = GrouperUtil.jsonJacksonToString(json);
    assertTrue(jsonString, jsonString.contains("\"email\""));
    assertTrue(jsonString, jsonString.contains("\"first_name\""));
    assertTrue(jsonString, jsonString.contains("\"last_name\""));
    assertTrue(jsonString, jsonString.contains("\"department\""));

    EmmaMember fromJson = EmmaMember.fromJson(GrouperUtil.jsonJacksonNode(
        "{\"member_id\":555,\"email\":\"test@example.com\",\"member_status_id\":\"a\","
            + "\"fields\":{\"first_name\":\"first\",\"last_name\":\"last\",\"department\":\"libraries\"}}"));

    assertEquals(new Long(555L), fromJson.getId());
    assertEquals("test@example.com", fromJson.getEmail());
    assertEquals("first", fromJson.getFirstName());
    assertEquals("last", fromJson.getLastName());
    assertEquals("a", fromJson.getMemberStatusId());
    assertEquals("libraries", fromJson.getCustomFields().get("department"));
    // first_name / last_name are modeled directly, not duplicated into customFields
    assertFalse(fromJson.getCustomFields().containsKey("first_name"));

    assertNull(EmmaMember.fromJson(null));
  }

  /**
   * a member translates to and from a target ProvisioningEntity; user-defined
   * fields become attributes named field_&lt;name&gt;
   */
  public void testEmmaMemberToAndFromProvisioningEntity() {

    EmmaMember emmaMember = new EmmaMember();
    emmaMember.setId(555L);
    emmaMember.setEmail("test@example.com");
    emmaMember.setFirstName("first");
    emmaMember.setLastName("last");
    emmaMember.setMemberStatusId("a");
    Map<String, Object> customFields = new HashMap<String, Object>();
    customFields.put("department", "libraries");
    emmaMember.setCustomFields(customFields);

    ProvisioningEntity provisioningEntity = emmaMember.toProvisioningEntity();

    assertEquals("test@example.com", provisioningEntity.retrieveAttributeValueString("email"));
    assertEquals("first", provisioningEntity.retrieveAttributeValueString("firstName"));
    assertEquals("last", provisioningEntity.retrieveAttributeValueString("lastName"));
    assertEquals("a", provisioningEntity.retrieveAttributeValueString("memberStatusId"));
    assertEquals("libraries", provisioningEntity.retrieveAttributeValueString(
        EmmaMember.CUSTOM_FIELD_ATTRIBUTE_PREFIX + "department"));

    EmmaMember roundTripped = EmmaMember.fromProvisioningEntity(provisioningEntity, null);

    assertEquals(emmaMember.getId(), roundTripped.getId());
    assertEquals(emmaMember.getEmail(), roundTripped.getEmail());
    assertEquals(emmaMember.getFirstName(), roundTripped.getFirstName());
    assertEquals(emmaMember.getLastName(), roundTripped.getLastName());
    assertEquals("libraries", roundTripped.getCustomFields().get("department"));
  }

  /**
   * EmmaMember.customFields only accepts String, Long, or Boolean - a decimal
   * number must be rejected rather than silently truncated
   */
  public void testEmmaMemberCustomFieldsRejectsDecimalValues() {

    EmmaMember emmaMember = new EmmaMember();
    Map<String, Object> customFields = new HashMap<String, Object>();
    customFields.put("gpa", 3.5d);

    try {
      emmaMember.setCustomFields(customFields);
      fail("expected an exception for a decimal customField value");
    } catch (RuntimeException re) {
      assertTrue(re.getMessage(), re.getMessage().contains("gpa"));
    }
  }

  /**
   * an EmmaMembership converts to a target ProvisioningMembership
   */
  public void testEmmaMembershipToProvisioningMembership() {

    EmmaMembership emmaMembership = new EmmaMembership();
    emmaMembership.setId(1L);
    emmaMembership.setGroupId(100L);
    emmaMembership.setUserId(200L);

    ProvisioningMembership provisioningMembership = emmaMembership.toProvisioningMembership();

    assertEquals("1", provisioningMembership.getId());
    assertEquals("100", provisioningMembership.getProvisioningGroupId());
    assertEquals("200", provisioningMembership.getProvisioningEntityId());
  }

  // ==================================================================
  // DAO / API level CRUD - needs tomcat serving the mock
  // ==================================================================

  /**
   * full CRUD lifecycle for an Emma group, straight against EmmaApiCommands
   */
  public void testEmmaGroupCrud() {
    if (!tomcatRunTests()) {
      return;
    }

    GrouperStartup.startup();

    if (startTomcat) {
      CommandLineExec commandLineExec = tomcatStart();
    }

    EmmaProvisionerTestUtils.setupEmmaExternalSystem("emmaDev");

    EmmaGroup toCreate = new EmmaGroup();
    toCreate.setName("crudTestGroup");

    EmmaGroup created = EmmaApiCommands.createGroup("emmaDev", toCreate);
    assertNotNull(created.getId());
    assertEquals("crudTestGroup", created.getName());

    EmmaGroup retrieved = EmmaApiCommands.retrieveGroup("emmaDev", created.getId());
    assertEquals("crudTestGroup", retrieved.getName());

    EmmaGroup toUpdate = new EmmaGroup();
    toUpdate.setId(created.getId());
    toUpdate.setName("crudTestGroupRenamed");
    EmmaApiCommands.updateGroup("emmaDev", toUpdate);

    retrieved = EmmaApiCommands.retrieveGroup("emmaDev", created.getId());
    assertEquals("crudTestGroupRenamed", retrieved.getName());

    List<EmmaGroup> allGroups = EmmaApiCommands.retrieveGroups("emmaDev");
    assertEquals(1, allGroups.size());

    EmmaApiCommands.deleteGroup("emmaDev", created.getId());

    assertNull(EmmaApiCommands.retrieveGroup("emmaDev", created.getId()));
    assertEquals(0, EmmaApiCommands.retrieveGroups("emmaDev").size());
  }

  /**
   * full CRUD lifecycle for an Emma member, straight against EmmaApiCommands,
   * plus a pagination check against a configured small page size
   */
  public void testEmmaMemberCrud() {
    if (!tomcatRunTests()) {
      return;
    }

    GrouperStartup.startup();

    if (startTomcat) {
      CommandLineExec commandLineExec = tomcatStart();
    }

    EmmaProvisionerTestUtils.setupEmmaExternalSystem("emmaDev");

    EmmaMember toAdd = new EmmaMember();
    toAdd.setEmail("crud@example.com");
    toAdd.setFirstName("first");
    toAdd.setLastName("last");

    EmmaMember added = EmmaApiCommands.addMember("emmaDev", toAdd);
    assertNotNull(added.getId());

    // addMember upserts on email: adding the same email again updates, not duplicates
    EmmaMember upsert = new EmmaMember();
    upsert.setEmail("crud@example.com");
    upsert.setFirstName("firstUpdated");
    upsert.setLastName("last");
    EmmaMember upserted = EmmaApiCommands.addMember("emmaDev", upsert);
    assertEquals(added.getId(), upserted.getId());

    EmmaMember retrievedById = EmmaApiCommands.retrieveMemberById("emmaDev", added.getId());
    assertEquals("firstUpdated", retrievedById.getFirstName());

    EmmaMember retrievedByEmail = EmmaApiCommands.retrieveMemberByEmail("emmaDev", "crud@example.com");
    assertEquals(added.getId(), retrievedByEmail.getId());

    Set<String> fieldsToUpdate = GrouperUtil.toSet("lastName");
    EmmaMember toUpdate = new EmmaMember();
    toUpdate.setId(added.getId());
    toUpdate.setLastName("lastUpdated");
    EmmaApiCommands.updateMember("emmaDev", toUpdate, fieldsToUpdate);

    retrievedById = EmmaApiCommands.retrieveMemberById("emmaDev", added.getId());
    assertEquals("lastUpdated", retrievedById.getLastName());

    EmmaApiCommands.deleteMember("emmaDev", added.getId());
    assertNull(EmmaApiCommands.retrieveMemberById("emmaDev", added.getId()));
    assertNull(EmmaApiCommands.retrieveMemberByEmail("emmaDev", "crud@example.com"));

    // pagination: force a small page size and confirm all members still come back
    new GrouperDbConfig().configFileName("grouper-loader.properties")
        .propertyName("grouper.wsBearerToken.emmaDev.pageSize").value("10").store();
    ConfigPropertiesCascadeBase.clearCache();

    for (int i = 0; i < 25; i++) {
      EmmaMember member = new EmmaMember();
      member.setEmail("paged" + i + "@example.com");
      EmmaApiCommands.addMember("emmaDev", member);
    }

    List<EmmaMember> allMembers = EmmaApiCommands.retrieveMembers("emmaDev");
    assertEquals(25, allMembers.size());
  }

  /**
   * add/remove group membership and retrieve members of a group
   */
  public void testEmmaGroupMembershipCrud() {
    if (!tomcatRunTests()) {
      return;
    }

    GrouperStartup.startup();

    if (startTomcat) {
      CommandLineExec commandLineExec = tomcatStart();
    }

    EmmaProvisionerTestUtils.setupEmmaExternalSystem("emmaDev");

    EmmaGroup group = EmmaApiCommands.createGroup("emmaDev", groupNamed("membershipTestGroup"));
    EmmaMember member1 = EmmaApiCommands.addMember("emmaDev", memberWithEmail("m1@example.com"));
    EmmaMember member2 = EmmaApiCommands.addMember("emmaDev", memberWithEmail("m2@example.com"));

    EmmaApiCommands.addGroupMembership("emmaDev", group.getId(), member1.getId());
    EmmaApiCommands.addGroupMembership("emmaDev", group.getId(), member2.getId());

    List<EmmaMember> groupMembers = EmmaApiCommands.retrieveMembershipsByGroup("emmaDev", group.getId());
    assertEquals(2, groupMembers.size());

    EmmaApiCommands.removeGroupMembership("emmaDev", group.getId(), member1.getId());

    groupMembers = EmmaApiCommands.retrieveMembershipsByGroup("emmaDev", group.getId());
    assertEquals(1, groupMembers.size());
    assertEquals(member2.getId(), groupMembers.get(0).getId());
  }

  private static EmmaGroup groupNamed(String name) {
    EmmaGroup emmaGroup = new EmmaGroup();
    emmaGroup.setName(name);
    return emmaGroup;
  }

  private static EmmaMember memberWithEmail(String email) {
    EmmaMember emmaMember = new EmmaMember();
    emmaMember.setEmail(email);
    return emmaMember;
  }

  // ==================================================================
  // full sync
  // ==================================================================

  /**
   * a provisionable group becomes an Emma group, and its members become Emma
   * members and group memberships; adding and removing members keeps Emma in sync
   */
  public void testFullSyncEmma() {
    if (!tomcatRunTests()) {
      return;
    }

    GrouperStartup.startup();

    if (startTomcat) {
      tomcatStart();
    }

    EmmaProvisionerTestUtils.configureEmmaProvisioner(new EmmaProvisionerTestConfigInput());

    GrouperSession grouperSession = GrouperSession.startRootSession();

    Stem stem = new StemSave(grouperSession).assignName("test").save();

    Group testGroup = new GroupSave(grouperSession).assignName("test:testGroup")
        .assignDescription("test description").save();

    testGroup.addMember(SubjectTestHelper.SUBJ0, false);
    testGroup.addMember(SubjectTestHelper.SUBJ1, false);

    assignProvisioningAttribute(stem, null);

    assertEquals(0, groups().size());

    GrouperProvisioningOutput grouperProvisioningOutput = fullProvision();

    assertTrue(1 <= grouperProvisioningOutput.getInsert());

    EmmaGroup emmaGroup = onlyGroup();
    assertEquals("testGroup", emmaGroup.getName());
    assertNotNull(emmaGroup.getId());

    assertEquals(2, membershipCount());

    // the target id is cached back onto the sync record
    GcGrouperSync gcGrouperSync = GcGrouperSyncDao.retrieveByProvisionerName(null, defaultConfigId());
    assertEquals(1, gcGrouperSync.getGroupCount().intValue());

    GcGrouperSyncGroup gcGrouperSyncGroup = gcGrouperSync.getGcGrouperSyncGroupDao()
        .groupRetrieveByGroupId(testGroup.getId());
    assertEquals(testGroup.getId(), gcGrouperSyncGroup.getGroupId());
    assertEquals(Long.toString(emmaGroup.getId()), gcGrouperSyncGroup.getGroupAttributeValueCache0());

    // remove a member
    testGroup.deleteMember(SubjectTestHelper.SUBJ1);

    fullProvision();

    assertEquals(1, groups().size());
    assertEquals(1, membershipCount());

    // add a different member
    testGroup.addMember(SubjectTestHelper.SUBJ3);

    fullProvision();

    assertEquals(1, groups().size());
    assertEquals(2, membershipCount());

    // a second full sync with nothing changed should not churn
    GrouperProvisioningOutput steadyState = fullProvision();
    assertEquals(0, steadyState.getInsert());
    assertEquals(0, steadyState.getDelete());
    assertEquals(1, groups().size());
    assertEquals(2, membershipCount());
  }

  /**
   * renaming the Grouper group patches the Emma group in place rather than
   * recreating it
   */
  public void testFullSyncUpdateGroupName() {
    if (!tomcatRunTests()) {
      return;
    }

    GrouperStartup.startup();

    if (startTomcat) {
      tomcatStart();
    }

    EmmaProvisionerTestUtils.configureEmmaProvisioner(new EmmaProvisionerTestConfigInput());

    GrouperSession grouperSession = GrouperSession.startRootSession();

    Stem stem = new StemSave(grouperSession).assignName("test").save();
    Group testGroup = new GroupSave(grouperSession).assignName("test:testGroup").assignDisplayExtension("testGroup").save();

    assignProvisioningAttribute(stem, null);

    fullProvision();

    EmmaGroup emmaGroup = onlyGroup();
    assertEquals("testGroup", emmaGroup.getName());
    Long originalId = emmaGroup.getId();

    new GroupSave(grouperSession).assignUuid(testGroup.getUuid())
        .assignName("test:testGroupRenamed").assignDisplayExtension("testGroupRenamed")
        .assignSaveMode(SaveMode.INSERT_OR_UPDATE).save();

    fullProvision();

    emmaGroup = onlyGroup();
    assertEquals(originalId, emmaGroup.getId());
    assertEquals("testGroupRenamed", emmaGroup.getName());
  }

  /**
   * deleting the Grouper group deletes the Emma group and its memberships
   */
  public void testFullSyncDeleteGroupDeletesEmmaGroup() {
    if (!tomcatRunTests()) {
      return;
    }

    GrouperStartup.startup();

    if (startTomcat) {
      tomcatStart();
    }

    EmmaProvisionerTestUtils.configureEmmaProvisioner(new EmmaProvisionerTestConfigInput());

    GrouperSession grouperSession = GrouperSession.startRootSession();

    Stem stem = new StemSave(grouperSession).assignName("test").save();
    Group testGroup = new GroupSave(grouperSession).assignName("test:testGroup").save();
    testGroup.addMember(SubjectTestHelper.SUBJ0, false);

    assignProvisioningAttribute(stem, null);

    fullProvision();

    assertEquals(1, groups().size());
    assertEquals(1, membershipCount());

    testGroup.delete();

    fullProvision();

    assertEquals(0, groups().size());
    assertEquals(0, membershipCount());
  }

  // ==================================================================
  // incremental sync
  // ==================================================================

  /**
   * the same lifecycle as testFullSyncEmma, driven off the change log
   */
  public void testIncrementalSyncEmma() {

    if (!tomcatRunTests()) {
      return;
    }

    GrouperStartup.startup();

    if (startTomcat) {
      tomcatStart();
    }

    EmmaProvisionerTestUtils.configureEmmaProvisioner(new EmmaProvisionerTestConfigInput());

    GrouperSession grouperSession = GrouperSession.startRootSession();

    // drain anything already queued so the assertions below are about our changes
    fullProvision();
    incrementalProvision();

    Stem stem = new StemSave(grouperSession).assignName("test").save();
    Group testGroup = new GroupSave(grouperSession).assignName("test:testGroup").save();

    testGroup.addMember(SubjectTestHelper.SUBJ0, false);
    testGroup.addMember(SubjectTestHelper.SUBJ1, false);

    assignProvisioningAttribute(stem, null);

    assertEquals(0, groups().size());

    incrementalProvision();

    assertEquals(1, groups().size());
    assertEquals(2, membershipCount());

    testGroup.deleteMember(SubjectTestHelper.SUBJ1);
    incrementalProvision();

    assertEquals(1, groups().size());
    assertEquals(1, membershipCount());

    testGroup.addMember(SubjectTestHelper.SUBJ3);
    incrementalProvision();

    assertEquals(1, groups().size());
    assertEquals(2, membershipCount());
  }

  // ==================================================================
  // diagnostics
  // ==================================================================

  /**
   * the diagnostics run for this provisioner reports no errors
   */
  public void testDiagnostics() {

    if (!tomcatRunTests()) {
      return;
    }

    GrouperStartup.startup();

    if (startTomcat) {
      tomcatStart();
    }

    EmmaProvisionerTestUtils.configureEmmaProvisioner(new EmmaProvisionerTestConfigInput());

    GrouperSession grouperSession = GrouperSession.startRootSession();

    Stem stem = new StemSave(grouperSession).assignName("test").save();
    Group testGroup = new GroupSave(grouperSession).assignName("test:testGroup").save();
    testGroup.addMember(SubjectTestHelper.SUBJ0, false);

    assignProvisioningAttribute(stem, null);

    fullProvision();

    assertEquals(1, groups().size());

    GrouperProvisioner provisioner = GrouperProvisioner.retrieveProvisioner(defaultConfigId());
    provisioner.initialize(GrouperProvisioningType.diagnostics);

    GrouperProvisioningDiagnosticsContainer diagnosticsContainer =
        provisioner.retrieveGrouperProvisioningDiagnosticsContainer();

    diagnosticsContainer.getGrouperProvisioningDiagnosticsSettings().setDiagnosticsGroupName("test:testGroup");
    diagnosticsContainer.getGrouperProvisioningDiagnosticsSettings()
        .setDiagnosticsSubjectIdOrIdentifier(SubjectTestHelper.SUBJ0_ID);
    diagnosticsContainer.getGrouperProvisioningDiagnosticsSettings().setDiagnosticsGroupsAllSelect(true);

    GrouperProvisioningOutput grouperProvisioningOutput = provisioner.provision(GrouperProvisioningType.diagnostics);

    assertEquals(0, grouperProvisioningOutput.getRecordsWithErrors());
    validateNoErrors(diagnosticsContainer);
  }

}

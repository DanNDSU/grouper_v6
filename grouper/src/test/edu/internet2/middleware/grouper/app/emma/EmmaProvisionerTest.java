package edu.internet2.middleware.grouper.app.emma;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
import edu.internet2.middleware.grouper.helper.SubjectTestHelper;
import edu.internet2.middleware.grouper.hibernate.HibernateSession;
import edu.internet2.middleware.grouper.misc.GrouperStartup;
import edu.internet2.middleware.grouper.misc.SaveMode;
import edu.internet2.middleware.grouper.util.GrouperUtil;
import edu.internet2.middleware.grouperClient.jdbc.GcDbAccess;
import edu.internet2.middleware.grouperClient.jdbc.tableSync.GcGrouperSync;
import edu.internet2.middleware.grouperClient.jdbc.tableSync.GcGrouperSyncDao;
import edu.internet2.middleware.grouperClient.jdbc.tableSync.GcGrouperSyncGroup;
import junit.textui.TestRunner;

/**
 * Tests for the Emma (e2ma) provisioner, running against EmmaMockServiceHandler.
 *
 * Modeled on GrouperTeamsChannelProvisionerTest / GrouperAzureProvisionerTest.
 *
 * Unlike the Teams channel provisioner, Emma manages its own members: the
 * /members/add endpoint upserts a member keyed on email and returns the
 * member_id, so the provisioner inserts, updates and deletes members as well as
 * groups and memberships.  There is therefore no external directory to seed - a
 * subject that ends up in an Emma group first becomes an Emma member.
 */
public class EmmaProvisionerTest extends GrouperProvisioningBaseTest {

  public static void main(String[] args) {
    TestRunner.run(new EmmaProvisionerTest("testFullSyncEmma"));
  }

  public EmmaProvisionerTest(String name) {
    super(name);
  }

  public EmmaProvisionerTest() {
  }

  public static boolean startTomcat = false;

  @Override
  public void setUp() {
    super.setUp();

    // create the mock tables if they are not already there
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

  private static List<EmmaGroup> emmaGroups() {
    return HibernateSession.byHqlStatic().createQuery("from EmmaGroup").list(EmmaGroup.class);
  }

  private static EmmaGroup onlyEmmaGroup() {
    List<EmmaGroup> groups = emmaGroups();
    assertEquals("expecting exactly one Emma group", 1, groups.size());
    return groups.get(0);
  }

  private static List<EmmaMember> emmaMembers() {
    return HibernateSession.byHqlStatic().createQuery("from EmmaMember").list(EmmaMember.class);
  }

  private static List<EmmaMembership> emmaMemberships() {
    return HibernateSession.byHqlStatic().createQuery("from EmmaMembership").list(EmmaMembership.class);
  }

  private static int emmaMembershipCount() {
    return emmaMemberships().size();
  }

  private static EmmaMember emmaMemberByEmail(String email) {
    List<EmmaMember> members = HibernateSession.byHqlStatic()
        .createQuery("from EmmaMember where email = :theEmail")
        .setString("theEmail", email).list(EmmaMember.class);
    return GrouperUtil.length(members) == 0 ? null : members.get(0);
  }

  /**
   * the email an Emma member is keyed on for a given test subject.  The email
   * entity attribute is translated from the subject's email attribute; the jdbc
   * test subjects expose one as "id@some.address" - adjust here if the source
   * mapping differs in your environment.
   */
  private static String emailForSubject(String subjectId) {
    return subjectId + "@example.edu";
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
   * a group round trips through the Emma json shape.  The request-side
   * serializer only ever emits group_name (the id is assigned by Emma), while a
   * response carries member_group_id + group_name.
   */
  public void testGroupJsonRoundTrip() {

    EmmaGroup group = new EmmaGroup();
    group.setId(4242L);
    group.setName("test:testGroup");

    // toJson is the request body: only group_name, never the id
    String json = GrouperUtil.jsonJacksonToString(group.toJson(null));
    assertTrue(json, json.contains("\"group_name\""));
    assertFalse(json, json.contains("member_group_id"));
    assertFalse(json, json.contains("\"id\""));

    // a response from Emma carries member_group_id
    EmmaGroup fromJson = EmmaGroup.fromJson(GrouperUtil.jsonJacksonNode(
        "{\"member_group_id\":123,\"group_name\":\"test:testGroup\",\"group_type\":\"g\"}"));
    assertEquals(new Long(123L), fromJson.getId());
    assertEquals("test:testGroup", fromJson.getName());

    // null node means no group
    assertNull(EmmaGroup.fromJson(null));
  }

  /**
   * a group translates to and from a target ProvisioningGroup, carrying name as
   * an attribute
   */
  public void testGroupToAndFromProvisioningGroup() {

    EmmaGroup group = new EmmaGroup();
    group.setId(99L);
    group.setName("some:group:name");

    ProvisioningGroup provisioningGroup = group.toProvisioningGroup();
    assertEquals("99", provisioningGroup.getId());
    assertEquals("some:group:name", provisioningGroup.retrieveAttributeValueString("name"));

    EmmaGroup roundTripped = EmmaGroup.fromProvisioningGroup(provisioningGroup, null);
    assertEquals(group.getId(), roundTripped.getId());
    assertEquals(group.getName(), roundTripped.getName());

    // a provisioning group with no id yields a null-id EmmaGroup (pre-insert)
    ProvisioningGroup noId = new ProvisioningGroup();
    noId.assignAttributeValue("name", "brandNew");
    EmmaGroup preInsert = EmmaGroup.fromProvisioningGroup(noId, null);
    assertNull(preInsert.getId());
    assertEquals("brandNew", preInsert.getName());
  }

  /**
   * a member round trips through the Emma json shape.  first_name / last_name and
   * any user-defined values live inside the nested "fields" object; the request
   * body is { email, fields: { ... } }.
   */
  public void testMemberJsonRoundTrip() {

    EmmaMember member = new EmmaMember();
    member.setId(7L);
    member.setEmail("jdoe@example.edu");
    member.setFirstName("Jane");
    member.setLastName("Doe");

    ObjectNode toJson = member.toJson(null);
    assertEquals("jdoe@example.edu", GrouperUtil.jsonJacksonGetString(toJson, "email"));
    // id is never sent in the body
    assertFalse(GrouperUtil.jsonJacksonToString(toJson), GrouperUtil.jsonJacksonToString(toJson).contains("member_id"));
    // first/last name are nested under fields
    assertEquals("Jane", GrouperUtil.jsonJacksonGetString(
        GrouperUtil.jsonJacksonGetNode(toJson, "fields"), "first_name"));
    assertEquals("Doe", GrouperUtil.jsonJacksonGetString(
        GrouperUtil.jsonJacksonGetNode(toJson, "fields"), "last_name"));

    // a response from Emma carries member_id, email, member_status_id and the fields object
    EmmaMember fromJson = EmmaMember.fromJson(GrouperUtil.jsonJacksonNode(
        "{\"member_id\":7,\"email\":\"jdoe@example.edu\",\"member_status_id\":\"a\","
            + "\"fields\":{\"first_name\":\"Jane\",\"last_name\":\"Doe\"}}"));
    assertEquals(new Long(7L), fromJson.getId());
    assertEquals("jdoe@example.edu", fromJson.getEmail());
    assertEquals("a", fromJson.getMemberStatusId());
    assertEquals("Jane", fromJson.getFirstName());
    assertEquals("Doe", fromJson.getLastName());

    assertNull(EmmaMember.fromJson(null));
  }

  /**
   * user-defined values other than first_name / last_name surface as
   * provisioning attributes named field_&lt;name&gt;, and round trip back into
   * the Emma "fields" object.  first_name / last_name are modeled directly and
   * must not be duplicated into customFields.
   */
  public void testMemberCustomFieldsRoundTrip() {

    EmmaMember fromJson = EmmaMember.fromJson(GrouperUtil.jsonJacksonNode(
        "{\"member_id\":8,\"email\":\"x@example.edu\","
            + "\"fields\":{\"first_name\":\"X\",\"last_name\":\"Y\",\"department\":\"IT\",\"employeeNumber\":12345,\"optIn\":true}}"));

    // first/last name are modeled fields, not custom fields
    assertFalse(fromJson.getCustomFields().containsKey("first_name"));
    assertFalse(fromJson.getCustomFields().containsKey("last_name"));

    // the rest are carried through, with numbers normalized to Long
    assertEquals("IT", fromJson.getCustomFields().get("department"));
    assertEquals(12345L, fromJson.getCustomFields().get("employeeNumber"));
    assertEquals(Boolean.TRUE, fromJson.getCustomFields().get("optIn"));

    // on a provisioning entity these appear prefixed with field_
    ProvisioningEntity provisioningEntity = fromJson.toProvisioningEntity();
    assertEquals("IT",
        provisioningEntity.retrieveAttributeValueString(EmmaMember.CUSTOM_FIELD_ATTRIBUTE_PREFIX + "department"));
    assertEquals(12345L,
        provisioningEntity.retrieveAttributeValue(EmmaMember.CUSTOM_FIELD_ATTRIBUTE_PREFIX + "employeeNumber"));

    // and back out to a member, they go into the request-body fields object
    Set<String> fieldNames = new HashSet<String>();
    fieldNames.add("email");
    fieldNames.add(EmmaMember.CUSTOM_FIELD_ATTRIBUTE_PREFIX + "department");
    EmmaMember backOut = EmmaMember.fromProvisioningEntity(provisioningEntity, fieldNames);
    ObjectNode body = backOut.toJson(fieldNames);
    assertEquals("IT", GrouperUtil.jsonJacksonGetString(
        GrouperUtil.jsonJacksonGetNode(body, "fields"), "department"));
  }

  /**
   * customFields values are constrained to String / Long / Boolean; a decimal or
   * any other type is rejected rather than silently mangled
   */
  public void testMemberCustomFieldsRejectDecimal() {

    EmmaMember member = new EmmaMember();
    Map<String, Object> bad = new HashMap<String, Object>();
    bad.put("score", 3.14d);

    try {
      member.setCustomFields(bad);
      fail("expecting a RuntimeException for a decimal custom field value");
    } catch (RuntimeException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("score"));
    }
  }

  /**
   * a membership translates to a target ProvisioningMembership keyed by group id
   * and member (user) id
   */
  public void testMembershipToProvisioningMembership() {

    EmmaMembership membership = new EmmaMembership();
    membership.setId(1L);
    membership.setGroupId(500L);
    membership.setUserId(900L);

    ProvisioningMembership provisioningMembership = membership.toProvisioningMembership();
    assertEquals("500", provisioningMembership.getProvisioningGroupId());
    assertEquals("900", provisioningMembership.getProvisioningEntityId());
    assertEquals("1", provisioningMembership.getId());
  }

  // ==================================================================
  // full sync
  // ==================================================================

  /**
   * a provisionable group becomes an Emma group, its members become Emma members
   * and group memberships; adding and removing members keeps Emma in sync
   */
  public void testFullSyncEmma() {

    GrouperStartup.startup();

    if (startTomcat) {
      tomcatStart();
    }

    EmmaProvisionerTestUtils.configureEmmaProvisioner(new EmmaProvisionerTestConfigInput());

    GrouperSession grouperSession = GrouperSession.startRootSession();

    Stem stem = new StemSave(grouperSession).assignName("test").save();

    Group testGroup = new GroupSave(grouperSession).assignName("test:testGroup").save();

    testGroup.addMember(SubjectTestHelper.SUBJ0, false);
    testGroup.addMember(SubjectTestHelper.SUBJ1, false);

    assignProvisioningAttribute(stem, null);

    assertEquals(new Integer(0), new GcDbAccess().connectionName("grouper")
        .sql("select count(1) from mock_emma_group").select(int.class));
    assertEquals(0, emmaGroups().size());

    GrouperProvisioningOutput grouperProvisioningOutput = fullProvision();

    assertTrue(1 <= grouperProvisioningOutput.getInsert());

    EmmaGroup group = onlyEmmaGroup();
    assertEquals("test:testGroup", group.getName());
    assertNotNull(group.getId());

    // both subjects became Emma members and members of the group
    assertEquals(2, emmaMembers().size());
    assertEquals(2, emmaMembershipCount());

    for (EmmaMembership membership : emmaMemberships()) {
      assertEquals(group.getId().longValue(), membership.getGroupId());
    }

    // the group id is cached back onto the sync record
    GcGrouperSync gcGrouperSync = GcGrouperSyncDao.retrieveByProvisionerName(null, defaultConfigId());
    assertEquals(1, gcGrouperSync.getGroupCount().intValue());

    GcGrouperSyncGroup gcGrouperSyncGroup = gcGrouperSync.getGcGrouperSyncGroupDao()
        .groupRetrieveByGroupId(testGroup.getId());
    assertEquals(testGroup.getId(), gcGrouperSyncGroup.getGroupId());
    assertEquals(Long.toString(group.getId()), gcGrouperSyncGroup.getGroupAttributeValueCache0());

    // remove a member
    testGroup.deleteMember(SubjectTestHelper.SUBJ1);
    fullProvision();

    assertEquals(1, emmaGroups().size());
    assertEquals(1, emmaMembershipCount());

    // add a different member
    testGroup.addMember(SubjectTestHelper.SUBJ2);
    fullProvision();

    assertEquals(1, emmaGroups().size());
    assertEquals(2, emmaMembershipCount());

    // a second full sync with nothing changed should not churn
    GrouperProvisioningOutput steadyState = fullProvision();
    assertEquals(0, steadyState.getInsert());
    assertEquals(0, steadyState.getDelete());
    assertEquals(1, emmaGroups().size());
    assertEquals(2, emmaMembershipCount());
  }

  /**
   * renaming the Grouper group patches the Emma group_name in place rather than
   * recreating it
   */
  public void testFullSyncUpdateGroupName() {

    GrouperStartup.startup();

    if (startTomcat) {
      tomcatStart();
    }

    EmmaProvisionerTestUtils.configureEmmaProvisioner(new EmmaProvisionerTestConfigInput());

    GrouperSession grouperSession = GrouperSession.startRootSession();

    Stem stem = new StemSave(grouperSession).assignName("test").save();
    Group testGroup = new GroupSave(grouperSession).assignName("test:testGroup").save();

    assignProvisioningAttribute(stem, null);

    fullProvision();

    EmmaGroup group = onlyEmmaGroup();
    assertEquals("test:testGroup", group.getName());
    Long originalGroupId = group.getId();

    // rename the Grouper group
    new GroupSave(grouperSession).assignUuid(testGroup.getUuid())
        .assignName("test:renamedGroup").assignSaveMode(SaveMode.INSERT_OR_UPDATE).save();

    fullProvision();

    group = onlyEmmaGroup();
    // patched in place, not recreated
    assertEquals(originalGroupId, group.getId());
    assertEquals("test:renamedGroup", group.getName());
  }

  /**
   * Emma keys members on email, so its /members/add endpoint upserts.  A subject
   * that is already an Emma member (same email) must be matched and reused, not
   * duplicated.  Here a member is pre-created in Emma before the group exists.
   */
  public void testFullSyncUpsertsExistingMemberByEmail() {

    GrouperStartup.startup();

    if (startTomcat) {
      tomcatStart();
    }

    EmmaProvisionerTestUtils.configureEmmaProvisioner(new EmmaProvisionerTestConfigInput());

    GrouperSession grouperSession = GrouperSession.startRootSession();

    // pre-existing Emma member with the same email SUBJ0 will resolve to
    EmmaMember preExisting = new EmmaMember();
    preExisting.setId(555L);
    preExisting.setEmail(emailForSubject(SubjectTestHelper.SUBJ0_ID));
    preExisting.setFirstName("Old");
    preExisting.setLastName("Name");
    preExisting.setMemberStatusId("a");
    HibernateSession.byObjectStatic().save(preExisting);

    Stem stem = new StemSave(grouperSession).assignName("test").save();
    Group testGroup = new GroupSave(grouperSession).assignName("test:testGroup").save();
    testGroup.addMember(SubjectTestHelper.SUBJ0, false);

    assignProvisioningAttribute(stem, null);

    fullProvision();

    // still exactly one member with that email - matched, not duplicated
    List<EmmaMember> withEmail = HibernateSession.byHqlStatic()
        .createQuery("from EmmaMember where email = :e")
        .setString("e", emailForSubject(SubjectTestHelper.SUBJ0_ID)).list(EmmaMember.class);
    assertEquals(withEmail.toString(), 1, withEmail.size());

    assertEquals(1, emmaMembershipCount());
    assertEquals(555L, emmaMemberships().get(0).getUserId());
  }

  /**
   * a change to a subject-derived member field (e.g. last name) flows through as
   * a member update (PUT), keyed on the existing member id
   */
  public void testFullSyncUpdateMemberField() {

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

    EmmaMember member = emmaMemberByEmail(emailForSubject(SubjectTestHelper.SUBJ0_ID));
    assertNotNull("expecting the subject to have become an Emma member", member);
    Long originalMemberId = member.getId();

    // simulate the source last name changing, then re-provision
    member.setLastName("ChangedLast");
    HibernateSession.byObjectStatic().saveOrUpdate(member);

    fullProvision();

    // the member is still the same row (same id), not a new one
    EmmaMember afterUpdate = emmaMemberByEmail(emailForSubject(SubjectTestHelper.SUBJ0_ID));
    assertNotNull(afterUpdate);
    assertEquals(originalMemberId, afterUpdate.getId());
    assertEquals(1, emmaMembers().size());
  }

  /**
   * a user-defined field, mapped through a field_&lt;name&gt; entity attribute,
   * is provisioned into the Emma member's fields object
   */
  public void testFullSyncCustomField() {

    GrouperStartup.startup();

    if (startTomcat) {
      tomcatStart();
    }

    // add a fifth entity attribute: field_department, a constant for the test
    EmmaProvisionerTestUtils.configureEmmaProvisioner(
        new EmmaProvisionerTestConfigInput()
            .assignEntityAttributeCount(5)
            .addExtraConfig("targetEntityAttribute.4.name",
                EmmaMember.CUSTOM_FIELD_ATTRIBUTE_PREFIX + "department")
            .addExtraConfig("targetEntityAttribute.4.translateExpressionType", "translationScript")
            .addExtraConfig("targetEntityAttribute.4.translateExpression", "${'IT'}"));

    GrouperSession grouperSession = GrouperSession.startRootSession();

    Stem stem = new StemSave(grouperSession).assignName("test").save();
    Group testGroup = new GroupSave(grouperSession).assignName("test:testGroup").save();
    testGroup.addMember(SubjectTestHelper.SUBJ0, false);

    assignProvisioningAttribute(stem, null);

    fullProvision();

    EmmaMember member = emmaMemberByEmail(emailForSubject(SubjectTestHelper.SUBJ0_ID));
    assertNotNull(member);
    assertEquals("IT", member.getCustomFields().get("department"));
  }

  /**
   * deleting the Grouper group deletes the Emma group, and the mock cascades the
   * membership rows away with it
   */
  public void testFullSyncDeleteGroupDeletesEmmaGroup() {

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

    assertEquals(1, emmaGroups().size());
    assertEquals(1, emmaMembershipCount());

    testGroup.delete();

    fullProvision();

    assertEquals(0, emmaGroups().size());
    assertEquals(0, emmaMembershipCount());
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

    assertEquals(0, emmaGroups().size());

    incrementalProvision();

    assertEquals(1, emmaGroups().size());
    assertEquals(2, emmaMembershipCount());

    EmmaGroup group = onlyEmmaGroup();
    assertEquals("test:testGroup", group.getName());

    testGroup.deleteMember(SubjectTestHelper.SUBJ1);
    incrementalProvision();

    assertEquals(1, emmaGroups().size());
    assertEquals(1, emmaMembershipCount());

    testGroup.addMember(SubjectTestHelper.SUBJ2);
    incrementalProvision();

    assertEquals(1, emmaGroups().size());
    assertEquals(2, emmaMembershipCount());
  }

  /**
   * a group rename flows through the change log as an Emma group patch
   */
  public void testIncrementalSyncUpdateGroupName() {

    if (!tomcatRunTests()) {
      return;
    }

    GrouperStartup.startup();

    if (startTomcat) {
      tomcatStart();
    }

    EmmaProvisionerTestUtils.configureEmmaProvisioner(new EmmaProvisionerTestConfigInput());

    GrouperSession grouperSession = GrouperSession.startRootSession();

    fullProvision();
    incrementalProvision();

    Stem stem = new StemSave(grouperSession).assignName("test").save();
    Group testGroup = new GroupSave(grouperSession).assignName("test:testGroup").save();

    assignProvisioningAttribute(stem, null);

    incrementalProvision();

    EmmaGroup group = onlyEmmaGroup();
    assertEquals("test:testGroup", group.getName());
    Long originalGroupId = group.getId();

    new GroupSave(grouperSession).assignUuid(testGroup.getUuid())
        .assignName("test:renamedGroup").assignSaveMode(SaveMode.INSERT_OR_UPDATE).save();

    incrementalProvision();

    group = onlyEmmaGroup();
    assertEquals(originalGroupId, group.getId());
    assertEquals("test:renamedGroup", group.getName());
  }

  // ==================================================================
  // diagnostics
  // ==================================================================

  /**
   * the diagnostics run for this provisioner reports no errors
   */
  public void testDiagnostics() {

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

    assertEquals(1, emmaGroups().size());

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

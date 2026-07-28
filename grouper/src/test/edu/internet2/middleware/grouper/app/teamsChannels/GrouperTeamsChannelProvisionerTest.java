package edu.internet2.middleware.grouper.app.teamsChannels;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.internet2.middleware.grouper.Group;
import edu.internet2.middleware.grouper.GroupSave;
import edu.internet2.middleware.grouper.GrouperSession;
import edu.internet2.middleware.grouper.Stem;
import edu.internet2.middleware.grouper.StemSave;
import edu.internet2.middleware.grouper.app.azure.AzureGrouperExternalSystem;
import edu.internet2.middleware.grouper.app.azure.GrouperAzureUser;
import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioner;
import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioningAttributeValue;
import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioningBaseTest;
import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioningDiagnosticsContainer;
import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioningOutput;
import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioningService;
import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioningType;
import edu.internet2.middleware.grouper.app.provisioning.ProvisioningGroup;
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
 * Tests for the Teams channel provisioner, running against
 * TeamsChannelMockServiceHandler.
 *
 * Modeled on GrouperAzureProvisionerTest.
 *
 * Note on entities: this provisioner never creates Entra users, so every test
 * that provisions a membership must first seed the mock directory with the users
 * the subjects resolve to (TeamsChannelProvisionerTestUtils.createEntraUsers).
 * That mirrors production, where the users already exist in the tenant.
 */
public class GrouperTeamsChannelProvisionerTest extends GrouperProvisioningBaseTest {

  public static void main(String[] args) {
    TestRunner.run(new GrouperTeamsChannelProvisionerTest("testFullSyncTeamsChannel"));
  }

  public GrouperTeamsChannelProvisionerTest(String name) {
    super(name);
  }

  public GrouperTeamsChannelProvisionerTest() {
  }

  public static boolean startTomcat = false;

  @Override
  public void setUp() {
    super.setUp();

    AzureGrouperExternalSystem.clearCache();

    // this will create the mock tables (teams channels plus the shared azure
    // user/auth tables) if they are not already there
    TeamsChannelMockServiceHandler.ensureTeamsChannelMockTables();

    new GcDbAccess().connectionName("grouper").sql("delete from mock_teams_channel_mship").executeSql();
    new GcDbAccess().connectionName("grouper").sql("delete from mock_teams_channel").executeSql();
    new GcDbAccess().connectionName("grouper").sql("delete from mock_azure_membership").executeSql();
    new GcDbAccess().connectionName("grouper").sql("delete from mock_azure_group").executeSql();
    new GcDbAccess().connectionName("grouper").sql("delete from mock_azure_user").executeSql();
  }

  @Override
  public String defaultConfigId() {
    return "myTeamsChannelProvisioner";
  }

  // ==================================================================
  // helpers
  // ==================================================================

  private static List<GrouperTeamsChannel> channels() {
    return HibernateSession.byHqlStatic().createQuery("from GrouperTeamsChannel").list(GrouperTeamsChannel.class);
  }

  private static GrouperTeamsChannel onlyChannel() {
    List<GrouperTeamsChannel> channels = channels();
    assertEquals("expecting exactly one channel", 1, channels.size());
    return channels.get(0);
  }

  private static List<GrouperTeamsChannelMembership> channelMemberships() {
    return HibernateSession.byHqlStatic().createQuery("from GrouperTeamsChannelMembership")
        .list(GrouperTeamsChannelMembership.class);
  }

  private static int channelMembershipCount() {
    return channelMemberships().size();
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
   * a channel round trips through the Graph json shape.  Note toJson never emits
   * the id (it is the request-side serializer) and only emits membershipType on
   * insert, since Teams will not let you change it afterwards.
   */
  public void testChannelJsonRoundTrip() {

    GrouperTeamsChannel channel = new GrouperTeamsChannel();
    channel.setTeamId(TeamsChannelProvisionerTestConfigInput.DEFAULT_TEAM_ID);
    channel.setId("19:4b6bed8d24574f6a9e436813cb2617d8@thread.tacv2");
    channel.setDisplayName("Architecture Discussion");
    channel.setDescription("some description");
    channel.setMembershipType("private");

    // insert: membershipType included, id never included
    String insertJson = GrouperUtil.jsonJacksonToString(channel.toJson(null, true));
    assertTrue(insertJson, insertJson.contains("\"membershipType\""));
    assertFalse(insertJson, insertJson.contains("\"id\""));

    // update: membershipType suppressed
    String updateJson = GrouperUtil.jsonJacksonToString(channel.toJson(null, false));
    assertFalse(updateJson, updateJson.contains("\"membershipType\""));

    // a response from Graph does carry the id
    GrouperTeamsChannel fromJson = GrouperTeamsChannel.fromJson(GrouperUtil.jsonJacksonNode(
        "{\"id\":\"19:abc@thread.tacv2\",\"displayName\":\"Architecture Discussion\","
            + "\"description\":\"some description\",\"membershipType\":\"private\"}"));

    assertEquals("19:abc@thread.tacv2", fromJson.getId());
    assertEquals("Architecture Discussion", fromJson.getDisplayName());
    assertEquals("some description", fromJson.getDescription());
    assertEquals("private", fromJson.getMembershipType());

    // no id means no channel
    assertNull(GrouperTeamsChannel.fromJson(GrouperUtil.jsonJacksonNode("{\"displayName\":\"x\"}")));
  }

  /**
   * a blank membershipType falls back to standard, on the setter and on the
   * json parse path
   */
  public void testMembershipTypeDefaultsToStandard() {

    GrouperTeamsChannel channel = new GrouperTeamsChannel();
    assertEquals(GrouperTeamsChannel.defaultMembershipType, channel.getMembershipType());

    channel.setMembershipType("");
    assertEquals("standard", channel.getMembershipType());

    channel.setMembershipType(null);
    assertEquals("standard", channel.getMembershipType());

    channel.setMembershipType("shared");
    assertEquals("shared", channel.getMembershipType());

    GrouperTeamsChannel fromJson = GrouperTeamsChannel.fromJson(
        GrouperUtil.jsonJacksonNode("{\"id\":\"19:abc@thread.tacv2\",\"displayName\":\"x\"}"));
    assertEquals("standard", fromJson.getMembershipType());
  }

  /**
   * a channel translates to and from a target ProvisioningGroup, carrying teamId
   * as an attribute (it is not one of the ProvisioningGroup core fields)
   */
  public void testChannelToAndFromProvisioningGroup() {

    GrouperTeamsChannel channel = new GrouperTeamsChannel();
    channel.setTeamId(TeamsChannelProvisionerTestConfigInput.DEFAULT_TEAM_ID);
    channel.setId("19:abc@thread.tacv2");
    channel.setDisplayName("testGroup");
    channel.setDescription("a description");
    channel.setMembershipType("private");

    ProvisioningGroup provisioningGroup = channel.toProvisioningGroup();

    assertEquals("19:abc@thread.tacv2", provisioningGroup.getId());
    assertEquals("testGroup", provisioningGroup.getDisplayName());
    assertEquals(TeamsChannelProvisionerTestConfigInput.DEFAULT_TEAM_ID,
        provisioningGroup.retrieveAttributeValueString("teamId"));
    assertEquals("a description", provisioningGroup.retrieveAttributeValueString("description"));
    assertEquals("private", provisioningGroup.retrieveAttributeValueString("membershipType"));

    GrouperTeamsChannel roundTripped = GrouperTeamsChannel.fromProvisioningGroup(provisioningGroup, null);

    assertEquals(channel.getId(), roundTripped.getId());
    assertEquals(channel.getTeamId(), roundTripped.getTeamId());
    assertEquals(channel.getDisplayName(), roundTripped.getDisplayName());
    assertEquals(channel.getDescription(), roundTripped.getDescription());
    assertEquals(channel.getMembershipType(), roundTripped.getMembershipType());
  }

  /**
   * the start with wizard emits a usable baseline provisioner configuration
   */
  public void testStartWithGeneratesExpectedConfig() {

    TeamsChannelProvisioningStartWith startWith = new TeamsChannelProvisioningStartWith();

    Map<String, String> startWithSuffixToValue = new HashMap<String, String>();
    startWithSuffixToValue.put("teamsExternalSystemConfigId", "myAzure");
    startWithSuffixToValue.put("groupDisplayNameAttributeValue", "extension");
    startWithSuffixToValue.put("useGroupDescription", "true");
    startWithSuffixToValue.put("teamIdValue", TeamsChannelProvisionerTestConfigInput.DEFAULT_TEAM_ID);
    startWithSuffixToValue.put("membershipTypeValue", "private");
    startWithSuffixToValue.put("groupSearchMatchingAttribute", "displayName");
    startWithSuffixToValue.put("hasMetadataForTeamId", "false");
    startWithSuffixToValue.put("hasMetadataForMembershipType", "false");

    Map<String, Object> provisionerSuffixToValue = new HashMap<String, Object>();

    startWith.populateProvisionerConfigurationValuesFromStartWith(startWithSuffixToValue, provisionerSuffixToValue);

    assertEquals("myAzure", provisionerSuffixToValue.get("teamsExternalSystemConfigId"));
    assertEquals(GrouperTeamsChannelProvisioner.class.getName(), provisionerSuffixToValue.get("class"));
    assertEquals("membershipObjects", provisionerSuffixToValue.get("provisioningType"));
    assertEquals("true", provisionerSuffixToValue.get("operateOnGrouperGroups"));
    assertEquals("true", provisionerSuffixToValue.get("operateOnGrouperMemberships"));

    // this provisioner never writes users
    assertEquals("false", provisionerSuffixToValue.get("makeChangesToEntities"));

    // id, displayName, description, teamId, membershipType
    assertEquals(5, GrouperUtil.intValue(provisionerSuffixToValue.get("numberOfGroupAttributes")));

    assertEquals("id", provisionerSuffixToValue.get("targetGroupAttribute.0.name"));
    assertEquals("false", provisionerSuffixToValue.get("targetGroupAttribute.0.insert"));
    assertEquals("false", provisionerSuffixToValue.get("targetGroupAttribute.0.update"));

    assertEquals("displayName", provisionerSuffixToValue.get("targetGroupAttribute.1.name"));
    assertEquals("extension", provisionerSuffixToValue.get("targetGroupAttribute.1.translateFromGrouperProvisioningGroupField"));

    assertEquals("description", provisionerSuffixToValue.get("targetGroupAttribute.2.name"));

    assertEquals("teamId", provisionerSuffixToValue.get("targetGroupAttribute.3.name"));
    assertEquals("${'" + TeamsChannelProvisionerTestConfigInput.DEFAULT_TEAM_ID + "'}",
        provisionerSuffixToValue.get("targetGroupAttribute.3.translateExpression"));

    assertEquals("membershipType", provisionerSuffixToValue.get("targetGroupAttribute.4.name"));
    assertEquals("${'private'}", provisionerSuffixToValue.get("targetGroupAttribute.4.translateExpression"));

    assertEquals("2", provisionerSuffixToValue.get("groupMatchingAttributeCount"));
    assertEquals("displayName", provisionerSuffixToValue.get("groupMatchingAttribute0name"));
    assertEquals("id", provisionerSuffixToValue.get("groupMatchingAttribute1name"));
  }

  /**
   * the metadata flags only surface the metadata items when they are turned on
   */
  public void testStartWithMetadataFlags() {

    TeamsChannelProvisioningStartWith startWith = new TeamsChannelProvisioningStartWith();

    Map<String, String> startWithSuffixToValue = new HashMap<String, String>();
    startWithSuffixToValue.put("teamsExternalSystemConfigId", "myAzure");
    startWithSuffixToValue.put("groupDisplayNameAttributeValue", "extension");
    startWithSuffixToValue.put("hasMetadataForTeamId", "true");
    startWithSuffixToValue.put("hasMetadataForMembershipType", "true");

    Map<String, Object> provisionerSuffixToValue = new HashMap<String, Object>();
    startWith.populateProvisionerConfigurationValuesFromStartWith(startWithSuffixToValue, provisionerSuffixToValue);

    assertEquals("true", provisionerSuffixToValue.get("teamIdMetadata"));
    assertEquals("true", provisionerSuffixToValue.get("membershipTypeMetadata"));

    // and with the flags off, neither is set
    Map<String, Object> withoutMetadata = new HashMap<String, Object>();
    startWithSuffixToValue.put("hasMetadataForTeamId", "false");
    startWithSuffixToValue.put("hasMetadataForMembershipType", "false");
    startWith.populateProvisionerConfigurationValuesFromStartWith(startWithSuffixToValue, withoutMetadata);

    assertNull(withoutMetadata.get("teamIdMetadata"));
    assertNull(withoutMetadata.get("membershipTypeMetadata"));
  }

  // ==================================================================
  // full sync
  // ==================================================================

  /**
   * a provisionable group becomes a channel, and its members become channel
   * members; adding and removing members keeps the channel in sync
   */
  public void testFullSyncTeamsChannel() {

    GrouperStartup.startup();

    if (startTomcat) {
      tomcatStart();
    }

    TeamsChannelProvisionerTestUtils.configureTeamsChannelProvisioner(
        new TeamsChannelProvisionerTestConfigInput());

    GrouperSession grouperSession = GrouperSession.startRootSession();

    // the provisioner never creates users, so the directory has to have them
    Map<String, String> subjectIdToEntraId = TeamsChannelProvisionerTestUtils.createEntraUsers(
        SubjectTestHelper.SUBJ0_ID, SubjectTestHelper.SUBJ1_ID,
        SubjectTestHelper.SUBJ2_ID, SubjectTestHelper.SUBJ3_ID);

    Stem stem = new StemSave(grouperSession).assignName("test").save();

    Group testGroup = new GroupSave(grouperSession).assignName("test:testGroup")
        .assignDescription("test description").save();

    testGroup.addMember(SubjectTestHelper.SUBJ0, false);
    testGroup.addMember(SubjectTestHelper.SUBJ1, false);

    assignProvisioningAttribute(stem, null);

    assertEquals(new Integer(0), new GcDbAccess().connectionName("grouper")
        .sql("select count(1) from mock_teams_channel").select(int.class));
    assertEquals(0, channels().size());

    GrouperProvisioningOutput grouperProvisioningOutput = fullProvision();

    assertTrue(1 <= grouperProvisioningOutput.getInsert());

    GrouperTeamsChannel channel = onlyChannel();

    // displayName comes from the group extension, not the full name: a Teams
    // channel name cannot contain a colon
    assertEquals("testGroup", channel.getDisplayName());
    assertEquals("test description", channel.getDescription());
    assertEquals("private", channel.getMembershipType());
    assertEquals(TeamsChannelProvisionerTestConfigInput.DEFAULT_TEAM_ID, channel.getTeamId());
    assertTrue(channel.getId(), channel.getId().startsWith("19:"));

    assertEquals(2, channelMembershipCount());

    // the channel members are keyed by the Entra object id, not the subject id
    List<String> memberUserIds = new ArrayList<String>();
    for (GrouperTeamsChannelMembership membership : channelMemberships()) {
      assertEquals(channel.getId(), membership.getChannelId());
      memberUserIds.add(membership.getUserId());
    }
    assertTrue(memberUserIds.toString(), memberUserIds.contains(subjectIdToEntraId.get(SubjectTestHelper.SUBJ0_ID)));
    assertTrue(memberUserIds.toString(), memberUserIds.contains(subjectIdToEntraId.get(SubjectTestHelper.SUBJ1_ID)));

    // the channel id is cached back onto the sync record
    GcGrouperSync gcGrouperSync = GcGrouperSyncDao.retrieveByProvisionerName(null, defaultConfigId());
    assertEquals(1, gcGrouperSync.getGroupCount().intValue());

    GcGrouperSyncGroup gcGrouperSyncGroup = gcGrouperSync.getGcGrouperSyncGroupDao()
        .groupRetrieveByGroupId(testGroup.getId());
    assertEquals(testGroup.getId(), gcGrouperSyncGroup.getGroupId());
    assertEquals(testGroup.getName(), gcGrouperSyncGroup.getGroupName());
    assertEquals(channel.getId(), gcGrouperSyncGroup.getGroupAttributeValueCache0());

    // remove a member
    testGroup.deleteMember(SubjectTestHelper.SUBJ1);

    fullProvision();

    assertEquals(1, channels().size());
    assertEquals(1, channelMembershipCount());
    assertEquals(subjectIdToEntraId.get(SubjectTestHelper.SUBJ0_ID), channelMemberships().get(0).getUserId());

    // add a different member
    testGroup.addMember(SubjectTestHelper.SUBJ3);

    fullProvision();

    assertEquals(1, channels().size());
    assertEquals(2, channelMembershipCount());

    // a second full sync with nothing changed should not churn
    GrouperProvisioningOutput steadyState = fullProvision();
    assertEquals(0, steadyState.getInsert());
    assertEquals(0, steadyState.getDelete());
    assertEquals(1, channels().size());
    assertEquals(2, channelMembershipCount());
  }

  /**
   * changing the group description patches the channel; membershipType is set at
   * create time and must never be patched (the mock rejects it if it is)
   */
  public void testFullSyncUpdateChannelDescription() {

    GrouperStartup.startup();

    if (startTomcat) {
      tomcatStart();
    }

    TeamsChannelProvisionerTestUtils.configureTeamsChannelProvisioner(
        new TeamsChannelProvisionerTestConfigInput());

    GrouperSession grouperSession = GrouperSession.startRootSession();

    Stem stem = new StemSave(grouperSession).assignName("test").save();
    Group testGroup = new GroupSave(grouperSession).assignName("test:testGroup")
        .assignDescription("test description").save();

    assignProvisioningAttribute(stem, null);

    fullProvision();

    GrouperTeamsChannel channel = onlyChannel();
    assertEquals("testGroup", channel.getDisplayName());
    assertEquals("test description", channel.getDescription());
    assertEquals("private", channel.getMembershipType());

    String originalChannelId = channel.getId();

    new GroupSave(grouperSession).assignUuid(testGroup.getUuid())
        .assignDescription("new description 1").assignSaveMode(SaveMode.INSERT_OR_UPDATE).save();

    fullProvision();

    channel = onlyChannel();

    // patched in place, not recreated
    assertEquals(originalChannelId, channel.getId());
    assertEquals("new description 1", channel.getDescription());
    assertEquals("testGroup", channel.getDisplayName());
    assertEquals("private", channel.getMembershipType());
  }

  /**
   * two groups in different folders provision into two different teams, and
   * retrieveAllGroups walks both teams
   */
  public void testFullSyncChannelsInTwoTeams() {

    GrouperStartup.startup();

    if (startTomcat) {
      tomcatStart();
    }

    TeamsChannelProvisionerTestUtils.configureTeamsChannelProvisioner(
        new TeamsChannelProvisionerTestConfigInput().assignTeamIdFromMetadata(true));

    GrouperSession grouperSession = GrouperSession.startRootSession();

    Stem stem = new StemSave(grouperSession).assignName("test").save();
    Stem stem2 = new StemSave(grouperSession).assignName("test2").save();

    new GroupSave(grouperSession).assignName("test:groupInTeamOne").save();
    new GroupSave(grouperSession).assignName("test2:groupInTeamTwo").save();

    Map<String, Object> teamOneMetadata = new HashMap<String, Object>();
    teamOneMetadata.put("md_grouper_teamId", TeamsChannelProvisionerTestConfigInput.DEFAULT_TEAM_ID);
    assignProvisioningAttribute(stem, teamOneMetadata);

    Map<String, Object> teamTwoMetadata = new HashMap<String, Object>();
    teamTwoMetadata.put("md_grouper_teamId", TeamsChannelProvisionerTestConfigInput.OTHER_TEAM_ID);
    assignProvisioningAttribute(stem2, teamTwoMetadata);

    fullProvision();

    assertEquals(2, channels().size());

    Map<String, String> displayNameToTeamId = new HashMap<String, String>();
    for (GrouperTeamsChannel channel : channels()) {
      displayNameToTeamId.put(channel.getDisplayName(), channel.getTeamId());
    }

    assertEquals(TeamsChannelProvisionerTestConfigInput.DEFAULT_TEAM_ID,
        displayNameToTeamId.get("groupInTeamOne"));
    assertEquals(TeamsChannelProvisionerTestConfigInput.OTHER_TEAM_ID,
        displayNameToTeamId.get("groupInTeamTwo"));

    // a second run must find both channels rather than trying to recreate them
    GrouperProvisioningOutput steadyState = fullProvision();
    assertEquals(0, steadyState.getInsert());
    assertEquals(2, channels().size());
  }

  /**
   * membershipType is honoured at create time, per folder, from metadata
   */
  public void testFullSyncMembershipTypeFromMetadata() {

    GrouperStartup.startup();

    if (startTomcat) {
      tomcatStart();
    }

    TeamsChannelProvisionerTestUtils.configureTeamsChannelProvisioner(
        new TeamsChannelProvisionerTestConfigInput().assignMembershipTypeFromMetadata(true));

    GrouperSession grouperSession = GrouperSession.startRootSession();

    Stem stem = new StemSave(grouperSession).assignName("test").save();
    new GroupSave(grouperSession).assignName("test:sharedChannelGroup").save();

    Map<String, Object> metadataNameValues = new HashMap<String, Object>();
    metadataNameValues.put("md_grouper_membershipType", "shared");
    assignProvisioningAttribute(stem, metadataNameValues);

    fullProvision();

    GrouperTeamsChannel channel = onlyChannel();
    assertEquals("shared", channel.getMembershipType());

    // and it stays put across a re-sync rather than being patched back
    fullProvision();
    assertEquals("shared", onlyChannel().getMembershipType());
  }

  /**
   * a group with no teamId cannot be provisioned - Teams channels only exist
   * inside a team.  The failure must be recorded against the group rather than
   * blowing up the whole run.
   */
  public void testFullSyncGroupWithNoTeamIdIsNotProvisioned() {

    GrouperStartup.startup();

    if (startTomcat) {
      tomcatStart();
    }

    // teamId is expected from metadata, but no metadata is assigned below
    TeamsChannelProvisionerTestUtils.configureTeamsChannelProvisioner(
        new TeamsChannelProvisionerTestConfigInput().assignTeamIdFromMetadata(true));

    GrouperSession grouperSession = GrouperSession.startRootSession();

    Stem stem = new StemSave(grouperSession).assignName("test").save();
    new GroupSave(grouperSession).assignName("test:groupWithNoTeam").save();

    assignProvisioningAttribute(stem, null);

    GrouperProvisioningOutput grouperProvisioningOutput = fullProvision(defaultConfigId(), true);

    assertEquals(0, channels().size());
    assertTrue("expecting the group to be recorded as an error",
        grouperProvisioningOutput.getRecordsWithErrors() > 0);
  }

  /**
   * deleting the Grouper group deletes the channel.
   *
   * NOTE: verify this one first when running the suite.  Channel deletes need the
   * parent teamId, and the DAO recovers it from the translated grouper-side target
   * groups (resolveTeamIdForGroupId).  Once the Grouper group is gone there may be
   * no translated group left to recover it from, in which case the channel is
   * orphaned instead of deleted.  If this test fails, the fix is to cache teamId
   * on the sync record (an extra groupAttributeValueCache) rather than to relax
   * the test.
   */
  public void testFullSyncDeleteGroupDeletesChannel() {

    GrouperStartup.startup();

    if (startTomcat) {
      tomcatStart();
    }

    TeamsChannelProvisionerTestUtils.configureTeamsChannelProvisioner(
        new TeamsChannelProvisionerTestConfigInput());

    GrouperSession grouperSession = GrouperSession.startRootSession();

    TeamsChannelProvisionerTestUtils.createEntraUsers(SubjectTestHelper.SUBJ0_ID);

    Stem stem = new StemSave(grouperSession).assignName("test").save();
    Group testGroup = new GroupSave(grouperSession).assignName("test:testGroup").save();
    testGroup.addMember(SubjectTestHelper.SUBJ0, false);

    assignProvisioningAttribute(stem, null);

    fullProvision();

    assertEquals(1, channels().size());
    assertEquals(1, channelMembershipCount());

    testGroup.delete();

    fullProvision();

    assertEquals(0, channels().size());
    assertEquals(0, channelMembershipCount());
  }

  // ==================================================================
  // incremental sync
  // ==================================================================

  /**
   * the same lifecycle as testFullSyncTeamsChannel, driven off the change log
   */
  public void testIncrementalSyncTeamsChannel() {

    if (!tomcatRunTests()) {
      return;
    }

    GrouperStartup.startup();

    if (startTomcat) {
      tomcatStart();
    }

    TeamsChannelProvisionerTestUtils.configureTeamsChannelProvisioner(
        new TeamsChannelProvisionerTestConfigInput());

    GrouperSession grouperSession = GrouperSession.startRootSession();

    Map<String, String> subjectIdToEntraId = TeamsChannelProvisionerTestUtils.createEntraUsers(
        SubjectTestHelper.SUBJ0_ID, SubjectTestHelper.SUBJ1_ID, SubjectTestHelper.SUBJ3_ID);

    // drain anything already queued so the assertions below are about our changes
    fullProvision();
    incrementalProvision();

    Stem stem = new StemSave(grouperSession).assignName("test").save();
    Group testGroup = new GroupSave(grouperSession).assignName("test:testGroup").save();

    testGroup.addMember(SubjectTestHelper.SUBJ0, false);
    testGroup.addMember(SubjectTestHelper.SUBJ1, false);

    assignProvisioningAttribute(stem, null);

    assertEquals(0, channels().size());

    incrementalProvision();

    assertEquals(1, channels().size());
    assertEquals(2, channelMembershipCount());

    GrouperTeamsChannel channel = onlyChannel();
    assertEquals("testGroup", channel.getDisplayName());
    assertEquals(TeamsChannelProvisionerTestConfigInput.DEFAULT_TEAM_ID, channel.getTeamId());

    testGroup.deleteMember(SubjectTestHelper.SUBJ1);
    incrementalProvision();

    assertEquals(1, channels().size());
    assertEquals(1, channelMembershipCount());
    assertEquals(subjectIdToEntraId.get(SubjectTestHelper.SUBJ0_ID), channelMemberships().get(0).getUserId());

    testGroup.addMember(SubjectTestHelper.SUBJ3);
    incrementalProvision();

    assertEquals(1, channels().size());
    assertEquals(2, channelMembershipCount());
  }

  /**
   * a description change flows through the change log as a channel patch
   */
  public void testIncrementalSyncUpdateChannelDescription() {

    if (!tomcatRunTests()) {
      return;
    }

    GrouperStartup.startup();

    if (startTomcat) {
      tomcatStart();
    }

    TeamsChannelProvisionerTestUtils.configureTeamsChannelProvisioner(
        new TeamsChannelProvisionerTestConfigInput());

    GrouperSession grouperSession = GrouperSession.startRootSession();

    fullProvision();
    incrementalProvision();

    Stem stem = new StemSave(grouperSession).assignName("test").save();
    Group testGroup = new GroupSave(grouperSession).assignName("test:testGroup")
        .assignDescription("test description").save();

    assignProvisioningAttribute(stem, null);

    incrementalProvision();

    GrouperTeamsChannel channel = onlyChannel();
    assertEquals("test description", channel.getDescription());
    String originalChannelId = channel.getId();

    new GroupSave(grouperSession).assignUuid(testGroup.getUuid())
        .assignDescription("new description 1").assignSaveMode(SaveMode.INSERT_OR_UPDATE).save();

    incrementalProvision();

    channel = onlyChannel();
    assertEquals(originalChannelId, channel.getId());
    assertEquals("new description 1", channel.getDescription());
  }

  // ==================================================================
  // entities
  // ==================================================================

  /**
   * a subject with no matching Entra user cannot be added to a channel.  The
   * provisioner must not attempt to create the user, and the rest of the group
   * must still provision.
   */
  public void testSubjectMissingFromDirectoryDoesNotCreateUser() {

    GrouperStartup.startup();

    if (startTomcat) {
      tomcatStart();
    }

    TeamsChannelProvisionerTestUtils.configureTeamsChannelProvisioner(
        new TeamsChannelProvisionerTestConfigInput());

    GrouperSession grouperSession = GrouperSession.startRootSession();

    // only SUBJ0 exists in the directory; SUBJ1 does not
    Map<String, String> subjectIdToEntraId =
        TeamsChannelProvisionerTestUtils.createEntraUsers(SubjectTestHelper.SUBJ0_ID);

    Stem stem = new StemSave(grouperSession).assignName("test").save();
    Group testGroup = new GroupSave(grouperSession).assignName("test:testGroup").save();

    testGroup.addMember(SubjectTestHelper.SUBJ0, false);
    testGroup.addMember(SubjectTestHelper.SUBJ1, false);

    assignProvisioningAttribute(stem, null);

    fullProvision(defaultConfigId(), true);

    // the channel is still created
    GrouperTeamsChannel channel = onlyChannel();
    assertEquals("testGroup", channel.getDisplayName());

    // only the resolvable subject became a channel member
    assertEquals(1, channelMembershipCount());
    assertEquals(subjectIdToEntraId.get(SubjectTestHelper.SUBJ0_ID), channelMemberships().get(0).getUserId());

    // and no user was invented in the directory
    assertEquals(1, HibernateSession.byHqlStatic().createQuery("from GrouperAzureUser")
        .list(GrouperAzureUser.class).size());
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

    TeamsChannelProvisionerTestUtils.configureTeamsChannelProvisioner(
        new TeamsChannelProvisionerTestConfigInput());

    GrouperSession grouperSession = GrouperSession.startRootSession();

    TeamsChannelProvisionerTestUtils.createEntraUsers(
        SubjectTestHelper.SUBJ0_ID, SubjectTestHelper.SUBJ4_ID);

    Stem stem = new StemSave(grouperSession).assignName("test").save();
    Group testGroup = new GroupSave(grouperSession).assignName("test:testGroup").save();
    testGroup.addMember(SubjectTestHelper.SUBJ0, false);

    assignProvisioningAttribute(stem, null);

    fullProvision();

    assertEquals(1, channels().size());

    GrouperProvisioner provisioner = GrouperProvisioner.retrieveProvisioner(defaultConfigId());
    provisioner.initialize(GrouperProvisioningType.diagnostics);

    GrouperProvisioningDiagnosticsContainer diagnosticsContainer =
        provisioner.retrieveGrouperProvisioningDiagnosticsContainer();

    diagnosticsContainer.getGrouperProvisioningDiagnosticsSettings().setDiagnosticsGroupName("test:testGroup");
    diagnosticsContainer.getGrouperProvisioningDiagnosticsSettings()
        .setDiagnosticsSubjectIdOrIdentifier(SubjectTestHelper.SUBJ4_ID);
    diagnosticsContainer.getGrouperProvisioningDiagnosticsSettings().setDiagnosticsGroupsAllSelect(true);

    GrouperProvisioningOutput grouperProvisioningOutput = provisioner.provision(GrouperProvisioningType.diagnostics);

    assertEquals(0, grouperProvisioningOutput.getRecordsWithErrors());
    validateNoErrors(diagnosticsContainer);
  }

}

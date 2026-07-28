package edu.internet2.middleware.grouper.app.teamsChannels;

import java.util.HashMap;
import java.util.Map;

/**
 * Fluent input bean describing how a Teams channel provisioner should be
 * configured for one test.
 *
 * Modeled on AzureProvisionerTestConfigInput.
 */
public class TeamsChannelProvisionerTestConfigInput {

  /**
   * a stable fake team (M365 group) id used by the tests.  The mock service does
   * not keep a team table - channels simply carry a team_id - so any GUID works.
   */
  public static final String DEFAULT_TEAM_ID = "57fb72d0-d811-46f4-8947-305e6072eaa5";

  /**
   * a second team id, for tests that provision channels into more than one team
   */
  public static final String OTHER_TEAM_ID = "8c2b4192-4f31-4a52-9a30-1d2f9d0f1b77";

  /** default to myTeamsChannelProvisioner */
  private String configId = "myTeamsChannelProvisioner";

  /**
   * the Graph external system config id.  This is an azureConnector config id
   * (grouper.azureConnector.&lt;id&gt;.*) because a Teams channel provisioner uses
   * the same app registration and bearer token as the Azure provisioner.
   */
  private String teamsExternalSystemConfigId = "myAzure";

  /** run against a real tenant instead of the mock service */
  private boolean realTeams = false;

  /**
   * which Grouper group field the channel displayName comes from.  Defaults to
   * extension rather than name: a Teams channel displayName is capped at 50
   * characters and cannot contain a colon, so the full group name is not usable.
   */
  private String displayNameMapping = "extension";

  /**
   * 4 (id, displayName, teamId, membershipType) or 5 (default, adds description)
   */
  private int groupAttributeCount = 5;

  /**
   * the fixed teamId every provisioned channel goes into.  Ignored when
   * teamIdFromMetadata is true.
   */
  private String teamId = DEFAULT_TEAM_ID;

  /**
   * the membershipType every provisioned channel is created with.  Defaults to
   * private: only private and shared channels manage membership independently of
   * the parent team, so those are the ones worth provisioning memberships for.
   */
  private String membershipType = "private";

  /** drive teamId from md_grouper_teamId group/folder metadata */
  private boolean teamIdFromMetadata = false;

  /** drive membershipType from md_grouper_membershipType group/folder metadata */
  private boolean membershipTypeFromMetadata = false;

  /** select all channels/entities up front rather than looking them up one at a time */
  private boolean selectAll = true;

  /** extra config by suffix and value */
  private Map<String, String> extraConfig = new HashMap<String, String>();

  public String getConfigId() {
    return configId;
  }

  public TeamsChannelProvisionerTestConfigInput assignConfigId(String configId) {
    this.configId = configId;
    return this;
  }

  public String getTeamsExternalSystemConfigId() {
    return teamsExternalSystemConfigId;
  }

  public TeamsChannelProvisionerTestConfigInput assignTeamsExternalSystemConfigId(String teamsExternalSystemConfigId) {
    this.teamsExternalSystemConfigId = teamsExternalSystemConfigId;
    return this;
  }

  public boolean isRealTeams() {
    return realTeams;
  }

  public TeamsChannelProvisionerTestConfigInput assignRealTeams(boolean realTeams) {
    this.realTeams = realTeams;
    return this;
  }

  public String getDisplayNameMapping() {
    return displayNameMapping;
  }

  public TeamsChannelProvisionerTestConfigInput assignDisplayNameMapping(String displayNameMapping) {
    this.displayNameMapping = displayNameMapping;
    return this;
  }

  public int getGroupAttributeCount() {
    return groupAttributeCount;
  }

  public TeamsChannelProvisionerTestConfigInput assignGroupAttributeCount(int groupAttributeCount) {
    this.groupAttributeCount = groupAttributeCount;
    return this;
  }

  public String getTeamId() {
    return teamId;
  }

  public TeamsChannelProvisionerTestConfigInput assignTeamId(String teamId) {
    this.teamId = teamId;
    return this;
  }

  public String getMembershipType() {
    return membershipType;
  }

  public TeamsChannelProvisionerTestConfigInput assignMembershipType(String membershipType) {
    this.membershipType = membershipType;
    return this;
  }

  public boolean isTeamIdFromMetadata() {
    return teamIdFromMetadata;
  }

  public TeamsChannelProvisionerTestConfigInput assignTeamIdFromMetadata(boolean teamIdFromMetadata) {
    this.teamIdFromMetadata = teamIdFromMetadata;
    return this;
  }

  public boolean isMembershipTypeFromMetadata() {
    return membershipTypeFromMetadata;
  }

  public TeamsChannelProvisionerTestConfigInput assignMembershipTypeFromMetadata(boolean membershipTypeFromMetadata) {
    this.membershipTypeFromMetadata = membershipTypeFromMetadata;
    return this;
  }

  public boolean isSelectAll() {
    return selectAll;
  }

  public TeamsChannelProvisionerTestConfigInput assignSelectAll(boolean selectAll) {
    this.selectAll = selectAll;
    return this;
  }

  /**
   * extra config by suffix and value
   * @param suffix
   * @param value
   * @return this for chaining
   */
  public TeamsChannelProvisionerTestConfigInput addExtraConfig(String suffix, String value) {
    this.extraConfig.put(suffix, value);
    return this;
  }

  public Map<String, String> getExtraConfig() {
    return this.extraConfig;
  }

}

package edu.internet2.middleware.grouper.app.teamsChannels;

import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioningConfiguration;
import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioningConfigurationAttribute;
import edu.internet2.middleware.grouper.app.provisioning.GrouperProvisioningConfigurationAttributeType;
import edu.internet2.middleware.grouper.util.GrouperUtil;

/**
 * Provisioning configuration for the Teams channel provisioner.
 *
 * The teamsExternalSystemConfigId points at the same
 * grouper.azureConnector.&lt;id&gt;.* external system used for Graph auth (an
 * Azure app registration with a bearer token) - a Teams channel provisioner
 * needs the exact same OAuth client-credentials login the Azure provisioner
 * uses, so it reuses that external system configuration rather than defining
 * a parallel one.
 *
 * Modeled on GrouperAzureConfiguration but pared down: channels only carry a
 * teamId and a membershipType, so the many Azure group-behavior flags are
 * dropped.
 */
public class GrouperTeamsChannelConfiguration extends GrouperProvisioningConfiguration {

  /**
   * config id of the Azure/Graph external system (grouper.azureConnector.&lt;id&gt;)
   * used to obtain the bearer token and resource endpoint.
   */
  private String teamsExternalSystemConfigId;

  /** whether the teamId can be supplied via group/folder metadata */
  private boolean teamIdMetadata;

  /** whether the membershipType can be supplied via group/folder metadata */
  private boolean membershipTypeMetadata;

  @Override
  public void configureAfterMetadata() {
    super.configureAfterMetadata();

    for (String attributeName : new String[] { "teamId", "membershipType" }) {

      String metadataName = "md_grouper_" + attributeName;
      if (!this.getGrouperProvisioner().retrieveGrouperProvisioningObjectMetadata()
          .getGrouperProvisioningObjectMetadataItemsByName().containsKey(metadataName)) {
        continue;
      }

      GrouperProvisioningConfigurationAttribute grouperProvisioningConfigurationAttribute =
          this.getTargetGroupAttributeNameToConfig().get(attributeName);

      if (grouperProvisioningConfigurationAttribute != null) {
        continue;
      }

      GrouperProvisioningConfigurationAttribute nameConfigurationAttribute = new GrouperProvisioningConfigurationAttribute();
      nameConfigurationAttribute.setGrouperProvisioner(this.getGrouperProvisioner());
      nameConfigurationAttribute.setGrouperProvisioningConfigurationAttributeType(GrouperProvisioningConfigurationAttributeType.group);
      nameConfigurationAttribute.setName(attributeName);
      nameConfigurationAttribute.setConfigIndex(this.getTargetGroupAttributeNameToConfig().size());

      this.getTargetGroupAttributeNameToConfig().put(attributeName, nameConfigurationAttribute);
    }
  }

  @Override
  public void configureSpecificSettings() {

    this.teamsExternalSystemConfigId = this.retrieveConfigString("teamsExternalSystemConfigId", true);
    this.teamIdMetadata = GrouperUtil.booleanValue(this.retrieveConfigString("teamIdMetadata", false), false);
    this.membershipTypeMetadata = GrouperUtil.booleanValue(this.retrieveConfigString("membershipTypeMetadata", false), false);
  }

  public String getTeamsExternalSystemConfigId() {
    return teamsExternalSystemConfigId;
  }

  public void setTeamsExternalSystemConfigId(String teamsExternalSystemConfigId) {
    this.teamsExternalSystemConfigId = teamsExternalSystemConfigId;
  }

  public boolean isTeamIdMetadata() {
    return teamIdMetadata;
  }

  public void setTeamIdMetadata(boolean teamIdMetadata) {
    this.teamIdMetadata = teamIdMetadata;
  }

  public boolean isMembershipTypeMetadata() {
    return membershipTypeMetadata;
  }

  public void setMembershipTypeMetadata(boolean membershipTypeMetadata) {
    this.membershipTypeMetadata = membershipTypeMetadata;
  }

  @Override
  public int getDaoSleepBeforeSelectAfterInsertMillis() {
    return GrouperUtil.intValue(this.retrieveConfigInt("sleepBeforeSelectAfterInsertMillis", false), 3000);
  }

}
